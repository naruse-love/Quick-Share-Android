package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * High-throughput file and directory slicing pipeline for pure LAN streaming.
 *
 * Reads local files, decomposes them into 1MB [FileBlock] chunks, and enqueues
 * them into a thread-safe blocking queue for sequential streaming.
 */
class ReadFileCall(
    private val buffers: BlockingQueue<ByteArray>,
    private val files: List<RemoteFile>,
    private val localDir: QuickShareDirectory,
    private val remoteDir: QuickShareDirectory,
    private val operateThreadCount: Int = 1,
    private val storageResolver: ((path: String) -> InputStream)? = null,
    val enable4KFriendly: Boolean = false
) {
    private val deque: BlockingQueue<FileBlock> = LinkedBlockingQueue()
    private val fileIndexCounter = AtomicInteger(-1)
    private var currentInputStream: InputStream? = null
    private val queueLock = Any()
    @Volatile
    private var isShutdown: Boolean = false

    companion object {
        const val SMALL_FILE_THRESHOLD: Long = 128L * 1024L // 128KB threshold for 4K friendliness
        val END_POINT = FileBlock.END_POINT
        val INTERRUPT = FileBlock.INTERRUPT
        val READ_ERROR = FileBlock.READ_ERROR
        val WRITE_ERROR = FileBlock.WRITE_ERROR
    }

    private data class BatchZipItem(
        val transferPath: String,
        val zipBytes: ByteArray,
        val lastModified: Long
    )

    private val zipSentinel = BatchZipItem("", ByteArray(0), -1L)

    /**
     * Executes the slicing loop asynchronously.
     * Enqueues FOLDER and FILE blocks, concluding with [operateThreadCount] END_POINT markers.
     */
    suspend fun executeAsync() = withContext(Dispatchers.IO) {
        if (enable4KFriendly) {
            executeWith4KFriendly()
        } else {
            executeNormal()
        }
    }

    private fun executeNormal() {
        try {
            for (file in files) {
                if (isShutdown) break
                if (storageResolver == null && !fileExists(file.path)) {
                    continue
                }
                readToDeque(file)
                if (file.isDirectory && !isShutdown) {
                    listFilesAndRead(file)
                }
            }

            synchronized(queueLock) {
                if (!isShutdown) {
                    // Fan-out END_POINT sentinel to each channel worker
                    for (i in 0 until operateThreadCount) {
                        deque.put(END_POINT)
                    }
                }
            }
        } catch (e: Throwable) {
            synchronized(queueLock) {
                if (!isShutdown) {
                    // In case of read failure, notify all channels with READ_ERROR
                    for (i in 0 until operateThreadCount) {
                        deque.put(READ_ERROR)
                    }
                }
            }
            throw e
        }
    }

    suspend fun executeWith4KFriendly() = withContext(Dispatchers.IO) {
        try {
            val looseFiles = mutableListOf<RemoteFile>()
            val allDirs = mutableListOf<RemoteFile>()
            val bigFiles = mutableListOf<RemoteFile>()
            val smallFilesByDir = mutableMapOf<String, MutableList<RemoteFile>>()

            // 1. Separate loose files and folder hierarchies
            for (file in files) {
                if (isShutdown) break
                if (storageResolver == null && !fileExists(file.path)) continue

                if (file.isDirectory) {
                    traverseDirectoryTree(file, allDirs, bigFiles, smallFilesByDir)
                } else {
                    looseFiles.add(file)
                }
            }

            // 2. Start background compression pipeline
            val zipQueue: BlockingQueue<BatchZipItem> = LinkedBlockingQueue(32)
            var compressionError: Throwable? = null

            val compressionJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    for ((dirPath, smallFiles) in smallFilesByDir) {
                        if (isShutdown) break
                        if (smallFiles.isEmpty()) continue

                        val zipBytes = createZipArchiveForFiles(smallFiles)
                        if (isShutdown) break

                        val hashHex = (dirPath.hashCode().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
                        val batchFileName = "__qs_batch_${hashHex}.zip"
                        val zipLocalPath = if (dirPath.endsWith("/") || dirPath.endsWith("\\")) {
                            "$dirPath$batchFileName"
                        } else {
                            "$dirPath${File.separator}$batchFileName"
                        }
                        val transferPath = localDir.generateTransferPath(zipLocalPath, remoteDir)
                        val lastModified = smallFiles.maxOfOrNull { it.lastModified } ?: System.currentTimeMillis()

                        while (!isShutdown) {
                            if (zipQueue.offer(BatchZipItem(transferPath, zipBytes, lastModified), 50, TimeUnit.MILLISECONDS)) {
                                break
                            }
                        }
                    }
                } catch (t: Throwable) {
                    compressionError = t
                } finally {
                    while (!isShutdown) {
                        if (zipQueue.offer(zipSentinel, 50, TimeUnit.MILLISECONDS)) {
                            break
                        }
                    }
                }
            }

            // 3. Phase 1: Stream directory metadata, loose files, and big files
            for (dir in allDirs) {
                if (isShutdown || compressionError != null) break
                val currentFileIndex = fileIndexCounter.incrementAndGet()
                val transferPath = localDir.generateTransferPath(dir.path, remoteDir)
                synchronized(queueLock) {
                    if (!isShutdown) {
                        deque.put(
                            FileBlock(
                                isFile = false,
                                fileIndex = currentFileIndex,
                                path = transferPath,
                                lastModified = dir.lastModified,
                                totalSize = 0L,
                                index = 0,
                                data = null,
                                dataLength = 0
                            )
                        )
                    }
                }
            }

            for (file in looseFiles) {
                if (isShutdown || compressionError != null) break
                readToDeque(file)
            }

            for (file in bigFiles) {
                if (isShutdown || compressionError != null) break
                readToDeque(file)
            }

            // 4. Phase 2: Stream batch ZIP packages
            while (!isShutdown) {
                val item = zipQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (item === zipSentinel || (item.lastModified == -1L && item.transferPath.isEmpty())) {
                    break
                }
                enqueueZipData(item.transferPath, item.zipBytes, item.lastModified)
            }

            compressionJob.join()
            compressionError?.let { throw it }

            synchronized(queueLock) {
                if (!isShutdown) {
                    for (i in 0 until operateThreadCount) {
                        deque.put(END_POINT)
                    }
                }
            }
        } catch (e: Throwable) {
            synchronized(queueLock) {
                if (!isShutdown) {
                    for (i in 0 until operateThreadCount) {
                        deque.put(READ_ERROR)
                    }
                }
            }
            throw e
        }
    }

    private fun traverseDirectoryTree(
        folder: RemoteFile,
        allDirs: MutableList<RemoteFile>,
        bigFiles: MutableList<RemoteFile>,
        smallFilesByDir: MutableMap<String, MutableList<RemoteFile>>
    ) {
        allDirs.add(folder)
        val dirList = smallFilesByDir.getOrPut(folder.path) { mutableListOf() }
        val subItems = listLocalFiles(folder.path)
        for (item in subItems) {
            if (item.isDirectory) {
                traverseDirectoryTree(item, allDirs, bigFiles, smallFilesByDir)
            } else {
                if (item.size > SMALL_FILE_THRESHOLD) {
                    bigFiles.add(item)
                } else {
                    dirList.add(item)
                }
            }
        }
    }

    private fun createZipArchiveForFiles(smallFiles: List<RemoteFile>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, StandardCharsets.UTF_8).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED)
            for (file in smallFiles) {
                val entry = ZipEntry(file.name)
                val zipTime = if (file.lastModified < 315532800000L) 315532800000L else minOf(file.lastModified, 4354819199000L)
                entry.time = zipTime
                zos.putNextEntry(entry)
                openFile(file.path).use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun enqueueZipData(transferPath: String, zipBytes: ByteArray, lastModified: Long) {
        if (isShutdown) return
        val currentFileIndex = fileIndexCounter.incrementAndGet()
        val fileLength = zipBytes.size.toLong()
        var remaining = fileLength

        if (fileLength == 0L) {
            val buffer = pollBuffer() ?: return
            synchronized(queueLock) {
                if (isShutdown) {
                    buffers.offer(buffer)
                    return
                }
                deque.put(
                    FileBlock(
                        isFile = true,
                        fileIndex = currentFileIndex,
                        path = transferPath,
                        lastModified = lastModified,
                        totalSize = 0L,
                        index = 0,
                        data = buffer,
                        dataLength = 0
                    )
                )
            }
            return
        }

        var blockIndex = 0
        var zipOffset = 0
        while (remaining > 0L && !isShutdown) {
            val blockSize = minOf(remaining, FileBlock.BLOCK_SIZE.toLong()).toInt()
            val buffer = pollBuffer() ?: break
            System.arraycopy(zipBytes, zipOffset, buffer, 0, blockSize)

            synchronized(queueLock) {
                if (isShutdown) {
                    buffers.offer(buffer)
                    return
                }
                deque.put(
                    FileBlock(
                        isFile = true,
                        fileIndex = currentFileIndex,
                        path = transferPath,
                        lastModified = lastModified,
                        totalSize = fileLength,
                        index = blockIndex,
                        data = buffer,
                        dataLength = blockSize
                    )
                )
            }

            remaining -= blockSize.toLong()
            zipOffset += blockSize
            blockIndex++
        }
    }

    private fun listFilesAndRead(folder: RemoteFile) {
        val subFiles = listLocalFiles(folder.path)
        for (file in subFiles) {
            if (isShutdown) break
            readToDeque(file)
            if (file.isDirectory && !isShutdown) {
                listFilesAndRead(file)
            }
        }
    }

    private fun pollBuffer(): ByteArray? {
        while (!isShutdown) {
            val buf = buffers.poll(50, TimeUnit.MILLISECONDS)
            if (buf != null) return buf
        }
        return null
    }

    private fun readToDeque(file: RemoteFile) {
        if (isShutdown) return
        val currentFileIndex = fileIndexCounter.incrementAndGet()
        val transferPath = localDir.generateTransferPath(file.path, remoteDir)

        if (file.isDirectory) {
            // Directory metadata frame: No payload, data = null
            synchronized(queueLock) {
                if (!isShutdown) {
                    deque.put(
                        FileBlock(
                            isFile = false,
                            fileIndex = currentFileIndex,
                            path = transferPath,
                            lastModified = file.lastModified,
                            totalSize = 0L,
                            index = 0,
                            data = null,
                            dataLength = 0
                        )
                    )
                }
            }
            return
        }

        val inputStream = openFile(file.path)
        currentInputStream = inputStream
        val fileLength = file.size
        val lastModified = file.lastModified
        var remaining = fileLength

        if (fileLength == 0L) {
            // Empty file slice: Acquire buffer, set dataLength = 0
            val buffer = pollBuffer() ?: run {
                closeCurrentFile()
                return
            }
            synchronized(queueLock) {
                if (isShutdown) {
                    buffers.offer(buffer)
                    closeCurrentFile()
                    return
                }
                deque.put(
                    FileBlock(
                        isFile = true,
                        fileIndex = currentFileIndex,
                        path = transferPath,
                        lastModified = lastModified,
                        totalSize = 0L,
                        index = 0,
                        data = buffer,
                        dataLength = 0
                    )
                )
            }
            closeCurrentFile()
            return
        }

        var blockIndex = 0
        while (remaining > 0L && !isShutdown) {
            val blockSize = minOf(remaining, FileBlock.BLOCK_SIZE.toLong()).toInt()
            val buffer = pollBuffer() ?: break

            var offset = 0
            var readFailed = false
            try {
                while (offset < blockSize && !isShutdown) {
                    val read = inputStream.read(buffer, offset, blockSize - offset)
                    if (read <= 0) {
                        buffers.offer(buffer)
                        readFailed = true
                        if (isShutdown) {
                            break
                        }
                        throw EOFException("Unexpected end of stream reading file ${file.path}")
                    }
                    offset += read
                }
            } catch (ioe: Throwable) {
                buffers.offer(buffer)
                readFailed = true
                if (isShutdown) {
                    break
                }
                throw ioe
            }

            if (readFailed) break

            var shouldBreak = false
            synchronized(queueLock) {
                if (isShutdown) {
                    buffers.offer(buffer)
                    shouldBreak = true
                } else {
                    deque.put(
                        FileBlock(
                            isFile = true,
                            fileIndex = currentFileIndex,
                            path = transferPath,
                            lastModified = lastModified,
                            totalSize = fileLength,
                            index = blockIndex,
                            data = buffer,
                            dataLength = blockSize
                        )
                    )
                }
            }
            if (shouldBreak) break
            remaining -= blockSize
            blockIndex++
        }

        closeCurrentFile()
    }

    /**
     * Pulls the next available [FileBlock] from the queue, blocking until one is available.
     */
    fun takeBlock(): FileBlock {
        return deque.take()
    }

    /**
     * Pulls the next available [FileBlock] with a timeout.
     */
    fun takeBlock(timeoutMs: Long): FileBlock? {
        return deque.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Recycles a buffer back to the memory pool.
     */
    fun recycleBuffer(buffer: ByteArray?) {
        if (buffer != null) {
            buffers.offer(buffer)
        }
    }

    /**
     * Called when receiver reports write error on control or data channel.
     */
    fun shutdownByWriteError() {
        synchronized(queueLock) {
            isShutdown = true
            recycleAllBuffers()
            clearAndAddAll(WRITE_ERROR)
        }
    }

    /**
     * Called when a data connection drops unexpectedly.
     */
    fun shutdownByConnectionBreak() {
        synchronized(queueLock) {
            isShutdown = true
            recycleAllBuffers()
            clearAndAddAll(INTERRUPT)
        }
    }

    private fun recycleAllBuffers() {
        val drained = mutableListOf<FileBlock>()
        deque.drainTo(drained)
        for (block in drained) {
            if (block.data != null) {
                recycleBuffer(block.data)
            }
        }
    }

    private fun clearAndAddAll(sentinel: FileBlock) {
        deque.clear()
        for (i in 0 until operateThreadCount) {
            deque.put(sentinel)
        }
    }

    private fun fileExists(path: String): Boolean {
        val f = File(path)
        return f.exists()
    }

    private fun listLocalFiles(path: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        val f = File(path)
        if (f.exists() && f.isDirectory) {
            f.listFiles()?.forEach { entry ->
                result.add(
                    RemoteFile(
                        name = entry.name,
                        path = entry.absolutePath,
                        lastModified = entry.lastModified(),
                        size = if (entry.isDirectory) 0L else entry.length(),
                        isDirectory = entry.isDirectory
                    )
                )
            }
        }
        return result
    }

    private fun openFile(path: String): InputStream {
        return storageResolver?.invoke(path) ?: FileInputStream(File(path))
    }

    private fun closeCurrentFile() {
        try {
            currentInputStream?.close()
        } catch (_: Throwable) {}
        currentInputStream = null
    }
}

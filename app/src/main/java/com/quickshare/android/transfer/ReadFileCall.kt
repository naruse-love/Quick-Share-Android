package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val storageResolver: ((path: String) -> InputStream)? = null
) {
    private val deque: BlockingQueue<FileBlock> = LinkedBlockingQueue()
    private val fileIndexCounter = AtomicInteger(-1)
    private var currentInputStream: InputStream? = null
    private val queueLock = Any()
    @Volatile
    private var isShutdown: Boolean = false

    companion object {
        val END_POINT = FileBlock.END_POINT
        val INTERRUPT = FileBlock.INTERRUPT
        val READ_ERROR = FileBlock.READ_ERROR
        val WRITE_ERROR = FileBlock.WRITE_ERROR
    }

    /**
     * Executes the slicing loop asynchronously.
     * Enqueues FOLDER and FILE blocks, concluding with [operateThreadCount] END_POINT markers.
     */
    suspend fun executeAsync() = withContext(Dispatchers.IO) {
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

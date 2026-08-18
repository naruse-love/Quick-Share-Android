package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.BlockingQueue

/**
 * Sequential LAN Stream Disk Writer for QuickShareProtocol v300.
 *
 * Receives chunk streams sequentially over pure LAN connection, performs direct 64-bit
 * random-access seeking and writing, recycles 1MB buffers back to the [BufferPool],
 * and restores original file timestamps.
 */
class WriteFileCall(
    val bufferPool: BufferPool,
    val channelCount: Int = 1,
    val storageManager: IStorageManager = DirectStorageEngine()
) {
    private val lock = Any()
    private val channelFinished = BooleanArray(channelCount)
    private val queue: ArrayDeque<FileBlock> = ArrayDeque()

    @Volatile
    private var canceled: Boolean = false
    private var currentHandle: RandomAccessHandle? = null

    /**
     * Secondary constructor accepting raw [BlockingQueue] of buffers for compatibility.
     */
    constructor(
        buffers: BlockingQueue<ByteArray>,
        channelCount: Int = 1,
        storageManager: IStorageManager = DirectStorageEngine()
    ) : this(
        bufferPool = BufferPool(maxOf(1, buffers.size)),
        channelCount = channelCount,
        storageManager = storageManager
    )

    /**
     * Acquires a reusable 1MB byte buffer from the [BufferPool].
     */
    fun getBuffer(): ByteArray {
        return bufferPool.acquire()
    }

    /**
     * Appends a received [FileBlock] to the sequential FIFO queue.
     */
    fun putBlock(block: FileBlock, channelIndex: Int = 0) {
        synchronized(lock) {
            queue.addLast(block)
            (lock as Object).notifyAll()
        }
    }

    /**
     * Marks the data stream as finished (received EOF or disconnected).
     */
    fun finishChannel(channelIndex: Int = 0) {
        synchronized(lock) {
            if (channelIndex in 0 until channelCount) {
                channelFinished[channelIndex] = true
            } else if (channelFinished.isNotEmpty()) {
                channelFinished[0] = true
            }
            (lock as Object).notifyAll()
        }
    }

    /**
     * Cancels the write operation, clears the queue, and returns in-flight buffers to [BufferPool].
     */
    fun cancel() {
        synchronized(lock) {
            canceled = true
            while (queue.isNotEmpty()) {
                val block = queue.removeFirst()
                if (block.data != null) {
                    bufferPool.release(block.data)
                }
            }
            (lock as Object).notifyAll()
        }
    }

    /**
     * Asynchronous execution loop for coroutine contexts.
     */
    suspend fun executeAsync() = withContext(Dispatchers.IO) {
        executeInternal()
    }

    /**
     * Synchronous execution loop. Writes chunks to disk in sequential order.
     */
    @Throws(IOException::class)
    fun execute() {
        executeInternal()
    }

    private fun executeInternal() {
        var lastBlock: FileBlock? = null
        var cursor = 0L

        try {
            var block = takeBlock()

            while (block != null) {
                if (!block.isFile) {
                    // Directory metadata frame: create directory hierarchy and restore timestamp
                    storageManager.mkdirs(block.path)
                    if (block.lastModified > 0L) {
                        storageManager.setLastModified(block.path, block.lastModified)
                    }
                    block = takeBlock()
                    continue
                }

                storageManager.createParentDirIfNotExists(block.path)

                // Detect transition to a different file
                if (lastBlock == null || lastBlock.path != block.path) {
                    if (currentHandle != null) {
                        closeCurrentFile()
                        if (lastBlock != null && lastBlock.lastModified > 0L) {
                            storageManager.setLastModified(lastBlock.path, lastBlock.lastModified)
                        }
                    }
                    currentHandle = createAndOpenFile(block.path, block.totalSize)
                    cursor = 0L
                }

                // Direct 64-bit random-access seek
                val startPos = block.getStartPosition()
                if (cursor != startPos) {
                    cursor = startPos
                    currentHandle?.seek(cursor)
                }

                // Write chunk payload to disk
                if (block.data != null && block.dataLength > 0) {
                    currentHandle?.write(block.data, 0, block.dataLength)
                    cursor += block.dataLength
                    // Recycle buffer back to BufferPool immediately
                    bufferPool.release(block.data)
                } else if (block.totalSize == 0L) {
                    // Empty file handling: truncate length to 0 and recycle buffer
                    currentHandle?.setLength(0L)
                    if (block.data != null) {
                        bufferPool.release(block.data)
                    }
                }

                lastBlock = block
                block = takeBlock()
            }

            // Close the final file and restore its timestamp
            if (lastBlock != null) {
                closeCurrentFile()
                if (lastBlock.lastModified > 0L) {
                    storageManager.setLastModified(lastBlock.path, lastBlock.lastModified)
                }
            }
        } catch (t: Throwable) {
            cancel()
            closeCurrentFile()
            throw if (t is IOException) t else IOException("WriteFileCall failed", t)
        }
    }

    /**
     * Blocks until a block is available in the queue, or returns null on completion/cancellation.
     */
    fun takeBlock(): FileBlock? {
        while (true) {
            synchronized(lock) {
                if (queue.isNotEmpty()) {
                    return queue.removeFirst()
                }
                if (canceled || allChannelsFinished()) {
                    return null
                }
                try {
                    (lock as Object).wait()
                } catch (e: InterruptedException) {
                    canceled = true
                    return null
                }
            }
        }
    }

    private fun allChannelsFinished(): Boolean {
        for (f in channelFinished) {
            if (!f) return false
        }
        return true
    }

    private fun createAndOpenFile(path: String, length: Long): RandomAccessHandle {
        val handle = storageManager.openRandomAccess(path, "rw")
        if (handle.length() < length) {
            handle.setLength(length)
        }
        return handle
    }

    private fun closeCurrentFile() {
        try {
            currentHandle?.close()
        } catch (_: Throwable) {
        } finally {
            currentHandle = null
        }
    }
}


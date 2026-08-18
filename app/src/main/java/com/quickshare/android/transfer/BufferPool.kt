package com.quickshare.android.transfer

import com.quickshare.android.protocol.QuickShareProtocolConstants
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * High-performance, zero-GC memory recycling pool for 1MB transfer buffers.
 * Thread-safe for multi-channel concurrent access.
 *
 * @property poolSize Number of pre-allocated buffers (default 8 = 8MB total heap memory).
 * @property bufferSize Size of each buffer in bytes (default 1,048,576 bytes = 1MB).
 */
class BufferPool(
    val poolSize: Int = QuickShareProtocolConstants.DEFAULT_BUFFER_COUNT,
    val bufferSize: Int = QuickShareProtocolConstants.BLOCK_SIZE
) {
    private val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(poolSize)

    init {
        require(poolSize > 0) { "Pool size must be positive, got: $poolSize" }
        require(bufferSize > 0) { "Buffer size must be positive, got: $bufferSize" }
        for (i in 0 until poolSize) {
            queue.add(ByteArray(bufferSize))
        }
    }

    val rawQueue: BlockingQueue<ByteArray>
        get() = queue

    /**
     * Acquires a buffer from the pool, blocking indefinitely until one is available.
     *
     * @return A 1MB [ByteArray] buffer.
     * @throws InterruptedException if interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    fun acquire(): ByteArray {
        return queue.take()
    }

    /**
     * Acquires a buffer from the pool, waiting up to [timeoutMs] milliseconds.
     *
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @return A 1MB [ByteArray] or null if timeout elapsed without an available buffer.
     */
    fun acquire(timeoutMs: Long): ByteArray? {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Recycles a buffer back into the pool.
     * Buffers not matching [bufferSize] or excess buffers are safely ignored.
     *
     * @param buffer Buffer to recycle.
     */
    fun release(buffer: ByteArray?) {
        if (buffer != null && buffer.size == bufferSize) {
            queue.offer(buffer)
        }
    }

    /**
     * Alias for [release] to match C# and Java API naming conventions.
     */
    fun recycle(buffer: ByteArray?) = release(buffer)

    /**
     * Returns the count of buffers currently available in the pool.
     */
    fun availableCount(): Int = queue.size

    /**
     * Checks if all pre-allocated buffers have returned to the pool (no leaks).
     */
    fun isFull(): Boolean = queue.size == poolSize
}

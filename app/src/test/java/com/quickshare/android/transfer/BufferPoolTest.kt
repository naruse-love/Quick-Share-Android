package com.quickshare.android.transfer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests validating zero-GC BufferPool recycling, timeout handling, and multi-thread concurrency.
 */
class BufferPoolTest {

    @Test
    fun testBufferPoolInitialization() {
        val pool = BufferPool(8, 1024 * 1024)
        assertEquals(8, pool.availableCount())
        assertTrue(pool.isFull())

        val b1 = pool.acquire()
        assertNotNull(b1)
        assertEquals(1024 * 1024, b1.size)
        assertEquals(7, pool.availableCount())
        assertFalse(pool.isFull())

        pool.release(b1)
        assertEquals(8, pool.availableCount())
        assertTrue(pool.isFull())
    }

    @Test
    fun testConcurrentBufferAcquireAndRelease() {
        val pool = BufferPool(8, 1024 * 1024)
        val numThreads = 16
        val operationsPerThread = 200
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val successCount = AtomicInteger(0)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until operationsPerThread) {
                        val buf = pool.acquire(5000)
                        if (buf != null) {
                            // Verify buffer integrity
                            buf[0] = (i % 128).toByte()
                            buf[buf.size - 1] = ((i + 1) % 128).toByte()
                            pool.recycle(buf)
                            successCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertEquals(numThreads * operationsPerThread, successCount.get())
        assertEquals(8, pool.availableCount())
        assertTrue(pool.isFull())
        executor.shutdownNow()
    }

    @Test
    fun testBufferExhaustionAndTimeout() {
        val pool = BufferPool(2, 1024)
        val b1 = pool.acquire(100)
        val b2 = pool.acquire(100)
        assertNotNull(b1)
        assertNotNull(b2)
        assertEquals(0, pool.availableCount())
        assertFalse(pool.isFull())

        // Acquire 3rd buffer should timeout
        val b3 = pool.acquire(200)
        assertNull(b3)

        // Release one, now acquire should succeed
        pool.release(b1!!)
        val b3Retry = pool.acquire(200)
        assertNotNull(b3Retry)

        pool.release(b2!!)
        pool.release(b3Retry!!)
        assertEquals(2, pool.availableCount())
        assertTrue(pool.isFull())
    }

    @Test
    fun testIgnoreMismatchedOrExcessBuffers() {
        val pool = BufferPool(2, 1024)
        val b1 = pool.acquire()
        val b2 = pool.acquire()

        // Attempting to release a wrong-sized buffer should be ignored
        val wrongBuffer = ByteArray(512)
        pool.release(wrongBuffer)
        assertEquals(0, pool.availableCount())

        // Releasing correct buffers restores pool
        pool.release(b1)
        pool.release(b2)
        assertEquals(2, pool.availableCount())

        // Attempting to release excess buffer is safely ignored (bounded queue)
        val extraBuffer = ByteArray(1024)
        pool.release(extraBuffer)
        assertEquals(2, pool.availableCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidPoolSizeThrows() {
        BufferPool(0, 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidBufferSizeThrows() {
        BufferPool(8, 0)
    }
}

package com.quickshare.android.transfer

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * StorageManager abstraction unit tests for Direct Unix I/O and RandomAccess handles.
 */
class StorageManagerTest {

    private val sandboxDir = File(System.getProperty("java.io.tmpdir"), "storage_mgr_test_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun testDirectRandomAccessFileWriteSeekRead() {
        val engine = DirectStorageEngine(sandboxDir)
        val handle = engine.openRandomAccess("test_raf.dat")

        val block0 = "BLOCK_0_DATA".toByteArray(Charsets.UTF_8)
        val block1 = "BLOCK_1_DATA".toByteArray(Charsets.UTF_8)

        handle.seek(0)
        handle.write(block0, 0, block0.size)

        handle.seek(1024 * 1024L) // Seek 1MB
        handle.write(block1, 0, block1.size)

        assertEquals(1024 * 1024L + block1.size, handle.length())

        val readBuf = ByteArray(block1.size)
        handle.seek(1024 * 1024L)
        val readBytes = handle.read(readBuf, 0, readBuf.size)
        assertEquals(block1.size, readBytes)
        assertArrayEquals(block1, readBuf)

        handle.close()
    }

    @Test
    fun testByteBufferWriteAndRead() {
        val engine = DirectStorageEngine(sandboxDir)
        val handle = engine.openRandomAccess("test_bytebuf.dat")

        val byteBuffer = ByteBuffer.allocate(100)
        for (i in 0 until 100) {
            byteBuffer.put(i.toByte())
        }
        byteBuffer.flip()

        handle.write(byteBuffer)
        assertEquals(100L, handle.length())

        handle.seek(0L)
        val readBuffer = ByteBuffer.allocate(100)
        val readCount = handle.read(readBuffer)
        assertEquals(100, readCount)
        readBuffer.flip()

        for (i in 0 until 100) {
            assertEquals(i.toByte(), readBuffer.get())
        }

        handle.close()
    }

    @Test
    fun testDirectoryAndFileOperations() {
        val engine = DirectStorageEngine(sandboxDir)

        assertTrue(engine.mkdir("", "nested_folder"))
        assertTrue(engine.exists("nested_folder"))

        val handle = engine.openRandomAccess("nested_folder/item.txt")
        val data = "Hello Storage".toByteArray()
        handle.write(data, 0, data.size)
        handle.close()

        assertEquals(data.size.toLong(), engine.getFileSize("nested_folder/item.txt"))

        val files = engine.listFiles("nested_folder")
        assertEquals(1, files.size)
        assertEquals("item.txt", files[0].name)
        assertFalse(files[0].isDirectory)
        assertEquals(data.size.toLong(), files[0].size)

        assertTrue(engine.delete("nested_folder"))
        assertFalse(engine.exists("nested_folder"))
    }

    @Test
    fun testTimestampPreservation() {
        val engine = DirectStorageEngine(sandboxDir)
        val handle = engine.openRandomAccess("time_test.bin")
        handle.write(byteArrayOf(1, 2, 3), 0, 3)
        handle.close()

        val expectedTime = 1600000000000L // fixed past timestamp
        assertTrue(engine.setLastModified("time_test.bin", expectedTime))

        val actualTime = File(sandboxDir, "time_test.bin").lastModified()
        // Allow within 2000ms variance due to filesystem FAT/ext4 timestamp granularity
        assertTrue(Math.abs(expectedTime - actualTime) <= 2000)
    }

    @Test
    fun testSetLengthAndTruncation() {
        val engine = DirectStorageEngine(sandboxDir)
        val handle = engine.openRandomAccess("truncate_test.bin")

        handle.setLength(5000L)
        assertEquals(5000L, handle.length())

        handle.setLength(2000L)
        assertEquals(2000L, handle.length())

        handle.close()
    }

    @Test
    fun testStorageManagerDirectRouting() {
        val sm = StorageManager(null, directEngine = DirectStorageEngine(sandboxDir))
        assertTrue(sm.isDirectAccessAvailable())

        val handle = sm.openRandomAccess("routing_test.bin")
        handle.write(byteArrayOf(10, 20, 30))
        handle.close()

        assertTrue(sm.exists("routing_test.bin"))
        assertEquals(3L, sm.getFileSize("routing_test.bin"))

        val stream = sm.openForRead("routing_test.bin")
        val b0 = stream.read()
        assertEquals(10, b0)
        stream.close()
    }
}

package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for [WriteFileCall] multi-channel reordering, 64-bit seek math,
 * directory frames, and buffer recycling.
 */
class WriteFileCallTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "write_file_call_test_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun testMultiChannelOutOfOrderReassembly() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val engine = DirectStorageEngine(tempDir)
        val writeFileCall = WriteFileCall(pool, channelCount = 3, storageManager = engine)

        val targetPath = "assembled.bin"
        val totalSize = 3 * 1024 * 1024L

        // Prepare 3 chunks
        val c0 = pool.acquire()
        val c1 = pool.acquire()
        val c2 = pool.acquire()
        for (i in 0 until 1024 * 1024) {
            c0[i] = (0).toByte()
            c1[i] = (1).toByte()
            c2[i] = (2).toByte()
        }

        val b0 = FileBlock(true, 0, targetPath, 1600000000000L, totalSize, 0, c0, 1024 * 1024)
        val b1 = FileBlock(true, 0, targetPath, 1600000000000L, totalSize, 1, c1, 1024 * 1024)
        val b2 = FileBlock(true, 0, targetPath, 1600000000000L, totalSize, 2, c2, 1024 * 1024)

        // Launch writer in background
        val writerJob = async {
            writeFileCall.executeAsync()
        }

        // Push chunks across 3 channels in reverse/scrambled order:
        // Channel 0 receives Chunk 2
        // Channel 1 receives Chunk 1
        // Channel 2 receives Chunk 0
        writeFileCall.putBlock(b2, 0)
        writeFileCall.putBlock(b1, 1)
        writeFileCall.putBlock(b0, 2)

        // Finish all channels
        writeFileCall.finishChannel(0)
        writeFileCall.finishChannel(1)
        writeFileCall.finishChannel(2)

        writerJob.await()

        val assembledFile = File(tempDir, targetPath)
        assertTrue(assembledFile.exists())
        assertEquals(totalSize, assembledFile.length())

        // Verify content
        val bytes = assembledFile.readBytes()
        assertEquals(0.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1024 * 1024])
        assertEquals(2.toByte(), bytes[2 * 1024 * 1024])

        // Verify buffer pool has all 8 buffers returned (zero leak)
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun testDirectoryAndEmptyFileHandling() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val engine = DirectStorageEngine(tempDir)
        val writeFileCall = WriteFileCall(pool, channelCount = 1, storageManager = engine)

        val writerJob = async {
            writeFileCall.executeAsync()
        }

        // Folder block
        val folderBlock = FileBlock(
            isFile = false,
            fileIndex = 0,
            path = "folder1/subfolder",
            lastModified = 1600000000000L,
            totalSize = 0L,
            index = 0,
            data = null,
            dataLength = 0
        )
        writeFileCall.putBlock(folderBlock, 0)

        // Empty file block inside folder
        val emptyBuf = pool.acquire()
        val emptyFileBlock = FileBlock(
            isFile = true,
            fileIndex = 1,
            path = "folder1/subfolder/empty.dat",
            lastModified = 1600000000000L,
            totalSize = 0L,
            index = 0,
            data = emptyBuf,
            dataLength = 0
        )
        writeFileCall.putBlock(emptyFileBlock, 0)

        writeFileCall.finishChannel(0)
        writerJob.await()

        val dir = File(tempDir, "folder1/subfolder")
        assertTrue(dir.exists() && dir.isDirectory)

        val emptyFile = File(dir, "empty.dat")
        assertTrue(emptyFile.exists())
        assertEquals(0L, emptyFile.length())

        assertEquals(8, pool.availableCount())
    }

    @Test
    fun test64BitSeekPositionCalculation() {
        val largeBlock = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "huge.bin",
            lastModified = 0L,
            totalSize = 5000000000L, // ~5GB
            index = 3000, // 3000 * 1MB = 3,145,728,000 bytes (> 2GB Int.MAX_VALUE)
            data = null,
            dataLength = 1048576
        )

        val startPos = largeBlock.getStartPosition()
        assertEquals(3000L * 1024 * 1024L, startPos)
        assertTrue(startPos > Int.MAX_VALUE)
    }

    @Test
    fun testCancellationRollsBackAndRecyclesBuffers() {
        val pool = BufferPool(4, 1024 * 1024)
        val engine = DirectStorageEngine(tempDir)
        val writeFileCall = WriteFileCall(pool, channelCount = 2, storageManager = engine)

        val b1 = pool.acquire()
        val b2 = pool.acquire()
        assertEquals(2, pool.availableCount())

        writeFileCall.putBlock(FileBlock(true, 0, "file1", 0L, 100L, 0, b1, 100), 0)
        writeFileCall.putBlock(FileBlock(true, 0, "file2", 0L, 100L, 1, b2, 100), 1)

        writeFileCall.cancel()

        // Buffers must be returned to pool
        assertEquals(4, pool.availableCount())
    }
}

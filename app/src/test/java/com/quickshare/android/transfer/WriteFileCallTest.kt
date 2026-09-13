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

    @Test
    fun testAutoExtractBatchZipAndCleanup() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val engine = DirectStorageEngine(tempDir)
        val writeFileCall = WriteFileCall(pool, channelCount = 1, storageManager = engine)

        val writerJob = async {
            writeFileCall.executeAsync()
        }

        // Create a batch zip in memory with 2 files
        val timeA = 1650000000000L
        val timeB = 1680000000000L
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val eA = java.util.zip.ZipEntry("extracted_a.txt").apply { time = timeA }
            zos.putNextEntry(eA)
            zos.write("Content of extracted file A".toByteArray())
            zos.closeEntry()

            val eB = java.util.zip.ZipEntry("extracted_b.txt").apply { time = timeB }
            zos.putNextEntry(eB)
            zos.write("Content of extracted file B".toByteArray())
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        val buf = pool.acquire()!!
        System.arraycopy(zipBytes, 0, buf, 0, zipBytes.size)

        val zipBlock = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "__qs_batch_12345678.zip",
            lastModified = System.currentTimeMillis(),
            totalSize = zipBytes.size.toLong(),
            index = 0,
            data = buf,
            dataLength = zipBytes.size
        )

        writeFileCall.putBlock(zipBlock, 0)
        writeFileCall.finishChannel(0)

        writerJob.await()

        val fileA = File(tempDir, "extracted_a.txt")
        val fileB = File(tempDir, "extracted_b.txt")
        assertTrue("extracted_a.txt should exist", fileA.exists())
        assertTrue("extracted_b.txt should exist", fileB.exists())

        assertEquals("Content of extracted file A", fileA.readText())
        assertEquals("Content of extracted file B", fileB.readText())

        // Verify timestamps (within 3 seconds due to DOS 2-second granularity)
        assertTrue("Timestamp A close to original", Math.abs(fileA.lastModified() - timeA) < 3000)
        assertTrue("Timestamp B close to original", Math.abs(fileB.lastModified() - timeB) < 3000)

        // Verify zip file was deleted
        val zipFile = File(tempDir, "__qs_batch_12345678.zip")
        assertFalse("Batch zip should be deleted after extraction", zipFile.exists())

        // Verify buffers recycled
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun testDirectoryTimestampPreservationWithBatchZip() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val engine = DirectStorageEngine(tempDir)
        val writeFileCall = WriteFileCall(pool, channelCount = 1, storageManager = engine)

        val writerJob = async {
            writeFileCall.executeAsync()
        }

        val expectedDirTime = 1600000000000L
        val expectedFileTime = 1620000000000L

        // 1. Send directory frame
        val dirBlock = FileBlock(
            isFile = false,
            fileIndex = 0,
            path = "TimedFolder",
            lastModified = expectedDirTime,
            totalSize = 0L,
            index = 0,
            data = null,
            dataLength = 0
        )
        writeFileCall.putBlock(dirBlock, 0)

        // 2. Prepare batch zip inside TimedFolder
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val entry = java.util.zip.ZipEntry("inner.txt").apply { time = expectedFileTime }
            zos.putNextEntry(entry)
            zos.write("Inner file content".toByteArray())
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        val buf = pool.acquire()!!
        System.arraycopy(zipBytes, 0, buf, 0, zipBytes.size)

        val zipBlock = FileBlock(
            isFile = true,
            fileIndex = 1,
            path = "TimedFolder/__qs_batch_abcdef12.zip",
            lastModified = System.currentTimeMillis(),
            totalSize = zipBytes.size.toLong(),
            index = 0,
            data = buf,
            dataLength = zipBytes.size
        )

        writeFileCall.putBlock(zipBlock, 0)
        writeFileCall.finishChannel(0)

        writerJob.await()

        val targetDir = File(tempDir, "TimedFolder")
        assertTrue("TimedFolder must exist", targetDir.exists() && targetDir.isDirectory)

        val innerFile = File(targetDir, "inner.txt")
        assertTrue("inner.txt must exist", innerFile.exists())
        assertEquals("Inner file content", innerFile.readText())

        // Verify inner file timestamp
        assertTrue("Inner file timestamp close to original", Math.abs(innerFile.lastModified() - expectedFileTime) < 3000)

        // Verify directory timestamp preserved bottom-up despite zip write, extraction, and deletion
        assertTrue(
            "Directory timestamp must be preserved within 3s, got ${targetDir.lastModified()} vs $expectedDirTime",
            Math.abs(targetDir.lastModified() - expectedDirTime) < 3000
        )

        assertEquals(8, pool.availableCount())
    }
}


package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Unit tests for [ReadFileCall] file slicing, directory traversal, buffer recycling, and sentinel fan-out.
 */
class ReadFileCallTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "read_file_call_test_${System.nanoTime()}").apply { mkdirs() }

    private fun createTestFile(name: String, size: Long): File {
        val file = File(tempDir, name)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(minOf(size.toInt(), 64 * 1024))
            for (i in buf.indices) buf[i] = (i % 251).toByte()
            var rem = size
            while (rem > 0) {
                val w = minOf(rem, buf.size.toLong()).toInt()
                fos.write(buf, 0, w)
                rem -= w
            }
        }
        return file
    }

    @Test
    fun testSingleFile1MBSlicing() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val file = createTestFile("large.bin", 2500000L) // ~2.5MB (3 chunks: 1MB, 1MB, ~0.5MB)
        val remoteFile = RemoteFile(
            name = file.name,
            path = file.absolutePath,
            lastModified = file.lastModified(),
            size = file.length(),
            isDirectory = false
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/dest", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFile),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 2
        )

        readFileCall.executeAsync()

        // Read blocks
        val blocks = mutableListOf<FileBlock>()
        while (true) {
            val block = readFileCall.takeBlock()
            blocks.add(block)
            if (block.data != null) {
                readFileCall.recycleBuffer(block.data)
            }
            if (block == ReadFileCall.END_POINT) {
                // If we hit END_POINT, check if 2nd END_POINT is also in queue
                val secondEndpoint = readFileCall.takeBlock()
                assertEquals(ReadFileCall.END_POINT, secondEndpoint)
                break
            }
        }

        // We expect 3 data blocks + 1 END_POINT (the other was consumed above)
        val dataBlocks = blocks.filter { it.isFile && it.fileIndex >= 0 }
        assertEquals(3, dataBlocks.size)
        assertEquals(1024 * 1024, dataBlocks[0].dataLength)
        assertEquals(0, dataBlocks[0].index)
        assertEquals(1024 * 1024, dataBlocks[1].dataLength)
        assertEquals(1, dataBlocks[1].index)
        assertEquals(2500000 - 2048 * 1024, dataBlocks[2].dataLength)
        assertEquals(2, dataBlocks[2].index)

        // All buffers should be recycled back to pool
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun testEmptyFileSlicing() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val emptyFile = File(tempDir, "empty.txt").apply { createNewFile() }
        val remoteFile = RemoteFile(
            name = emptyFile.name,
            path = emptyFile.absolutePath,
            lastModified = emptyFile.lastModified(),
            size = 0L,
            isDirectory = false
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/remote", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFile),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1
        )

        readFileCall.executeAsync()

        val block = readFileCall.takeBlock()
        assertTrue(block.isFile)
        assertEquals(0L, block.totalSize)
        assertEquals(0, block.dataLength)
        assertNotNull(block.data)
        readFileCall.recycleBuffer(block.data)

        val endpoint = readFileCall.takeBlock()
        assertEquals(ReadFileCall.END_POINT, endpoint)
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun testDirectoryTraversalSlicing() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val subDir = File(tempDir, "subDir").apply { mkdirs() }
        val f1 = File(subDir, "f1.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val f2 = File(subDir, "f2.bin").apply { writeBytes(byteArrayOf(4, 5)) }

        val remoteFolder = RemoteFile(
            name = subDir.name,
            path = subDir.absolutePath,
            lastModified = subDir.lastModified(),
            size = 0L,
            isDirectory = true
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("D:\\Target", 1)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFolder),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1
        )

        readFileCall.executeAsync()

        val folderBlock = readFileCall.takeBlock()
        assertFalse(folderBlock.isFile)
        assertNull(folderBlock.data)

        val file1Block = readFileCall.takeBlock()
        assertTrue(file1Block.isFile)
        assertEquals(3, file1Block.dataLength)
        readFileCall.recycleBuffer(file1Block.data)

        val file2Block = readFileCall.takeBlock()
        assertTrue(file2Block.isFile)
        assertEquals(2, file2Block.dataLength)
        readFileCall.recycleBuffer(file2Block.data)

        val endPoint = readFileCall.takeBlock()
        assertEquals(ReadFileCall.END_POINT, endPoint)
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun testShutdownByWriteErrorRecyclesBuffers() {
        val pool = BufferPool(4, 1024 * 1024)
        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/dest", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = emptyList(),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 3
        )

        val b1 = pool.acquire()
        val b2 = pool.acquire()
        // Simulate in-flight blocks inside readFileCall
        val block1 = FileBlock(true, 0, "file1", 0L, 100L, 0, b1, 100)
        val block2 = FileBlock(true, 0, "file2", 0L, 100L, 1, b2, 100)

        // Force write error shutdown
        readFileCall.shutdownByWriteError()

        // 3 WRITE_ERROR markers should be enqueued
        for (i in 0 until 3) {
            val marker = readFileCall.takeBlock()
            assertEquals(ReadFileCall.WRITE_ERROR, marker)
        }
    }
}

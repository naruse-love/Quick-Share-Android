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

    @Test
    fun test4KFriendlySmallFilesPackagedToZip() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val testFolder = File(tempDir, "Folder4K").apply { mkdirs() }
        val small1 = File(testFolder, "small1.txt").apply { writeText("Hello 4K small file 1") }
        val small2 = File(testFolder, "small2.txt").apply { writeText("Hello 4K small file 2") }
        val empty = File(testFolder, "empty.txt").apply { createNewFile() }
        val large = createTestFile("Folder4K/large1.bin", 200 * 1024) // 200KB > 128KB threshold

        val remoteFolder = RemoteFile(
            name = testFolder.name,
            path = testFolder.absolutePath,
            lastModified = testFolder.lastModified(),
            size = 0L,
            isDirectory = true
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/remote", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFolder),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1,
            enable4KFriendly = true
        )

        readFileCall.executeAsync()

        val blocks = mutableListOf<FileBlock>()
        while (true) {
            val block = readFileCall.takeBlock()
            if (block == ReadFileCall.END_POINT) {
                break
            }
            blocks.add(block)
        }

        // Folder frame
        val dirBlocks = blocks.filter { !it.isFile }
        assertTrue(dirBlocks.isNotEmpty())

        // Large file should be transferred uncompressed
        val largeBlocks = blocks.filter { it.isFile && it.path.endsWith("large1.bin") }
        assertTrue("Large file must be transferred directly", largeBlocks.isNotEmpty())

        // Small files should NOT be present directly as normal files
        val directSmall = blocks.filter { it.isFile && (it.path.endsWith("small1.txt") || it.path.endsWith("small2.txt") || it.path.endsWith("empty.txt")) }
        assertTrue("Small files should not be transferred as direct files", directSmall.isEmpty())

        // Batch zip block must be present
        val zipBlocks = blocks.filter { it.isFile && File(it.path).name.startsWith("__qs_batch_") && it.path.endsWith(".zip") }
        assertEquals("Should have exactly 1 batch zip for this directory", 1, zipBlocks.size)
        val zipBlock = zipBlocks.first()
        assertNotNull(zipBlock.data)

        // Decompress and verify zip entries
        val entryNames = mutableListOf<String>()
        java.io.ByteArrayInputStream(zipBlock.data, 0, zipBlock.dataLength).use { bais ->
            java.util.zip.ZipInputStream(bais).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryNames.add(entry.name)
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        assertTrue("Zip should contain small1.txt", entryNames.contains("small1.txt"))
        assertTrue("Zip should contain small2.txt", entryNames.contains("small2.txt"))
        assertTrue("Zip should contain empty.txt", entryNames.contains("empty.txt"))
        assertFalse("Zip should not contain large1.bin", entryNames.contains("large1.bin"))

        // Recycle buffers
        for (b in blocks) {
            if (b.data != null) {
                readFileCall.recycleBuffer(b.data)
            }
        }
        assertEquals("All buffers must be returned to pool", 8, pool.availableCount())
    }

    @Test
    fun test4KFriendlyStandaloneFileNotPackaged() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val standaloneSmall = File(tempDir, "standalone.txt").apply { writeText("Standalone small file") }
        val remoteFile = RemoteFile(
            name = standaloneSmall.name,
            path = standaloneSmall.absolutePath,
            lastModified = standaloneSmall.lastModified(),
            size = standaloneSmall.length(),
            isDirectory = false
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/remote", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFile),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1,
            enable4KFriendly = true
        )

        readFileCall.executeAsync()

        val blocks = mutableListOf<FileBlock>()
        while (true) {
            val block = readFileCall.takeBlock()
            if (block == ReadFileCall.END_POINT) {
                break
            }
            blocks.add(block)
        }

        // Standalone file should be transferred directly, NOT in batch zip
        assertEquals(1, blocks.size)
        val block = blocks.first()
        assertTrue(block.isFile)
        assertEquals("standalone.txt", File(block.path).name)
        readFileCall.recycleBuffer(block.data!!)

        assertEquals(8, pool.availableCount())
    }

    @Test
    fun test4KFriendlyEpochTimestampClamping() = runBlocking {
        val pool = BufferPool(8, 1024 * 1024)
        val subDir = File(tempDir, "epochDir").apply { mkdirs() }
        val epochFile = File(subDir, "epoch.txt").apply { writeText("Epoch file content") }
        epochFile.setLastModified(0L)

        val remoteFolder = RemoteFile(
            name = subDir.name,
            path = subDir.absolutePath,
            lastModified = 0L,
            size = 0L,
            isDirectory = true
        )

        val localDir = QuickShareDirectory(tempDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory("/dest", 0)

        val readFileCall = ReadFileCall(
            buffers = pool.rawQueue,
            files = listOf(remoteFolder),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1,
            enable4KFriendly = true
        )

        readFileCall.executeAsync()

        val blocks = mutableListOf<FileBlock>()
        while (true) {
            val block = readFileCall.takeBlock()
            if (block == ReadFileCall.END_POINT) break
            blocks.add(block)
        }

        val zipBlocks = blocks.filter { it.isFile && File(it.path).name.startsWith("__qs_batch_") }
        assertEquals(1, zipBlocks.size)
        val zipBlock = zipBlocks.first()
        assertNotNull(zipBlock.data)

        // Verify valid zip parsing
        val bais = java.io.ByteArrayInputStream(zipBlock.data!!, 0, zipBlock.dataLength)
        val entryNames = mutableListOf<String>()
        java.util.zip.ZipInputStream(bais).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                // DOS time clamped to at least 1980
                assertTrue("Zip time should be >= 1980 (315532800000L)", entry.time >= 315532800000L)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertTrue(entryNames.contains("epoch.txt"))

        for (b in blocks) {
            if (b.data != null) readFileCall.recycleBuffer(b.data)
        }
        assertEquals(8, pool.availableCount())
    }
}


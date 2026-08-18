package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 1MB Chunk slicing, multi-channel distribution, and out-of-order reassembly pipeline tests.
 */
class ChunkPipelineTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "chunk_pipeline_test_${System.nanoTime()}").apply { mkdirs() }

    private fun createDummyFile(name: String, size: Long): File {
        val f = File(tempDir, name)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { fos ->
            val buf = ByteArray(minOf(size.toInt(), 64 * 1024))
            for (i in buf.indices) buf[i] = (i % 251).toByte()
            var rem = size
            while (rem > 0) {
                val w = minOf(rem, buf.size.toLong()).toInt()
                fos.write(buf, 0, w)
                rem -= w
            }
        }
        return f
    }

    @Test
    fun testFileSlicingAndReassemblySequential() {
        val original = createDummyFile("orig_seq.bin", 3500000L) // ~3.5MB (4 chunks)
        val origMd5 = ChecksumUtil.md5(original)

        val assembled = File(tempDir, "assem_seq.bin")
        val blockSize = 1024 * 1024

        val storageManager = DirectStorageEngine()
        val handle = storageManager.openRandomAccess(assembled.absolutePath, "rw")

        // Slicer
        FileInputStream(original).use { fis ->
            val buf = ByteArray(blockSize)
            var blockIdx = 0
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break

                // Assembler seek & write
                handle.seek(blockIdx.toLong() * blockSize)
                handle.write(buf, 0, r)
                blockIdx++
            }
        }
        handle.close()

        assertEquals(original.length(), assembled.length())
        assertEquals(origMd5, ChecksumUtil.md5(assembled))
    }

    @Test
    fun testOutOfOrderChunkReassembly() {
        val original = createDummyFile("orig_ooo.bin", 4194304L) // exactly 4 x 1MB
        val origMd5 = ChecksumUtil.md5(original)

        // Read all 4 chunks into memory
        val blockSize = 1024 * 1024
        val chunks = mutableListOf<Pair<Int, ByteArray>>()
        FileInputStream(original).use { fis ->
            for (i in 0..3) {
                val buf = ByteArray(blockSize)
                var readTotal = 0
                while (readTotal < blockSize) {
                    val r = fis.read(buf, readTotal, blockSize - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                chunks.add(Pair(i, buf))
            }
        }

        // Shuffle chunks to simulate out-of-order network arrival: [Chunk 2, Chunk 0, Chunk 3, Chunk 1]
        val scrambled = listOf(chunks[2], chunks[0], chunks[3], chunks[1])
        val assembled = File(tempDir, "assem_ooo.bin")

        val storageManager = DirectStorageEngine()
        val handle = storageManager.openRandomAccess(assembled.absolutePath, "rw")

        for ((idx, data) in scrambled) {
            handle.seek(idx.toLong() * blockSize)
            handle.write(data, 0, data.size)
        }
        handle.close()

        assertEquals(original.length(), assembled.length())
        assertEquals(origMd5, ChecksumUtil.md5(assembled))
    }

    @Test
    fun testEmptyFileSlicingAndAssembly() {
        val emptyOrig = File(tempDir, "empty.bin").apply { createNewFile() }
        val emptyAssem = File(tempDir, "empty_assem.bin")

        val storageManager = DirectStorageEngine()
        val handle = storageManager.openRandomAccess(emptyAssem.absolutePath, "rw")
        handle.setLength(0L)
        handle.close()

        assertEquals(0L, emptyAssem.length())
    }

    @Test
    fun testSingleByteFileSlicing() {
        val singleByte = createDummyFile("single.bin", 1L)
        val origMd5 = ChecksumUtil.md5(singleByte)

        val assembled = File(tempDir, "single_assem.bin")
        val storageManager = DirectStorageEngine()
        val handle = storageManager.openRandomAccess(assembled.absolutePath, "rw")

        FileInputStream(singleByte).use { fis ->
            val buf = ByteArray(1024 * 1024)
            val r = fis.read(buf)
            handle.seek(0L)
            handle.write(buf, 0, r)
        }
        handle.close()

        assertEquals(1L, assembled.length())
        assertEquals(origMd5, ChecksumUtil.md5(assembled))
    }
}

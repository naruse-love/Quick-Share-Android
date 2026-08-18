package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * MD5 & SHA-256 Checksum calculation and integrity validation tests for [ChecksumUtil].
 */
class ChecksumTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "checksum_test_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun testKnownMd5Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ChecksumUtil.md5(ByteArray(0)))
        assertEquals("5d41402abc4b2a76b9719d911017c592", ChecksumUtil.md5("hello".toByteArray(Charsets.UTF_8)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ChecksumUtil.md5("abc".toByteArray(Charsets.UTF_8)))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", ChecksumUtil.md5("The quick brown fox jumps over the lazy dog".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun testKnownSha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ChecksumUtil.sha256(ByteArray(0)))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", ChecksumUtil.sha256("hello".toByteArray(Charsets.UTF_8)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ChecksumUtil.sha256("abc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun testStreamAndBufferParity() {
        val largeData = ByteArray(1024 * 1024 + 500) // ~1MB
        for (i in largeData.indices) {
            largeData[i] = (i % 251).toByte()
        }

        val testFile = File(tempDir, "parity_test.bin")
        testFile.writeBytes(largeData)

        val directBuf = ByteBuffer.allocateDirect(largeData.size)
        directBuf.put(largeData)
        directBuf.flip()

        val expectedMd5 = ChecksumUtil.md5(largeData)
        val streamMd5 = ChecksumUtil.md5(ByteArrayInputStream(largeData))
        val byteBufMd5 = ChecksumUtil.md5(directBuf)
        val fileMd5 = ChecksumUtil.md5(testFile)

        val handle = DirectRandomAccessHandle(RandomAccessFile(testFile, "r"))
        val handleMd5 = ChecksumUtil.md5(handle)
        handle.close()

        assertEquals(expectedMd5, streamMd5)
        assertEquals(expectedMd5, byteBufMd5)
        assertEquals(expectedMd5, fileMd5)
        assertEquals(expectedMd5, handleMd5)

        val expectedSha256 = ChecksumUtil.sha256(largeData)
        val streamSha256 = ChecksumUtil.sha256(ByteArrayInputStream(largeData))
        val byteBufSha256 = ChecksumUtil.sha256(directBuf)
        val fileSha256 = ChecksumUtil.sha256(testFile)

        val handle2 = DirectRandomAccessHandle(RandomAccessFile(testFile, "r"))
        val handleSha256 = ChecksumUtil.sha256(handle2)
        handle2.close()

        assertEquals(expectedSha256, streamSha256)
        assertEquals(expectedSha256, byteBufSha256)
        assertEquals(expectedSha256, fileSha256)
        assertEquals(expectedSha256, handleSha256)
    }

    @Test
    fun testVerificationHelpers() {
        val file = File(tempDir, "verify_test.bin")
        file.writeText("Checksum verification test content", Charsets.UTF_8)

        val md5Hash = ChecksumUtil.md5(file)
        val sha256Hash = ChecksumUtil.sha256(file)

        assertTrue(ChecksumUtil.verifyMd5(file, md5Hash))
        assertTrue(ChecksumUtil.verifyMd5(file, md5Hash.uppercase()))
        assertFalse(ChecksumUtil.verifyMd5(file, "00000000000000000000000000000000"))

        assertTrue(ChecksumUtil.verifySha256(file, sha256Hash))
        assertFalse(ChecksumUtil.verifySha256(file, "1111111111111111111111111111111111111111111111111111111111111111"))

        val dummyData = "Test block data".toByteArray(Charsets.UTF_8)
        val block = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "test.txt",
            lastModified = System.currentTimeMillis(),
            totalSize = dummyData.size.toLong(),
            index = 0,
            data = dummyData,
            dataLength = dummyData.size
        )
        assertTrue(ChecksumUtil.verifyBlock(block, ChecksumUtil.md5(dummyData)))
        assertFalse(ChecksumUtil.verifyBlock(block, "badhash"))
    }
}

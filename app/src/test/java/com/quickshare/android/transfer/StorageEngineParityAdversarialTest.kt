package com.quickshare.android.transfer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Adversarial parity test suite comparing Direct POSIX (RandomAccessFile / FileChannel)
 * and SAF-style (FileOutputStream / FileInputStream FileChannel) RandomAccessHandle semantics:
 * 1. Out-of-order random offset seeks and writes
 * 2. Overlapping slice mutations
 * 3. File expansion, truncation, and setLength behaviors
 * 4. ByteBuffer vs ByteArray read/write parity
 * 5. EOF boundary conditions and -1 return codes
 * 6. Flush & close idempotency
 */
class StorageEngineParityAdversarialTest {

    private lateinit var testDir: File

    @Before
    fun setUp() {
        testDir = File(System.getProperty("java.io.tmpdir"), "adv_storage_${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    /**
     * Test-double of SAF RandomAccessHandle operating on standard FileChannel,
     * matching [SafRandomAccessHandle]'s channel-backed implementation.
     */
    private class ChannelRandomAccessHandle(
        private val file: File,
        mode: String = "rw"
    ) : RandomAccessHandle {
        private val raf = RandomAccessFile(file, mode)
        private val channel: FileChannel = raf.channel

        override fun seek(position: Long) {
            channel.position(position)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
            while (byteBuffer.hasRemaining()) {
                channel.write(byteBuffer)
            }
        }

        override fun write(buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
            return channel.read(byteBuffer)
        }

        override fun read(buffer: ByteBuffer): Int {
            return channel.read(buffer)
        }

        override fun setLength(length: Long) {
            channel.truncate(length)
        }

        override fun length(): Long = channel.size()

        override fun flush() {
            try {
                channel.force(false)
            } catch (_: Throwable) {}
        }

        override fun close() {
            try {
                channel.close()
            } catch (_: Throwable) {}
            try {
                raf.close()
            } catch (_: Throwable) {}
        }
    }

    @Test
    fun testDirectVsSafOutOfOrderChunkWriteParity() {
        val posixFile = File(testDir, "posix_ooo.bin")
        val safFile = File(testDir, "saf_ooo.bin")

        val posixHandle = DirectRandomAccessHandle(RandomAccessFile(posixFile, "rw"))
        val safHandle = ChannelRandomAccessHandle(safFile, "rw")

        val chunkSize = 1024 * 1024 // 1MB
        val c0 = ByteArray(chunkSize) { 0x11 }
        val c1 = ByteArray(chunkSize) { 0x22 }
        val c2 = ByteArray(chunkSize) { 0x33 }
        val c3 = ByteArray(512 * 1024) { 0x44 } // 512KB fractional tail

        // Out-of-order sequence: Chunk 2 (offset 2MB), Chunk 0 (offset 0), Chunk 3 (offset 3MB), Chunk 1 (offset 1MB)
        val ops = listOf(
            Triple(2L * chunkSize, c2, c2.size),
            Triple(0L, c0, c0.size),
            Triple(3L * chunkSize, c3, c3.size),
            Triple(1L * chunkSize, c1, c1.size)
        )

        for ((offset, buf, len) in ops) {
            posixHandle.seek(offset)
            posixHandle.write(buf, 0, len)

            safHandle.seek(offset)
            safHandle.write(buf, 0, len)
        }

        posixHandle.flush()
        safHandle.flush()

        assertEquals("Both handles must report identical file length", posixHandle.length(), safHandle.length())
        assertEquals(3L * chunkSize + c3.size, posixHandle.length())

        posixHandle.close()
        safHandle.close()

        // Verify on-disk checksums and byte contents match identically
        assertEquals(ChecksumUtil.sha256(posixFile), ChecksumUtil.sha256(safFile))
        assertEquals(ChecksumUtil.md5(posixFile), ChecksumUtil.md5(safFile))
    }

    @Test
    fun testOverlappingSlicesMutationParity() {
        val posixFile = File(testDir, "posix_overlap.bin")
        val safFile = File(testDir, "saf_overlap.bin")

        val posixHandle = DirectRandomAccessHandle(RandomAccessFile(posixFile, "rw"))
        val safHandle = ChannelRandomAccessHandle(safFile, "rw")

        // Initial 200 bytes filled with 0xAA
        val initial = ByteArray(200) { 0xAA.toByte() }
        posixHandle.write(initial, 0, initial.size)
        safHandle.write(initial, 0, initial.size)

        // Overwrite range [50, 150) with 0xBB
        val patch = ByteArray(100) { 0xBB.toByte() }
        posixHandle.seek(50L)
        posixHandle.write(patch, 0, patch.size)

        safHandle.seek(50L)
        safHandle.write(patch, 0, patch.size)

        // Read back ranges [0, 50), [50, 150), [150, 200)
        val posixRead = ByteArray(200)
        val safRead = ByteArray(200)

        posixHandle.seek(0L)
        assertEquals(200, posixHandle.read(posixRead, 0, 200))

        safHandle.seek(0L)
        assertEquals(200, safHandle.read(safRead, 0, 200))

        assertArrayEquals(posixRead, safRead)
        assertEquals(0xAA.toByte(), posixRead[49])
        assertEquals(0xBB.toByte(), posixRead[50])
        assertEquals(0xBB.toByte(), posixRead[149])
        assertEquals(0xAA.toByte(), posixRead[150])

        posixHandle.close()
        safHandle.close()
    }

    @Test
    fun testByteBufferVsByteArrayParity() {
        val file = File(testDir, "bytebuf_parity.bin")
        val handle = DirectRandomAccessHandle(RandomAccessFile(file, "rw"))

        val data = ByteArray(256) { it.toByte() }
        val byteBuffer = ByteBuffer.wrap(data)

        handle.write(byteBuffer)
        assertEquals(256L, handle.length())

        // Read using ByteArray
        handle.seek(0L)
        val readByteArray = ByteArray(256)
        val readBytesCount = handle.read(readByteArray, 0, 256)
        assertEquals(256, readBytesCount)
        assertArrayEquals(data, readByteArray)

        // Read using ByteBuffer
        handle.seek(0L)
        val readByteBuffer = ByteBuffer.allocate(256)
        val readBufCount = handle.read(readByteBuffer)
        assertEquals(256, readBufCount)
        assertArrayEquals(data, readByteBuffer.array())

        handle.close()
    }

    @Test
    fun testEofBoundaryAndNegativeOneReturn() {
        val file = File(testDir, "eof_test.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }

        val handle = DirectRandomAccessHandle(RandomAccessFile(file, "r"))
        handle.seek(5L) // Seek to exact end of file

        val buf = ByteArray(10)
        val r = handle.read(buf, 0, buf.size)
        assertEquals("Reading at EOF must return -1", -1, r)

        val byteBuf = ByteBuffer.allocate(10)
        val r2 = handle.read(byteBuf)
        assertEquals("ByteBuffer reading at EOF must return -1", -1, r2)

        handle.close()
    }

    @Test
    fun testSetLengthTruncationAndZeroByteParity() {
        val posixFile = File(testDir, "posix_trunc.bin")
        val safFile = File(testDir, "saf_trunc.bin")

        val posixHandle = DirectRandomAccessHandle(RandomAccessFile(posixFile, "rw"))
        val safHandle = ChannelRandomAccessHandle(safFile, "rw")

        val initialData = ByteArray(1000) { 0x55 }
        posixHandle.write(initialData, 0, initialData.size)
        safHandle.write(initialData, 0, initialData.size)

        assertEquals(1000L, posixHandle.length())
        assertEquals(1000L, safHandle.length())

        // Truncate to 300 bytes
        posixHandle.setLength(300L)
        safHandle.setLength(300L)

        assertEquals(300L, posixHandle.length())
        assertEquals(300L, safHandle.length())

        // Truncate to 0 bytes (Empty file simulation)
        posixHandle.setLength(0L)
        safHandle.setLength(0L)

        assertEquals(0L, posixHandle.length())
        assertEquals(0L, safHandle.length())

        posixHandle.close()
        safHandle.close()
    }

    @Test
    fun testFlushAndCloseIdempotency() {
        val file = File(testDir, "idempotent.bin")
        val handle = DirectRandomAccessHandle(RandomAccessFile(file, "rw"))
        handle.write(byteArrayOf(1, 2, 3))

        // Multiple flushes should not throw
        handle.flush()
        handle.flush()

        // Multiple closes should not throw
        handle.close()
        handle.close()
    }
}

package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * High-performance, zero-allocation MD5 & SHA-256 hashing and integrity verification utilities.
 */
object ChecksumUtil {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }

    // --- MD5 Algorithms ---

    fun md5(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(data, offset, length)
        return bytesToHex(digest.digest())
    }

    fun md5(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("MD5")
        val duplicate = buffer.asReadOnlyBuffer()
        digest.update(duplicate)
        return bytesToHex(digest.digest())
    }

    fun md5(stream: InputStream, bufferSize: Int = 64 * 1024): String {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(bufferSize)
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        return bytesToHex(digest.digest())
    }

    fun md5(file: File): String {
        return FileInputStream(file).use { md5(it) }
    }

    fun md5(handle: RandomAccessHandle): String {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)
        handle.seek(0L)
        while (true) {
            val read = handle.read(buf, 0, buf.size)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        return bytesToHex(digest.digest())
    }

    // --- SHA-256 Algorithms ---

    fun sha256(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, offset, length)
        return bytesToHex(digest.digest())
    }

    fun sha256(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val duplicate = buffer.asReadOnlyBuffer()
        digest.update(duplicate)
        return bytesToHex(digest.digest())
    }

    fun sha256(stream: InputStream, bufferSize: Int = 64 * 1024): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        return bytesToHex(digest.digest())
    }

    fun sha256(file: File): String {
        return FileInputStream(file).use { sha256(it) }
    }

    fun sha256(handle: RandomAccessHandle): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        handle.seek(0L)
        while (true) {
            val read = handle.read(buf, 0, buf.size)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        return bytesToHex(digest.digest())
    }

    // --- Verification Helpers ---

    fun verifyMd5(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        return md5(file).equals(expectedHash.trim(), ignoreCase = true)
    }

    fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        return sha256(file).equals(expectedHash.trim(), ignoreCase = true)
    }

    fun verifyBlock(block: FileBlock, expectedMd5: String): Boolean {
        if (block.data == null || block.dataLength == 0) {
            return expectedMd5.equals(md5(ByteArray(0)), ignoreCase = true)
        }
        return md5(block.data, 0, block.dataLength).equals(expectedMd5.trim(), ignoreCase = true)
    }
}

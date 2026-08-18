package com.quickshare.android.e2e.harness

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * LoopbackHarness orchestrates a dual-endpoint mock server & mock client environment
 * for end-to-end integration and protocol verification tests.
 */
class LoopbackHarness(
    val advertisedNics: List<String> = listOf("wlan0", "rndis0")
) : AutoCloseable {

    val server = MockQuickShareServer(
        port = DynamicPortAllocator.allocateFreePort(),
        advertisedNics = advertisedNics
    )

    val client = MockQuickShareClient(
        serverPort = server.port,
        clientNicNames = advertisedNics
    )

    fun startAndConnect(timeoutSeconds: Long = 10): Boolean {
        server.start()
        val connected = client.connect((timeoutSeconds * 1000).toInt())
        if (!connected) return false
        return server.onHandshakeComplete.get(timeoutSeconds, TimeUnit.SECONDS)
    }

    /**
     * Creates a test file of given size with deterministic content.
     */
    fun createTestFile(dir: File, name: String, sizeBytes: Long): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        if (sizeBytes == 0L) {
            file.createNewFile()
            return file
        }

        val buffer = ByteArray(minOf(sizeBytes.toInt(), 64 * 1024))
        for (i in buffer.indices) {
            buffer[i] = (i % 256).toByte()
        }

        file.outputStream().buffered().use { fos ->
            var remaining = sizeBytes
            while (remaining > 0) {
                val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                fos.write(buffer, 0, toWrite)
                remaining -= toWrite
            }
        }
        return file
    }

    /**
     * Creates a hierarchical directory structure with files for sync testing.
     */
    fun createDirectoryTree(baseDir: File, depth: Int, filesPerDir: Int, fileSize: Long): List<File> {
        val createdFiles = mutableListOf<File>()
        fun buildTree(currentDir: File, currentDepth: Int) {
            currentDir.mkdirs()
            for (i in 1..filesPerDir) {
                val f = createTestFile(currentDir, "file_d${currentDepth}_$i.dat", fileSize)
                createdFiles.add(f)
            }
            if (currentDepth < depth) {
                val subDir = File(currentDir, "subdir_$currentDepth")
                buildTree(subDir, currentDepth + 1)
            }
        }
        buildTree(baseDir, 1)
        return createdFiles
    }

    /**
     * Computes MD5 checksum of a file.
     */
    fun computeMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes SHA-256 checksum of a file.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        try { client.close() } catch (_: Exception) {}
        try { server.close() } catch (_: Exception) {}
    }
}

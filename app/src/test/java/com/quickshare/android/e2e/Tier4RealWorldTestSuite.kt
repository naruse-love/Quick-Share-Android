package com.quickshare.android.e2e

import com.quickshare.android.e2e.harness.*
import com.quickshare.android.model.*
import com.quickshare.android.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tier 4: Real-World Workload & Interoperability Test Suite.
 * Covers deep directory tree synchronization, PC server interop, custom port rebinding, and large file streaming.
 */
class Tier4RealWorldTestSuite {

    // Test 1: Full Directory Tree Sync (Depth 3, nested files)
    @Test
    fun testRealWorld01_FullDirectoryTreeSync() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue("Loopback handshake failed", harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val syncRoot = File(clientDir, "ProjectRoot")
            val files = harness.createDirectoryTree(syncRoot, depth = 3, filesPerDir = 2, fileSize = 250000L)
            val expectedHashes = files.associate { it.name to harness.computeMd5(it) }

            assertTrue(harness.client.sendFiles(files, "/synced_tree"))

            // Verify all files exist in server sandbox and match checksums
            for (f in files) {
                val rel = f.relativeTo(clientDir).path.replace('\\', '/')
                val serverFile = File(harness.server.sandboxDir, "synced_tree/$rel")
                assertTrue("Synced file missing: $rel", serverFile.exists())
                assertEquals("Checksum mismatch on ${f.name}", expectedHashes[f.name], harness.computeMd5(serverFile))
            }
        }
    }

    // Test 2: Mock PC Server Interoperability (Windows File System Code 1 & Path Normalization)
    @Test
    fun testRealWorld02_MockPCServerInterop() {
        val server = MockQuickShareServer(advertisedNics = listOf("以太网", "WLAN"))
        server.remoteFileSystem = 1 // Windows
        server.start()

        val client = MockQuickShareClient(serverPort = server.port, clientNicNames = listOf("eth0", "wlan0"))
        assertTrue("Failed to connect to PC server mock", client.connect())

        val testFile = harnessCreateFile(client.localSandboxDir, "pc_interop.txt", 1024L)
        val md5 = computeMd5(testFile)

        assertTrue(client.sendFiles(listOf(testFile), "D:\\SharedFolder"))

        val received = File(server.sandboxDir, "SharedFolder/pc_interop.txt")
        assertTrue(received.exists())
        assertEquals(md5, computeMd5(received))

        client.close()
        server.close()
    }

    // Test 3: Custom Port Re-binding (18888 and 29999)
    @Test
    fun testRealWorld03_CustomPortRebinding() {
        val customPorts = listOf(18888, 29999)
        for (port in customPorts) {
            val server = MockQuickShareServer(port = port)
            server.start()

            val client = MockQuickShareClient(serverPort = port)
            assertTrue("Failed to connect on custom port $port", client.connect())

            val f = harnessCreateFile(client.localSandboxDir, "port_$port.txt", 512L)
            assertTrue(client.sendFiles(listOf(f), "/"))
            assertTrue(File(server.sandboxDir, "port_$port.txt").exists())

            client.close()
            server.close()
        }
    }

    // Test 4: Large File Multi-Channel Slicing (10MB across 4 channels)
    @Test
    fun testRealWorld04_LargeFileMultiChannelStreaming() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0", "eth0", "wlan1")).use { harness ->
            assertTrue(harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val largeFile = harness.createTestFile(clientDir, "large_10mb.iso", 10485760L) // 10MB (10 blocks)
            val expectedMd5 = harness.computeMd5(largeFile)

            assertTrue(harness.client.sendFiles(listOf(largeFile), "/media"))

            val received = File(harness.server.sandboxDir, "media/large_10mb.iso")
            assertTrue(received.exists())
            assertEquals(10485760L, received.length())
            assertEquals(expectedMd5, harness.computeMd5(received))
        }
    }

    // Test 5: Multi-NIC Traffic Metering Verification
    @Test
    fun testRealWorld05_MultiNicTrafficMetering() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())

            val testFile = harness.createTestFile(harness.client.localSandboxDir, "meter.dat", 4194304L) // 4MB
            assertTrue(harness.client.sendFiles(listOf(testFile), "/"))

            assertEquals(4194304L, harness.client.bytesSent.get())
            assertEquals(4194304L, harness.server.bytesReceived.get())
        }
    }

    // Test 6: Unicode and Multi-Language Directory Sync
    @Test
    fun testRealWorld06_UnicodeDirectorySync() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val unicodeFolder = File(clientDir, "📁_项目资料_2026_🚀/文档").apply { mkdirs() }
            val f1 = harness.createTestFile(unicodeFolder, "报告_中文.pdf", 2048L)
            val f2 = harness.createTestFile(unicodeFolder, "レポート_日本語.docx", 2048L)
            val f3 = harness.createTestFile(unicodeFolder, "문서_한국어.xlsx", 2048L)

            val md5_1 = harness.computeMd5(f1)

            assertTrue(harness.client.sendFiles(listOf(f1, f2, f3), "/synced_unicode"))

            val rel1 = f1.relativeTo(clientDir).path.replace('\\', '/')
            val rel2 = f2.relativeTo(clientDir).path.replace('\\', '/')
            val rel3 = f3.relativeTo(clientDir).path.replace('\\', '/')

            val recv1 = File(harness.server.sandboxDir, "synced_unicode/$rel1")
            val recv2 = File(harness.server.sandboxDir, "synced_unicode/$rel2")
            val recv3 = File(harness.server.sandboxDir, "synced_unicode/$rel3")

            assertTrue(recv1.exists())
            assertTrue(recv2.exists())
            assertTrue(recv3.exists())
            assertEquals(md5_1, harness.computeMd5(recv1))
        }
    }

    // Test 7: Remote File Management Full Lifecycle
    @Test
    fun testRealWorld07_RemoteFileManagementFullLifecycle() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            // 1. Mkdir
            assertTrue(harness.client.mkdir("/", "Workspace"))

            // 2. List
            val list1 = harness.client.listFiles("/")
            assertTrue(list1?.any { it.name == "Workspace" && it.isDirectory } == true)

            // 3. Push file inside Workspace
            val f = harness.createTestFile(harness.client.localSandboxDir, "work.txt", 100L)
            assertTrue(harness.client.sendFiles(listOf(f), "/Workspace"))

            // 4. List inside Workspace
            val list2 = harness.client.listFiles("Workspace")
            assertTrue(list2?.any { it.name == "work.txt" } == true)

            // 5. Delete file
            assertTrue(harness.client.deleteFile("Workspace/work.txt"))

            // 6. Delete directory
            assertTrue(harness.client.deleteFile("Workspace"))

            // 7. Verify empty
            val list3 = harness.client.listFiles("/")
            assertTrue(list3?.isEmpty() == true)
        }
    }

    // Test 8: Sequential Push and Pull Workload in Same Session
    @Test
    fun testRealWorld08_BidirectionalSequentialWorkload() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())

            // Client Push
            val pushFile = harness.createTestFile(harness.client.localSandboxDir, "push_task.dat", 1500000L)
            val pushMd5 = harness.computeMd5(pushFile)
            assertTrue(harness.client.sendFiles(listOf(pushFile), "/shared"))

            // Server Prepare & Client Pull
            val serverFile = harness.createTestFile(harness.server.sandboxDir, "server_repo/download.bin", 2000000L)
            val pullMd5 = harness.computeMd5(serverFile)
            val localDest = File(harness.client.localSandboxDir, "my_downloads").apply { mkdirs() }

            assertTrue(harness.client.pullFiles(listOf("server_repo/download.bin"), "/", localDest))

            // Verify both directions
            val recvOnServer = File(harness.server.sandboxDir, "shared/push_task.dat")
            val recvOnClient = File(localDest, "server_repo/download.bin")

            assertTrue(recvOnServer.exists())
            assertTrue(recvOnClient.exists())

            assertEquals(pushMd5, harness.computeMd5(recvOnServer))
            assertEquals(pullMd5, harness.computeMd5(recvOnClient))
        }
    }

    // Test 9: Empty Directories and Zero-Byte Files Sync
    @Test
    fun testRealWorld09_EmptyDirectoriesAndZeroByteFilesSync() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val emptyDir1 = File(clientDir, "empty_tree/sub1").apply { mkdirs() }
            val emptyDir2 = File(clientDir, "empty_tree/sub2").apply { mkdirs() }
            val zeroFile = harness.createTestFile(emptyDir1, "zero.txt", 0L)

            assertTrue(harness.client.sendFiles(listOf(emptyDir1, emptyDir2, zeroFile), "/synced_empty"))

            val sSub1 = File(harness.server.sandboxDir, "synced_empty/empty_tree/sub1")
            val sSub2 = File(harness.server.sandboxDir, "synced_empty/empty_tree/sub2")
            val sZero = File(harness.server.sandboxDir, "synced_empty/empty_tree/sub1/zero.txt")

            assertTrue(sSub1.exists() && sSub1.isDirectory)
            assertTrue(sSub2.exists() && sSub2.isDirectory)
            assertTrue(sZero.exists() && sZero.length() == 0L)
        }
    }

    // Test 10: High-Frequency Burst File Transfers
    @Test
    fun testRealWorld10_HighFrequencyBurstTransfers() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            for (i in 1..10) {
                val f = harness.createTestFile(clientDir, "burst_$i.dat", 50000L) // 50KB
                assertTrue("Burst transfer $i failed", harness.client.sendFiles(listOf(f), "/burst"))
                assertTrue(File(harness.server.sandboxDir, "burst/burst_$i.dat").exists())
            }
        }
    }

    // Helper functions
    private fun harnessCreateFile(dir: File, name: String, size: Long): File {
        val f = File(dir, name).apply { parentFile?.mkdirs() }
        f.outputStream().buffered().use { fos ->
            val buf = ByteArray(minOf(size.toInt(), 64 * 1024))
            var rem = size
            while (rem > 0) {
                val w = minOf(rem, buf.size.toLong()).toInt()
                fos.write(buf, 0, w)
                rem -= w
            }
        }
        return f
    }

    private fun computeMd5(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

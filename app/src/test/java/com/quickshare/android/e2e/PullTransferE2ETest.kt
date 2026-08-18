package com.quickshare.android.e2e

import com.quickshare.android.e2e.harness.LoopbackHarness
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * End-to-End Pull Transfer (REQUEST_SEND = 0x000B) Tests across single and multi-channel configurations.
 */
class PullTransferE2ETest {

    @Test
    fun testSingleChannelPullTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val serverDir = harness.server.sandboxDir
            val testFile = harness.createTestFile(serverDir, "pull_single.bin", 3145728L) // 3MB (3 chunks)
            val expectedMd5 = harness.computeMd5(testFile)

            val clientDest = File(harness.client.localSandboxDir, "pulled_files")
            clientDest.mkdirs()

            val success = harness.client.pullFiles(
                remotePaths = listOf("pull_single.bin"),
                remoteParentDir = "/",
                destDir = clientDest
            )
            assertTrue("Pull transfer failed", success)

            val localFile = File(clientDest, "pull_single.bin")
            assertTrue("Pulled file does not exist", localFile.exists())
            assertEquals(testFile.length(), localFile.length())
            assertEquals(expectedMd5, harness.computeMd5(localFile))
        }
    }

    @Test
    fun testDualChannelPullTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val serverDir = harness.server.sandboxDir
            val testFile = harness.createTestFile(serverDir, "pull_dual.bin", 6291456L) // 6MB (6 chunks across 2 channels)
            val expectedMd5 = harness.computeMd5(testFile)

            val clientDest = File(harness.client.localSandboxDir, "pulled_dual")
            clientDest.mkdirs()

            val success = harness.client.pullFiles(
                remotePaths = listOf("pull_dual.bin"),
                remoteParentDir = "/",
                destDir = clientDest
            )
            assertTrue(success)

            val localFile = File(clientDest, "pull_dual.bin")
            assertTrue(localFile.exists())
            assertEquals(testFile.length(), localFile.length())
            assertEquals(expectedMd5, harness.computeMd5(localFile))
        }
    }

    @Test
    fun testMultiFilePullTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val serverDir = harness.server.sandboxDir
            val fileA = harness.createTestFile(serverDir, "docs/docA.txt", 4096L)
            val fileB = harness.createTestFile(serverDir, "docs/docB.bin", 2097152L) // 2MB
            val fileC = harness.createTestFile(serverDir, "docs/empty.txt", 0L)

            val md5A = harness.computeMd5(fileA)
            val md5B = harness.computeMd5(fileB)

            val clientDest = File(harness.client.localSandboxDir, "pulled_docs")
            clientDest.mkdirs()

            val success = harness.client.pullFiles(
                remotePaths = listOf("docs/docA.txt", "docs/docB.bin", "docs/empty.txt"),
                remoteParentDir = "/docs",
                destDir = clientDest
            )
            assertTrue(success)

            val localA = File(clientDest, "docs/docA.txt")
            val localB = File(clientDest, "docs/docB.bin")
            val localC = File(clientDest, "docs/empty.txt")

            assertTrue(localA.exists())
            assertTrue(localB.exists())
            assertTrue(localC.exists())

            assertEquals(md5A, harness.computeMd5(localA))
            assertEquals(md5B, harness.computeMd5(localB))
            assertEquals(0L, localC.length())
        }
    }
}

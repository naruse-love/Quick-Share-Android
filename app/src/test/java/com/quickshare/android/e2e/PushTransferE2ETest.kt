package com.quickshare.android.e2e

import com.quickshare.android.e2e.harness.LoopbackHarness
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * End-to-End Push Transfer (REQUEST_RECEIVE = 0x000A) Tests across single and multi-channel configurations.
 */
class PushTransferE2ETest {

    @Test
    fun testSingleChannelPushTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val testFile = harness.createTestFile(clientDir, "push_single.bin", 2500000L) // ~2.5MB (3 chunks)
            val expectedMd5 = harness.computeMd5(testFile)

            val success = harness.client.sendFiles(listOf(testFile), "/received")
            assertTrue("Push transfer failed", success)

            val receivedFile = File(harness.server.sandboxDir, "received/push_single.bin")
            assertTrue("Received file does not exist", receivedFile.exists())
            assertEquals("File size mismatch", testFile.length(), receivedFile.length())
            assertEquals("MD5 checksum mismatch", expectedMd5, harness.computeMd5(receivedFile))
        }
    }

    @Test
    fun testDualChannelPushTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val testFile = harness.createTestFile(clientDir, "push_dual.bin", 5242880L) // 5MB (5 chunks across 2 channels)
            val expectedMd5 = harness.computeMd5(testFile)

            val success = harness.client.sendFiles(listOf(testFile), "/received_dual")
            assertTrue("Push transfer failed", success)

            val receivedFile = File(harness.server.sandboxDir, "received_dual/push_dual.bin")
            assertTrue(receivedFile.exists())
            assertEquals(testFile.length(), receivedFile.length())
            assertEquals(expectedMd5, harness.computeMd5(receivedFile))
        }
    }

    @Test
    fun testQuadChannelPushTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0", "eth0", "wlan1")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val testFile = harness.createTestFile(clientDir, "push_quad.bin", 10485760L) // 10MB (10 chunks across 4 channels)
            val expectedMd5 = harness.computeMd5(testFile)

            val success = harness.client.sendFiles(listOf(testFile), "/received_quad")
            assertTrue("Push transfer failed", success)

            val receivedFile = File(harness.server.sandboxDir, "received_quad/push_quad.bin")
            assertTrue(receivedFile.exists())
            assertEquals(testFile.length(), receivedFile.length())
            assertEquals(expectedMd5, harness.computeMd5(receivedFile))
        }
    }

    @Test
    fun testPushTransferWithMultipleFilesAndFolders() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue("Handshake failed", harness.startAndConnect())

            val clientDir = harness.client.localSandboxDir
            val file1 = harness.createTestFile(clientDir, "batch/file1.txt", 1024L)
            val file2 = harness.createTestFile(clientDir, "batch/file2.bin", 3000000L)
            val file3 = harness.createTestFile(clientDir, "batch/empty.txt", 0L)

            val md5_1 = harness.computeMd5(file1)
            val md5_2 = harness.computeMd5(file2)

            val success = harness.client.sendFiles(listOf(file1, file2, file3), "/batch_dest")
            assertTrue(success)

            val recv1 = File(harness.server.sandboxDir, "batch_dest/batch/file1.txt")
            val recv2 = File(harness.server.sandboxDir, "batch_dest/batch/file2.bin")
            val recv3 = File(harness.server.sandboxDir, "batch_dest/batch/empty.txt")

            assertTrue(recv1.exists())
            assertTrue(recv2.exists())
            assertTrue(recv3.exists())

            assertEquals(md5_1, harness.computeMd5(recv1))
            assertEquals(md5_2, harness.computeMd5(recv2))
            assertEquals(0L, recv3.length())
        }
    }
}

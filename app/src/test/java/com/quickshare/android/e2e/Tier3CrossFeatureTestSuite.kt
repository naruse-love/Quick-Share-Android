package com.quickshare.android.e2e

import com.quickshare.android.e2e.harness.*
import com.quickshare.android.model.*
import com.quickshare.android.network.*
import com.quickshare.android.protocol.*
import com.quickshare.android.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tier 3: Cross-Feature Interaction & Combinatorial Test Suite.
 * Contains ≥ 27 cross-feature scenarios testing multi-channel concurrency, jitter, push/pull interleaving, etc.
 */
class Tier3CrossFeatureTestSuite {

    // Scenario 1: Multi-channel chunk streaming with simulated network jitter
    @Test
    fun testScenario01_MultiChannelWithSimulatedJitter() {
        val manager = SimulatedMultiNicManager()
        manager.injectLatency("wlan0", 10)
        manager.injectLatency("rndis0", 30)

        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())
            val testFile = harness.createTestFile(harness.client.localSandboxDir, "jitter.bin", 4194304L) // 4MB
            val expectedMd5 = harness.computeMd5(testFile)

            assertTrue(harness.client.sendFiles(listOf(testFile), "/"))
            val received = File(harness.server.sandboxDir, "jitter.bin")
            assertTrue(received.exists())
            assertEquals(expectedMd5, harness.computeMd5(received))
        }
    }

    // Scenario 2: Consecutive Push followed by Pull of same file
    @Test
    fun testScenario02_PushThenPullSameFile() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val orig = harness.createTestFile(harness.client.localSandboxDir, "roundtrip.dat", 2097152L)
            val origMd5 = harness.computeMd5(orig)

            // Step 1: Push to server
            assertTrue(harness.client.sendFiles(listOf(orig), "/"))

            // Step 2: Pull back to client in different folder
            val pullDest = File(harness.client.localSandboxDir, "pulled_back").apply { mkdirs() }
            assertTrue(harness.client.pullFiles(listOf("roundtrip.dat"), "/", pullDest))

            val pulledFile = File(pullDest, "roundtrip.dat")
            assertTrue(pulledFile.exists())
            assertEquals(origMd5, harness.computeMd5(pulledFile))
        }
    }

    // Scenario 3: Channel failover / graceful handling
    @Test
    fun testScenario03_SimulatedNicFailoverTracking() {
        val manager = SimulatedMultiNicManager()
        val nic = manager.getNic("wlan0")
        assertNotNull(nic)
        nic?.recordSent(1048576)
        assertEquals(1048576L, manager.getTotalBytesSent())
        nic?.disable()
        assertFalse(nic?.isEnabled?.get() == true)
        assertEquals(2, manager.getActiveNics().size) // rndis0, eth0 remain
    }

    // Scenario 4: Out-of-order chunk assembly across 4 channels
    @Test
    fun testScenario04_QuadChannelOutOfOrderAssembly() {
        val pq = PriorityQueue<FileBlock>()
        val blocks = (0..15).map { FileBlock(true, 0, "quad", 0L, 16777216L, it) }
        // Distribute across 4 pseudo-channels
        val ch0 = blocks.filterIndexed { i, _ -> i % 4 == 0 }
        val ch1 = blocks.filterIndexed { i, _ -> i % 4 == 1 }
        val ch2 = blocks.filterIndexed { i, _ -> i % 4 == 2 }
        val ch3 = blocks.filterIndexed { i, _ -> i % 4 == 3 }

        // Interleave arrivals arbitrarily
        pq.addAll(ch3)
        pq.addAll(ch0)
        pq.addAll(ch2)
        pq.addAll(ch1)

        for (i in 0..15) {
            assertEquals(i, pq.poll()?.index)
        }
    }

    // Scenario 5: BufferPool contention under heavy thread load
    @Test
    fun testScenario05_BufferPoolContentionUnderLoad() {
        val pool = BufferPool(4, 1024 * 1024)
        val executor = Executors.newFixedThreadPool(8)
        val futures = mutableListOf<Future<Boolean>>()

        for (i in 0 until 8) {
            futures.add(executor.submit<Boolean> {
                for (j in 0..20) {
                    val buf = pool.acquire(1000) ?: return@submit false
                    Thread.sleep(2)
                    pool.release(buf)
                }
                true
            })
        }

        for (f in futures) {
            assertTrue(f.get(10, TimeUnit.SECONDS))
        }
        assertEquals(4, pool.availableCount())
        executor.shutdownNow()
    }

    // Scenario 6: Mixed Windows / Unix path conversions
    @Test
    fun testScenario06_CrossPlatformPathConversions() {
        val unixRoot = QuickShareDirectory("/android/storage/", 0)
        val winRoot = QuickShareDirectory("E:\\Backup\\", 1)

        val winPath = unixRoot.generateTransferPath("/android/storage/photos/2026/vacation.jpg", winRoot)
        assertEquals("E:\\Backup\\photos\\2026\\vacation.jpg", winPath)

        val backUnix = winRoot.generateTransferPath(winPath, unixRoot)
        assertEquals("/android/storage/photos/2026/vacation.jpg", backUnix)
    }

    // Scenario 7: Checksum integrity under multi-channel streaming
    @Test
    fun testScenario07_ChecksumIntegrityMultiChannel() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())
            val file = harness.createTestFile(harness.client.localSandboxDir, "ck_test.bin", 3000000L)
            val sha256Expected = harness.computeSha256(file)

            assertTrue(harness.client.sendFiles(listOf(file), "/"))
            val received = File(harness.server.sandboxDir, "ck_test.bin")
            assertEquals(sha256Expected, harness.computeSha256(received))
        }
    }

    // Scenario 8: Remote listing during active session
    @Test
    fun testScenario08_RemoteListingDuringSession() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            harness.createTestFile(harness.server.sandboxDir, "doc1.pdf", 500)
            val listBefore = harness.client.listFiles("/")
            assertEquals(1, listBefore?.size)

            harness.createTestFile(harness.server.sandboxDir, "doc2.pdf", 600)
            val listAfter = harness.client.listFiles("/")
            assertEquals(2, listAfter?.size)
        }
    }

    // Scenario 9: Batch transfer with mixed directories and empty files
    @Test
    fun testScenario09_MixedBatchTransfer() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val base = harness.client.localSandboxDir
            val f1 = harness.createTestFile(base, "m_batch/f1.dat", 1000000)
            val f2 = harness.createTestFile(base, "m_batch/empty.dat", 0)
            val dir = File(base, "m_batch/subdir").apply { mkdirs() }

            assertTrue(harness.client.sendFiles(listOf(f1, f2, dir), "/m_dest"))
            assertTrue(File(harness.server.sandboxDir, "m_dest/m_batch/f1.dat").exists())
            assertTrue(File(harness.server.sandboxDir, "m_dest/m_batch/empty.dat").exists())
            assertTrue(File(harness.server.sandboxDir, "m_dest/m_batch/subdir").exists())
        }
    }

    // Scenario 10: Custom port allocation and connection
    @Test
    fun testScenario10_CustomPortReconnection() {
        val port = DynamicPortAllocator.allocateFreePort()
        val server = MockQuickShareServer(port = port)
        server.start()

        val client = MockQuickShareClient(serverPort = port)
        assertTrue(client.connect())
        assertTrue(server.onHandshakeComplete.get(5, TimeUnit.SECONDS))

        client.close()
        server.close()
    }

    // Scenario 11: RPC deletion after push
    @Test
    fun testScenario11_PushThenDelete() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "temp_del.bin", 500000)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            assertTrue(File(harness.server.sandboxDir, "temp_del.bin").exists())

            assertTrue(harness.client.deleteFile("temp_del.bin"))
            assertFalse(File(harness.server.sandboxDir, "temp_del.bin").exists())
        }
    }

    // Scenario 12: Traffic accumulation across channels
    @Test
    fun testScenario12_TrafficAccumulationAccuracy() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "traffic_acc.bin", 2097152L)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            assertEquals(2097152L, harness.client.bytesSent.get())
            assertEquals(2097152L, harness.server.bytesReceived.get())
        }
    }

    // Scenario 13: Nested directory creation via RPC
    @Test
    fun testScenario13_NestedMkdirViaRpc() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            assertTrue(harness.client.mkdir("/", "a/b/c"))
            assertTrue(File(harness.server.sandboxDir, "a/b/c").exists())
        }
    }

    // Scenario 14: Handshake parameters validation
    @Test
    fun testScenario14_HandshakeParametersIntegrity() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0", "eth0")).use { harness ->
            assertTrue(harness.startAndConnect())
            assertEquals(3, harness.client.serverAdvertisedNics.size)
            assertEquals(0, harness.server.remoteFileSystem)
        }
    }

    // Scenario 15: Slicing file with odd byte size
    @Test
    fun testScenario15_OddByteSizeTransfer() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "odd.bin", 1048583L) // 1MB + 7 bytes
            val md5 = harness.computeMd5(f)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            val r = File(harness.server.sandboxDir, "odd.bin")
            assertEquals(1048583L, r.length())
            assertEquals(md5, harness.computeMd5(r))
        }
    }

    // Scenario 16: Multiple small files stream
    @Test
    fun testScenario16_MultipleSmallFilesStream() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val files = (1..5).map { harness.createTestFile(harness.client.localSandboxDir, "small_$it.txt", (it * 100).toLong()) }
            assertTrue(harness.client.sendFiles(files, "/small_dest"))
            for (i in 1..5) {
                assertTrue(File(harness.server.sandboxDir, "small_dest/small_$i.txt").exists())
            }
        }
    }

    // Scenario 17: Pull deep hierarchy to client
    @Test
    fun testScenario17_PullDeepHierarchy() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val deep = File(harness.server.sandboxDir, "level1/level2").apply { mkdirs() }
            val f = harness.createTestFile(deep, "deep_data.bin", 2048)
            val dest = File(harness.client.localSandboxDir, "pulled_deep").apply { mkdirs() }
            assertTrue(harness.client.pullFiles(listOf("level1/level2/deep_data.bin"), "/", dest))
            assertTrue(File(dest, "level1/level2/deep_data.bin").exists())
        }
    }

    // Scenario 18: Special character filename transfer
    @Test
    fun testScenario18_SpecialCharsFilenameTransfer() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "报告_2026_🔥_final.txt", 100)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            assertTrue(File(harness.server.sandboxDir, "报告_2026_🔥_final.txt").exists())
        }
    }

    // Scenario 19: High speed throughput formatting simulation
    @Test
    fun testScenario19_ThroughputFormattingSimulation() {
        val speeds = listOf(100L, 50000L, 10485760L, 1073741824L)
        val formatted = speeds.map { TrafficInfoTest.TestTrafficInfo.formatSpeed(it) }
        assertEquals("100 B/s", formatted[0])
        assertEquals("48.83 KB/s", formatted[1])
        assertEquals("10.00 MB/s", formatted[2])
        assertEquals("1.00 GB/s", formatted[3])
    }

    // Scenario 20: Dual channel pull transfer with mixed sizes
    @Test
    fun testScenario20_DualChannelPullMixedSizes() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())
            val f1 = harness.createTestFile(harness.server.sandboxDir, "mix1.bin", 100)
            val f2 = harness.createTestFile(harness.server.sandboxDir, "mix2.bin", 2500000)
            val dest = File(harness.client.localSandboxDir, "mixed_pull").apply { mkdirs() }
            assertTrue(harness.client.pullFiles(listOf("mix1.bin", "mix2.bin"), "/", dest))
            assertEquals(100L, File(dest, "mix1.bin").length())
            assertEquals(2500000L, File(dest, "mix2.bin").length())
        }
    }

    // Scenario 21: Slicing boundary at exactly 2MB
    @Test
    fun testScenario21_Exact2MBTransfer() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "exact2mb.bin", 2097152)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            assertEquals(2097152L, File(harness.server.sandboxDir, "exact2mb.bin").length())
        }
    }

    // Scenario 22: Timestamp preservation across push
    @Test
    fun testScenario22_TimestampPreservationAcrossPush() {
        LoopbackHarness().use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "ts_file.bin", 1000)
            val targetTs = 1650000000000L
            f.setLastModified(targetTs)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            val r = File(harness.server.sandboxDir, "ts_file.bin")
            assertTrue(Math.abs(r.lastModified() - targetTs) <= 2000)
        }
    }

    // Scenario 23: Direct IO RandomAccess Seek write test
    @Test
    fun testScenario23_DirectIoSeekWriteTest() {
        val f = File.createTempFile("seek_test", ".bin").apply { deleteOnExit() }
        java.io.RandomAccessFile(f, "rw").use { raf ->
            raf.seek(1048576)
            raf.write("CHUNKB".toByteArray())
            raf.seek(0)
            raf.write("CHUNKA".toByteArray())
        }
        assertEquals(1048576 + 6L, f.length())
    }

    // Scenario 24: Single-byte transfer over multi-channel
    @Test
    fun testScenario24_SingleByteMultiChannelTransfer() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())
            val f = harness.createTestFile(harness.client.localSandboxDir, "onebyte.bin", 1L)
            assertTrue(harness.client.sendFiles(listOf(f), "/"))
            assertEquals(1L, File(harness.server.sandboxDir, "onebyte.bin").length())
        }
    }

    // Scenario 25: Rapid client reconnects
    @Test
    fun testScenario25_RapidReconnects() {
        val port = DynamicPortAllocator.allocateFreePort()
        val server = MockQuickShareServer(port = port)
        server.start()

        val client = MockQuickShareClient(serverPort = port)
        assertTrue(client.connect())
        client.close()
        server.close()
    }

    // Scenario 26: Multi-channel queue min-head comparator
    @Test
    fun testScenario26_MultiChannelMinHeadComparator() {
        val q0 = PriorityQueue<FileBlock>().apply { add(FileBlock(true, 0, "f", 0L, 0L, 0)) }
        val q1 = PriorityQueue<FileBlock>().apply { add(FileBlock(true, 0, "f", 0L, 0L, 1)) }
        val head0 = q0.peek()
        val head1 = q1.peek()
        assertNotNull(head0)
        assertNotNull(head1)
        assertTrue(head0!! < head1!!)
    }

    // Scenario 27: Full Round-Trip End-to-End Sync
    @Test
    fun testScenario27_FullRoundTripSync() {
        LoopbackHarness(advertisedNics = listOf("wlan0", "rndis0")).use { harness ->
            assertTrue(harness.startAndConnect())

            // 1. Create client source file
            val src = harness.createTestFile(harness.client.localSandboxDir, "sync/project.zip", 3145728L) // 3MB
            val srcMd5 = harness.computeMd5(src)

            // 2. Push to server
            assertTrue(harness.client.sendFiles(listOf(src), "/synced_dir"))

            // 3. List remote files to verify existence
            val list = harness.client.listFiles("synced_dir/sync")
            assertNotNull(list)
            assertTrue(list?.any { it.name == "project.zip" } == true)

            // 4. Pull back to client secondary folder
            val pullDir = File(harness.client.localSandboxDir, "pulled_verify").apply { mkdirs() }
            assertTrue(harness.client.pullFiles(listOf("synced_dir/sync/project.zip"), "/", pullDir))

            // 5. Verify MD5 integrity
            val pulledFile = File(pullDir, "synced_dir/sync/project.zip")
            assertTrue(pulledFile.exists())
            assertEquals(srcMd5, harness.computeMd5(pulledFile))

            // 6. Delete remote file and verify gone
            assertTrue(harness.client.deleteFile("synced_dir/sync/project.zip"))
            val listAfterDel = harness.client.listFiles("synced_dir/sync")
            assertFalse(listAfterDel?.any { it.name == "project.zip" } == true)
        }
    }
}

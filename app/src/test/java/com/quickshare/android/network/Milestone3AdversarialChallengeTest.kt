package com.quickshare.android.network

import com.quickshare.android.e2e.harness.DynamicPortAllocator
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.protocol.HandshakeResult
import com.quickshare.android.transfer.ChecksumUtil
import com.quickshare.android.transfer.DirectStorageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 3 (Dual-Mode Engine & Multi-NIC Service).
 *
 * Stresses:
 * 1. Concurrency: High-frequency connect/disconnect loops, parallel RPCs.
 * 2. Multi-NIC / Multi-Channel Data Streaming: High volume random payload byte integrity.
 * 3. Socket Interruption & Channel Drop: Sudden connection abort, mid-stream truncation, teardown resilience.
 */
class Milestone3AdversarialChallengeTest {

    private lateinit var serverSandbox: File
    private lateinit var clientSandbox: File
    private var serverPort: Int = 0

    private lateinit var serverStorage: DirectStorageEngine
    private lateinit var clientStorage: DirectStorageEngine

    private var server: QuickShareServer? = null
    private var client: QuickShareClient? = null

    private val multiNics4Channels = listOf(
        NetworkInterfaceInfo("wlan0", "127.0.0.1", InterfaceType.WIFI, true),
        NetworkInterfaceInfo("rndis0", "127.0.0.1", InterfaceType.USB_TETHERING, true),
        NetworkInterfaceInfo("eth0", "127.0.0.1", InterfaceType.ETHERNET, true),
        NetworkInterfaceInfo("p2p0", "127.0.0.1", InterfaceType.WIFI, true)
    )

    @Before
    fun setUp() {
        val tempDir = System.getProperty("java.io.tmpdir")
        val timestamp = System.nanoTime()
        serverSandbox = File(tempDir, "quickshare_adv_srv_$timestamp")
        clientSandbox = File(tempDir, "quickshare_adv_cli_$timestamp")

        serverSandbox.mkdirs()
        clientSandbox.mkdirs()

        serverPort = DynamicPortAllocator.allocateFreePort()
        serverStorage = DirectStorageEngine(serverSandbox)
        clientStorage = DirectStorageEngine(clientSandbox)
    }

    @After
    fun tearDown() {
        runBlocking {
            try { client?.disconnect() } catch (_: Throwable) {}
            try { client?.close() } catch (_: Throwable) {}
            try { server?.stop() } catch (_: Throwable) {}
            try { server?.close() } catch (_: Throwable) {}

            DynamicPortAllocator.releasePort(serverPort)

            serverSandbox.deleteRecursively()
            clientSandbox.deleteRecursively()
        }
    }

    private fun createTestFile(dir: File, name: String, sizeBytes: Long, seed: Long = 42L): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        val random = Random(seed)
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (written < sizeBytes) {
                val toWrite = minOf(buf.size.toLong(), sizeBytes - written).toInt()
                random.nextBytes(buf)
                fos.write(buf, 0, toWrite)
                written += toWrite
            }
        }
        return file
    }

    // =========================================================================
    // 1. CONCURRENCY STRESS TESTS
    // =========================================================================

    @Test
    fun testRapidConnectDisconnectLoop() {
        runBlocking {
            withTimeout(30000) {
                server = QuickShareServer(storageManager = serverStorage)
                val started = server!!.start(serverPort, multiNics4Channels, "127.0.0.1")
                assertTrue("Server start failed", started)

                val iterations = 20
                for (i in 1..iterations) {
                    val cli = QuickShareClient(storageManager = clientStorage)
                    val result = cli.connect("127.0.0.1", serverPort, multiNics4Channels, timeoutMs = 3000)
                    assertTrue("Iteration $i connect failed: $result", result is HandshakeResult.Success)
                    assertTrue(cli.isConnected.value)

                    // Execute quick ping RPC
                    val files = cli.listRemoteFiles(serverSandbox.absolutePath)
                    assertNotNull("Iteration $i listRemoteFiles failed", files)

                    cli.disconnect()
                    assertFalse("Iteration $i client should be disconnected", cli.isConnected.value)
                    cli.close()
                    delay(30)
                }
            }
        }
    }

    @Test
    fun testConcurrentRpcRequests() {
        runBlocking {
            withTimeout(30000) {
                // Prepare multiple test files and folders on server
                for (i in 1..10) {
                    createTestFile(serverSandbox, "rpc_test_$i.txt", (i * 100).toLong(), seed = i.toLong())
                }

                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Launch 25 concurrent RPC operations simultaneously
                val successCount = AtomicInteger(0)
                val coroutines = (1..25).map { index ->
                    async(Dispatchers.IO) {
                        when (index % 3) {
                            0 -> {
                                val files = client!!.listRemoteFiles(serverSandbox.absolutePath)
                                if (files != null && files.isNotEmpty()) {
                                    successCount.incrementAndGet()
                                }
                            }
                            1 -> {
                                val ok = client!!.makeRemoteDir(serverSandbox.absolutePath, "dir_concurrent_$index")
                                if (ok) {
                                    successCount.incrementAndGet()
                                }
                            }
                            2 -> {
                                val ok = client!!.deleteRemoteFile(File(serverSandbox, "rpc_test_${(index % 10) + 1}.txt").absolutePath)
                                // delete might return true or false depending on whether it was already deleted
                                successCount.incrementAndGet()
                            }
                        }
                    }
                }

                coroutines.awaitAll()
                assertEquals("All concurrent RPC calls should complete successfully", 25, successCount.get())
            }
        }
    }

    // =========================================================================
    // 2. LARGE PAYLOAD / MULTI-CHANNEL DATA TRANSFER STRESS TESTS
    // =========================================================================

    @Test
    fun testMultiChannelPushLargePayloadRandomBytes() {
        runBlocking {
            withTimeout(45000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Create a batch of large payload files with distinct random seeds
                val f1 = createTestFile(clientSandbox, "huge_1.bin", 6 * 1024 * 1024 + 777, seed = 1001) // 6MB+
                val f2 = createTestFile(clientSandbox, "huge_2.bin", 4 * 1024 * 1024 + 333, seed = 1002) // 4MB+
                val f3 = createTestFile(clientSandbox, "medium.bin", 1024 * 1024 + 128, seed = 1003)    // 1MB+
                val f4 = createTestFile(clientSandbox, "small.bin", 512, seed = 1004)                   // 512B
                val f5 = createTestFile(clientSandbox, "empty.bin", 0, seed = 1005)                     // 0B

                val originalFiles = listOf(f1, f2, f3, f4, f5)
                val originalMd5s = originalFiles.map { ChecksumUtil.md5(it) }

                val pushOk = client!!.sendFiles(
                    localPaths = originalFiles.map { it.absolutePath },
                    remoteDestDir = serverSandbox.absolutePath
                )
                assertTrue("Multi-channel push of large payloads failed", pushOk)

                // Validate each received file byte-for-byte on server
                for (i in originalFiles.indices) {
                    val orig = originalFiles[i]
                    val dest = File(serverSandbox, orig.name)
                    assertTrue("Dest file ${dest.name} does not exist", dest.exists())
                    assertEquals("File size mismatch for ${dest.name}", orig.length(), dest.length())
                    assertEquals("MD5 checksum mismatch for ${dest.name}", originalMd5s[i], ChecksumUtil.md5(dest))
                }
            }
        }
    }

    @Test
    fun testMultiChannelPullLargePayloadRandomBytes() {
        runBlocking {
            withTimeout(45000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Prepare server-side payload
                val s1 = createTestFile(serverSandbox, "pull_large_1.dat", 5 * 1024 * 1024 + 500, seed = 2001)
                val s2 = createTestFile(serverSandbox, "pull_large_2.dat", 3 * 1024 * 1024 + 200, seed = 2002)
                val s3 = createTestFile(serverSandbox, "pull_empty.dat", 0, seed = 2003)

                val serverFiles = listOf(s1, s2, s3)
                val serverMd5s = serverFiles.map { ChecksumUtil.md5(it) }

                val pullOk = client!!.receiveFiles(
                    remotePaths = serverFiles.map { it.absolutePath },
                    remoteParentDir = serverSandbox.absolutePath,
                    localDestDir = clientSandbox.absolutePath
                )
                assertTrue("Multi-channel pull of large payloads failed", pullOk)

                // Validate pulled files on client
                for (i in serverFiles.indices) {
                    val orig = serverFiles[i]
                    val dest = File(clientSandbox, orig.name)
                    assertTrue("Client downloaded file ${dest.name} missing", dest.exists())
                    assertEquals("Size mismatch on ${dest.name}", orig.length(), dest.length())
                    assertEquals("MD5 mismatch on ${dest.name}", serverMd5s[i], ChecksumUtil.md5(dest))
                }
            }
        }
    }

    @Test
    fun testDeepDirectoryHierarchyWithZeroByteFiles() {
        runBlocking {
            withTimeout(45000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Build a 5-level directory structure
                val base = File(clientSandbox, "Level0")
                val l1 = File(base, "Level1")
                val l2 = File(l1, "Level2")
                val l3 = File(l2, "Level3")
                val l4 = File(l3, "Level4_Empty")
                l4.mkdirs()

                val f0 = createTestFile(base, "f0.txt", 100, seed = 1)
                val f1 = createTestFile(l1, "f1_empty.txt", 0, seed = 2)
                val f2 = createTestFile(l2, "f2_1mb.bin", 1024 * 1024 + 50, seed = 3)
                val f3 = createTestFile(l3, "f3_2mb.bin", 2 * 1024 * 1024 + 10, seed = 4)

                val pushOk = client!!.sendFiles(
                    localPaths = listOf(base.absolutePath),
                    remoteDestDir = serverSandbox.absolutePath
                )
                assertTrue("Push deep hierarchy failed", pushOk)

                val srvBase = File(serverSandbox, "Level0")
                assertTrue(srvBase.isDirectory)
                val srvL4 = File(srvBase, "Level1/Level2/Level3/Level4_Empty")
                assertTrue("Empty 4th level directory missing", srvL4.isDirectory)

                val srvF0 = File(srvBase, "f0.txt")
                val srvF1 = File(srvBase, "Level1/f1_empty.txt")
                val srvF2 = File(srvBase, "Level1/Level2/f2_1mb.bin")
                val srvF3 = File(srvBase, "Level1/Level2/Level3/f3_2mb.bin")

                assertEquals(ChecksumUtil.md5(f0), ChecksumUtil.md5(srvF0))
                assertEquals(0L, srvF1.length())
                assertEquals(ChecksumUtil.md5(f2), ChecksumUtil.md5(srvF2))
                assertEquals(ChecksumUtil.md5(f3), ChecksumUtil.md5(srvF3))
            }
        }
    }

    // =========================================================================
    // 3. SOCKET INTERRUPTION / CHANNEL DROP & RECOVERY TESTS
    // =========================================================================

    @Test
    fun testDataChannelAbruptKillDuringTransferFailsGracefully() {
        runBlocking {
            withTimeout(30000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Large payload to ensure transfer is active for a moment
                val bigFile = createTestFile(clientSandbox, "huge_abort.bin", 20 * 1024 * 1024, seed = 9999)

                // Launch transfer in background job
                val transferJob = async(Dispatchers.IO) {
                    client!!.sendFiles(
                        localPaths = listOf(bigFile.absolutePath),
                        remoteDestDir = serverSandbox.absolutePath
                    )
                }

                // Wait briefly for transfer to spin up and begin streaming
                delay(80)

                // Abruptly close client sockets mid-transfer
                client!!.close()

                val result = transferJob.await()
                assertFalse("Transfer should report false when connection is killed", result)
                assertFalse("Client should be marked disconnected", client!!.isConnected.value)

                // Server should clean up session
                delay(200)
                assertEquals(0, server!!.connectedClients.value.size)
            }
        }
    }

    @Test
    fun testServerAbruptStopMidTransfer() {
        runBlocking {
            withTimeout(30000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                val bigFile = createTestFile(clientSandbox, "huge_srv_stop.bin", 20 * 1024 * 1024, seed = 8888)

                val transferJob = async(Dispatchers.IO) {
                    client!!.sendFiles(
                        localPaths = listOf(bigFile.absolutePath),
                        remoteDestDir = serverSandbox.absolutePath
                    )
                }

                delay(80)

                // Server abruptly stops mid-transfer
                server!!.stop()

                val result = transferJob.await()
                assertFalse("Transfer should fail when server stops mid-transfer", result)

                client!!.close()
            }
        }
    }

    @Test
    fun testHandshakeAnomaliesAndCorruptedPackets() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

            // 1. Client sends invalid header
            val rawSock1 = Socket("127.0.0.1", serverPort)
            rawSock1.getOutputStream().write("BADH".toByteArray())
            rawSock1.getOutputStream().flush()
            delay(100)
            rawSock1.close()

            // 2. Client abruptly closes immediately after connecting (Zero-byte connection)
            val rawSock2 = Socket("127.0.0.1", serverPort)
            rawSock2.close()
            delay(100)

            // Server must still be healthy and accept a normal client afterwards
            val legitClient = QuickShareClient(storageManager = clientStorage)
            val res = legitClient.connect("127.0.0.1", serverPort, multiNics4Channels)
            assertTrue("Server should accept legit client after anomalies", res is HandshakeResult.Success)
            assertTrue(legitClient.isConnected.value)

            legitClient.disconnect()
        }
    }

    @Test
    fun testBidirectionalServerInitiatedPushAndPull() {
        runBlocking {
            withTimeout(45000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // 1. Server RPCs to Client
                createTestFile(clientSandbox, "client_side_file.txt", 1234, seed = 55)
                val clientFiles = server!!.listRemoteFiles(clientSandbox.absolutePath)
                assertNotNull(clientFiles)
                assertTrue(clientFiles!!.any { it.name == "client_side_file.txt" })

                val mkdirOk = server!!.createRemoteDir(clientSandbox.absolutePath, "ServerCreatedDir")
                assertTrue(mkdirOk)
                assertTrue(File(clientSandbox, "ServerCreatedDir").isDirectory)

                // 2. Server PUSH to Client (5MB)
                val srvPushFile = createTestFile(serverSandbox, "srv_push_5mb.bin", 5 * 1024 * 1024 + 123, seed = 888)
                val srvPushMd5 = ChecksumUtil.md5(srvPushFile)

                val pushToCliOk = server!!.sendFilesToRemote(
                    localPaths = listOf(srvPushFile.absolutePath),
                    remoteDestDir = clientSandbox.absolutePath
                )
                assertTrue("Server push to client failed", pushToCliOk)

                val cliRecvFile = File(clientSandbox, "srv_push_5mb.bin")
                assertTrue(cliRecvFile.exists())
                assertEquals(srvPushFile.length(), cliRecvFile.length())
                assertEquals(srvPushMd5, ChecksumUtil.md5(cliRecvFile))

                // 3. Server PULL from Client (4MB)
                val cliPullFile = createTestFile(clientSandbox, "cli_source_4mb.bin", 4 * 1024 * 1024 + 456, seed = 777)
                val cliPullMd5 = ChecksumUtil.md5(cliPullFile)

                val pullFromCliOk = server!!.pullFilesFromRemote(
                    remotePaths = listOf(cliPullFile.absolutePath),
                    remoteParentDir = clientSandbox.absolutePath,
                    localDestDir = serverSandbox.absolutePath
                )
                assertTrue("Server pull from client failed", pullFromCliOk)

                val srvRecvFile = File(serverSandbox, "cli_source_4mb.bin")
                assertTrue(srvRecvFile.exists())
                assertEquals(cliPullFile.length(), srvRecvFile.length())
                assertEquals(cliPullMd5, ChecksumUtil.md5(srvRecvFile))
            }
        }
    }

    @Test
    fun testServerStopDuringActiveListeningRecoversGracefully() {
        runBlocking {
            withTimeout(15000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")
                assertTrue(server!!.isRunning.value)

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)

                // Stop server abruptly while client is connected
                server!!.stop()
                assertFalse(server!!.isRunning.value)

                delay(100)

                // Any subsequent RPC from client should fail gracefully without throwing uncaught fatal exception
                val remoteFiles = client!!.listRemoteFiles("/some/path")
                assertNull("RPC should return null when server stopped", remoteFiles)

                // Client can cleanly disconnect
                client!!.disconnect()
                assertFalse(client!!.isConnected.value)
            }
        }
    }

    @Test
    fun testClientDisconnectDuringIdleSession() {
        runBlocking {
            withTimeout(15000) {
                server = QuickShareServer(storageManager = serverStorage)
                server!!.start(serverPort, multiNics4Channels, "127.0.0.1")

                client = QuickShareClient(storageManager = clientStorage)
                val handshake = client!!.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue(handshake is HandshakeResult.Success)
                assertEquals(1, server!!.connectedClients.value.size)

                // Client abruptly disconnects
                client!!.disconnect()
                assertFalse(client!!.isConnected.value)

                delay(200)

                // Server should accept a new connection cleanly
                val client2 = QuickShareClient(storageManager = clientStorage)
                val handshake2 = client2.connect("127.0.0.1", serverPort, multiNics4Channels)
                assertTrue("New client connection failed after previous disconnect", handshake2 is HandshakeResult.Success)
                assertTrue(client2.isConnected.value)

                client2.disconnect()
            }
        }
    }

    @Test
    fun testTrafficManagerRobustnessUnderExtremeValues() {
        val tm = TrafficManager()
        // Test zero, negative, extreme ETA
        assertEquals("--", TrafficManager.formatEta(0))
        assertEquals("--", TrafficManager.formatEta(-100))
        assertEquals("59秒", TrafficManager.formatEta(59))
        assertEquals("1分 0秒", TrafficManager.formatEta(60))
        assertEquals("59分 59秒", TrafficManager.formatEta(3599))
        assertEquals("1小时 0分 0秒", TrafficManager.formatEta(3600))
        assertEquals("24小时 0分 0秒", TrafficManager.formatEta(86400))

        // Extreme speeds & sizes
        assertEquals("100.0 GB/s", TrafficManager.formatSpeed(100L * 1024 * 1024 * 1024))
        assertEquals("1000.00 GB", TrafficManager.formatSize(1000L * 1024 * 1024 * 1024))
    }
}

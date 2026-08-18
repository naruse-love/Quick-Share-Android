package com.quickshare.android.network

import com.quickshare.android.e2e.harness.DynamicPortAllocator
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.protocol.HandshakeResult
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.protocol.QuickShareStream
import com.quickshare.android.transfer.ChecksumUtil
import com.quickshare.android.transfer.DirectStorageEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Random

class QuickShareClientServerIntegrationTest {

    private lateinit var serverSandbox: File
    private lateinit var clientSandbox: File
    private var serverPort: Int = 0

    private lateinit var serverStorage: DirectStorageEngine
    private lateinit var clientStorage: DirectStorageEngine

    private var server: QuickShareServer? = null
    private var client: QuickShareClient? = null

    private val localNics = listOf(
        NetworkInterfaceInfo("wlan0", "127.0.0.1", InterfaceType.WIFI, true),
        NetworkInterfaceInfo("rndis0", "127.0.0.1", InterfaceType.USB_TETHERING, true)
    )

    @Before
    fun setUp() {
        val tempDir = System.getProperty("java.io.tmpdir")
        val timestamp = System.nanoTime()
        serverSandbox = File(tempDir, "quickshare_test_srv_$timestamp")
        clientSandbox = File(tempDir, "quickshare_test_cli_$timestamp")

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

    @Test
    fun test12StepHandshakeSuccess() {
        runBlocking {
            server = QuickShareServer(
                storageManager = serverStorage,
                socketFactory = MultiPathSocketFactory()
            )
            val started = server!!.start(
                listenPort = serverPort,
                activeNics = localNics,
                bindAddress = "127.0.0.1"
            )
            assertTrue("Server failed to start", started)
            assertTrue(server!!.isRunning.value)

            client = QuickShareClient(
                storageManager = clientStorage,
                socketFactory = MultiPathSocketFactory()
            )

            val result = client!!.connect(
                targetIp = "127.0.0.1",
                targetPort = serverPort,
                selectedNics = localNics,
                localHomeDir = clientSandbox.absolutePath
            )

            assertTrue("Handshake failed: $result", result is HandshakeResult.Success)
            assertTrue(client!!.isConnected.value)

            delay(100)
            assertEquals(1, server!!.connectedClients.value.size)
            assertEquals("127.0.0.1", server!!.connectedClients.value[0].ipAddress)
        }
    }

    @Test
    fun testVersionMismatchRejection() {
        runBlocking {
            server = QuickShareServer(
                storageManager = serverStorage,
                socketFactory = MultiPathSocketFactory()
            )
            server!!.start(
                listenPort = serverPort,
                activeNics = localNics,
                bindAddress = "127.0.0.1"
            )

            // Connect raw socket and send invalid version code (200)
            val socket = Socket("127.0.0.1", serverPort)
            val stream = QuickShareStream(socket)

            val headerBytes = QuickShareProtocolConstants.CLIENT_HEADER.toByteArray(StandardCharsets.UTF_8)
            stream.write(headerBytes, 0, headerBytes.size)
            stream.writeInt(200) // Invalid version
            stream.flush()

            val versionMatched = stream.readBoolean()
            assertFalse(versionMatched)
            val serverVersion = stream.readInt()
            assertEquals(QuickShareProtocolConstants.VERSION_CODE, serverVersion)

            socket.close()
        }
    }

    @Test
    fun testRemoteFileOperationsRpc() {
        runBlocking {
            // Setup initial files on server
            val f1 = createTestFile(serverSandbox, "doc1.txt", 1024)
            val f2 = createTestFile(serverSandbox, "nested/doc2.txt", 2048)

            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake is HandshakeResult.Success)

            // 1. Test LIST_FILES
            val files = client!!.listRemoteFiles(serverSandbox.absolutePath)
            assertNotNull(files)
            assertTrue(files!!.any { it.name == "doc1.txt" })
            assertTrue(files.any { it.name == "nested" && it.isDirectory })

            // 2. Test MKDIR
            val mkdirOk = client!!.makeRemoteDir(serverSandbox.absolutePath, "NewFolder")
            assertTrue(mkdirOk)
            assertTrue(File(serverSandbox, "NewFolder").isDirectory)

            // 3. Test DELETE_FILE
            val delOk = client!!.deleteRemoteFile(f1.absolutePath)
            assertTrue(delOk)
            assertFalse(f1.exists())
        }
    }

    @Test
    fun testPushTransferSingleAndMultiChannel() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake is HandshakeResult.Success)

            // 1. Small file push (512 bytes)
            val smallFile = createTestFile(clientSandbox, "small.dat", 512, seed = 101)
            val smallMd5 = ChecksumUtil.md5(smallFile)

            val pushSmallOk = client!!.sendFiles(
                localPaths = listOf(smallFile.absolutePath),
                remoteDestDir = serverSandbox.absolutePath
            )
            assertTrue("Push small file failed", pushSmallOk)

            val serverSmallFile = File(serverSandbox, "small.dat")
            assertTrue(serverSmallFile.exists())
            assertEquals(512, serverSmallFile.length())
            assertEquals(smallMd5, ChecksumUtil.md5(serverSmallFile))

            // 2. Multi-channel large file push (3.5MB across 2 channels)
            val largeFile = createTestFile(clientSandbox, "large_3_5mb.dat", (3.5 * 1024 * 1024).toLong(), seed = 202)
            val largeMd5 = ChecksumUtil.md5(largeFile)

            val pushLargeOk = client!!.sendFiles(
                localPaths = listOf(largeFile.absolutePath),
                remoteDestDir = serverSandbox.absolutePath
            )
            assertTrue("Push large file failed", pushLargeOk)

            val serverLargeFile = File(serverSandbox, "large_3_5mb.dat")
            assertTrue(serverLargeFile.exists())
            assertEquals(largeFile.length(), serverLargeFile.length())
            assertEquals(largeMd5, ChecksumUtil.md5(serverLargeFile))
        }
    }

    @Test
    fun testPushTransferDirectoryHierarchy() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake is HandshakeResult.Success)

            // Create folder hierarchy in client sandbox
            val rootDir = File(clientSandbox, "MyFolder")
            rootDir.mkdirs()
            val f1 = createTestFile(rootDir, "file1.txt", 1000, seed = 1)
            val subDir = File(rootDir, "SubDir")
            subDir.mkdirs()
            val f2 = createTestFile(subDir, "file2.bin", 50000, seed = 2)
            val emptySubDir = File(rootDir, "EmptyDir")
            emptySubDir.mkdirs()

            val pushDirOk = client!!.sendFiles(
                localPaths = listOf(rootDir.absolutePath),
                remoteDestDir = serverSandbox.absolutePath
            )
            assertTrue("Push directory tree failed", pushDirOk)

            val srvRootDir = File(serverSandbox, "MyFolder")
            assertTrue(srvRootDir.isDirectory)
            val srvF1 = File(srvRootDir, "file1.txt")
            assertTrue(srvF1.exists())
            assertEquals(ChecksumUtil.md5(f1), ChecksumUtil.md5(srvF1))

            val srvSubDir = File(srvRootDir, "SubDir")
            assertTrue(srvSubDir.isDirectory)
            val srvF2 = File(srvSubDir, "file2.bin")
            assertTrue(srvF2.exists())
            assertEquals(ChecksumUtil.md5(f2), ChecksumUtil.md5(srvF2))

            val srvEmptyDir = File(srvRootDir, "EmptyDir")
            assertTrue(srvEmptyDir.isDirectory)
        }
    }

    @Test
    fun testPullTransferMultiChannel() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake is HandshakeResult.Success)

            // Server has files to be pulled
            val srvFile = createTestFile(serverSandbox, "server_payload.dat", (2.5 * 1024 * 1024).toLong(), seed = 303)
            val expectedMd5 = ChecksumUtil.md5(srvFile)

            val pullOk = client!!.receiveFiles(
                remotePaths = listOf(srvFile.absolutePath),
                remoteParentDir = serverSandbox.absolutePath,
                localDestDir = clientSandbox.absolutePath
            )
            assertTrue("Pull transfer failed", pullOk)

            val downloadedFile = File(clientSandbox, "server_payload.dat")
            assertTrue(downloadedFile.exists())
            assertEquals(srvFile.length(), downloadedFile.length())
            assertEquals(expectedMd5, ChecksumUtil.md5(downloadedFile))
        }
    }

    @Test
    fun testServerInitiatedTransfers() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake is HandshakeResult.Success)

            // 1. Server sends file to client
            val srvLocalFile = createTestFile(serverSandbox, "from_server.bin", 150000, seed = 404)
            val sendToRemoteOk = server!!.sendFilesToRemote(
                localPaths = listOf(srvLocalFile.absolutePath),
                remoteDestDir = clientSandbox.absolutePath
            )
            assertTrue(sendToRemoteOk)

            val clientRecvFile = File(clientSandbox, "from_server.bin")
            assertTrue(clientRecvFile.exists())
            assertEquals(ChecksumUtil.md5(srvLocalFile), ChecksumUtil.md5(clientRecvFile))
        }
    }

    @Test
    fun testClientDisconnectAndReconnection() {
        runBlocking {
            server = QuickShareServer(storageManager = serverStorage)
            server!!.start(serverPort, localNics, "127.0.0.1")

            client = QuickShareClient(storageManager = clientStorage)
            val handshake1 = client!!.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake1 is HandshakeResult.Success)

            // Disconnect
            client!!.disconnect()
            assertFalse(client!!.isConnected.value)
            delay(300)

            // Reconnect with a new client instance
            val client2 = QuickShareClient(storageManager = clientStorage)
            val handshake2 = client2.connect("127.0.0.1", serverPort, localNics)
            assertTrue(handshake2 is HandshakeResult.Success)
            assertTrue(client2.isConnected.value)

            val testF = createTestFile(clientSandbox, "reconnect_test.dat", 1024, seed = 505)
            val pushOk = client2.sendFiles(listOf(testF.absolutePath), serverSandbox.absolutePath)
            assertTrue(pushOk)

            client2.disconnect()
            client2.close()
        }
    }
}

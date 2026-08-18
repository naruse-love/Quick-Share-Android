package com.quickshare.android.e2e.harness

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MockQuickShareClient provides a high-fidelity loopback client for QuickShareProtocol v300.
 * Can connect to MockQuickShareServer or real QuickShareServer to test RPCs and multi-channel transfers.
 */
class MockQuickShareClient(
    val serverHost: String = "127.0.0.1",
    val serverPort: Int,
    val clientNicNames: List<String> = listOf("wlan0", "rndis0"),
    val localSandboxDir: File = File(System.getProperty("java.io.tmpdir"), "quickshare_client_test_${System.nanoTime()}")
) : AutoCloseable {

    private var controlSocket: Socket? = null
    private var controlIn: DataInputStream? = null
    private var controlOut: DataOutputStream? = null

    private val dataSockets = CopyOnWriteArrayList<Socket>()
    private val dataIns = CopyOnWriteArrayList<DataInputStream>()
    private val dataOuts = CopyOnWriteArrayList<DataOutputStream>()

    private val isConnected = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    val serverAdvertisedNics = mutableListOf<String>()

    init {
        if (!localSandboxDir.exists()) {
            localSandboxDir.mkdirs()
        }
    }

    fun connect(timeoutMs: Int = 5000): Boolean {
        try {
            val ctrl = Socket()
            ctrl.tcpNoDelay = true
            ctrl.connect(InetSocketAddress(serverHost, serverPort), timeoutMs)
            controlSocket = ctrl
            controlIn = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
            controlOut = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))

            if (performHandshake()) {
                isConnected.set(true)
                return true
            }
            return false
        } catch (e: Exception) {
            close()
            return false
        }
    }

    private fun performHandshake(): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        // 1. Write header "HFXC" (4 bytes)
        dout.write("HFXC".toByteArray(Charsets.UTF_8))

        // 2. Write version (int 300)
        dout.writeInt(300)
        dout.flush()

        // 3. Read version matched
        val versionMatched = din.readBoolean()
        if (!versionMatched) {
            val serverVersion = din.readInt()
            return false
        }

        // 4. Read server advertised NIC count
        val nicCount = din.readInt()
        serverAdvertisedNics.clear()
        val serverIps = mutableListOf<ByteArray>()

        for (i in 0 until nicCount) {
            val nicName = din.readUTF()
            serverAdvertisedNics.add(nicName)
            val ipLen = din.readByte().toInt()
            val ipBytes = ByteArray(ipLen)
            din.readFully(ipBytes)
            serverIps.add(ipBytes)
            val clientBindFlag = din.readByte() // 0
        }

        // 5. Connect data channels
        dataSockets.clear()
        dataIns.clear()
        dataOuts.clear()

        for (i in 0 until nicCount) {
            val nicName = if (i < clientNicNames.size) clientNicNames[i] else "nic_$i"
            dout.writeBoolean(true) // clientSucceed
            dout.writeUTF(nicName)
            dout.flush()

            val dataSock = Socket()
            dataSock.tcpNoDelay = true
            dataSock.connect(InetSocketAddress(serverHost, serverPort), 5000)
            dataSockets.add(dataSock)
            dataIns.add(DataInputStream(BufferedInputStream(dataSock.getInputStream())))
            dataOuts.add(DataOutputStream(BufferedOutputStream(dataSock.getOutputStream())))

            val serverAccepted = din.readBoolean()
            if (!serverAccepted) return false
        }

        // 6. Buffer negotiation
        val serverBufferCount = din.readInt()
        dout.writeBoolean(true) // allocate buffer ok
        dout.flush()
        val serverBufferOk = din.readBoolean()
        if (!serverBufferOk) return false

        // 7. Send client filesystem info (0 = UNIX, home dir)
        dout.writeInt(0) // Unix FS
        dout.writeUTF(localSandboxDir.absolutePath)
        dout.flush()

        return true
    }

    fun listFiles(path: String): List<MockRemoteFile>? {
        val din = controlIn ?: return null
        val dout = controlOut ?: return null

        synchronized(dout) {
            dout.writeShort(1) // LIST_FILES
            dout.writeUTF(path)
            dout.flush()

            val listSize = din.readInt()
            if (listSize == -1) return null

            val result = mutableListOf<MockRemoteFile>()
            for (i in 0 until listSize) {
                result.add(
                    MockRemoteFile(
                        name = din.readUTF(),
                        path = din.readUTF(),
                        lastModified = din.readLong(),
                        size = din.readLong(),
                        isDirectory = din.readBoolean()
                    )
                )
            }
            return result
        }
    }

    fun deleteFile(path: String): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        synchronized(dout) {
            dout.writeShort(2) // DELETE_FILE
            dout.writeUTF(path)
            dout.flush()
            return din.readBoolean()
        }
    }

    fun mkdir(parentPath: String, childName: String): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        synchronized(dout) {
            dout.writeShort(3) // MKDIR
            dout.writeUTF(parentPath)
            dout.writeUTF(childName)
            dout.flush()
            return din.readBoolean()
        }
    }

    fun shutdown() {
        val dout = controlOut ?: return
        try {
            synchronized(dout) {
                dout.writeShort(0) // SHUTDOWN
                dout.flush()
            }
        } catch (_: Exception) {}
    }

    fun sendFiles(files: List<File>, remoteDestDir: String): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        synchronized(dout) {
            dout.writeShort(10) // REQUEST_RECEIVE
            dout.flush()
        }

        val numChannels = dataOuts.size
        var currentChannel = 0

        val cleanDest = remoteDestDir.replace("\\", "/").trim('/')

        for ((fileIdx, file) in files.withIndex()) {
            val relInside = if (file.absolutePath.startsWith(localSandboxDir.absolutePath)) {
                file.relativeTo(localSandboxDir).path.replace('\\', '/')
            } else {
                file.name
            }
            val transferPath = if (cleanDest.isEmpty()) relInside else "$cleanDest/$relInside"

            if (file.isDirectory) {
                val chOut = dataOuts[currentChannel % numChannels]
                synchronized(chOut) {
                    chOut.writeShort(1) // FOLDER
                    chOut.writeInt(fileIdx)
                    chOut.writeUTF(transferPath)
                    chOut.writeLong(file.lastModified())
                    chOut.flush()
                }
                currentChannel++
            } else {
                val totalSize = if (file.exists()) file.length() else 0L
                if (totalSize == 0L) {
                    val chOut = dataOuts[currentChannel % numChannels]
                    synchronized(chOut) {
                        chOut.writeShort(0) // FILE
                        chOut.writeInt(fileIdx)
                        chOut.writeUTF(transferPath)
                        chOut.writeLong(file.lastModified())
                        chOut.writeLong(0L)
                        chOut.writeInt(0)
                        chOut.writeInt(0)
                        chOut.flush()
                    }
                    currentChannel++
                } else {
                    val buffer = ByteArray(1024 * 1024)
                    var blockIdx = 0
                    FileInputStream(file).use { fis ->
                        while (true) {
                            val read = fis.read(buffer)
                            if (read <= 0) break
                            val chOut = dataOuts[currentChannel % numChannels]
                            synchronized(chOut) {
                                chOut.writeShort(0) // FILE
                                chOut.writeInt(fileIdx)
                                chOut.writeUTF(transferPath)
                                chOut.writeLong(file.lastModified())
                                chOut.writeLong(totalSize)
                                chOut.writeInt(blockIdx)
                                chOut.writeInt(read)
                                chOut.write(buffer, 0, read)
                                chOut.flush()
                            }
                            bytesSent.addAndGet(read.toLong())
                            blockIdx++
                            currentChannel++
                        }
                    }
                }
            }
        }

        // Send EOF on all data channels
        for (chOut in dataOuts) {
            synchronized(chOut) {
                chOut.writeShort(3) // EOF
                chOut.flush()
            }
        }

        // Read server write feedback
        val writeOk = din.readBoolean()
        if (!writeOk) {
            val errorMsg = din.readUTF()
            return false
        }

        // Send completion ack
        synchronized(dout) {
            dout.writeBoolean(true)
            dout.flush()
        }

        return true
    }

    fun pullFiles(remotePaths: List<String>, remoteParentDir: String, destDir: File): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        synchronized(dout) {
            dout.writeShort(11) // REQUEST_SEND
            dout.writeInt(remotePaths.size)
            for (p in remotePaths) {
                dout.writeUTF(p)
            }
            dout.writeUTF(remoteParentDir)
            dout.writeInt(0) // Unix FS
            dout.writeUTF(destDir.absolutePath)
            dout.flush()
        }

        val numChannels = dataIns.size
        val channelTasks = mutableListOf<Future<*>>()

        for (chIdx in 0 until numChannels) {
            val chIn = dataIns[chIdx]
            channelTasks.add(executor.submit {
                while (isConnected.get()) {
                    val frameType = chIn.readShort().toInt()
                    if (frameType == 3) break // EOF
                    when (frameType) {
                        1 -> { // FOLDER
                            val fileIndex = chIn.readInt()
                            val relPath = chIn.readUTF()
                            val lastModified = chIn.readLong()
                            val target = File(destDir, relPath.trimStart('/'))
                            target.mkdirs()
                            if (lastModified > 0) {
                                target.setLastModified(lastModified)
                            }
                        }
                        0 -> { // FILE
                            val fileIndex = chIn.readInt()
                            val relPath = chIn.readUTF()
                            val lastModified = chIn.readLong()
                            val totalSize = chIn.readLong()
                            val chunkIndex = chIn.readInt()
                            val dataLen = chIn.readInt()
                            val chunkData = ByteArray(dataLen)
                            if (dataLen > 0) {
                                chIn.readFully(chunkData)
                            }
                            bytesReceived.addAndGet(dataLen.toLong())

                            val target = File(destDir, relPath.trimStart('/'))
                            target.parentFile?.mkdirs()
                            synchronized(this) {
                                RandomAccessFile(target, "rw").use { raf ->
                                    if (totalSize > 0) {
                                        raf.seek(chunkIndex.toLong() * 1024 * 1024)
                                        raf.write(chunkData, 0, dataLen)
                                    } else {
                                        raf.setLength(0)
                                    }
                                }
                                if (lastModified > 0) {
                                    target.setLastModified(lastModified)
                                }
                            }
                        }
                    }
                }
            })
        }

        for (t in channelTasks) {
            t.get(60, TimeUnit.SECONDS)
        }

        // Send write feedback
        synchronized(dout) {
            dout.writeBoolean(true) // writeOk
            dout.writeBoolean(true) // channelsFinished
            dout.flush()
        }

        // Read sender read complete
        return din.readBoolean()
    }

    override fun close() {
        isConnected.set(false)
        try { controlSocket?.close() } catch (_: Exception) {}
        for (s in dataSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        executor.shutdownNow()
        localSandboxDir.deleteRecursively()
    }
}

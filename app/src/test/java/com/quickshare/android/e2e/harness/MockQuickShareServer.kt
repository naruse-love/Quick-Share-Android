package com.quickshare.android.e2e.harness

import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Data model for mock remote file entries.
 */
data class MockRemoteFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * MockQuickShareServer provides a high-fidelity loopback implementation of QuickShareProtocol v300 server.
 * Supports full 12-step handshake, control RPCs, multi-channel chunk slicing and reassembly.
 */
class MockQuickShareServer(
    val port: Int = DynamicPortAllocator.allocateFreePort(),
    val sandboxDir: File = File(System.getProperty("java.io.tmpdir"), "quickshare_server_test_${System.nanoTime()}"),
    val advertisedNics: List<String> = listOf("wlan0", "rndis0")
) : AutoCloseable {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    private var controlSocket: Socket? = null
    private var controlIn: DataInputStream? = null
    private var controlOut: DataOutputStream? = null

    private val dataSockets = CopyOnWriteArrayList<Socket>()
    private val dataIns = CopyOnWriteArrayList<DataInputStream>()
    private val dataOuts = CopyOnWriteArrayList<DataOutputStream>()

    val bytesReceived = AtomicLong(0)
    val bytesSent = AtomicLong(0)

    var remoteFileSystem: Int = 0 // 0 = UNIX, 1 = WINDOWS
    var remoteHomeDir: String = ""

    val onHandshakeComplete = CompletableFuture<Boolean>()
    val onTransferComplete = CompletableFuture<Boolean>()

    init {
        if (!sandboxDir.exists()) {
            sandboxDir.mkdirs()
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        serverSocket = ServerSocket(port)
        executor.submit {
            try {
                // Accept control connection
                val ctrl = serverSocket?.accept() ?: return@submit
                ctrl.tcpNoDelay = true
                controlSocket = ctrl
                controlIn = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
                controlOut = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))

                if (handleHandshake()) {
                    onHandshakeComplete.complete(true)
                    handleControlLoop()
                } else {
                    onHandshakeComplete.complete(false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onHandshakeComplete.completeExceptionally(e)
                }
            }
        }
    }

    private fun handleHandshake(): Boolean {
        val din = controlIn ?: return false
        val dout = controlOut ?: return false

        // 1. Read header "HFXC" (4 bytes)
        val header = ByteArray(4)
        din.readFully(header)
        val headerStr = String(header, Charsets.UTF_8)
        if (headerStr != "HFXC") {
            return false
        }

        // 2. Read version (int 300)
        val versionCode = din.readInt()
        if (versionCode != 300) {
            dout.writeBoolean(false)
            dout.writeInt(300)
            dout.flush()
            return false
        }
        dout.writeBoolean(true)
        dout.flush()

        // 3. Send interface list
        dout.writeInt(advertisedNics.size)
        val loopbackAddr = InetAddress.getByName("127.0.0.1").address
        for (nicName in advertisedNics) {
            dout.writeUTF(nicName)
            dout.writeByte(loopbackAddr.size)
            dout.write(loopbackAddr)
            dout.writeByte(0) // clientBindAddress flag = 0
        }
        dout.flush()

        // 4. Accept data channel connections
        dataSockets.clear()
        dataIns.clear()
        dataOuts.clear()

        for (i in advertisedNics.indices) {
            val clientSucceed = din.readBoolean()
            val clientNicName = din.readUTF()
            if (clientSucceed) {
                val dataSock = serverSocket?.accept() ?: return false
                dataSock.tcpNoDelay = true
                dataSockets.add(dataSock)
                dataIns.add(DataInputStream(BufferedInputStream(dataSock.getInputStream())))
                dataOuts.add(DataOutputStream(BufferedOutputStream(dataSock.getOutputStream())))
                dout.writeBoolean(true)
                dout.flush()
            } else {
                dout.writeBoolean(false)
                dout.flush()
                return false
            }
        }

        // 5. Buffer pool negotiation
        val bufferCount = 8
        dout.writeInt(bufferCount)
        dout.flush()
        val remoteBufferOk = din.readBoolean()
        if (!remoteBufferOk) return false
        dout.writeBoolean(true) // local buffer ok
        dout.flush()

        // 6. Read client file system info
        remoteFileSystem = din.readInt()
        remoteHomeDir = din.readUTF()

        return true
    }

    private fun handleControlLoop() {
        val din = controlIn ?: return
        val dout = controlOut ?: return

        try {
            while (isRunning.get()) {
                val cmd = try {
                    din.readShort().toInt()
                } catch (e: EOFException) {
                    break
                }

                when (cmd) {
                    0 -> { // SHUTDOWN
                        break
                    }
                    1 -> { // LIST_FILES
                        val targetPath = din.readUTF()
                        handleListFiles(targetPath, dout)
                    }
                    2 -> { // DELETE_FILE
                        val targetPath = din.readUTF()
                        handleDeleteFile(targetPath, dout)
                    }
                    3 -> { // MKDIR
                        val parentPath = din.readUTF()
                        val childName = din.readUTF()
                        handleMkdir(parentPath, childName, dout)
                    }
                    10 -> { // REQUEST_RECEIVE (Push from client to server)
                        handleReceivePush(din, dout)
                    }
                    11 -> { // REQUEST_SEND (Pull from server to client)
                        handleSendPull(din, dout)
                    }
                }
            }
        } catch (e: Exception) {
            // Socket closed or connection terminated
        }
    }

    private fun handleListFiles(path: String, dout: DataOutputStream) {
        val target = resolveSandboxPath(path)
        if (!target.exists()) {
            dout.writeInt(-1)
            dout.flush()
            return
        }

        val files = target.listFiles() ?: emptyArray()
        dout.writeInt(files.size)
        for (f in files) {
            dout.writeUTF(f.name)
            dout.writeUTF(f.absolutePath)
            dout.writeLong(f.lastModified())
            dout.writeLong(if (f.isDirectory) 0L else f.length())
            dout.writeBoolean(f.isDirectory)
        }
        dout.flush()
    }

    private fun handleDeleteFile(path: String, dout: DataOutputStream) {
        val target = resolveSandboxPath(path)
        val success = if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
        dout.writeBoolean(success)
        dout.flush()
    }

    private fun handleMkdir(parentPath: String, childName: String, dout: DataOutputStream) {
        val parent = resolveSandboxPath(parentPath)
        val target = File(parent, childName)
        val success = target.mkdirs() || target.exists()
        dout.writeBoolean(success)
        dout.flush()
    }

    private fun handleReceivePush(din: DataInputStream, dout: DataOutputStream) {
        try {
            val numChannels = dataIns.size
            val channelTasks = mutableListOf<Future<*>>()

            // Concurrent chunk receiver across data channels
            for (chIndex in 0 until numChannels) {
                val chIn = dataIns[chIndex]
                channelTasks.add(executor.submit {
                    while (isRunning.get()) {
                        val frameType = chIn.readShort().toInt()
                        if (frameType == 3) { // EOF
                            break
                        }
                        when (frameType) {
                            1 -> { // FOLDER
                                val fileIndex = chIn.readInt()
                                val relPath = chIn.readUTF()
                                val lastModified = chIn.readLong()
                                val targetDir = resolveSandboxPath(relPath)
                                targetDir.mkdirs()
                                if (lastModified > 0) {
                                    targetDir.setLastModified(lastModified)
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

                                val targetFile = resolveSandboxPath(relPath)
                                targetFile.parentFile?.mkdirs()
                                synchronized(this) {
                                    RandomAccessFile(targetFile, "rw").use { raf ->
                                        if (totalSize > 0) {
                                            raf.seek(chunkIndex.toLong() * 1024 * 1024)
                                            raf.write(chunkData, 0, dataLen)
                                        } else {
                                            raf.setLength(0)
                                        }
                                    }
                                    if (lastModified > 0) {
                                        targetFile.setLastModified(lastModified)
                                    }
                                }
                            }
                            else -> {
                                throw IOException("Unknown frame type: $frameType")
                            }
                        }
                    }
                })
            }

            for (task in channelTasks) {
                task.get(60, TimeUnit.SECONDS)
            }

            // Write status feedback to client
            dout.writeBoolean(true)
            dout.flush()

            // Wait for client sender complete ack
            val clientCompleteAck = din.readBoolean()
            onTransferComplete.complete(clientCompleteAck)
        } catch (e: Exception) {
            dout.writeBoolean(false)
            dout.writeUTF(e.message ?: "Receive Push Error")
            dout.flush()
            onTransferComplete.complete(false)
        }
    }

    private fun handleSendPull(din: DataInputStream, dout: DataOutputStream) {
        try {
            val fileCount = din.readInt()
            val remotePaths = mutableListOf<String>()
            for (i in 0 until fileCount) {
                remotePaths.add(din.readUTF())
            }
            val remoteParentDir = din.readUTF()
            val requestorFs = din.readInt()
            val destinationDir = din.readUTF()

            val filesToSend = remotePaths.map { Pair(it, resolveSandboxPath(it)) }
            val numChannels = dataOuts.size

            var currentChannel = 0
            for ((fileIdx, pair) in filesToSend.withIndex()) {
                val relPath = pair.first
                val file = pair.second

                if (file.isDirectory) {
                    val chOut = dataOuts[currentChannel % numChannels]
                    synchronized(chOut) {
                        chOut.writeShort(1) // FOLDER
                        chOut.writeInt(fileIdx)
                        chOut.writeUTF(relPath)
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
                            chOut.writeUTF(relPath)
                            chOut.writeLong(file.lastModified())
                            chOut.writeLong(0L)
                            chOut.writeInt(0)
                            chOut.writeInt(0)
                            chOut.flush()
                        }
                        currentChannel++
                    } else {
                        val buffer = ByteArray(1024 * 1024)
                        var blockIndex = 0
                        FileInputStream(file).use { fis ->
                            while (true) {
                                val read = fis.read(buffer)
                                if (read <= 0) break
                                val chOut = dataOuts[currentChannel % numChannels]
                                synchronized(chOut) {
                                    chOut.writeShort(0) // FILE
                                    chOut.writeInt(fileIdx)
                                    chOut.writeUTF(relPath)
                                    chOut.writeLong(file.lastModified())
                                    chOut.writeLong(totalSize)
                                    chOut.writeInt(blockIndex)
                                    chOut.writeInt(read)
                                    chOut.write(buffer, 0, read)
                                    chOut.flush()
                                }
                                bytesSent.addAndGet(read.toLong())
                                blockIndex++
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

            // Read client feedback
            val clientWriteOk = din.readBoolean()
            val clientChFinished = din.readBoolean()

            // Ack sender complete
            dout.writeBoolean(clientWriteOk && clientChFinished)
            dout.flush()

            onTransferComplete.complete(clientWriteOk)
        } catch (e: Exception) {
            onTransferComplete.complete(false)
        }
    }

    fun resolveSandboxPath(path: String): File {
        if (path.isEmpty() || path == "/") return sandboxDir
        val normalized = path.replace("\\", "/")
        val sandboxNorm = sandboxDir.absolutePath.replace("\\", "/")
        if (normalized.startsWith(sandboxNorm)) {
            return File(path)
        }

        // Handle DOS drive prefix (e.g. D:/Folder or D:\Folder)
        var cleaned = if (normalized.length >= 2 && normalized[1] == ':') {
            normalized.substring(2)
        } else {
            normalized
        }.trimStart('/')

        return File(sandboxDir, cleaned)
    }

    override fun close() {
        isRunning.set(false)
        try { controlSocket?.close() } catch (_: Exception) {}
        for (s in dataSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        DynamicPortAllocator.releasePort(port)
        executor.shutdownNow()
        sandboxDir.deleteRecursively()
    }
}

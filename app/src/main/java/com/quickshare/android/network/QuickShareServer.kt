package com.quickshare.android.network

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.protocol.QuickShareStream
import com.quickshare.android.protocol.IQuickShareStream
import com.quickshare.android.transfer.BufferPool
import com.quickshare.android.transfer.DirectStorageEngine
import com.quickshare.android.transfer.IStorageManager
import com.quickshare.android.transfer.ReadFileCall
import com.quickshare.android.transfer.ReceiveFileCall
import com.quickshare.android.transfer.SendFileCall
import com.quickshare.android.transfer.TransferConnection
import com.quickshare.android.transfer.WriteFileCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Metadata snapshot for an active connected client session.
 */
data class ClientSessionInfo(
    val ipAddress: String,
    val port: Int,
    val connectedTimeMs: Long = System.currentTimeMillis(),
    val remoteFileSystem: Int = QuickShareProtocolConstants.FILE_SYSTEM_UNIX,
    val remoteHomeDir: String = "",
    val activeChannels: List<String> = emptyList()
)

interface IQuickShareServer : Closeable, AutoCloseable {
    val isRunning: StateFlow<Boolean>
    val connectedClients: StateFlow<List<ClientSessionInfo>>
    val activeTransfers: StateFlow<List<TransferTask>>
    val serverStatusText: StateFlow<String>
    val channelTraffic: StateFlow<List<TrafficInfo>>
    val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot>

    suspend fun start(
        listenPort: Int = QuickShareProtocolConstants.DEFAULT_PORT,
        activeNics: List<NetworkInterfaceInfo> = emptyList(),
        bindAddress: String = "0.0.0.0"
    ): Boolean

    suspend fun stop()

    // Server-initiated operations to connected client
    suspend fun listRemoteFiles(path: String): List<RemoteFile>?
    suspend fun deleteRemoteFile(path: String): Boolean
    suspend fun createRemoteDir(parent: String, child: String): Boolean
    suspend fun sendFilesToRemote(
        localPaths: List<String>,
        remoteDestDir: String,
        onProgress: ((TransferTask) -> Unit)? = null
    ): Boolean
    suspend fun pullFilesFromRemote(
        remotePaths: List<String>,
        remoteParentDir: String,
        localDestDir: String,
        onProgress: ((TransferTask) -> Unit)? = null
    ): Boolean
}

/**
 * Full server-mode engine implementing QuickShareProtocol v300.
 * Listens on TCP ServerSocket, performs 12-step handshake responder, handles remote RPCs,
 * and coordinates multi-channel push and pull file transfers.
 */
class QuickShareServer(
    val storageManager: IStorageManager = DirectStorageEngine(),
    val socketFactory: IMultiPathSocketFactory = MultiPathSocketFactory(),
    val interfaceEnumerator: IInterfaceEnumerator = InterfaceEnumerator(),
    val trafficManager: TrafficManager = TrafficManager()
) : IQuickShareServer {

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val controlMutex = Mutex()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentPort: Int = QuickShareProtocolConstants.DEFAULT_PORT

    // Active client session state
    private var controlSocket: Socket? = null
    private var controlStream: IQuickShareStream? = null
    private val dataConnections = CopyOnWriteArrayList<TransferConnection>()
    private val dataSockets = CopyOnWriteArrayList<Socket>()
    private var bufferPool: BufferPool? = null

    private var remoteFsCode: Int = QuickShareProtocolConstants.FILE_SYSTEM_UNIX
    private var remoteHomePath: String = ""

    // Observable states
    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ClientSessionInfo>>(emptyList())
    override val connectedClients: StateFlow<List<ClientSessionInfo>> = _connectedClients.asStateFlow()

    private val _activeTransfers = MutableStateFlow<List<TransferTask>>(emptyList())
    override val activeTransfers: StateFlow<List<TransferTask>> = _activeTransfers.asStateFlow()

    private val _serverStatusText = MutableStateFlow("已停止")
    override val serverStatusText: StateFlow<String> = _serverStatusText.asStateFlow()

    private val _channelTraffic = MutableStateFlow<List<TrafficInfo>>(emptyList())
    override val channelTraffic: StateFlow<List<TrafficInfo>> = _channelTraffic.asStateFlow()

    override val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot> = trafficManager.trafficState

    override suspend fun start(
        listenPort: Int,
        activeNics: List<NetworkInterfaceInfo>,
        bindAddress: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true

        try {
            val bindInet = try {
                InetAddress.getByName(bindAddress)
            } catch (_: Throwable) {
                InetAddress.getByName("0.0.0.0")
            }

            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindInet, listenPort), 50)
            serverSocket = socket
            currentPort = socket.localPort

            _isRunning.value = true
            _serverStatusText.value = "正在监听 端口 $currentPort"

            acceptJob = serverScope.launch {
                acceptLoop(activeNics)
            }

            true
        } catch (e: Throwable) {
            _isRunning.value = false
            _serverStatusText.value = "启动失败: ${e.message}"
            false
        }
    }

    private suspend fun acceptLoop(configuredNics: List<NetworkInterfaceInfo>) {
        val server = serverSocket ?: return

        while (serverScope.isActive && !server.isClosed) {
            try {
                val client = withContext(Dispatchers.IO) {
                    server.accept()
                }
                socketFactory.configurePerformanceSocket(client)

                if (controlSocket != null && controlSocket?.isConnected == true) {
                    // Session already active: Reject subsequent client to maintain single active session
                    try { client.close() } catch (_: Throwable) {}
                    continue
                }

                val remoteIp = (client.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
                val remotePort = (client.remoteSocketAddress as? InetSocketAddress)?.port ?: 0

                val handshakeSuccess = handleHandshake(client, configuredNics)
                if (handshakeSuccess) {
                    _connectedClients.value = listOf(
                        ClientSessionInfo(
                            ipAddress = remoteIp,
                            port = remotePort,
                            remoteFileSystem = remoteFsCode,
                            remoteHomeDir = remoteHomePath,
                            activeChannels = dataConnections.map { it.iName }
                        )
                    )
                    _serverStatusText.value = "已连接: $remoteIp"

                    try {
                        handleControlLoop()
                    } finally {
                        disconnectSession()
                    }
                } else {
                    disconnectSession()
                }
            } catch (e: Throwable) {
                if (server.isClosed) break
                disconnectSession()
            }
        }
    }

    private suspend fun handleHandshake(
        clientSocket: Socket,
        configuredNics: List<NetworkInterfaceInfo>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = QuickShareStream(clientSocket)
            controlSocket = clientSocket
            controlStream = stream

            // Step 1: Read Header "HFXC" (4 bytes)
            val header = ByteArray(4)
            stream.readFully(header, 0, 4)
            val headerStr = String(header, StandardCharsets.UTF_8)
            if (headerStr != QuickShareProtocolConstants.CLIENT_HEADER) {
                return@withContext false
            }

            // Step 2: Read Version Code
            val versionCode = stream.readInt()
            if (versionCode != QuickShareProtocolConstants.VERSION_CODE) {
                stream.writeBoolean(false)
                stream.writeInt(QuickShareProtocolConstants.VERSION_CODE)
                stream.flush()
                return@withContext false
            }
            stream.writeBoolean(true) // Version matched
            stream.flush()

            // Step 3: Send Advertised Interface (1 LAN interface)
            val primaryNic = if (configuredNics.isNotEmpty()) {
                configuredNics.firstOrNull { it.isSelected } ?: configuredNics.first()
            } else {
                val found = interfaceEnumerator.getAvailableInterfaces()
                found.firstOrNull() ?: NetworkInterfaceInfo("wlan0", "127.0.0.1", InterfaceType.WIFI, true)
            }

            stream.writeInt(1)
            stream.writeUTF(primaryNic.name)
            val ipBytes = try {
                InetAddress.getByName(primaryNic.ipAddress).address
            } catch (_: Throwable) {
                InetAddress.getByName("127.0.0.1").address
            }
            stream.writeByte(ipBytes.size.toByte())
            stream.write(ipBytes, 0, ipBytes.size)
            stream.writeByte(0) // clientBindAddressFlag = 0
            stream.flush()

            // Step 4: Accept 1 Pure LAN Data Channel
            dataConnections.clear()
            dataSockets.clear()

            val clientSucceed = stream.readBoolean()
            val clientNicName = stream.readUTF()

            if (clientSucceed) {
                val transSock = serverSocket?.accept() ?: return@withContext false
                socketFactory.configurePerformanceSocket(transSock)
                dataSockets.add(transSock)
                val dataStream = QuickShareStream(transSock)
                dataConnections.add(TransferConnection(clientNicName, dataStream))
                stream.writeBoolean(true)
                stream.flush()
            } else {
                stream.writeBoolean(false)
                stream.flush()
                return@withContext false
            }

            // Step 5: Setup Buffer Pool
            val localBufferCount = QuickShareProtocolConstants.DEFAULT_BUFFER_COUNT
            stream.writeInt(localBufferCount)
            stream.flush()

            val remoteBufferOk = stream.readBoolean()
            if (!remoteBufferOk) return@withContext false

            bufferPool = BufferPool(localBufferCount)
            stream.writeBoolean(true)
            stream.flush()

            // Step 6: Read Remote File System Info
            remoteFsCode = stream.readInt()
            remoteHomePath = stream.readUTF()

            true
        } catch (e: Throwable) {
            false
        }
    }

    private suspend fun handleControlLoop() = withContext(Dispatchers.IO) {
        while (_isRunning.value && controlSocket != null && controlSocket?.isClosed == false) {
            val opCode: Short? = controlMutex.withLock {
                val stream = controlStream ?: return@withContext
                try {
                    controlSocket?.soTimeout = 200
                    stream.readShort()
                } catch (_: SocketTimeoutException) {
                    null
                } catch (_: Throwable) {
                    return@withContext
                }
            }

            if (opCode != null) {
                controlMutex.withLock {
                    val stream = controlStream ?: return@withContext
                    try {
                        controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS
                        when (opCode) {
                            QuickShareProtocolConstants.SHUTDOWN -> {
                                return@withContext
                            }
                            QuickShareProtocolConstants.LIST_FILES -> {
                                val path = stream.readUTF()
                                handleRpcListFiles(path, stream)
                            }
                            QuickShareProtocolConstants.DELETE_FILE -> {
                                val path = stream.readUTF()
                                handleRpcDeleteFile(path, stream)
                            }
                            QuickShareProtocolConstants.MKDIR -> {
                                val parent = stream.readUTF()
                                val child = stream.readUTF()
                                handleRpcMkdir(parent, child, stream)
                            }
                            QuickShareProtocolConstants.REQUEST_RECEIVE -> {
                                handlePushReceive(stream)
                            }
                            QuickShareProtocolConstants.REQUEST_SEND -> {
                                handlePullSend(stream)
                            }
                        }
                    } finally {
                        controlSocket?.soTimeout = 200
                    }
                }
            } else {
                delay(30)
            }
        }

        disconnectSession()
    }

    private fun handleRpcListFiles(path: String, stream: IQuickShareStream) {
        try {
            if (!storageManager.exists(path)) {
                stream.writeInt(-1)
                stream.flush()
                return
            }
            val files = storageManager.listFiles(path)
            stream.writeInt(files.size)
            for (file in files) {
                stream.writeUTF(file.name)
                stream.writeUTF(file.path)
                stream.writeLong(file.lastModified)
                stream.writeLong(file.size)
                stream.writeBoolean(file.isDirectory)
            }
            stream.flush()
        } catch (e: Throwable) {
            try {
                stream.writeInt(-1)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private fun handleRpcDeleteFile(path: String, stream: IQuickShareStream) {
        try {
            val success = storageManager.delete(path)
            stream.writeBoolean(success)
            stream.flush()
        } catch (e: Throwable) {
            try {
                stream.writeBoolean(false)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private fun handleRpcMkdir(parent: String, child: String, stream: IQuickShareStream) {
        try {
            val success = storageManager.mkdir(parent, child)
            stream.writeBoolean(success)
            stream.flush()
        } catch (e: Throwable) {
            try {
                stream.writeBoolean(false)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun handlePushReceive(stream: IQuickShareStream) = withContext(Dispatchers.IO) {
        val pool = bufferPool ?: return@withContext
        if (dataConnections.isEmpty()) {
            stream.writeBoolean(false)
            stream.writeUTF("No active data channels")
            stream.flush()
            return@withContext
        }

        var task = TransferTask(
            fileName = "接收远程传输",
            direction = TransferDirection.RECEIVE,
            status = TransferStatus.RUNNING,
            startTimeMs = System.currentTimeMillis()
        )
        _activeTransfers.value = listOf(task)

        try {
            val writeFileCall = WriteFileCall(pool, dataConnections.size, storageManager)
            val writeJob = launch { writeFileCall.executeAsync() }

            val recvJobs = dataConnections.mapIndexed { idx, conn ->
                launch {
                    val recvCall = ReceiveFileCall(
                        channelIndex = idx,
                        connection = conn,
                        writeFileCall = writeFileCall,
                        onProgress = { _, _, _, totalSize ->
                            if (task.size < totalSize) task = task.copy(size = totalSize)
                            val totalRecv = dataConnections.sumOf { it.getTotalTraffic().downloadTraffic }
                            val currentT = task.withBytesTransferred(minOf(totalRecv, task.size))
                            task = currentT
                            _activeTransfers.value = listOf(currentT)
                        }
                    )
                    recvCall.executeAsync()
                }
            }

            trafficManager.startMonitoring(
                connections = dataConnections,
                taskTotalSize = task.size,
                direction = TransferDirection.RECEIVE,
                transferredBytesProvider = { dataConnections.sumOf { it.getTotalTraffic().downloadTraffic } },
                coroutineScope = this
            )

            writeJob.join()
            recvJobs.joinAll()
            trafficManager.stopMonitoring()

            // Report write success to client
            stream.writeBoolean(true)
            stream.flush()

            val clientAck = stream.readBoolean()
            if (clientAck) {
                task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                _activeTransfers.value = listOf(task)
            } else {
                task = task.withStatus(TransferStatus.FAILED, "Client reported completion failure")
                _activeTransfers.value = listOf(task)
            }
        } catch (e: Throwable) {
            trafficManager.stopMonitoring()
            try {
                stream.writeBoolean(false)
                stream.writeUTF(e.message ?: "Write Error")
                stream.flush()
            } catch (_: Throwable) {}
            task = task.withStatus(TransferStatus.FAILED, e.message)
            _activeTransfers.value = listOf(task)
        }
    }

    private suspend fun handlePullSend(stream: IQuickShareStream) = withContext(Dispatchers.IO) {
        val pool = bufferPool ?: return@withContext
        if (dataConnections.isEmpty()) return@withContext

        try {
            val fileCount = stream.readInt()
            val remotePaths = ArrayList<String>(fileCount)
            for (i in 0 until fileCount) {
                remotePaths.add(stream.readUTF())
            }
            val remoteParentDir = stream.readUTF()
            val clientFs = stream.readInt()
            val clientDestDir = stream.readUTF()

            val remoteFiles = mutableListOf<RemoteFile>()
            var totalBytes = 0L

            for (path in remotePaths) {
                if (storageManager.exists(path)) {
                    val isDir = isDirectory(path)
                    val size = if (isDir) 0L else storageManager.getFileSize(path)
                    val name = File(path).name.ifEmpty { path }
                    remoteFiles.add(RemoteFile(name, path, getFileLastModified(path), size, isDir))
                    totalBytes += size
                }
            }

            var task = TransferTask(
                fileName = if (remotePaths.size == 1) File(remotePaths[0]).name else "${File(remotePaths[0]).name} 等 ${remotePaths.size} 个文件",
                direction = TransferDirection.SEND,
                size = totalBytes,
                status = TransferStatus.RUNNING,
                startTimeMs = System.currentTimeMillis()
            )
            _activeTransfers.value = listOf(task)

            val localBase = remoteParentDir
            val localFs = when {
                localBase.contains(":\\") || localBase.contains(":/") -> QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                localBase.startsWith("/") -> QuickShareProtocolConstants.FILE_SYSTEM_UNIX
                else -> QuickShareDirectory.getCurrentFileSystem()
            }
            val localDir = QuickShareDirectory(localBase, localFs)
            val remoteDir = QuickShareDirectory(clientDestDir, clientFs)

            val readFileCall = ReadFileCall(
                buffers = pool.rawQueue,
                files = remoteFiles,
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = dataConnections.size,
                storageResolver = { p -> storageManager.openForRead(p) }
            )

            val readJob = launch { readFileCall.executeAsync() }

            val sendJobs = dataConnections.map { conn ->
                launch {
                    val sendCall = SendFileCall(
                        readFileCall = readFileCall,
                        connection = conn,
                        onProgress = { _, _, _, _ ->
                            val totalSent = dataConnections.sumOf { it.getTotalTraffic().uploadTraffic }
                            val currentT = task.withBytesTransferred(minOf(totalSent, task.size))
                            task = currentT
                            _activeTransfers.value = listOf(currentT)
                        }
                    )
                    sendCall.executeAsync()
                }
            }

            trafficManager.startMonitoring(
                connections = dataConnections,
                taskTotalSize = totalBytes,
                direction = TransferDirection.SEND,
                transferredBytesProvider = { dataConnections.sumOf { it.getTotalTraffic().uploadTraffic } },
                coroutineScope = this
            )

            val clientWriteOk = stream.readBoolean()
            trafficManager.stopMonitoring()

            if (!clientWriteOk) {
                val errMsg = stream.readUTF()
                readFileCall.shutdownByWriteError()
                task = task.withStatus(TransferStatus.FAILED, errMsg)
                _activeTransfers.value = listOf(task)
                return@withContext
            }

            val clientChFinished = stream.readBoolean()
            sendJobs.joinAll()
            readJob.join()

            // Ack to client
            stream.writeBoolean(clientWriteOk && clientChFinished)
            stream.flush()

            task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
            _activeTransfers.value = listOf(task)
        } catch (e: Throwable) {
            trafficManager.stopMonitoring()
            val task = TransferTask(
                fileName = "拉取传输失败",
                direction = TransferDirection.SEND,
                status = TransferStatus.FAILED,
                errorMessage = e.message
            )
            _activeTransfers.value = listOf(task)
        }
    }

    // --- Server-initiated operations to connected client ---

    override suspend fun listRemoteFiles(path: String): List<RemoteFile>? = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext null
            try {
                stream.writeShort(QuickShareProtocolConstants.LIST_FILES)
                stream.writeUTF(path)
                stream.flush()

                val count = stream.readInt()
                if (count == -1) return@withContext null

                val results = ArrayList<RemoteFile>(count)
                for (i in 0 until count) {
                    val name = stream.readUTF()
                    val p = stream.readUTF()
                    val lastModified = stream.readLong()
                    val size = stream.readLong()
                    val isDir = stream.readBoolean()
                    results.add(RemoteFile(name, p, lastModified, size, isDir))
                }
                results
            } catch (e: Throwable) {
                null
            }
        }
    }

    override suspend fun deleteRemoteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            try {
                stream.writeShort(QuickShareProtocolConstants.DELETE_FILE)
                stream.writeUTF(path)
                stream.flush()
                stream.readBoolean()
            } catch (e: Throwable) {
                false
            }
        }
    }

    override suspend fun createRemoteDir(parent: String, child: String): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            try {
                stream.writeShort(QuickShareProtocolConstants.MKDIR)
                stream.writeUTF(parent)
                stream.writeUTF(child)
                stream.flush()
                stream.readBoolean()
            } catch (e: Throwable) {
                false
            }
        }
    }

    override suspend fun sendFilesToRemote(
        localPaths: List<String>,
        remoteDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            val pool = bufferPool ?: return@withContext false
            if (dataConnections.isEmpty()) return@withContext false

            val remoteFiles = mutableListOf<RemoteFile>()
            var totalBytes = 0L

            for (path in localPaths) {
                if (storageManager.exists(path)) {
                    val isDir = isDirectory(path)
                    val size = if (isDir) 0L else storageManager.getFileSize(path)
                    val name = File(path).name.ifEmpty { path }
                    remoteFiles.add(RemoteFile(name, path, getFileLastModified(path), size, isDir))
                    totalBytes += size
                }
            }

            if (remoteFiles.isEmpty()) return@withContext false

            var task = TransferTask(
                fileName = if (localPaths.size == 1) File(localPaths[0]).name else "${File(localPaths[0]).name} 等 ${localPaths.size} 个文件",
                direction = TransferDirection.SEND,
                size = totalBytes,
                status = TransferStatus.RUNNING,
                startTimeMs = System.currentTimeMillis()
            )
            _activeTransfers.value = listOf(task)
            onProgress?.invoke(task)

            try {
                stream.writeShort(QuickShareProtocolConstants.REQUEST_RECEIVE)
                stream.flush()

                val firstPath = localPaths[0]
                val localBase = File(firstPath).parent ?: (if (File.separatorChar == '\\') "C:\\" else "/")
                val localFs = when {
                    localBase.contains(":\\") || localBase.contains(":/") -> QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                    localBase.startsWith("/") -> QuickShareProtocolConstants.FILE_SYSTEM_UNIX
                    else -> QuickShareDirectory.getCurrentFileSystem()
                }
                val detectedRemoteFs = when {
                    remoteDestDir.contains(":\\") || remoteDestDir.contains(":/") -> QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                    remoteDestDir.startsWith("/") -> QuickShareProtocolConstants.FILE_SYSTEM_UNIX
                    else -> remoteFsCode
                }
                val localDir = QuickShareDirectory(localBase, localFs)
                val remoteDir = QuickShareDirectory(remoteDestDir, detectedRemoteFs)

                val readFileCall = ReadFileCall(
                    buffers = pool.rawQueue,
                    files = remoteFiles,
                    localDir = localDir,
                    remoteDir = remoteDir,
                    operateThreadCount = dataConnections.size,
                    storageResolver = { p -> storageManager.openForRead(p) }
                )

                supervisorScope {
                    val readJob = launch {
                        try {
                            readFileCall.executeAsync()
                        } catch (_: Throwable) {}
                    }

                    val sendJobs = dataConnections.map { conn ->
                        launch {
                            try {
                                val sendCall = SendFileCall(
                                    readFileCall = readFileCall,
                                    connection = conn,
                                    onProgress = { _, _, _, _ ->
                                        val totalSent = dataConnections.sumOf { it.getTotalTraffic().uploadTraffic }
                                        val currentT = task.withBytesTransferred(minOf(totalSent, task.size))
                                        task = currentT
                                        _activeTransfers.value = listOf(currentT)
                                        onProgress?.invoke(currentT)
                                    }
                                )
                                sendCall.executeAsync()
                            } catch (_: Throwable) {}
                        }
                    }

                    trafficManager.startMonitoring(
                        connections = dataConnections,
                        taskTotalSize = totalBytes,
                        direction = TransferDirection.SEND,
                        transferredBytesProvider = { dataConnections.sumOf { it.getTotalTraffic().uploadTraffic } },
                        coroutineScope = this
                    )

                    try {
                        val remoteWriteOk = stream.readBoolean()
                        trafficManager.stopMonitoring()

                        if (!remoteWriteOk) {
                            val errMsg = stream.readUTF()
                            readFileCall.shutdownByWriteError()
                            task = task.withStatus(TransferStatus.FAILED, errMsg)
                            _activeTransfers.value = listOf(task)
                            onProgress?.invoke(task)
                            return@supervisorScope false
                        }

                        sendJobs.joinAll()
                        readJob.join()

                        stream.writeBoolean(true)
                        stream.flush()

                        task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                        _activeTransfers.value = listOf(task)
                        onProgress?.invoke(task)
                        true
                    } catch (t: Throwable) {
                        trafficManager.stopMonitoring()
                        readFileCall.shutdownByConnectionBreak()
                        sendJobs.forEach { it.cancel() }
                        readJob.cancel()
                        throw t
                    }
                }
            } catch (e: Throwable) {
                trafficManager.stopMonitoring()
                task = task.withStatus(TransferStatus.FAILED, e.message)
                _activeTransfers.value = listOf(task)
                onProgress?.invoke(task)
                false
            }
        }
    }

    override suspend fun pullFilesFromRemote(
        remotePaths: List<String>,
        remoteParentDir: String,
        localDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            val pool = bufferPool ?: return@withContext false
            if (dataConnections.isEmpty()) return@withContext false

            var task = TransferTask(
                fileName = if (remotePaths.size == 1) File(remotePaths[0]).name else "${File(remotePaths[0]).name} 等 ${remotePaths.size} 个文件",
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.RUNNING,
                startTimeMs = System.currentTimeMillis()
            )
            _activeTransfers.value = listOf(task)
            onProgress?.invoke(task)

            try {
                stream.writeShort(QuickShareProtocolConstants.REQUEST_SEND)
                stream.writeInt(remotePaths.size)
                for (p in remotePaths) stream.writeUTF(p)
                stream.writeUTF(remoteParentDir)
                val localFs = when {
                    localDestDir.contains(":\\") || localDestDir.contains(":/") -> QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                    localDestDir.startsWith("/") -> QuickShareProtocolConstants.FILE_SYSTEM_UNIX
                    else -> QuickShareDirectory.getCurrentFileSystem()
                }
                stream.writeInt(localFs)
                stream.writeUTF(localDestDir)
                stream.flush()

                val writeFileCall = WriteFileCall(pool, dataConnections.size, storageManager)
                supervisorScope {
                    val writeJob = launch {
                        try {
                            writeFileCall.executeAsync()
                        } catch (_: Throwable) {}
                    }

                    val recvJobs = dataConnections.mapIndexed { idx, conn ->
                        launch {
                            try {
                                val recvCall = ReceiveFileCall(
                                    channelIndex = idx,
                                    connection = conn,
                                    writeFileCall = writeFileCall,
                                    onProgress = { _, _, _, totalSize ->
                                        if (task.size < totalSize) task = task.copy(size = totalSize)
                                        val totalRecv = dataConnections.sumOf { it.getTotalTraffic().downloadTraffic }
                                        val currentT = task.withBytesTransferred(minOf(totalRecv, task.size))
                                        task = currentT
                                        _activeTransfers.value = listOf(currentT)
                                        onProgress?.invoke(currentT)
                                    }
                                )
                                recvCall.executeAsync()
                            } catch (_: Throwable) {}
                        }
                    }

                    trafficManager.startMonitoring(
                        connections = dataConnections,
                        taskTotalSize = task.size,
                        direction = TransferDirection.RECEIVE,
                        transferredBytesProvider = { dataConnections.sumOf { it.getTotalTraffic().downloadTraffic } },
                        coroutineScope = this
                    )

                    try {
                        writeJob.join()
                        recvJobs.joinAll()
                        trafficManager.stopMonitoring()

                        stream.writeBoolean(true) // write ok
                        stream.writeBoolean(true) // channels finished ok
                        stream.flush()

                        val senderAck = stream.readBoolean()
                        if (senderAck) {
                            task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                            _activeTransfers.value = listOf(task)
                            onProgress?.invoke(task)
                            true
                        } else {
                            val errMsg = stream.readUTF()
                            task = task.withStatus(TransferStatus.FAILED, errMsg)
                            _activeTransfers.value = listOf(task)
                            onProgress?.invoke(task)
                            false
                        }
                    } catch (t: Throwable) {
                        trafficManager.stopMonitoring()
                        writeFileCall.cancel()
                        writeJob.cancel()
                        recvJobs.forEach { it.cancel() }
                        throw t
                    }
                }
            } catch (e: Throwable) {
                trafficManager.stopMonitoring()
                task = task.withStatus(TransferStatus.FAILED, e.message)
                _activeTransfers.value = listOf(task)
                onProgress?.invoke(task)
                false
            }
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        _isRunning.value = false
        acceptJob?.cancel()
        acceptJob = null

        disconnectSession()

        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null

        _serverStatusText.value = "已停止"
    }

    private fun disconnectSession() {
        trafficManager.reset()
        try {
            controlStream?.writeShort(QuickShareProtocolConstants.SHUTDOWN)
            controlStream?.flush()
        } catch (_: Throwable) {}

        try { controlStream?.close() } catch (_: Throwable) {}
        controlStream = null

        try { controlSocket?.close() } catch (_: Throwable) {}
        controlSocket = null

        for (conn in dataConnections) {
            try { conn.close() } catch (_: Throwable) {}
        }
        dataConnections.clear()

        for (sock in dataSockets) {
            try { sock.close() } catch (_: Throwable) {}
        }
        dataSockets.clear()

        bufferPool = null

        _connectedClients.value = emptyList()
        _activeTransfers.value = emptyList()
        _serverStatusText.value = if (_isRunning.value) "等待连接 (端口 $currentPort)" else "已停止"
    }

    private fun isDirectory(path: String): Boolean {
        val f = File(path)
        return if (f.exists()) f.isDirectory else false
    }

    private fun getFileLastModified(path: String): Long {
        val f = File(path)
        return if (f.exists()) f.lastModified() else System.currentTimeMillis()
    }

    override fun close() {
        _isRunning.value = false
        disconnectSession()
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }
}

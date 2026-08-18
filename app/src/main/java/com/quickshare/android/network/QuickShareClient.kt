package com.quickshare.android.network

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.protocol.AdvertisedNic
import com.quickshare.android.protocol.HandshakeResult
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

interface IQuickShareClient : Closeable, AutoCloseable {
    val isConnected: StateFlow<Boolean>
    val connectedServerIp: StateFlow<String>
    val remoteFileSystem: StateFlow<Int>
    val remoteHomeDir: StateFlow<String>
    val currentTask: StateFlow<TransferTask?>
    val channelTraffic: StateFlow<List<TrafficInfo>>
    val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot>

    suspend fun connect(
        targetIp: String,
        targetPort: Int = QuickShareProtocolConstants.DEFAULT_PORT,
        selectedNics: List<NetworkInterfaceInfo> = emptyList(),
        timeoutMs: Int = 5000,
        interfaceEnumerator: IInterfaceEnumerator? = null,
        localHomeDir: String = "/sdcard"
    ): HandshakeResult

    suspend fun listRemoteFiles(remotePath: String): List<RemoteFile>?
    suspend fun makeRemoteDir(parentPath: String, childName: String): Boolean
    suspend fun deleteRemoteFile(remotePath: String): Boolean

    suspend fun sendFiles(
        localPaths: List<String>,
        remoteDestDir: String,
        onProgress: ((TransferTask) -> Unit)? = null
    ): Boolean

    suspend fun receiveFiles(
        remotePaths: List<String>,
        remoteParentDir: String,
        localDestDir: String,
        onProgress: ((TransferTask) -> Unit)? = null
    ): Boolean

    suspend fun disconnect()
}

/**
 * Full client-mode engine implementing QuickShareProtocol v300.
 * Orchestrates multi-channel physical NIC data streaming, remote file operations,
 * and passive responder coordination for server-initiated transfers.
 */
class QuickShareClient(
    val storageManager: IStorageManager = DirectStorageEngine(),
    val socketFactory: IMultiPathSocketFactory = MultiPathSocketFactory(),
    val trafficManager: TrafficManager = TrafficManager()
) : IQuickShareClient {

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val controlMutex = Mutex()

    private var controlSocket: Socket? = null
    private var controlStream: IQuickShareStream? = null
    private val dataConnections = CopyOnWriteArrayList<TransferConnection>()
    private val dataSockets = CopyOnWriteArrayList<Socket>()
    private var bufferPool: BufferPool? = null

    private var passiveListenerJob: Job? = null
    @Volatile
    private var activeOperationInProgress: Boolean = false

    // State flows
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedServerIp = MutableStateFlow("")
    override val connectedServerIp: StateFlow<String> = _connectedServerIp.asStateFlow()

    private val _remoteFileSystem = MutableStateFlow(QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
    override val remoteFileSystem: StateFlow<Int> = _remoteFileSystem.asStateFlow()

    private val _remoteHomeDir = MutableStateFlow("")
    override val remoteHomeDir: StateFlow<String> = _remoteHomeDir.asStateFlow()

    private val _currentTask = MutableStateFlow<TransferTask?>(null)
    override val currentTask: StateFlow<TransferTask?> = _currentTask.asStateFlow()

    private val _channelTraffic = MutableStateFlow<List<TrafficInfo>>(emptyList())
    override val channelTraffic: StateFlow<List<TrafficInfo>> = _channelTraffic.asStateFlow()

    override val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot> = trafficManager.trafficState

    override suspend fun connect(
        targetIp: String,
        targetPort: Int,
        selectedNics: List<NetworkInterfaceInfo>,
        timeoutMs: Int,
        interfaceEnumerator: IInterfaceEnumerator?,
        localHomeDir: String
    ): HandshakeResult = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            try {
                disconnectInternal()

                val ctrlSocket = socketFactory.createBoundSocket(null, null)
                ctrlSocket.connect(InetSocketAddress(InetAddress.getByName(targetIp), targetPort), timeoutMs)
                val stream = QuickShareStream(ctrlSocket)

                controlSocket = ctrlSocket
                controlStream = stream

                // Step 1 & 2: Header "HFXC" & Version Code (300)
                val headerBytes = QuickShareProtocolConstants.CLIENT_HEADER.toByteArray(StandardCharsets.UTF_8)
                stream.write(headerBytes, 0, headerBytes.size)
                stream.writeInt(QuickShareProtocolConstants.VERSION_CODE)
                stream.flush()

                // Step 3: Version Match Check
                val versionMatched = stream.readBoolean()
                if (!versionMatched) {
                    val serverVer = stream.readInt()
                    val msg = "Version mismatch: server supports version $serverVer"
                    disconnectInternal()
                    return@withContext HandshakeResult.Failure(msg)
                }

                // Step 4: Read Advertised Server NICs
                val serverNicCount = stream.readInt()
                val advertisedNics = mutableListOf<AdvertisedNic>()
                for (i in 0 until serverNicCount) {
                    val nicName = stream.readUTF()
                    val ipLen = stream.readByte().toInt() and 0xFF
                    val ipBytes = ByteArray(ipLen)
                    stream.readFully(ipBytes, 0, ipLen)
                    val bindFlag = stream.readByte()
                    advertisedNics.add(AdvertisedNic(nicName, InetAddress.getByAddress(ipBytes), bindFlag))
                }

                // Step 5: Connect Pure LAN Data Channel
                dataConnections.clear()
                dataSockets.clear()

                for (i in 0 until serverNicCount) {
                    val clientNicName = "lan_$i"
                    stream.writeBoolean(true) // clientSucceed
                    stream.writeUTF(clientNicName)
                    stream.flush()

                    val targetServerNic = advertisedNics[i]
                    val dataSock = socketFactory.createConnectedSocket(
                        localNic = null,
                        targetAddress = targetServerNic.ipAddress,
                        targetPort = targetPort,
                        timeoutMs = timeoutMs,
                        interfaceEnumerator = null
                    )
                    dataSockets.add(dataSock)
                    val dataStream = QuickShareStream(dataSock)
                    val connection = TransferConnection(clientNicName, dataStream)
                    dataConnections.add(connection)

                    val serverAccepted = stream.readBoolean()
                    if (!serverAccepted) {
                        disconnectInternal()
                        return@withContext HandshakeResult.Failure("Server rejected data channel connection for $clientNicName")
                    }
                }

                // Step 6: Buffer Negotiation
                val serverBufCount = stream.readInt()
                val localPoolSize = if (serverBufCount > 0) serverBufCount else QuickShareProtocolConstants.DEFAULT_BUFFER_COUNT
                bufferPool = BufferPool(localPoolSize)

                stream.writeBoolean(true) // client buffer ok
                stream.flush()

                val serverBufferOk = stream.readBoolean()
                if (!serverBufferOk) {
                    disconnectInternal()
                    return@withContext HandshakeResult.Failure("Server failed buffer allocation")
                }

                // Step 7: Client File System Info
                val localFs = QuickShareDirectory.getCurrentFileSystem()
                stream.writeInt(localFs)
                stream.writeUTF(localHomeDir)
                stream.flush()

                _connectedServerIp.value = targetIp
                _remoteFileSystem.value = QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                _remoteHomeDir.value = "C:\\"
                _isConnected.value = true

                // Start passive listener for server-initiated commands
                try {
                    ctrlSocket.soTimeout = 1000
                } catch (_: Throwable) {}
                startPassiveControlListener()

                return@withContext HandshakeResult.Success(
                    remoteFileSystem = _remoteFileSystem.value,
                    remoteHomeDir = _remoteHomeDir.value,
                    bufferCount = localPoolSize,
                    remoteNics = advertisedNics
                )
            } catch (e: Throwable) {
                disconnectInternal()
                return@withContext HandshakeResult.Failure(e.message ?: "Connection failed", e)
            }
        }
    }

    private fun startPassiveControlListener() {
        passiveListenerJob?.cancel()
        passiveListenerJob = clientScope.launch {
            while (isActive && _isConnected.value && controlSocket != null && controlSocket?.isClosed == false) {
                val opCode: Short? = controlMutex.withLock {
                    val stream = controlStream ?: return@launch
                    try {
                        controlSocket?.soTimeout = 200
                        stream.readShort()
                    } catch (_: SocketTimeoutException) {
                        null
                    } catch (_: Throwable) {
                        disconnectInternal()
                        return@launch
                    }
                }

                if (opCode != null) {
                    controlMutex.withLock {
                        val stream = controlStream ?: return@launch
                        try {
                            controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS
                            when (opCode) {
                                QuickShareProtocolConstants.SHUTDOWN -> {
                                    disconnectInternal()
                                    return@launch
                                }
                                QuickShareProtocolConstants.LIST_FILES -> {
                                    val path = stream.readUTF()
                                    handlePassiveListFiles(path, stream)
                                }
                                QuickShareProtocolConstants.DELETE_FILE -> {
                                    val path = stream.readUTF()
                                    handlePassiveDeleteFile(path, stream)
                                }
                                QuickShareProtocolConstants.MKDIR -> {
                                    val parent = stream.readUTF()
                                    val child = stream.readUTF()
                                    handlePassiveMkdir(parent, child, stream)
                                }
                                QuickShareProtocolConstants.REQUEST_RECEIVE -> {
                                    handlePassivePushReceive(stream)
                                }
                                QuickShareProtocolConstants.REQUEST_SEND -> {
                                    handlePassivePullSend(stream)
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
        }
    }

    private fun handlePassiveListFiles(path: String, stream: IQuickShareStream) {
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
        } catch (_: Throwable) {
            try {
                stream.writeInt(-1)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private fun handlePassiveDeleteFile(path: String, stream: IQuickShareStream) {
        try {
            val success = storageManager.delete(path)
            stream.writeBoolean(success)
            stream.flush()
        } catch (_: Throwable) {
            try {
                stream.writeBoolean(false)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private fun handlePassiveMkdir(parent: String, child: String, stream: IQuickShareStream) {
        try {
            val success = storageManager.mkdir(parent, child)
            stream.writeBoolean(success)
            stream.flush()
        } catch (_: Throwable) {
            try {
                stream.writeBoolean(false)
                stream.flush()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun handlePassivePushReceive(stream: IQuickShareStream) = withContext(Dispatchers.IO) {
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
        _currentTask.value = task

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
                            _currentTask.value = currentT
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

            // Report write success to server
            stream.writeBoolean(true)
            stream.flush()

            val serverAck = stream.readBoolean()
            if (serverAck) {
                task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                _currentTask.value = task
            } else {
                task = task.withStatus(TransferStatus.FAILED, "Server reported completion failure")
                _currentTask.value = task
            }
        } catch (e: Throwable) {
            trafficManager.stopMonitoring()
            try {
                stream.writeBoolean(false)
                stream.writeUTF(e.message ?: "Write Error")
                stream.flush()
            } catch (_: Throwable) {}
            task = task.withStatus(TransferStatus.FAILED, e.message)
            _currentTask.value = task
        }
    }

    private suspend fun handlePassivePullSend(stream: IQuickShareStream) = withContext(Dispatchers.IO) {
        val pool = bufferPool ?: return@withContext
        if (dataConnections.isEmpty()) return@withContext

        try {
            val fileCount = stream.readInt()
            val remotePaths = ArrayList<String>(fileCount)
            for (i in 0 until fileCount) {
                remotePaths.add(stream.readUTF())
            }
            val remoteParentDir = stream.readUTF()
            val serverFs = stream.readInt()
            val serverDestDir = stream.readUTF()

            val localFiles = mutableListOf<RemoteFile>()
            var totalBytes = 0L

            for (path in remotePaths) {
                if (storageManager.exists(path)) {
                    val isDir = isDirectory(path)
                    val size = if (isDir) 0L else storageManager.getFileSize(path)
                    val name = File(path).name.ifEmpty { path }
                    localFiles.add(RemoteFile(name, path, getFileLastModified(path), size, isDir))
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
            _currentTask.value = task

            val localFs = when {
                remoteParentDir.contains(":\\") || remoteParentDir.contains(":/") -> QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
                remoteParentDir.startsWith("/") -> QuickShareProtocolConstants.FILE_SYSTEM_UNIX
                else -> QuickShareDirectory.getCurrentFileSystem()
            }
            val localDir = QuickShareDirectory(remoteParentDir, localFs)
            val remoteDir = QuickShareDirectory(serverDestDir, serverFs)

            val readFileCall = ReadFileCall(
                buffers = pool.rawQueue,
                files = localFiles,
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
                            _currentTask.value = currentT
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

            val serverWriteOk = stream.readBoolean()
            trafficManager.stopMonitoring()

            if (!serverWriteOk) {
                val errMsg = stream.readUTF()
                readFileCall.shutdownByWriteError()
                task = task.withStatus(TransferStatus.FAILED, errMsg)
                _currentTask.value = task
                return@withContext
            }

            val serverChFinished = stream.readBoolean()
            sendJobs.joinAll()
            readJob.join()

            // Ack to server
            stream.writeBoolean(serverWriteOk && serverChFinished)
            stream.flush()

            task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
            _currentTask.value = task
        } catch (e: Throwable) {
            trafficManager.stopMonitoring()
            val task = TransferTask(
                fileName = "拉取传输失败",
                direction = TransferDirection.SEND,
                status = TransferStatus.FAILED,
                errorMessage = e.message
            )
            _currentTask.value = task
        }
    }

    override suspend fun listRemoteFiles(remotePath: String): List<RemoteFile>? = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext null
            activeOperationInProgress = true
            try {
                controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS
                stream.writeShort(QuickShareProtocolConstants.LIST_FILES)
                stream.writeUTF(remotePath)
                stream.flush()

                val count = stream.readInt()
                if (count == -1) return@withContext null

                val results = ArrayList<RemoteFile>(count)
                for (i in 0 until count) {
                    val name = stream.readUTF()
                    val path = stream.readUTF()
                    val lastModified = stream.readLong()
                    val size = stream.readLong()
                    val isDir = stream.readBoolean()
                    results.add(RemoteFile(name, path, lastModified, size, isDir))
                }
                results
            } catch (e: Throwable) {
                null
            } finally {
                controlSocket?.soTimeout = 1000
                activeOperationInProgress = false
            }
        }
    }

    override suspend fun makeRemoteDir(parentPath: String, childName: String): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            activeOperationInProgress = true
            try {
                controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS
                stream.writeShort(QuickShareProtocolConstants.MKDIR)
                stream.writeUTF(parentPath)
                stream.writeUTF(childName)
                stream.flush()
                stream.readBoolean()
            } catch (e: Throwable) {
                false
            } finally {
                controlSocket?.soTimeout = 1000
                activeOperationInProgress = false
            }
        }
    }

    override suspend fun deleteRemoteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            val stream = controlStream ?: return@withContext false
            activeOperationInProgress = true
            try {
                controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS
                stream.writeShort(QuickShareProtocolConstants.DELETE_FILE)
                stream.writeUTF(remotePath)
                stream.flush()
                stream.readBoolean()
            } catch (e: Throwable) {
                false
            } finally {
                controlSocket?.soTimeout = 1000
                activeOperationInProgress = false
            }
        }
    }

    override suspend fun sendFiles(
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
                filePath = localPaths.firstOrNull() ?: "",
                direction = TransferDirection.SEND,
                size = totalBytes,
                status = TransferStatus.RUNNING,
                startTimeMs = System.currentTimeMillis()
            )
            _currentTask.value = task
            onProgress?.invoke(task)

            activeOperationInProgress = true
            try {
                controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS

                // Signal REQUEST_RECEIVE
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
                    else -> _remoteFileSystem.value
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
                                        _currentTask.value = currentT
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
                        // Wait for remote receiver write feedback
                        val remoteWriteOk = stream.readBoolean()
                        trafficManager.stopMonitoring()

                        if (!remoteWriteOk) {
                            val errMsg = stream.readUTF()
                            readFileCall.shutdownByWriteError()
                            task = task.withStatus(TransferStatus.FAILED, errMsg)
                            _currentTask.value = task
                            onProgress?.invoke(task)
                            return@supervisorScope false
                        }

                        sendJobs.joinAll()
                        readJob.join()

                        // Final sender complete ack
                        stream.writeBoolean(true)
                        stream.flush()

                        task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                        _currentTask.value = task
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
                _currentTask.value = task
                onProgress?.invoke(task)
                false
            } finally {
                controlSocket?.soTimeout = 1000
                activeOperationInProgress = false
            }
        }
    }

    override suspend fun receiveFiles(
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
                filePath = localDestDir,
                direction = TransferDirection.RECEIVE,
                status = TransferStatus.RUNNING,
                startTimeMs = System.currentTimeMillis()
            )
            _currentTask.value = task
            onProgress?.invoke(task)

            activeOperationInProgress = true
            try {
                controlSocket?.soTimeout = MultiPathSocketFactory.DEFAULT_TIMEOUT_MS

                // Signal REQUEST_SEND
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
                                        _currentTask.value = currentT
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

                        // Feedback to sender
                        stream.writeBoolean(true) // write ok
                        stream.writeBoolean(true) // channels finished ok
                        stream.flush()

                        val senderAck = stream.readBoolean()
                        if (senderAck) {
                            task = task.withBytesTransferred(task.size).withStatus(TransferStatus.COMPLETED)
                            _currentTask.value = task
                            onProgress?.invoke(task)
                            true
                        } else {
                            val errMsg = stream.readUTF()
                            task = task.withStatus(TransferStatus.FAILED, errMsg)
                            _currentTask.value = task
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
                _currentTask.value = task
                onProgress?.invoke(task)
                false
            } finally {
                controlSocket?.soTimeout = 1000
                activeOperationInProgress = false
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        passiveListenerJob?.cancel()
        passiveListenerJob = null
        activeOperationInProgress = false

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

        _isConnected.value = false
        _connectedServerIp.value = ""
        _currentTask.value = null
        _channelTraffic.value = emptyList()
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
        disconnectInternal()
    }
}

# Project: QuickShare-Android (QuickShareProtocol v300)

## Architecture
QuickShare-Android is an ultra-high performance, multi-transport hybrid file transfer application for Android, completely interoperable with the PC QuickShareServer running QuickShareProtocol v300.

```
+-----------------------------------------------------------------------------------+
|                        QuickShare-Android Architecture                        |
+-----------------------------------------------------------------------------------+
|                                PRESENTATION LAYER                                 |
|  - Jetpack Compose (Material 3) UI                                                |
|  - ConnectionScreen (Custom IP/Port, Saved History, Client Mode Setup)             |
|  - ServerModeScreen (Custom Port, Multi-IP Broadcast, QR / Connection Status)    |
|  - FileBrowserScreen (Local / Remote dual explorer, Multi-select, Breadcrumbs)    |
|  - TransferDashboardScreen (Per-NIC speed badges, Total speed, ETA, Progress)     |
|  - ViewModels: MainViewModel, ConnectionViewModel, FileBrowserViewModel, TransferVM|
+-----------------------------------------------------------------------------------+
|                                 APPLICATION CORE                                  |
|  - QuickShareApplication & TransferForegroundService (DataSync foreground service)        |
|  - AppConfig (Preferences & persistence)                                          |
|  - TrafficManager (Real-time 1s window calculation, per-channel & cumulative stats)|
+-----------------------------------------------------------------------------------+
|                              DUAL-MODE ENGINE LAYER                               |
|  - QuickShareClient: Control channel signaling, RPC client, Push/Pull transfer driver    |
|  - QuickShareServer: TCP ServerSocket listener, Multi-NIC advertiser, Remote RPC server   |
|  - Remote Management: LIST_FILES(1), DELETE_FILE(2), MKDIR(3), SHUTDOWN(0)        |
|  - Transfer Requests: REQUEST_RECEIVE(10) [Push], REQUEST_SEND(11) [Pull]         |
+-----------------------------------------------------------------------------------+
|                           SLICING & PIPELINE ENGINE                               |
|  - 1MB Chunk Slicing: ReadFileCall (Queue distributor)                            |
|  - Multi-Channel Reordering: WriteFileCall (Priority Deque sorter by index/seek)  |
|  - Data Streamers: SendFileCall & ReceiveFileCall (Per-NIC TCP Socket frames)     |
|  - Zero-GC BufferPool (8x1MB ByteBuffers recycled in-flight)                      |
|  - ChecksumUtil (MD5 / SHA256 integrity verification)                              |
+-----------------------------------------------------------------------------------+
|                           STORAGE & NETWORK ABSTRACTION                           |
|  - StorageManager: High-throughput Direct IO (All-Files API 30+) + SAF Fallback   |
|  - QuickShareDirectory: Cross-platform Unix/Windows path normalization & sanitization    |
|  - NetworkManager & MultiPathSocketFactory (Physical binding via bindSocket)      |
|  - InterfaceEnumerator: Wi-Fi (wlan0), USB Tethering (rndis0), Ethernet (eth0)   |
+-----------------------------------------------------------------------------------+
|                             PROTOCOL & WIRE FORMAT                                |
|  - QuickShareProtocolConstants: Magic "HFXC", Version 300, 1MB Block size, OpCodes       |
|  - QuickShareStream: Big-Endian binary reader/writer (DataInputStream/DataOutputStream)  |
|  - Models: FileBlock, RemoteFile, TransferTask, TrafficInfo, NetworkInterfaceInfo |
+-----------------------------------------------------------------------------------+
```

---

## Feature Inventory
Every single requirement and discovered protocol feature is indexed and mapped to a milestone below.

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Gradle Build & Workspace Setup | Gradle Kotlin DSL setup, AGP 8.5+, SDK 35, dependencies (Compose, Coroutines, Okio, Gson, DocumentFile, etc.) | M1 | Request §5 |
| 2 | QuickShareStream Big-Endian Codec | Big-Endian binary serializer/deserializer matching Java `DataInputStream`/`DataOutputStream` standards | M1 | Protocol Spec §2 |
| 3 | Protocol Constants & Magic | Header `"HFXC"`, `VERSION_CODE = 300`, `BLOCK_SIZE = 1048576`, command IDs, transfer flags | M1 | Protocol Spec §3 |
| 4 | Core Data Models | `FileBlock` (1MB slice with CompareTo sorter), `RemoteFile`, `TransferTask`, `TrafficInfo`, `NetworkInterfaceInfo` | M1 | Protocol Spec §6 |
| 5 | Cross-Platform Path Normalization | `QuickShareDirectory`: Unix ↔ Windows path conversion, sanitization of illegal characters `[\\:*?"<>|]` | M1 | Protocol Spec §6.4 |
| 6 | Handshake Wire Protocol | Exact 12-step HFXC handshake, version check, NIC count/list exchange, buffer negotiation, FS info exchange | M1 | Protocol Spec §4.1 |
| 7 | 1MB Slicing Reader Pipeline | `ReadFileCall`: splits single/multiple files into 1MB `FileBlock` items, manages memory bounds | M2 | Protocol Spec §6.2 |
| 8 | Out-of-Order Multi-Channel Assembler | `WriteFileCall`: multi-queue min-head comparator, random-access seeking (`Position = Index * 1MB`), timestamp update | M2 | Protocol Spec §6.2 |
| 9 | Per-Channel Data Streaming | `SendFileCall` & `ReceiveFileCall`: binary data framing (`FILE`, `FOLDER`, `EOF`, error markers) | M2 | Protocol Spec §4.3 |
| 10 | Zero-GC Buffer Pool | Pre-allocated 8x1MB ByteBuffers recycled between network reader and disk writer | M2 | Protocol Spec §6.3 |
| 11 | Data Integrity & Checksum | MD5 / SHA256 stream & block hashing utilities for transfer validation | M2 | Request §3 |
| 12 | Storage Engine (Direct + SAF) | `StorageManager`: Direct Unix I/O for `MANAGE_EXTERNAL_STORAGE` + Storage Access Framework `DocumentFile`/`ParcelFileDescriptor` | M2 | Request §3 |
| 13 | Multi-NIC Discovery & Enumeration | `InterfaceEnumerator`: scans Wi-Fi (`wlan0`), USB Tethering (`rndis0`), Ethernet (`eth0`), cell data | M3 | Request §1 |
| 14 | Physical Socket NIC Binding | `MultiPathSocketFactory`: `ConnectivityManager.Network.bindSocket()` and `Socket.bind()` to force multi-NIC routing | M3 | Protocol Spec §7 |
| 15 | Client Mode Engine (`QuickShareClient`) | Connects to custom IP/Port, handles handshake, control loop, command RPCs, and transfer orchestration | M3 | Request §1, §2 |
| 16 | Server Mode Engine (`QuickShareServer`) | Listens on custom port, advertises local NIC IPs, accepts client data channels, executes RPCs | M3 | Request §1, §2 |
| 17 | Remote Management RPCs | `LIST_FILES` (0x0001), `DELETE_FILE` (0x0002), `MKDIR` (0x0003), `SHUTDOWN` (0x0000) execution & responses | M3 | Protocol Spec §4.2 |
| 18 | Dual Mode Push/Pull Transfers | `REQUEST_RECEIVE` (0x000A push) and `REQUEST_SEND` (0x000B pull) state machines & coordination | M3 | Protocol Spec §5 |
| 19 | Real-Time Traffic & Speed Metering | `TransferConnection` & `TrafficManager`: 1-second window throughput calculation, speed formatting | M3 | Protocol Spec §8 |
| 20 | Jetpack Compose M3 Theme & Navigation | Material 3 dark/light theme, typography, color palette, `MainNavHost` screen routing | M4 | Request §4 |
| 21 | Connection Screen (Client Mode) | Input target IP/Port, history list, port presets, active NIC selector, connection status indicators | M4 | Request §4 |
| 22 | Server Mode Screen | Configure listen port, toggle server on/off, display local IP list, active clients and connection state | M4 | Request §4 |
| 23 | Local & Remote File Browser | Dual-tab browser, breadcrumb path navigation, multi-select, sort by name/size/date, remote file operations | M4 | Request §4 |
| 24 | Transfer Dashboard & Per-NIC Speeds | Overall progress, per-NIC speed badges & gauges, instantaneous rate, cumulative bytes, ETA display | M4 | Request §4 |
| 25 | Foreground Service & Notifications | `TransferForegroundService` with notification progress bar and wake lock during active transfers | M4 | Survey §3.1 |
| 26 | Unit & Protocol Compatibility Tests | Comprehensive unit test suite (QuickShareStreamTest, HandshakeTest, FileBlockSortTest, PathTest, PipelineTest) | M5 | Request §5 |
| 27 | Gradle Build & APK Verification | `./gradlew assembleDebug` verification, installable APK output in `app/build/outputs/apk/debug/` | M5 | Request §5 |
| 28 | E2E Integration & Protocol Validation | Full opaque-box E2E test suite validation (Tiers 1-4) against QuickShareProtocol v300 specification | M5 | Request §5 |
| 29 | Adversarial Coverage Hardening | Tier 5 white-box gap analysis, edge case stress testing, packet corruption recovery | M5 | Project Pattern |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Protocol & Network Foundation | Gradle project structure, Manifest, `QuickShareProtocolConstants`, `QuickShareStream`, `FileBlock`, `RemoteFile`, `QuickShareDirectory`, Handshake packets | none | DONE |
| M2 | Slicing Pipeline & Storage Engine | `ReadFileCall`, `WriteFileCall`, `SendFileCall`, `ReceiveFileCall`, BufferPool, `ChecksumUtil`, `StorageManager` (Direct + SAF) | M1 | PLANNED |
| M3 | Dual-Mode Engine & Multi-NIC Service | `InterfaceEnumerator`, `MultiPathSocketFactory`, `QuickShareClient`, `QuickShareServer`, RPC commands, Push/Pull flows, `TrafficManager` | M2 | PLANNED |
| M4 | Jetpack Compose UI & Real-Time Monitoring | Material 3 Theme, `MainNavHost`, `ConnectionScreen`, `ServerModeScreen`, `FileBrowserScreen`, `TransferDashboardScreen`, `TransferForegroundService` | M3 | PLANNED |
| M5 | Build & E2E Test Suite Pass | Complete unit tests, E2E Test Suite (Tiers 1-4), `./gradlew assembleDebug` APK build, Tier 5 adversarial hardening | M4 | PLANNED |

---

## Interface Contracts

### 1. `QuickShareStream` (Binary Serialization Contract)
```kotlin
interface IQuickShareStream {
    fun readShort(): Short
    fun writeShort(v: Short)
    fun readInt(): Int
    fun writeInt(v: Int)
    fun readLong(): Long
    fun writeLong(v: Long)
    fun readBoolean(): Boolean
    fun writeBoolean(v: Boolean)
    fun readByte(): Byte
    fun writeByte(v: Byte)
    fun readUTF(): String
    fun writeUTF(str: String)
    fun readFully(b: ByteArray, off: Int, len: Int)
    fun write(b: ByteArray, off: Int, len: Int)
    fun flush()
}
```

### 2. `StorageManager` (Storage Engine Contract)
```kotlin
interface IStorageManager {
    fun openForRead(path: String): InputStream
    fun openRandomAccess(path: String, mode: String): RandomAccessHandle
    fun listFiles(dirPath: String): List<RemoteFile>
    fun mkdir(parentPath: String, childName: String): Boolean
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
    fun getFileSize(path: String): Long
    fun setLastModified(path: String, timeMs: Long): Boolean
}

interface RandomAccessHandle : AutoCloseable {
    fun seek(position: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun setLength(length: Long)
}
```

### 3. `QuickShareClient` & `QuickShareServer` (Dual-Mode Engine Contract)
```kotlin
interface IQuickShareClient {
    suspend fun connect(targetIp: String, targetPort: Int, selectedNics: List<NetworkInterfaceInfo>): Boolean
    suspend fun listRemoteFiles(remotePath: String): List<RemoteFile>?
    suspend fun makeRemoteDir(parentPath: String, childName: String): Boolean
    suspend fun deleteRemoteFile(remotePath: String): Boolean
    suspend fun sendFiles(localPaths: List<String>, remoteDestDir: String, onProgress: (TransferProgress) -> Unit): Boolean
    suspend fun receiveFiles(remotePaths: List<String>, remoteParentDir: String, localDestDir: String, onProgress: (TransferProgress) -> Unit): Boolean
    suspend fun disconnect()
}

interface IQuickShareServer {
    suspend fun start(listenPort: Int, activeNics: List<NetworkInterfaceInfo>): Boolean
    suspend fun stop()
    val isRunning: StateFlow<Boolean>
    val connectedClients: StateFlow<List<ClientSessionInfo>>
}
```

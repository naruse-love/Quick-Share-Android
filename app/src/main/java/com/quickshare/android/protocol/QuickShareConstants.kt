package com.quickshare.android.protocol

/**
 * Protocol constants, magic signatures, opcodes, and framing markers
 * for QuickShareProtocol v300.
 *
 * Wire Byte Order: Big-Endian (Network Byte Order).
 */
object QuickShareProtocolConstants {
    // Magic header & version
    const val CLIENT_HEADER: String = "HFXC"
    const val VERSION_CODE: Int = 300
    const val DEFAULT_PORT: Int = 5740

    // Transfer sizing defaults
    const val BLOCK_SIZE: Int = 1024 * 1024 // 1,048,576 bytes (1MB)
    const val DEFAULT_BUFFER_COUNT: Int = 8 // 8 x 1MB = 8MB

    // Control Channel Command Identifiers (Short: 2 bytes)
    const val SHUTDOWN: Short = 0
    const val LIST_FILES: Short = 1
    const val DELETE_FILE: Short = 2
    const val MKDIR: Short = 3
    const val REQUEST_RECEIVE: Short = 10
    const val REQUEST_SEND: Short = 11

    // Data Channel Transfer Frame Identifiers (Short: 2 bytes)
    const val END_POINT: Short = -1
    const val FILE: Short = 0
    const val FOLDER: Short = 1
    const val FILE_SLICE: Short = 2
    const val EOF: Short = 3
    const val END_OF_INTERRUPTED: Short = 4
    const val END_OF_READ_ERROR: Short = 5
    const val END_OF_WRITE_ERROR: Short = 6

    // Operating System Filesystem Types (Int: 4 bytes)
    const val FILE_SYSTEM_UNIX: Int = 0
    const val FILE_SYSTEM_WINDOWS: Int = 1
}

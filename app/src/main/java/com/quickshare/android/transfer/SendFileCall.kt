package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.protocol.QuickShareProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated per-channel transmission worker.
 *
 * Pulls [FileBlock] chunks from [ReadFileCall] and serializes binary Big-Endian frames
 * over the channel's network connection.
 */
class SendFileCall(
    private val readFileCall: ReadFileCall,
    private val connection: TransferConnection,
    private val onProgress: ((iName: String, path: String, sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    private val onComplete: ((iName: String, totalUploaded: Long, elapsedMs: Long) -> Unit)? = null,
    private val onError: ((iName: String, errorCode: Int, message: String?) -> Unit)? = null
) {
    init {
        connection.resetTotalTrafficInfo()
    }

    suspend fun executeAsync() = withContext(Dispatchers.IO) {
        var fileBlock: FileBlock? = null
        val startTime = System.currentTimeMillis()
        val channel = connection.channel
        val iName = connection.iName

        try {
            while (true) {
                fileBlock = readFileCall.takeBlock()

                // Sentinel Marker Handling
                if (fileBlock.fileIndex == -1) {
                    when (fileBlock.path) {
                        "END_POINT" -> {
                            channel.writeShort(QuickShareProtocolConstants.EOF)
                            channel.flush()
                            val elapsed = System.currentTimeMillis() - startTime
                            onComplete?.invoke(iName, connection.getTotalTraffic().uploadTraffic, elapsed)
                        }
                        "INTERRUPT" -> {
                            channel.writeShort(QuickShareProtocolConstants.END_OF_INTERRUPTED)
                            channel.flush()
                            onError?.invoke(iName, 4, null)
                        }
                        "READ_ERROR" -> {
                            channel.writeShort(QuickShareProtocolConstants.END_OF_READ_ERROR)
                            channel.flush()
                            onError?.invoke(iName, 5, null)
                        }
                        "WRITE_ERROR" -> {
                            channel.writeShort(QuickShareProtocolConstants.END_OF_WRITE_ERROR)
                            channel.flush()
                            onError?.invoke(iName, 6, null)
                        }
                    }
                    break
                }

                // Standard Data Framing
                val frameHeader = if (fileBlock.isFile) QuickShareProtocolConstants.FILE else QuickShareProtocolConstants.FOLDER
                channel.writeShort(frameHeader)
                channel.writeInt(fileBlock.fileIndex)
                channel.writeUTF(fileBlock.path)
                channel.writeLong(fileBlock.lastModified)

                if (!fileBlock.isFile) {
                    channel.flush()
                    continue
                }

                channel.writeLong(fileBlock.totalSize)
                channel.writeInt(fileBlock.index)
                channel.writeInt(fileBlock.dataLength)

                onProgress?.invoke(
                    iName,
                    fileBlock.path,
                    fileBlock.getStartPosition() + fileBlock.dataLength,
                    fileBlock.totalSize
                )

                if (fileBlock.data != null) {
                    if (fileBlock.dataLength > 0) {
                        channel.write(fileBlock.data, 0, fileBlock.dataLength)
                        channel.flush()
                        connection.addUploadedBytes(fileBlock.dataLength.toLong())
                    }
                    readFileCall.recycleBuffer(fileBlock.data)
                }
            }
        } catch (ex: Throwable) {
            if (fileBlock?.data != null) {
                readFileCall.recycleBuffer(fileBlock.data)
            }
            readFileCall.shutdownByConnectionBreak()
            onError?.invoke(iName, -1, ex.message)
            throw ex
        }
    }
}

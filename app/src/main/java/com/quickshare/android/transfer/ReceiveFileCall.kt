package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.protocol.QuickShareProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ProtocolException

/**
 * Dedicated per-channel reception worker.
 *
 * Reads framed binary data from the channel's connection, acquires zero-GC buffers
 * from [WriteFileCall], and enqueues [FileBlock] chunks into the corresponding channel deque.
 */
class ReceiveFileCall(
    private val channelIndex: Int,
    private val connection: TransferConnection,
    private val writeFileCall: WriteFileCall,
    private val onProgress: ((iName: String, path: String, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    private val onComplete: ((iName: String, totalDownloaded: Long, elapsedMs: Long) -> Unit)? = null,
    private val onError: ((iName: String, errorCode: Int, message: String?) -> Unit)? = null
) {
    init {
        connection.resetTotalTrafficInfo()
    }

    suspend fun executeAsync() = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val channel = connection.channel
        val iName = connection.iName

        try {
            while (true) {
                val header = channel.readShort()
                when (header) {
                    QuickShareProtocolConstants.FOLDER -> {
                        val fileIndex = channel.readInt()
                        val path = channel.readUTF()
                        val lastModified = channel.readLong()

                        val folderBlock = FileBlock(
                            isFile = false,
                            fileIndex = fileIndex,
                            path = path,
                            lastModified = lastModified,
                            totalSize = 0L,
                            index = 0,
                            data = null,
                            dataLength = 0
                        )
                        writeFileCall.putBlock(folderBlock, channelIndex)
                    }

                    QuickShareProtocolConstants.FILE -> {
                        val fileIndex = channel.readInt()
                        val path = channel.readUTF()
                        val lastModified = channel.readLong()
                        val totalSize = channel.readLong()
                        val index = channel.readInt()
                        val length = channel.readInt()

                        onProgress?.invoke(
                            iName,
                            path,
                            index.toLong() * FileBlock.BLOCK_SIZE + length,
                            totalSize
                        )

                        val buffer = if (length > 0) writeFileCall.getBuffer() else null
                        if (length > 0 && (buffer == null || writeFileCall.isCanceled)) {
                            writeFileCall.cancel()
                            return@withContext
                        }

                        if (length > 0 && buffer != null) {
                            channel.readFully(buffer, 0, length)
                            connection.addDownloadedBytes(length.toLong())
                        }

                        val chunkBlock = FileBlock(
                            isFile = true,
                            fileIndex = fileIndex,
                            path = path,
                            lastModified = lastModified,
                            totalSize = totalSize,
                            index = index,
                            data = buffer,
                            dataLength = length
                        )
                        writeFileCall.putBlock(chunkBlock, channelIndex)
                    }

                    QuickShareProtocolConstants.EOF -> {
                        writeFileCall.finishChannel(channelIndex)
                        val elapsed = System.currentTimeMillis() - startTime
                        onComplete?.invoke(iName, connection.getTotalTraffic().downloadTraffic, elapsed)
                        return@withContext
                    }

                    QuickShareProtocolConstants.END_OF_INTERRUPTED -> {
                        writeFileCall.cancel()
                        onError?.invoke(iName, 4, null)
                        return@withContext
                    }

                    QuickShareProtocolConstants.END_OF_READ_ERROR -> {
                        writeFileCall.cancel()
                        onError?.invoke(iName, 5, null)
                        return@withContext
                    }

                    QuickShareProtocolConstants.END_OF_WRITE_ERROR -> {
                        writeFileCall.cancel()
                        onError?.invoke(iName, 6, null)
                        return@withContext
                    }

                    else -> {
                        writeFileCall.cancel()
                        throw ProtocolException("Unknown data channel frame header: 0x${header.toString(16)}")
                    }
                }
            }
        } catch (ex: Throwable) {
            writeFileCall.finishChannel(channelIndex)
            onError?.invoke(iName, -1, ex.message)
            throw ex
        }
    }
}

package com.quickshare.android.model

/**
 * FileBlock represents an atomic 1MB file slice or directory metadata entry
 * transferred across multi-transport data channels.
 */
class FileBlock(
    val isFile: Boolean,
    val fileIndex: Int,
    val path: String,
    val lastModified: Long,
    val totalSize: Long,
    val index: Int,
    val data: ByteArray? = null,
    val dataLength: Int = 0
) : Comparable<FileBlock> {

    companion object {
        const val BLOCK_SIZE: Int = 1024 * 1024 // 1MB

        val END_POINT = FileBlock(
            isFile = true,
            fileIndex = -1,
            path = "END_POINT",
            lastModified = 0L,
            totalSize = 0L,
            index = -1,
            data = null,
            dataLength = 0
        )

        val INTERRUPT = FileBlock(
            isFile = true,
            fileIndex = -1,
            path = "INTERRUPT",
            lastModified = 0L,
            totalSize = 0L,
            index = -1,
            data = null,
            dataLength = 0
        )

        val READ_ERROR = FileBlock(
            isFile = true,
            fileIndex = -1,
            path = "READ_ERROR",
            lastModified = 0L,
            totalSize = 0L,
            index = -1,
            data = null,
            dataLength = 0
        )

        val WRITE_ERROR = FileBlock(
            isFile = true,
            fileIndex = -1,
            path = "WRITE_ERROR",
            lastModified = 0L,
            totalSize = 0L,
            index = -1,
            data = null,
            dataLength = 0
        )
    }

    /**
     * Computes the byte offset in the destination file for this slice block.
     */
    fun getStartPosition(): Long {
        return BLOCK_SIZE.toLong() * index
    }

    /**
     * Calculates the total number of 1MB blocks required for this file.
     */
    fun calcBlockCount(): Long {
        if (totalSize == 0L) return 1L
        return (totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE
    }

    /**
     * Primary sort: fileIndex ASC (ensures files are processed in order).
     * Secondary sort: index ASC (ensures 1MB slices are written in sequential order).
     */
    override fun compareTo(other: FileBlock): Int {
        val fileCmp = this.fileIndex.compareTo(other.fileIndex)
        if (fileCmp != 0) {
            return fileCmp
        }
        return this.index.compareTo(other.index)
    }

    override fun toString(): String {
        return "FileBlock(isFile=$isFile, fileIndex=$fileIndex, path='$path', " +
                "lastModified=$lastModified, totalSize=$totalSize, index=$index, dataLength=$dataLength)"
    }
}

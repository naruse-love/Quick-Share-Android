package com.quickshare.android.model

/**
 * RemoteFile represents metadata for a file or directory on a local or remote endpoint.
 */
data class RemoteFile(
    val name: String = "",
    val path: String = "",
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val isDirectory: Boolean = false
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")
}

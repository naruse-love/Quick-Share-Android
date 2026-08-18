package com.quickshare.android.model

import java.io.File

/**
 * Cross-platform path normalizer and sanitizer bridging Unix (Android/Linux)
 * and Windows directory structures.
 */
class QuickShareDirectory(
    rawPath: String,
    val fileSystem: Int
) {
    val path: String = normalizePath(rawPath)

    companion object {
        const val FILE_SYSTEM_UNIX: Int = 0
        const val FILE_SYSTEM_WINDOWS: Int = 1

        private val ILLEGAL_CHARS_REGEX = Regex("""[\\:*?"<>|]""")
        private val WINDOWS_DRIVE_REGEX = Regex("^[A-Za-z]:$")

        fun getCurrentFileSystem(): Int {
            return if (File.separatorChar == '\\') FILE_SYSTEM_WINDOWS else FILE_SYSTEM_UNIX
        }
    }

    private fun normalizePath(raw: String): String {
        if (raw.isEmpty() || raw == "/") return "/"

        val separator = if (fileSystem == FILE_SYSTEM_UNIX) "/" else "\\"
        var result = raw

        if (fileSystem == FILE_SYSTEM_WINDOWS) {
            result = result.replace("/", separator)
        }

        if (fileSystem == FILE_SYSTEM_WINDOWS && WINDOWS_DRIVE_REGEX.matches(result)) {
            result += separator
        }

        if (result != separator && !result.endsWith(separator)) {
            result += separator
        }

        return result
    }

    fun parent(): QuickShareDirectory? {
        if (fileSystem == FILE_SYSTEM_UNIX) {
            if (path == "/") return null
            val trimmed = path.substring(0, path.length - 1)
            val idx = trimmed.lastIndexOf('/')
            val parentPath = if (idx <= 0) "/" else trimmed.substring(0, idx + 1)
            return QuickShareDirectory(parentPath, fileSystem)
        } else {
            if (path == "/") return null
            val norm = path.substring(0, path.length - 1)
            val idx = norm.lastIndexOf('\\')
            if (idx <= 2) {
                return QuickShareDirectory("/", fileSystem)
            }
            return QuickShareDirectory(norm.substring(0, idx + 1), fileSystem)
        }
    }

    fun append(child: String): QuickShareDirectory {
        if (child.isEmpty()) return this
        var trimmed = child
        while (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            trimmed = trimmed.substring(1)
        }
        return QuickShareDirectory(path + trimmed, fileSystem)
    }

    fun generateTransferPath(file: String, remote: QuickShareDirectory): String {
        val localSep = if (this.fileSystem == FILE_SYSTEM_UNIX) "/" else "\\"
        val remoteSep = if (remote.fileSystem == FILE_SYSTEM_UNIX) "/" else "\\"

        val normalizedFile = if (this.fileSystem == FILE_SYSTEM_UNIX) file else file.replace("/", localSep)
        val localFolder = this.path
        val relativePath: String

        if (normalizedFile.startsWith(localFolder, ignoreCase = true)) {
            relativePath = normalizedFile.substring(localFolder.length)
        } else {
            relativePath = if (normalizedFile.startsWith(localSep)) {
                normalizedFile.substring(1)
            } else {
                normalizedFile
            }
        }

        val segments = relativePath.split(localSep)
        val sanitizedSegments = mutableListOf<String>()

        for (seg in segments) {
            if (seg.isEmpty()) continue
            val sanitized = ILLEGAL_CHARS_REGEX.replace(seg, "_")
            sanitizedSegments.add(sanitized)
        }

        val sanitizedRelative = sanitizedSegments.joinToString(remoteSep)

        return if (sanitizedRelative.isEmpty()) {
            remote.path
        } else {
            remote.path + sanitizedRelative
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuickShareDirectory) return false
        return path == other.path && fileSystem == other.fileSystem
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + fileSystem
        return result
    }

    override fun toString(): String {
        val fsName = if (fileSystem == FILE_SYSTEM_UNIX) "UNIX" else "WINDOWS"
        return "Directory{path='$path', fileSystem=$fsName}"
    }
}

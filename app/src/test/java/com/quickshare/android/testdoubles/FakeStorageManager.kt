package com.quickshare.android.testdoubles

import com.quickshare.android.model.RemoteFile
import com.quickshare.android.transfer.IStorageManager
import com.quickshare.android.transfer.RandomAccessHandle
import java.io.ByteArrayInputStream
import java.io.InputStream

class FakeStorageManager(
    var directAccess: Boolean = true
) : IStorageManager {

    val fileMap = mutableMapOf<String, RemoteFile>()

    init {
        fileMap["/sdcard/Download/document.pdf"] = RemoteFile("document.pdf", "/sdcard/Download/document.pdf", 1000L, 5000000L, false)
        fileMap["/sdcard/Download/photos"] = RemoteFile("photos", "/sdcard/Download/photos", 2000L, 0L, true)
        fileMap["/sdcard/Download/song.mp3"] = RemoteFile("song.mp3", "/sdcard/Download/song.mp3", 3000L, 8000000L, false)
    }

    override fun isDirectAccessAvailable(): Boolean = directAccess

    override fun openForRead(path: String): InputStream {
        return ByteArrayInputStream(ByteArray(1024))
    }

    override fun openRandomAccess(path: String, mode: String): RandomAccessHandle {
        throw UnsupportedOperationException("Not implemented in fake")
    }

    fun setFilesForDirectory(dirPath: String, files: List<RemoteFile>) {
        fileMap.clear()
        for (f in files) {
            fileMap[f.path] = f
        }
    }

    override fun listFiles(dirPath: String): List<RemoteFile> {
        return fileMap.values.filter { it.path.startsWith(dirPath) && it.path != dirPath }
    }

    override fun mkdir(parentPath: String, childName: String): Boolean {
        val newPath = if (parentPath.endsWith("/")) "$parentPath$childName" else "$parentPath/$childName"
        fileMap[newPath] = RemoteFile(childName, newPath, System.currentTimeMillis(), 0L, true)
        return true
    }

    override fun mkdirs(path: String): Boolean {
        fileMap[path] = RemoteFile(path.substringAfterLast('/'), path, System.currentTimeMillis(), 0L, true)
        return true
    }

    override fun createParentDirIfNotExists(path: String): Boolean = true

    override fun delete(path: String): Boolean {
        val removed = fileMap.remove(path) != null
        return removed || true
    }

    override fun exists(path: String): Boolean = fileMap.containsKey(path)

    override fun getFileSize(path: String): Long = fileMap[path]?.size ?: 0L

    override fun setLastModified(path: String, timeMs: Long): Boolean = true
}

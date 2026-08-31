package com.quickshare.android.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.quickshare.android.model.RemoteFile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Unified random-access handle for seekable chunked reads and writes
 * across Direct POSIX FileChannel and SAF ParcelFileDescriptor backends.
 */
interface RandomAccessHandle : Closeable, AutoCloseable {
    fun seek(position: Long)
    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun write(buffer: ByteBuffer)
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    fun read(buffer: ByteBuffer): Int
    fun setLength(length: Long)
    fun length(): Long
    fun flush()
    override fun close()
}

/**
 * Direct POSIX FileChannel & RandomAccessFile implementation of [RandomAccessHandle].
 */
class DirectRandomAccessHandle(private val raf: RandomAccessFile) : RandomAccessHandle {
    private val channel: FileChannel = raf.channel

    override fun seek(position: Long) {
        channel.position(position)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        while (byteBuffer.hasRemaining()) {
            channel.write(byteBuffer)
        }
    }

    override fun write(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        return channel.read(byteBuffer)
    }

    override fun read(buffer: ByteBuffer): Int {
        return channel.read(buffer)
    }

    override fun setLength(length: Long) {
        raf.setLength(length)
    }

    override fun length(): Long = channel.size()

    override fun flush() {
        try {
            channel.force(false)
        } catch (_: Throwable) {}
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Throwable) {}
        try {
            raf.close()
        } catch (_: Throwable) {}
    }
}

/**
 * Storage Access Framework (SAF) ParcelFileDescriptor implementation of [RandomAccessHandle].
 */
class SafRandomAccessHandle(
    private val pfd: ParcelFileDescriptor,
    mode: String = "rw"
) : RandomAccessHandle {
    private val channel: FileChannel = if (mode.contains("w")) {
        FileOutputStream(pfd.fileDescriptor).channel
    } else {
        FileInputStream(pfd.fileDescriptor).channel
    }

    override fun seek(position: Long) {
        channel.position(position)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        while (byteBuffer.hasRemaining()) {
            channel.write(byteBuffer)
        }
    }

    override fun write(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        return channel.read(byteBuffer)
    }

    override fun read(buffer: ByteBuffer): Int {
        return channel.read(buffer)
    }

    override fun setLength(length: Long) {
        channel.truncate(length)
    }

    override fun length(): Long = channel.size()

    override fun flush() {
        try {
            channel.force(false)
        } catch (_: Throwable) {}
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Throwable) {}
        try {
            pfd.close()
        } catch (_: Throwable) {}
    }
}

/**
 * Interface contract for storage engines abstracting Direct Unix I/O and SAF backends.
 */
interface IStorageManager {
    fun isDirectAccessAvailable(): Boolean
    fun openForRead(path: String): InputStream
    fun openRandomAccess(path: String, mode: String = "rw"): RandomAccessHandle
    fun listFiles(dirPath: String): List<RemoteFile>
    fun mkdir(parentPath: String, childName: String): Boolean
    fun mkdirs(path: String): Boolean
    fun createParentDirIfNotExists(path: String): Boolean
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
    fun getFileSize(path: String): Long
    fun setLastModified(path: String, timeMs: Long): Boolean
}

/**
 * Direct Unix POSIX storage engine for all-files access or app-specific sandbox storage.
 */
class DirectStorageEngine(
    private val baseDir: File? = null,
    private val context: Context? = null
) : IStorageManager {

    private fun resolveFile(path: String): File {
        val f = File(path)
        return if (f.isAbsolute || baseDir == null) f else File(baseDir, path)
    }

    override fun isDirectAccessAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        } catch (_: Throwable) {
            // Running in JVM test environment
            true
        }
    }

    override fun openForRead(path: String): InputStream {
        val file = resolveFile(path)
        if (!file.exists()) throw FileNotFoundException("File not found: $path")
        return FileInputStream(file).buffered(64 * 1024)
    }

    override fun openRandomAccess(path: String, mode: String): RandomAccessHandle {
        val file = resolveFile(path)
        try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, mode)
            return DirectRandomAccessHandle(raf)
        } catch (e: Throwable) {
            if (mode.contains("w")) {
                // 1. Try public Download directory
                try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val fallbackFile = File(downloads, file.name)
                    fallbackFile.parentFile?.mkdirs()
                    val raf = RandomAccessFile(fallbackFile, mode)
                    return DirectRandomAccessHandle(raf)
                } catch (_: Throwable) {}

                // 2. Try app external files directory
                if (context != null) {
                    try {
                        val appExt = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                        val fallbackApp = File(appExt, file.name)
                        fallbackApp.parentFile?.mkdirs()
                        val raf = RandomAccessFile(fallbackApp, mode)
                        return DirectRandomAccessHandle(raf)
                    } catch (_: Throwable) {}
                }
            }
            throw if (e is Exception) e else RuntimeException(e)
        }
    }

    override fun listFiles(dirPath: String): List<RemoteFile> {
        val file = resolveFile(dirPath)
        if (!file.exists() || !file.isDirectory) return emptyList()
        val list = file.listFiles() ?: return emptyList()
        return list.map { entry ->
            RemoteFile(
                name = entry.name,
                path = entry.absolutePath,
                lastModified = entry.lastModified(),
                size = if (entry.isDirectory) 0L else entry.length(),
                isDirectory = entry.isDirectory
            )
        }
    }

    override fun mkdir(parentPath: String, childName: String): Boolean {
        val parent = resolveFile(parentPath)
        val child = if (childName.isEmpty()) parent else File(parent, childName)
        return child.mkdirs() || child.exists()
    }

    override fun mkdirs(path: String): Boolean {
        val file = resolveFile(path)
        return file.mkdirs() || file.exists()
    }

    override fun createParentDirIfNotExists(path: String): Boolean {
        val file = resolveFile(path)
        val parent = file.parentFile ?: return true
        return parent.mkdirs() || parent.exists()
    }

    override fun delete(path: String): Boolean {
        val file = resolveFile(path)
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun exists(path: String): Boolean {
        return resolveFile(path).exists()
    }

    override fun getFileSize(path: String): Long {
        val file = resolveFile(path)
        return if (file.exists() && file.isFile) file.length() else 0L
    }

    override fun setLastModified(path: String, timeMs: Long): Boolean {
        if (timeMs <= 0L) return false
        val file = resolveFile(path)
        return if (file.exists()) file.setLastModified(timeMs) else false
    }
}

/**
 * Storage Access Framework (SAF) engine for Android scoped storage fallback.
 */
class SafStorageEngine(private val context: Context? = null) : IStorageManager {
    private var treeUri: Uri? = null
    private var rootDocumentFile: DocumentFile? = null

    fun setTreeUri(uri: Uri?) {
        treeUri = uri
        rootDocumentFile = if (uri != null && context != null) {
            try {
                DocumentFile.fromTreeUri(context, uri)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun getTreeUri(): Uri? = treeUri

    override fun isDirectAccessAvailable(): Boolean = false

    override fun openForRead(path: String): InputStream {
        val ctx = context ?: throw IllegalStateException("Context is required for SAF operations")
        val uri = if (path.startsWith("content://")) Uri.parse(path) else resolveUri(path)
            ?: throw FileNotFoundException("Could not resolve SAF URI for: $path")
        return ctx.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open input stream for: $uri")
    }

    override fun openRandomAccess(path: String, mode: String): RandomAccessHandle {
        val ctx = context ?: throw IllegalStateException("Context is required for SAF operations")
        val uri = if (path.startsWith("content://")) Uri.parse(path) else getOrCreateDocumentUri(path)
            ?: throw FileNotFoundException("Could not create/resolve SAF document for: $path")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, mode)
            ?: throw FileNotFoundException("Cannot open ParcelFileDescriptor for: $uri with mode $mode")
        return SafRandomAccessHandle(pfd, mode)
    }

    override fun listFiles(dirPath: String): List<RemoteFile> {
        val dirDoc = resolveDocumentFile(dirPath) ?: return emptyList()
        if (!dirDoc.isDirectory) return emptyList()
        return dirDoc.listFiles().map { doc ->
            RemoteFile(
                name = doc.name ?: "",
                path = doc.uri.toString(),
                lastModified = doc.lastModified(),
                size = if (doc.isDirectory) 0L else doc.length(),
                isDirectory = doc.isDirectory
            )
        }
    }

    override fun mkdir(parentPath: String, childName: String): Boolean {
        val parentDoc = resolveDocumentFile(parentPath) ?: return false
        val existing = parentDoc.findFile(childName)
        if (existing != null && existing.isDirectory) return true
        val created = parentDoc.createDirectory(childName)
        return created != null
    }

    override fun mkdirs(path: String): Boolean {
        val segments = normalizeSegments(path)
        var current = rootDocumentFile ?: return false
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return false
            }
        }
        return true
    }

    override fun createParentDirIfNotExists(path: String): Boolean {
        val segments = normalizeSegments(path)
        if (segments.size <= 1) return true
        val parentSegments = segments.subList(0, segments.size - 1)
        var current = rootDocumentFile ?: return false
        for (segment in parentSegments) {
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return false
            }
        }
        return true
    }

    override fun delete(path: String): Boolean {
        val doc = resolveDocumentFile(path) ?: return false
        return doc.delete()
    }

    override fun exists(path: String): Boolean {
        return resolveDocumentFile(path)?.exists() ?: false
    }

    override fun getFileSize(path: String): Long {
        val doc = resolveDocumentFile(path) ?: return 0L
        return if (doc.isFile) doc.length() else 0L
    }

    override fun setLastModified(path: String, timeMs: Long): Boolean {
        // DocumentFile does not natively support setLastModified across all Android versions
        // Non-fatal warning / return false gracefully
        return false
    }

    private fun resolveUri(path: String): Uri? {
        val doc = resolveDocumentFile(path)
        return doc?.uri
    }

    private fun resolveDocumentFile(path: String): DocumentFile? {
        if (path.startsWith("content://") && context != null) {
            return DocumentFile.fromSingleUri(context, Uri.parse(path))
        }
        var current = rootDocumentFile ?: return null
        val segments = normalizeSegments(path)
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun getOrCreateDocumentUri(path: String): Uri? {
        val segments = normalizeSegments(path)
        if (segments.isEmpty()) return null
        var current = rootDocumentFile ?: return null
        val fileName = segments.last()
        val dirSegments = segments.subList(0, segments.size - 1)

        for (segment in dirSegments) {
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return null
            }
        }

        val existingFile = current.findFile(fileName)
        if (existingFile != null) {
            return existingFile.uri
        }
        val created = current.createFile("application/octet-stream", fileName)
        return created?.uri
    }

    private fun normalizeSegments(path: String): List<String> {
        return path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    }
}

/**
 * Unified StorageManager router dispatching between Direct Unix I/O and SAF Engines.
 */
class StorageManager(
    private val context: Context? = null,
    val directEngine: DirectStorageEngine = DirectStorageEngine(null, context),
    val safEngine: SafStorageEngine = SafStorageEngine(context)
) : IStorageManager {

    private var activeSafTreeUri: Uri? = null

    fun setSafTreeUri(treeUri: Uri?) {
        activeSafTreeUri = treeUri
        safEngine.setTreeUri(treeUri)
    }

    fun getSafTreeUri(): Uri? = activeSafTreeUri

    override fun isDirectAccessAvailable(): Boolean {
        return directEngine.isDirectAccessAvailable()
    }

    fun selectEngine(path: String): IStorageManager {
        if (path.startsWith("content://")) {
            return safEngine
        }
        if (isDirectAccessAvailable()) {
            return directEngine
        }
        if (context != null) {
            val filesDir = context.filesDir?.absolutePath
            val extDir = context.getExternalFilesDir(null)?.absolutePath
            if (filesDir != null && path.startsWith(filesDir)) {
                return directEngine
            }
            if (extDir != null && path.startsWith(extDir)) {
                return directEngine
            }
        }
        if (activeSafTreeUri != null) {
            return safEngine
        }
        return directEngine
    }

    override fun openForRead(path: String): InputStream = selectEngine(path).openForRead(path)
    override fun openRandomAccess(path: String, mode: String): RandomAccessHandle = selectEngine(path).openRandomAccess(path, mode)
    override fun listFiles(dirPath: String): List<RemoteFile> = selectEngine(dirPath).listFiles(dirPath)
    override fun mkdir(parentPath: String, childName: String): Boolean = selectEngine(parentPath).mkdir(parentPath, childName)
    override fun mkdirs(path: String): Boolean = selectEngine(path).mkdirs(path)
    override fun createParentDirIfNotExists(path: String): Boolean = selectEngine(path).createParentDirIfNotExists(path)
    override fun delete(path: String): Boolean = selectEngine(path).delete(path)
    override fun exists(path: String): Boolean = selectEngine(path).exists(path)
    override fun getFileSize(path: String): Long = selectEngine(path).getFileSize(path)
    override fun setLastModified(path: String, timeMs: Long): Boolean = selectEngine(path).setLastModified(path, timeMs)
}

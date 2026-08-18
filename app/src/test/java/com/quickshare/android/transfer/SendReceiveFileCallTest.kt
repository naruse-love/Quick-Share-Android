package com.quickshare.android.transfer

import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.protocol.QuickShareStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * End-to-end unit tests validating streaming transmission and reception
 * via [SendFileCall], [ReceiveFileCall], [ReadFileCall], and [WriteFileCall]
 * over interconnected [QuickShareStream] pipes.
 */
class SendReceiveFileCallTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "send_recv_test_${System.nanoTime()}").apply { mkdirs() }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createDummyFile(name: String, size: Long): File {
        val f = File(tempDir, name)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { fos ->
            val buf = ByteArray(minOf(size.toInt(), 64 * 1024))
            for (i in buf.indices) buf[i] = (i % 251).toByte()
            var rem = size
            while (rem > 0) {
                val w = minOf(rem, buf.size.toLong()).toInt()
                fos.write(buf, 0, w)
                rem -= w
            }
        }
        return f
    }

    @Test
    fun testLoopbackStreamingTransfer() = runBlocking {
        // Setup source files
        val srcFile = createDummyFile("src/sample.bin", 2500000L) // ~2.5MB
        val srcMd5 = ChecksumUtil.md5(srcFile)

        val srcFolder = File(tempDir, "src")
        val destFolder = File(tempDir, "dest").apply { mkdirs() }

        val remoteFile = RemoteFile(
            name = srcFile.name,
            path = srcFile.absolutePath,
            lastModified = srcFile.lastModified(),
            size = srcFile.length(),
            isDirectory = false
        )

        val senderPool = BufferPool(8, 1024 * 1024)
        val receiverPool = BufferPool(8, 1024 * 1024)

        val localDir = QuickShareDirectory(srcFolder.absolutePath, QuickShareDirectory.getCurrentFileSystem())
        val remoteDir = QuickShareDirectory(destFolder.absolutePath, QuickShareDirectory.getCurrentFileSystem())

        // Set up in-memory piped streams
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 2 * 1024 * 1024)

        // Sender stream and Receiver stream
        // Sender writes to pipeOut; Receiver reads from pipeIn
        val sendChannel = QuickShareStream(PipedInputStream(), pipeOut)
        val recvChannel = QuickShareStream(pipeIn, PipedOutputStream())

        val sendConn = TransferConnection("wlan0", sendChannel)
        val recvConn = TransferConnection("wlan0", recvChannel)

        val readFileCall = ReadFileCall(
            buffers = senderPool.rawQueue,
            files = listOf(remoteFile),
            localDir = localDir,
            remoteDir = remoteDir,
            operateThreadCount = 1
        )

        val writeFileEngine = DirectStorageEngine()
        val writeFileCall = WriteFileCall(receiverPool, channelCount = 1, storageManager = writeFileEngine)

        val sentBytes = AtomicLong(0)
        val recvBytes = AtomicLong(0)

        val sendFileCall = SendFileCall(
            readFileCall = readFileCall,
            connection = sendConn,
            onProgress = { _, _, sent, _ -> sentBytes.set(sent) }
        )

        val receiveFileCall = ReceiveFileCall(
            channelIndex = 0,
            connection = recvConn,
            writeFileCall = writeFileCall,
            onProgress = { _, _, recv, _ -> recvBytes.set(recv) }
        )

        // Run reader, sender, receiver, and writer concurrently
        val readerJob = async { readFileCall.executeAsync() }
        val senderJob = async { sendFileCall.executeAsync() }
        val receiverJob = async { receiveFileCall.executeAsync() }
        val writerJob = async { writeFileCall.executeAsync() }

        readerJob.await()
        senderJob.await()
        receiverJob.await()
        writerJob.await()

        val expectedDestFile = File(destFolder, "sample.bin")
        assertTrue("Destination file should exist", expectedDestFile.exists())
        assertEquals(srcFile.length(), expectedDestFile.length())
        assertEquals(srcMd5, ChecksumUtil.md5(expectedDestFile))

        // Verify traffic counts
        assertEquals(2500000L, sendConn.getTotalTraffic().uploadTraffic)
        assertEquals(2500000L, recvConn.getTotalTraffic().downloadTraffic)

        // Verify all buffers are recycled back to pools (Zero-GC)
        assertEquals(8, senderPool.availableCount())
        assertEquals(8, receiverPool.availableCount())
    }

    @Test
    fun testTrafficConnectionCounters() {
        val channel = QuickShareStream(PipedInputStream(), PipedOutputStream())
        val conn = TransferConnection("eth0", channel)

        conn.addUploadedBytes(1024)
        conn.addDownloadedBytes(2048)

        val current = conn.resetCurrentTrafficInfo()
        assertEquals(1024L, current.uploadTraffic)
        assertEquals(2048L, current.downloadTraffic)

        // Next window starts at 0
        val emptyWindow = conn.resetCurrentTrafficInfo()
        assertEquals(0L, emptyWindow.uploadTraffic)
        assertEquals(0L, emptyWindow.downloadTraffic)

        // Cumulative total remains
        val total = conn.getTotalTraffic()
        assertEquals(1024L, total.uploadTraffic)
        assertEquals(2048L, total.downloadTraffic)

        conn.resetTotalTrafficInfo()
        assertEquals(0L, conn.getTotalTraffic().uploadTraffic)
    }
}

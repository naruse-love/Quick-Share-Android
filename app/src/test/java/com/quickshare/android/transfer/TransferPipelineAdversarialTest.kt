package com.quickshare.android.transfer

import com.quickshare.android.model.FileBlock
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.protocol.QuickShareStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial edge-case test suite for Milestone 2:
 * 1. Sentinel markers (END_OF_INTERRUPTED, END_OF_READ_ERROR, END_OF_WRITE_ERROR) & zero buffer leak guarantees
 * 2. Mid-stream connection breaks and error recovery
 * 3. Boundary file sizes (0-byte empty, 1-byte minimal, 3.5MB fractional chunk)
 * 4. Deeply nested recursive directory trees with mixed file types and timestamps
 */
class TransferPipelineAdversarialTest {

    private lateinit var testWorkDir: File

    @Before
    fun setUp() {
        testWorkDir = File(System.getProperty("java.io.tmpdir"), "adv_xfer_${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    private fun createDummyDataFile(parent: File, relativePath: String, sizeBytes: Long): File {
        val file = File(parent, relativePath)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(minOf(sizeBytes.toInt(), 64 * 1024))
            for (i in buf.indices) {
                buf[i] = ((i * 31 + 7) % 256).toByte()
            }
            var remaining = sizeBytes
            while (remaining > 0) {
                val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                fos.write(buf, 0, toWrite)
                remaining -= toWrite
            }
        }
        return file
    }

    // =========================================================================
    // SECTION 1: SENTINELS & BUFFER POOL ZERO-LEAK VERIFICATION
    // =========================================================================

    @Test
    fun testInterruptedSentinelPropagatesAndRecyclesAllBuffers() {
        runBlocking {
            val pool = BufferPool(8, 1024 * 1024)
            val srcFile = createDummyDataFile(testWorkDir, "src/large.bin", 3000000L) // 3MB (3 blocks)
            val remoteFile = RemoteFile(
                name = srcFile.name,
                path = srcFile.absolutePath,
                lastModified = srcFile.lastModified(),
                size = srcFile.length(),
                isDirectory = false
            )

            val localDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory("/dest", 0)

            val readFileCall = ReadFileCall(
                buffers = pool.rawQueue,
                files = listOf(remoteFile),
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = 2
            )

            // Start reader job in IO dispatcher
            val readerJob = async(Dispatchers.IO) {
                try {
                    readFileCall.executeAsync()
                } catch (_: Throwable) {}
            }

            // Read the first block with timeout
            val b0 = withContext(Dispatchers.IO) { readFileCall.takeBlock(3000) }
            assertNotNull("First block must not be null", b0)
            assertTrue(b0!!.isFile)
            assertEquals(0, b0.index)
            assertNotNull(b0.data)

            // Now simulate receiver / channel reporting interruption
            readFileCall.shutdownByConnectionBreak()
            readerJob.await()

            // Recycle b0 that was already held outside
            readFileCall.recycleBuffer(b0.data)

            // The remaining blocks in deque must be drained, replaced by INTERRUPT sentinels
            val sentinel1 = withContext(Dispatchers.IO) { readFileCall.takeBlock(3000) }
            assertNotNull("Sentinel 1 must not be null", sentinel1)
            assertEquals(ReadFileCall.INTERRUPT, sentinel1)
            val sentinel2 = withContext(Dispatchers.IO) { readFileCall.takeBlock(3000) }
            assertNotNull("Sentinel 2 must not be null", sentinel2)
            assertEquals(ReadFileCall.INTERRUPT, sentinel2)

            // Verify that all 8 buffers are completely returned to BufferPool (ZERO LEAK)
            assertEquals("BufferPool must have all 8 buffers returned after interruption", 8, pool.availableCount())
            assertTrue("BufferPool must be full", pool.isFull())
        }
    }

    @Test
    fun testReadErrorSentinelPropagatesAndRecyclesAllBuffers() {
        runBlocking {
            val pool = BufferPool(6, 1024 * 1024)
            val nonExistentFile = RemoteFile(
                name = "ghost.bin",
                path = File(testWorkDir, "ghost.bin").absolutePath,
                lastModified = 0L,
                size = 5000000L,
                isDirectory = false
            )

            val localDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory("/dest", 0)

            // Use custom storage resolver that throws IOException on open
            val readFileCall = ReadFileCall(
                buffers = pool.rawQueue,
                files = listOf(nonExistentFile),
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = 3,
                storageResolver = { throw IOException("Disk hardware read error simulated") }
            )

            val readerJob = async {
                try {
                    readFileCall.executeAsync()
                } catch (t: Throwable) {
                    // Expected exception
                }
            }

            readerJob.await()

            // 3 READ_ERROR sentinels must be dispatched for 3 channel workers
            for (i in 0 until 3) {
                val sentinel = readFileCall.takeBlock()
                assertEquals(ReadFileCall.READ_ERROR, sentinel)
            }

            // Verify zero buffer leaks
            assertEquals(6, pool.availableCount())
            assertTrue(pool.isFull())
        }
    }

    @Test
    fun testWriteErrorSentinelShutsDownAndRecyclesAllBuffers() {
        runBlocking {
            val pool = BufferPool(8, 1024 * 1024)
            val srcFile = createDummyDataFile(testWorkDir, "src/write_err.bin", 4000000L) // 4MB
            val remoteFile = RemoteFile(
                name = srcFile.name,
                path = srcFile.absolutePath,
                lastModified = srcFile.lastModified(),
                size = srcFile.length(),
                isDirectory = false
            )

            val localDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory("/dest", 0)

            val readFileCall = ReadFileCall(
                buffers = pool.rawQueue,
                files = listOf(remoteFile),
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = 4
            )

            val readerJob = async { readFileCall.executeAsync() }

            // Slicer enqueues blocks into deque. We simulate receiver calling shutdownByWriteError()
            delay(50) // Let slicer fill deque
            readFileCall.shutdownByWriteError()
            readerJob.await()

            // Drain all 4 sentinels
            for (i in 0 until 4) {
                val s = readFileCall.takeBlock()
                assertEquals(ReadFileCall.WRITE_ERROR, s)
            }

            // Verify zero buffer leak
            assertEquals(8, pool.availableCount())
            assertTrue(pool.isFull())
        }
    }

    @Test
    fun testSendReceiveEndToEndSentinelHandling() {
        runBlocking {
            val senderPool = BufferPool(4, 1024 * 1024)
            val receiverPool = BufferPool(4, 1024 * 1024)

            val srcFile = createDummyDataFile(testWorkDir, "src/sentinel_test.bin", 2000000L)
            val remoteFile = RemoteFile(
                name = srcFile.name,
                path = srcFile.absolutePath,
                lastModified = srcFile.lastModified(),
                size = srcFile.length(),
                isDirectory = false
            )

            val localDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 1024 * 1024)

            val sendStream = QuickShareStream(PipedInputStream(), pipeOut)
            val recvStream = QuickShareStream(pipeIn, PipedOutputStream())

            val sendConn = TransferConnection("wlan0", sendStream)
            val recvConn = TransferConnection("wlan0", recvStream)

            val readFileCall = ReadFileCall(
                buffers = senderPool.rawQueue,
                files = listOf(remoteFile),
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = 1
            )

            val storageEngine = DirectStorageEngine(testWorkDir)
            val writeFileCall = WriteFileCall(receiverPool, channelCount = 1, storageManager = storageEngine)

            val errorReceived = AtomicInteger(0)

            val sendFileCall = SendFileCall(
                readFileCall = readFileCall,
                connection = sendConn,
                onError = { _, code, _ -> errorReceived.set(code) }
            )

            val receiveFileCall = ReceiveFileCall(
                channelIndex = 0,
                connection = recvConn,
                writeFileCall = writeFileCall,
                onError = { _, code, _ -> errorReceived.set(code) }
            )

            // Inject INTERRUPT before sending
            readFileCall.shutdownByConnectionBreak()

            val senderJob = async {
                try {
                    sendFileCall.executeAsync()
                } catch (_: Throwable) {}
            }
            val receiverJob = async {
                try {
                    receiveFileCall.executeAsync()
                } catch (_: Throwable) {}
            }

            senderJob.await()
            receiverJob.await()

            assertEquals(4, errorReceived.get())
            assertEquals(4, senderPool.availableCount())
            assertEquals(4, receiverPool.availableCount())
        }
    }

    @Test
    fun testMidStreamAbruptStreamBreakBufferReclaim() {
        runBlocking {
            val senderPool = BufferPool(4, 1024 * 1024)
            val receiverPool = BufferPool(4, 1024 * 1024)

            val srcFile = createDummyDataFile(testWorkDir, "src/broken.bin", 4000000L) // 4MB
            val remoteFile = RemoteFile(
                name = srcFile.name,
                path = srcFile.absolutePath,
                lastModified = srcFile.lastModified(),
                size = srcFile.length(),
                isDirectory = false
            )

            val localDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(testWorkDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 1024 * 1024)

            val sendStream = QuickShareStream(PipedInputStream(), pipeOut)
            val recvStream = QuickShareStream(pipeIn, PipedOutputStream())

            val sendConn = TransferConnection("eth0", sendStream)
            val recvConn = TransferConnection("eth0", recvStream)

            val readFileCall = ReadFileCall(
                buffers = senderPool.rawQueue,
                files = listOf(remoteFile),
                localDir = localDir,
                remoteDir = remoteDir,
                operateThreadCount = 1
            )

            val storageEngine = DirectStorageEngine(testWorkDir)
            val writeFileCall = WriteFileCall(receiverPool, channelCount = 1, storageManager = storageEngine)

            val sendFileCall = SendFileCall(readFileCall = readFileCall, connection = sendConn)
            val receiveFileCall = ReceiveFileCall(0, recvConn, writeFileCall)

            val readerJob = async { readFileCall.executeAsync() }
            val senderJob = async {
                try {
                    sendFileCall.executeAsync()
                } catch (_: Throwable) {}
            }
            val receiverJob = async {
                try {
                    receiveFileCall.executeAsync()
                } catch (_: Throwable) {}
            }

            // Abruptly sever the pipe after short delay
            delay(30)
            pipeOut.close()
            pipeIn.close()

            senderJob.await()
            receiverJob.await()

            // Shutdown and recycle any remaining reader buffers
            readFileCall.shutdownByConnectionBreak()
            writeFileCall.cancel()
            readerJob.await()

            assertEquals("Sender buffer pool must reclaim all buffers", 4, senderPool.availableCount())
            assertEquals("Receiver buffer pool must reclaim all buffers", 4, receiverPool.availableCount())
        }
    }

    // =========================================================================
    // SECTION 2: BOUNDARY FILE SIZES (0-BYTE, 1-BYTE, 3.5MB FRACTIONAL)
    // =========================================================================

    @Test
    fun testEmptyFileBoundaryStreaming() {
        runBlocking {
            val srcDir = File(testWorkDir, "empty_src").apply { mkdirs() }
            val emptyFile = File(srcDir, "zero_byte.dat").apply { createNewFile() }

            val destDir = File(testWorkDir, "empty_dest").apply { mkdirs() }

            val remoteFile = RemoteFile(
                name = emptyFile.name,
                path = emptyFile.absolutePath,
                lastModified = 1500000000000L,
                size = 0L,
                isDirectory = false
            )

            val senderPool = BufferPool(4, 1024 * 1024)
            val receiverPool = BufferPool(4, 1024 * 1024)

            val localDir = QuickShareDirectory(srcDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(destDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 1024 * 1024)

            val sendConn = TransferConnection("c0", QuickShareStream(PipedInputStream(), pipeOut))
            val recvConn = TransferConnection("c0", QuickShareStream(pipeIn, PipedOutputStream()))

            val readFileCall = ReadFileCall(senderPool.rawQueue, listOf(remoteFile), localDir, remoteDir, 1)
            val writeFileCall = WriteFileCall(receiverPool, 1, DirectStorageEngine())

            val sendFileCall = SendFileCall(readFileCall, sendConn)
            val receiveFileCall = ReceiveFileCall(0, recvConn, writeFileCall)

            val rJ = async { readFileCall.executeAsync() }
            val sJ = async { sendFileCall.executeAsync() }
            val rcJ = async { receiveFileCall.executeAsync() }
            val wJ = async { writeFileCall.executeAsync() }

            rJ.await()
            sJ.await()
            rcJ.await()
            wJ.await()

            val destFile = File(destDir, "zero_byte.dat")
            assertTrue("Destination zero-byte file must exist", destFile.exists())
            assertEquals(0L, destFile.length())
            assertEquals(4, senderPool.availableCount())
            assertEquals(4, receiverPool.availableCount())
        }
    }

    @Test
    fun testSingleByteBoundaryStreaming() {
        runBlocking {
            val srcDir = File(testWorkDir, "single_src").apply { mkdirs() }
            val singleFile = File(srcDir, "single.bin").apply {
                writeBytes(byteArrayOf(0xAB.toByte()))
            }

            val destDir = File(testWorkDir, "single_dest").apply { mkdirs() }

            val remoteFile = RemoteFile(
                name = singleFile.name,
                path = singleFile.absolutePath,
                lastModified = singleFile.lastModified(),
                size = 1L,
                isDirectory = false
            )

            val senderPool = BufferPool(4, 1024 * 1024)
            val receiverPool = BufferPool(4, 1024 * 1024)

            val localDir = QuickShareDirectory(srcDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(destDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 1024 * 1024)

            val sendConn = TransferConnection("c0", QuickShareStream(PipedInputStream(), pipeOut))
            val recvConn = TransferConnection("c0", QuickShareStream(pipeIn, PipedOutputStream()))

            val readFileCall = ReadFileCall(senderPool.rawQueue, listOf(remoteFile), localDir, remoteDir, 1)
            val writeFileCall = WriteFileCall(receiverPool, 1, DirectStorageEngine())

            val sendFileCall = SendFileCall(readFileCall, sendConn)
            val receiveFileCall = ReceiveFileCall(0, recvConn, writeFileCall)

            val rJ = async { readFileCall.executeAsync() }
            val sJ = async { sendFileCall.executeAsync() }
            val rcJ = async { receiveFileCall.executeAsync() }
            val wJ = async { writeFileCall.executeAsync() }

            rJ.await()
            sJ.await()
            rcJ.await()
            wJ.await()

            val destFile = File(destDir, "single.bin")
            assertTrue(destFile.exists())
            assertEquals(1L, destFile.length())
            assertEquals(0xAB.toByte(), destFile.readBytes()[0])
            assertEquals(ChecksumUtil.sha256(singleFile), ChecksumUtil.sha256(destFile))
            assertEquals(4, senderPool.availableCount())
            assertEquals(4, receiverPool.availableCount())
        }
    }

    @Test
    fun testFractional3_5MBMultiChannelOutOfOrderStreaming() {
        runBlocking {
            val fileSize = 3670016L // Exactly 3.5 MB (3 x 1MB + 1 x 512KB)
            val srcDir = File(testWorkDir, "frac_src").apply { mkdirs() }
            val srcFile = createDummyDataFile(srcDir, "frac_3_5mb.bin", fileSize)
            val srcSha256 = ChecksumUtil.sha256(srcFile)
            val srcMd5 = ChecksumUtil.md5(srcFile)

            val destDir = File(testWorkDir, "frac_dest").apply { mkdirs() }

            val remoteFile = RemoteFile(
                name = srcFile.name,
                path = srcFile.absolutePath,
                lastModified = srcFile.lastModified(),
                size = fileSize,
                isDirectory = false
            )

            val channelCount = 3
            val senderPool = BufferPool(8, 1024 * 1024)
            val receiverPool = BufferPool(8, 1024 * 1024)

            val localDir = QuickShareDirectory(srcDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(destDir.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val readFileCall = ReadFileCall(senderPool.rawQueue, listOf(remoteFile), localDir, remoteDir, channelCount)
            val writeFileCall = WriteFileCall(receiverPool, channelCount, DirectStorageEngine())

            // Create 3 interconnected pipe channels
            val sendJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            val recvJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (i in 0 until channelCount) {
                val pipeOut = PipedOutputStream()
                val pipeIn = PipedInputStream(pipeOut, 2 * 1024 * 1024)

                val sConn = TransferConnection("nic_$i", QuickShareStream(PipedInputStream(), pipeOut))
                val rConn = TransferConnection("nic_$i", QuickShareStream(pipeIn, PipedOutputStream()))

                val sCall = SendFileCall(readFileCall, sConn)
                val rCall = ReceiveFileCall(i, rConn, writeFileCall)

                sendJobs.add(async { sCall.executeAsync() })
                recvJobs.add(async { rCall.executeAsync() })
            }

            val readerJob = async { readFileCall.executeAsync() }
            val writerJob = async { writeFileCall.executeAsync() }

            readerJob.await()
            sendJobs.forEach { it.await() }
            recvJobs.forEach { it.await() }
            writerJob.await()

            val destFile = File(destDir, "frac_3_5mb.bin")
            assertTrue("Destination file must exist", destFile.exists())
            assertEquals(fileSize, destFile.length())
            assertEquals("MD5 must match original", srcMd5, ChecksumUtil.md5(destFile))
            assertEquals("SHA-256 must match original", srcSha256, ChecksumUtil.sha256(destFile))

            assertEquals(8, senderPool.availableCount())
            assertEquals(8, receiverPool.availableCount())
        }
    }

    // =========================================================================
    // SECTION 3: DEEPLY NESTED DIRECTORY TREES WITH MIXED CONTENT
    // =========================================================================

    @Test
    fun testDeeplyNestedDirectoryTreeEndToEnd() {
        runBlocking {
            val rootSrc = File(testWorkDir, "tree_src").apply { mkdirs() }

            // Build 5-level deep directory structure with various files
            val l1Empty = File(rootSrc, "empty_l1").apply { mkdirs() }
            val l1Dir = File(rootSrc, "dir_l1").apply { mkdirs() }
            createDummyDataFile(l1Dir, "file_l1.txt", 1500L)

            val l2Empty = File(l1Dir, "empty_l2").apply { mkdirs() }
            val l2Dir = File(l1Dir, "dir_l2").apply { mkdirs() }
            createDummyDataFile(l2Dir, "empty_in_l2.dat", 0L)

            val l3Dir = File(l2Dir, "dir_l3").apply { mkdirs() }
            createDummyDataFile(l3Dir, "single_byte_l3.bin", 1L)

            val l4Dir = File(l3Dir, "dir_l4").apply { mkdirs() }
            val l5Dir = File(l4Dir, "dir_l5").apply { mkdirs() }
            createDummyDataFile(l5Dir, "deep_large.bin", 2200000L) // 2.2MB

            val rootDest = File(testWorkDir, "tree_dest").apply { mkdirs() }

            val rootRemoteFile = RemoteFile(
                name = rootSrc.name,
                path = rootSrc.absolutePath,
                lastModified = rootSrc.lastModified(),
                size = 0L,
                isDirectory = true
            )

            val senderPool = BufferPool(8, 1024 * 1024)
            val receiverPool = BufferPool(8, 1024 * 1024)

            val localDir = QuickShareDirectory(rootSrc.absolutePath, QuickShareDirectory.getCurrentFileSystem())
            val remoteDir = QuickShareDirectory(rootDest.absolutePath, QuickShareDirectory.getCurrentFileSystem())

            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 2 * 1024 * 1024)

            val sendConn = TransferConnection("wlan0", QuickShareStream(PipedInputStream(), pipeOut))
            val recvConn = TransferConnection("wlan0", QuickShareStream(pipeIn, PipedOutputStream()))

            val readFileCall = ReadFileCall(senderPool.rawQueue, listOf(rootRemoteFile), localDir, remoteDir, 1)
            val writeFileCall = WriteFileCall(receiverPool, 1, DirectStorageEngine())

            val sendFileCall = SendFileCall(readFileCall, sendConn)
            val receiveFileCall = ReceiveFileCall(0, recvConn, writeFileCall)

            val rJ = async { readFileCall.executeAsync() }
            val sJ = async { sendFileCall.executeAsync() }
            val rcJ = async { receiveFileCall.executeAsync() }
            val wJ = async { writeFileCall.executeAsync() }

            rJ.await()
            sJ.await()
            rcJ.await()
            wJ.await()

            // Verify all levels and contents
            assertTrue(File(rootDest, "empty_l1").exists())
            assertTrue(File(rootDest, "dir_l1").exists())
            assertTrue(File(rootDest, "dir_l1/file_l1.txt").exists())
            assertEquals(1500L, File(rootDest, "dir_l1/file_l1.txt").length())

            assertTrue(File(rootDest, "dir_l1/empty_l2").exists())
            assertTrue(File(rootDest, "dir_l1/dir_l2").exists())
            assertTrue(File(rootDest, "dir_l1/dir_l2/empty_in_l2.dat").exists())
            assertEquals(0L, File(rootDest, "dir_l1/dir_l2/empty_in_l2.dat").length())

            assertTrue(File(rootDest, "dir_l1/dir_l2/dir_l3/single_byte_l3.bin").exists())
            assertEquals(1L, File(rootDest, "dir_l1/dir_l2/dir_l3/single_byte_l3.bin").length())

            val deepDestFile = File(rootDest, "dir_l1/dir_l2/dir_l3/dir_l4/dir_l5/deep_large.bin")
            assertTrue(deepDestFile.exists())
            assertEquals(2200000L, deepDestFile.length())
            assertEquals(
                ChecksumUtil.md5(File(rootSrc, "dir_l1/dir_l2/dir_l3/dir_l4/dir_l5/deep_large.bin")),
                ChecksumUtil.md5(deepDestFile)
            )

            assertEquals(8, senderPool.availableCount())
            assertEquals(8, receiverPool.availableCount())
        }
    }
}

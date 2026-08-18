package com.quickshare.android.protocol

import com.quickshare.android.model.RemoteFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class ProtocolCodecTest {

    @Test
    fun testProtocolConstantsValues() {
        assertEquals("HFXC", QuickShareProtocolConstants.CLIENT_HEADER)
        assertEquals(300, QuickShareProtocolConstants.VERSION_CODE)
        assertEquals(5740, QuickShareProtocolConstants.DEFAULT_PORT)
        assertEquals(1048576, QuickShareProtocolConstants.BLOCK_SIZE)
        assertEquals(8, QuickShareProtocolConstants.DEFAULT_BUFFER_COUNT)

        // Command opcodes
        assertEquals(0.toShort(), QuickShareProtocolConstants.SHUTDOWN)
        assertEquals(1.toShort(), QuickShareProtocolConstants.LIST_FILES)
        assertEquals(2.toShort(), QuickShareProtocolConstants.DELETE_FILE)
        assertEquals(3.toShort(), QuickShareProtocolConstants.MKDIR)
        assertEquals(10.toShort(), QuickShareProtocolConstants.REQUEST_RECEIVE)
        assertEquals(11.toShort(), QuickShareProtocolConstants.REQUEST_SEND)

        // Data frame headers
        assertEquals((-1).toShort(), QuickShareProtocolConstants.END_POINT)
        assertEquals(0.toShort(), QuickShareProtocolConstants.FILE)
        assertEquals(1.toShort(), QuickShareProtocolConstants.FOLDER)
        assertEquals(2.toShort(), QuickShareProtocolConstants.FILE_SLICE)
        assertEquals(3.toShort(), QuickShareProtocolConstants.EOF)
        assertEquals(4.toShort(), QuickShareProtocolConstants.END_OF_INTERRUPTED)
        assertEquals(5.toShort(), QuickShareProtocolConstants.END_OF_READ_ERROR)
        assertEquals(6.toShort(), QuickShareProtocolConstants.END_OF_WRITE_ERROR)

        // Filesystem types
        assertEquals(0, QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(1, QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
    }

    @Test
    fun testHandshakeSuccessFlow() {
        val clientToServer = ByteArrayOutputStream()
        val serverToClient = ByteArrayOutputStream()

        val clientOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), clientToServer)
        val serverOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), serverToClient)

        // Step 1 & 2: Client writes header & version
        clientOut.write(QuickShareProtocolConstants.CLIENT_HEADER.toByteArray(Charsets.US_ASCII))
        clientOut.writeInt(QuickShareProtocolConstants.VERSION_CODE)
        clientOut.flush()

        // Server reads client header & version
        val serverIn = QuickShareStream(ByteArrayInputStream(clientToServer.toByteArray()), ByteArrayOutputStream())
        val headerBytes = ByteArray(4)
        serverIn.readFully(headerBytes, 0, 4)
        val header = String(headerBytes, Charsets.US_ASCII)
        val version = serverIn.readInt()

        assertEquals("HFXC", header)
        assertEquals(300, version)

        // Step 3: Server writes version matched = true
        serverOut.writeBoolean(true)
        // Step 4: Server advertises NICs
        serverOut.writeInt(2) // 2 NICs
        // NIC 1: wlan0 (192.168.1.50)
        serverOut.writeUTF("wlan0")
        val ip1 = byteArrayOf(192.toByte(), 168.toByte(), 1, 50)
        serverOut.writeByte(ip1.size.toByte())
        serverOut.write(ip1)
        serverOut.writeByte(0.toByte()) // client bind flag

        // NIC 2: rndis0 (192.168.42.2)
        serverOut.writeUTF("rndis0")
        val ip2 = byteArrayOf(192.toByte(), 168.toByte(), 42, 2)
        serverOut.writeByte(ip2.size.toByte())
        serverOut.write(ip2)
        serverOut.writeByte(0.toByte())
        serverOut.flush()

        // Client reads Server response
        val clientIn = QuickShareStream(ByteArrayInputStream(serverToClient.toByteArray()), ByteArrayOutputStream())
        val versionMatched = clientIn.readBoolean()
        assertTrue(versionMatched)

        val nicCount = clientIn.readInt()
        assertEquals(2, nicCount)

        val nic1Name = clientIn.readUTF()
        val nic1IpLen = clientIn.readByte().toInt() and 0xFF
        val nic1IpBytes = ByteArray(nic1IpLen)
        clientIn.readFully(nic1IpBytes)
        val nic1BindFlag = clientIn.readByte()
        assertEquals("wlan0", nic1Name)
        assertEquals("192.168.1.50", InetAddress.getByAddress(nic1IpBytes).hostAddress)
        assertEquals(0.toByte(), nic1BindFlag)

        val nic2Name = clientIn.readUTF()
        val nic2IpLen = clientIn.readByte().toInt() and 0xFF
        val nic2IpBytes = ByteArray(nic2IpLen)
        clientIn.readFully(nic2IpBytes)
        val nic2BindFlag = clientIn.readByte()
        assertEquals("rndis0", nic2Name)
        assertEquals("192.168.42.2", InetAddress.getByAddress(nic2IpBytes).hostAddress)
        assertEquals(0.toByte(), nic2BindFlag)
    }

    @Test
    fun testHandshakeVersionMismatch() {
        val serverToClient = ByteArrayOutputStream()
        val serverOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), serverToClient)

        // Server writes version mismatch response
        serverOut.writeBoolean(false)
        serverOut.writeInt(QuickShareProtocolConstants.VERSION_CODE)
        serverOut.flush()

        val clientIn = QuickShareStream(ByteArrayInputStream(serverToClient.toByteArray()), ByteArrayOutputStream())
        val matched = clientIn.readBoolean()
        assertFalse(matched)
        val serverVersion = clientIn.readInt()
        assertEquals(300, serverVersion)
    }

    @Test
    fun testListFilesRpcCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Request: Command LIST_FILES + path
        streamOut.writeShort(QuickShareProtocolConstants.LIST_FILES)
        streamOut.writeUTF("/sdcard/Download")
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        val cmd = streamIn.readShort()
        val path = streamIn.readUTF()
        assertEquals(QuickShareProtocolConstants.LIST_FILES, cmd)
        assertEquals("/sdcard/Download", path)

        // Response: count + RemoteFile entries
        val respBos = ByteArrayOutputStream()
        val respOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        val files = listOf(
            RemoteFile(name = "video.mp4", path = "/sdcard/Download/video.mp4", lastModified = 1700000000000L, size = 104857600L, isDirectory = false),
            RemoteFile(name = "docs", path = "/sdcard/Download/docs", lastModified = 1700000050000L, size = 0L, isDirectory = true)
        )

        respOut.writeInt(files.size)
        for (f in files) {
            respOut.writeUTF(f.name)
            respOut.writeUTF(f.path)
            respOut.writeLong(f.lastModified)
            respOut.writeLong(f.size)
            respOut.writeBoolean(f.isDirectory)
        }
        respOut.flush()

        val respIn = QuickShareStream(ByteArrayInputStream(respBos.toByteArray()), ByteArrayOutputStream())
        val count = respIn.readInt()
        assertEquals(2, count)

        val readFiles = mutableListOf<RemoteFile>()
        for (i in 0 until count) {
            readFiles.add(
                RemoteFile(
                    name = respIn.readUTF(),
                    path = respIn.readUTF(),
                    lastModified = respIn.readLong(),
                    size = respIn.readLong(),
                    isDirectory = respIn.readBoolean()
                )
            )
        }

        assertEquals(files[0], readFiles[0])
        assertEquals(files[1], readFiles[1])
        assertEquals("mp4", readFiles[0].extension)
        assertEquals("", readFiles[1].extension)
    }

    @Test
    fun testDeleteFileRpcCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.DELETE_FILE)
        streamOut.writeUTF("/sdcard/Download/temp.txt")
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.DELETE_FILE, streamIn.readShort())
        assertEquals("/sdcard/Download/temp.txt", streamIn.readUTF())

        val respBos = ByteArrayOutputStream()
        val respOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        respOut.writeBoolean(true)
        respOut.flush()

        val respIn = QuickShareStream(ByteArrayInputStream(respBos.toByteArray()), ByteArrayOutputStream())
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testMkdirRpcCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.MKDIR)
        streamOut.writeUTF("/sdcard/Download")
        streamOut.writeUTF("NewFolder")
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.MKDIR, streamIn.readShort())
        assertEquals("/sdcard/Download", streamIn.readUTF())
        assertEquals("NewFolder", streamIn.readUTF())

        val respBos = ByteArrayOutputStream()
        val respOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        respOut.writeBoolean(true)
        respOut.flush()

        val respIn = QuickShareStream(ByteArrayInputStream(respBos.toByteArray()), ByteArrayOutputStream())
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testShutdownRpcCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.SHUTDOWN)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.SHUTDOWN, streamIn.readShort())
    }

    @Test
    fun testFolderFrameCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.FOLDER)
        streamOut.writeInt(0) // fileIndex
        streamOut.writeUTF("subfolder/")
        streamOut.writeLong(1700000000000L)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.FOLDER, streamIn.readShort())
        assertEquals(0, streamIn.readInt())
        assertEquals("subfolder/", streamIn.readUTF())
        assertEquals(1700000000000L, streamIn.readLong())
    }

    @Test
    fun testFileSliceFrameCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        val payload = ByteArray(1024) { (it % 128).toByte() }
        streamOut.writeShort(QuickShareProtocolConstants.FILE)
        streamOut.writeInt(1) // fileIndex
        streamOut.writeUTF("photo.jpg")
        streamOut.writeLong(1690000000000L)
        streamOut.writeLong(1024L) // totalSize
        streamOut.writeInt(0) // chunk index
        streamOut.writeInt(payload.size) // dataLength
        streamOut.write(payload)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.FILE, streamIn.readShort())
        assertEquals(1, streamIn.readInt())
        assertEquals("photo.jpg", streamIn.readUTF())
        assertEquals(1690000000000L, streamIn.readLong())
        assertEquals(1024L, streamIn.readLong())
        assertEquals(0, streamIn.readInt())
        val readLen = streamIn.readInt()
        assertEquals(1024, readLen)
        val readBuf = ByteArray(readLen)
        streamIn.readFully(readBuf)
        assertArrayEquals(payload, readBuf)
    }

    @Test
    fun testEmptyFileFrameCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.FILE)
        streamOut.writeInt(2) // fileIndex
        streamOut.writeUTF("empty.txt")
        streamOut.writeLong(1695000000000L)
        streamOut.writeLong(0L) // totalSize = 0
        streamOut.writeInt(0) // chunk index = 0
        streamOut.writeInt(0) // dataLength = 0
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.FILE, streamIn.readShort())
        assertEquals(2, streamIn.readInt())
        assertEquals("empty.txt", streamIn.readUTF())
        assertEquals(1695000000000L, streamIn.readLong())
        assertEquals(0L, streamIn.readLong())
        assertEquals(0, streamIn.readInt())
        assertEquals(0, streamIn.readInt())
    }

    @Test
    fun testTerminalFramesCodec() {
        val terminalOpcodes = listOf(
            QuickShareProtocolConstants.EOF,
            QuickShareProtocolConstants.END_OF_INTERRUPTED,
            QuickShareProtocolConstants.END_OF_READ_ERROR,
            QuickShareProtocolConstants.END_OF_WRITE_ERROR
        )

        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        for (op in terminalOpcodes) {
            streamOut.writeShort(op)
        }
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        for (expected in terminalOpcodes) {
            assertEquals(expected, streamIn.readShort())
        }
    }
}

package com.quickshare.android.network

import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.protocol.QuickShareStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class QuickShareRpcTest {

    @Test
    fun testListFilesRpcSerializationAndParsing() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // 1. Client serializes LIST_FILES request
        streamOut.writeShort(QuickShareProtocolConstants.LIST_FILES)
        streamOut.writeUTF("/sdcard/Download")
        streamOut.flush()

        // 2. Server parses request and writes response
        val bis = ByteArrayInputStream(bos.toByteArray())
        val streamIn = QuickShareStream(bis, ByteArrayOutputStream())

        val opCode = streamIn.readShort()
        val path = streamIn.readUTF()

        assertEquals(QuickShareProtocolConstants.LIST_FILES, opCode)
        assertEquals("/sdcard/Download", path)

        // Server writes 2 files
        val respBos = ByteArrayOutputStream()
        val respStreamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        respStreamOut.writeInt(2)

        // File 1
        respStreamOut.writeUTF("photo.jpg")
        respStreamOut.writeUTF("/sdcard/Download/photo.jpg")
        respStreamOut.writeLong(1600000000000L)
        respStreamOut.writeLong(2048576L)
        respStreamOut.writeBoolean(false)

        // File 2 (Directory)
        respStreamOut.writeUTF("Documents")
        respStreamOut.writeUTF("/sdcard/Download/Documents")
        respStreamOut.writeLong(1600000001000L)
        respStreamOut.writeLong(0L)
        respStreamOut.writeBoolean(true)
        respStreamOut.flush()

        // 3. Client parses response
        val respBis = ByteArrayInputStream(respBos.toByteArray())
        val clientStreamIn = QuickShareStream(respBis, ByteArrayOutputStream())

        val count = clientStreamIn.readInt()
        assertEquals(2, count)

        val name1 = clientStreamIn.readUTF()
        val path1 = clientStreamIn.readUTF()
        val time1 = clientStreamIn.readLong()
        val size1 = clientStreamIn.readLong()
        val isDir1 = clientStreamIn.readBoolean()

        assertEquals("photo.jpg", name1)
        assertEquals("/sdcard/Download/photo.jpg", path1)
        assertEquals(1600000000000L, time1)
        assertEquals(2048576L, size1)
        assertFalse(isDir1)

        val name2 = clientStreamIn.readUTF()
        val path2 = clientStreamIn.readUTF()
        val time2 = clientStreamIn.readLong()
        val size2 = clientStreamIn.readLong()
        val isDir2 = clientStreamIn.readBoolean()

        assertEquals("Documents", name2)
        assertEquals("/sdcard/Download/Documents", path2)
        assertEquals(1600000001000L, time2)
        assertEquals(0L, size2)
        assertTrue(isDir2)
    }

    @Test
    fun testListFilesRpcNotFoundResponse() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Server writes -1 for not found
        streamOut.writeInt(-1)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        val count = streamIn.readInt()
        assertEquals(-1, count)
    }

    @Test
    fun testDeleteFileRpc() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Client sends DELETE_FILE
        streamOut.writeShort(QuickShareProtocolConstants.DELETE_FILE)
        streamOut.writeUTF("/sdcard/temp.txt")
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.DELETE_FILE, streamIn.readShort())
        assertEquals("/sdcard/temp.txt", streamIn.readUTF())

        // Server replies true
        val respBos = ByteArrayOutputStream()
        val respOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        respOut.writeBoolean(true)
        respOut.flush()

        val respIn = QuickShareStream(ByteArrayInputStream(respBos.toByteArray()), ByteArrayOutputStream())
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testMkdirRpc() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Client sends MKDIR
        streamOut.writeShort(QuickShareProtocolConstants.MKDIR)
        streamOut.writeUTF("/sdcard")
        streamOut.writeUTF("NewFolder")
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.MKDIR, streamIn.readShort())
        assertEquals("/sdcard", streamIn.readUTF())
        assertEquals("NewFolder", streamIn.readUTF())

        // Server replies true
        val respBos = ByteArrayOutputStream()
        val respOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), respBos)
        respOut.writeBoolean(true)
        respOut.flush()

        val respIn = QuickShareStream(ByteArrayInputStream(respBos.toByteArray()), ByteArrayOutputStream())
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testShutdownRpc() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(QuickShareProtocolConstants.SHUTDOWN)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(QuickShareProtocolConstants.SHUTDOWN, streamIn.readShort())
    }
}

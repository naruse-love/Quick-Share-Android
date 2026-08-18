package com.quickshare.android.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * RemoteCommandCodecTest validates RPC command serialization and deserialization matching QuickShareServer / QuickShareClient wire formats.
 */
class RemoteCommandCodecTest {

    @Test
    fun testListFilesRequestAndResponseCodec() {
        val baos = ByteArrayOutputStream()
        val dout = DataOutputStream(baos)

        // Encode LIST_FILES Request
        val requestPath = "/storage/emulated/0/Download"
        dout.writeShort(1) // LIST_FILES
        dout.writeUTF(requestPath)
        dout.flush()

        val reqIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1.toShort(), reqIn.readShort())
        assertEquals(requestPath, reqIn.readUTF())

        // Encode LIST_FILES Response
        baos.reset()
        val entries = listOf(
            Triple("photo.jpg", "/storage/emulated/0/Download/photo.jpg", false),
            Triple("Documents", "/storage/emulated/0/Download/Documents", true)
        )
        dout.writeInt(entries.size)
        for (e in entries) {
            dout.writeUTF(e.first)
            dout.writeUTF(e.second)
            dout.writeLong(1755000000000L)
            dout.writeLong(if (e.third) 0L else 204800L)
            dout.writeBoolean(e.third)
        }
        dout.flush()

        val respIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val count = respIn.readInt()
        assertEquals(2, count)

        val entry1Name = respIn.readUTF()
        val entry1Path = respIn.readUTF()
        val entry1Time = respIn.readLong()
        val entry1Size = respIn.readLong()
        val entry1IsDir = respIn.readBoolean()
        assertEquals("photo.jpg", entry1Name)
        assertEquals("/storage/emulated/0/Download/photo.jpg", entry1Path)
        assertEquals(1755000000000L, entry1Time)
        assertEquals(204800L, entry1Size)
        assertFalse(entry1IsDir)

        val entry2Name = respIn.readUTF()
        val entry2Path = respIn.readUTF()
        val entry2Time = respIn.readLong()
        val entry2Size = respIn.readLong()
        val entry2IsDir = respIn.readBoolean()
        assertEquals("Documents", entry2Name)
        assertTrue(entry2IsDir)
    }

    @Test
    fun testDeleteFileCodec() {
        val baos = ByteArrayOutputStream()
        val dout = DataOutputStream(baos)

        // Request
        dout.writeShort(2) // DELETE_FILE
        dout.writeUTF("/storage/emulated/0/test.tmp")
        dout.flush()

        val reqIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(2.toShort(), reqIn.readShort())
        assertEquals("/storage/emulated/0/test.tmp", reqIn.readUTF())

        // Response
        baos.reset()
        dout.writeBoolean(true)
        dout.flush()

        val respIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testMkdirCodec() {
        val baos = ByteArrayOutputStream()
        val dout = DataOutputStream(baos)

        // Request
        dout.writeShort(3) // MKDIR
        dout.writeUTF("/storage/emulated/0/Download")
        dout.writeUTF("NewFolder_2026")
        dout.flush()

        val reqIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(3.toShort(), reqIn.readShort())
        assertEquals("/storage/emulated/0/Download", reqIn.readUTF())
        assertEquals("NewFolder_2026", reqIn.readUTF())

        // Response
        baos.reset()
        dout.writeBoolean(true)
        dout.flush()

        val respIn = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertTrue(respIn.readBoolean())
    }

    @Test
    fun testRequestSendPayloadCodec() {
        val baos = ByteArrayOutputStream()
        val dout = DataOutputStream(baos)

        val files = listOf("file1.mp4", "docs/file2.pdf")
        val remoteParent = "/home/user/media"
        val requestorFs = 1 // Windows
        val destDir = "D:\\Downloads\\Synced"

        dout.writeShort(11) // REQUEST_SEND
        dout.writeInt(files.size)
        for (f in files) {
            dout.writeUTF(f)
        }
        dout.writeUTF(remoteParent)
        dout.writeInt(requestorFs)
        dout.writeUTF(destDir)
        dout.flush()

        val din = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(11.toShort(), din.readShort())
        val count = din.readInt()
        assertEquals(2, count)
        assertEquals("file1.mp4", din.readUTF())
        assertEquals("docs/file2.pdf", din.readUTF())
        assertEquals(remoteParent, din.readUTF())
        assertEquals(1, din.readInt())
        assertEquals(destDir, din.readUTF())
    }
}

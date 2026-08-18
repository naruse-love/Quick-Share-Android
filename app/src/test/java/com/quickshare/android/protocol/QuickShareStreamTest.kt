package com.quickshare.android.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class QuickShareStreamTest {

    @Test
    fun testBigEndianPrimitives() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(12345.toShort())
        streamOut.writeInt(987654321)
        streamOut.writeLong(1234567890123456789L)
        streamOut.writeBoolean(true)
        streamOut.writeBoolean(false)
        streamOut.writeByte(0x7F.toByte())
        streamOut.flush()

        val rawBytes = bos.toByteArray()
        // 2 (short) + 4 (int) + 8 (long) + 1 (bool) + 1 (bool) + 1 (byte) = 17 bytes
        assertEquals(17, rawBytes.size)

        // Verify Big-Endian byte layout for short: 12345 = 0x3039
        assertEquals(0x30.toByte(), rawBytes[0])
        assertEquals(0x39.toByte(), rawBytes[1])

        val streamIn = QuickShareStream(ByteArrayInputStream(rawBytes), ByteArrayOutputStream())
        assertEquals(12345.toShort(), streamIn.readShort())
        assertEquals(987654321, streamIn.readInt())
        assertEquals(1234567890123456789L, streamIn.readLong())
        assertTrue(streamIn.readBoolean())
        assertFalse(streamIn.readBoolean())
        assertEquals(0x7F.toByte(), streamIn.readByte())
    }

    @Test
    fun testBoundaryPrimitives() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(Short.MIN_VALUE)
        streamOut.writeShort(Short.MAX_VALUE)
        streamOut.writeInt(Int.MIN_VALUE)
        streamOut.writeInt(Int.MAX_VALUE)
        streamOut.writeLong(Long.MIN_VALUE)
        streamOut.writeLong(Long.MAX_VALUE)
        streamOut.writeByte(Byte.MIN_VALUE)
        streamOut.writeByte(Byte.MAX_VALUE)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        assertEquals(Short.MIN_VALUE, streamIn.readShort())
        assertEquals(Short.MAX_VALUE, streamIn.readShort())
        assertEquals(Int.MIN_VALUE, streamIn.readInt())
        assertEquals(Int.MAX_VALUE, streamIn.readInt())
        assertEquals(Long.MIN_VALUE, streamIn.readLong())
        assertEquals(Long.MAX_VALUE, streamIn.readLong())
        assertEquals(Byte.MIN_VALUE, streamIn.readByte())
        assertEquals(Byte.MAX_VALUE, streamIn.readByte())
    }

    @Test
    fun testUTF8StringCodec() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        val testStrings = listOf(
            "",
            "Hello World!",
            "混合文件传输 Android <-> PC",
            "Special chars: !@#$%^&*()_+{}|:\"<>?[];',./",
            "Emojis: 🚀🔥📁🎉"
        )

        for (str in testStrings) {
            streamOut.writeUTF(str)
        }
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        for (str in testStrings) {
            assertEquals(str, streamIn.readUTF())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUTF8StringLengthLimit() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        val oversizedString = "A".repeat(65536)
        streamOut.writeUTF(oversizedString)
    }

    @Test
    fun testReadFully() {
        val expectedData = ByteArray(1024) { (it % 256).toByte() }
        val streamIn = QuickShareStream(ByteArrayInputStream(expectedData), ByteArrayOutputStream())
        val actualData = ByteArray(1024)
        streamIn.readFully(actualData, 0, 1024)
        assertArrayEquals(expectedData, actualData)
    }

    @Test
    fun testReadFullyWithOffset() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val streamIn = QuickShareStream(ByteArrayInputStream(payload), ByteArrayOutputStream())
        val buffer = ByteArray(10)
        streamIn.readFully(buffer, 2, 5)
        assertEquals(0, buffer[0].toInt())
        assertEquals(0, buffer[1].toInt())
        assertEquals(1, buffer[2].toInt())
        assertEquals(5, buffer[6].toInt())
        assertEquals(0, buffer[7].toInt())
    }

    @Test(expected = EOFException::class)
    fun testPrematureEOF() {
        val partialData = ByteArray(5)
        val streamIn = QuickShareStream(ByteArrayInputStream(partialData), ByteArrayOutputStream())
        val buffer = ByteArray(10)
        streamIn.readFully(buffer, 0, 10)
    }

    @Test(expected = EOFException::class)
    fun testBooleanEOF() {
        val emptyStream = QuickShareStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        emptyStream.readBoolean()
    }

    @Test(expected = EOFException::class)
    fun testByteEOF() {
        val emptyStream = QuickShareStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        emptyStream.readByte()
    }

    @Test
    fun testCloseSafety() {
        val bos = ByteArrayOutputStream()
        val stream = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        stream.close()
        // Should not throw on double close
        stream.close()
    }
}

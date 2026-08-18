package com.quickshare.android.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * High-performance Big-Endian binary stream reader & writer implementing [IQuickShareStream].
 *
 * Backed by [DataInputStream] and [DataOutputStream] with standard UTF-8 string codec
 * to ensure 100% interoperability with C# and Java QuickShareProtocol endpoints.
 */
class QuickShareStream(
    override val inputStream: InputStream,
    override val outputStream: OutputStream
) : IQuickShareStream {

    constructor(socket: Socket) : this(
        inputStream = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE),
        outputStream = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
    )

    private val dataIn = DataInputStream(inputStream)
    private val dataOut = DataOutputStream(outputStream)

    override fun readShort(): Short {
        return dataIn.readShort()
    }

    override fun writeShort(v: Short) {
        dataOut.writeShort(v.toInt())
    }

    override fun readInt(): Int {
        return dataIn.readInt()
    }

    override fun writeInt(v: Int) {
        dataOut.writeInt(v)
    }

    override fun readLong(): Long {
        return dataIn.readLong()
    }

    override fun writeLong(v: Long) {
        dataOut.writeLong(v)
    }

    override fun readBoolean(): Boolean {
        val b = dataIn.read()
        if (b < 0) throw EOFException("Unexpected EOF while reading boolean")
        return b != 0
    }

    override fun writeBoolean(v: Boolean) {
        dataOut.write(if (v) 1 else 0)
    }

    override fun readByte(): Byte {
        val b = dataIn.read()
        if (b < 0) throw EOFException("Unexpected EOF while reading byte")
        return b.toByte()
    }

    override fun writeByte(v: Byte) {
        dataOut.write(v.toInt() and 0xFF)
    }

    override fun readUTF(): String {
        val utflen = readShort().toInt() and 0xFFFF
        if (utflen == 0) return ""
        val bytes = ByteArray(utflen)
        readFully(bytes, 0, utflen)
        return String(bytes, StandardCharsets.UTF_8)
    }

    override fun writeUTF(str: String) {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 65535) { "String UTF-8 byte length exceeds 65535: ${bytes.size}" }
        writeShort(bytes.size.toShort())
        if (bytes.isNotEmpty()) {
            write(bytes, 0, bytes.size)
        }
    }

    override fun readFully(b: ByteArray, off: Int, len: Int) {
        dataIn.readFully(b, off, len)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        dataOut.write(b, off, len)
    }

    override fun flush() {
        dataOut.flush()
    }

    override fun close() {
        try {
            dataOut.flush()
        } catch (_: Throwable) {}
        try {
            dataOut.close()
        } catch (_: Throwable) {}
        try {
            dataIn.close()
        } catch (_: Throwable) {}
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB stream buffer
    }
}

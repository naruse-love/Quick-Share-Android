package com.quickshare.android.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Interface contract for Big-Endian binary stream serialization and deserialization
 * in QuickShareProtocol v300.
 */
interface IQuickShareStream : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream

    fun readShort(): Short
    fun writeShort(v: Short)

    fun readInt(): Int
    fun writeInt(v: Int)

    fun readLong(): Long
    fun writeLong(v: Long)

    fun readBoolean(): Boolean
    fun writeBoolean(v: Boolean)

    fun readByte(): Byte
    fun writeByte(v: Byte)

    fun readUTF(): String
    fun writeUTF(str: String)

    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size)
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size)

    fun flush()
}

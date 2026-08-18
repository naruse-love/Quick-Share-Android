package com.quickshare.android.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * Adversarial and empirical stress test suite for [QuickShareStream].
 *
 * Tests boundary conditions, UTF-8 edge cases, integer extremes,
 * fragmented network stream handling, and high-volume randomized fuzzing.
 */
class QuickShareStreamAdversarialTest {

    /**
     * FragmentedInputStream simulates adversarial network conditions where
     * the underlying socket returns only a small number of bytes (or 1 byte) per read call.
     */
    private class FragmentedInputStream(
        private val source: ByteArray,
        private val maxBytesPerRead: Int = 1
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= source.size) return -1
            return source[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= source.size) return -1
            val toRead = minOf(len, maxBytesPerRead, source.size - position)
            System.arraycopy(source, position, b, off, toRead)
            position += toRead
            return toRead
        }

        override fun available(): Int = source.size - position
    }

    // ==========================================
    // 1. INTEGER & PRIMITIVE BOUNDARY VALUE TESTS
    // ==========================================

    @Test
    fun testAllIntegerBoundaryValues() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Long boundaries
        val testLongs = listOf(
            Long.MIN_VALUE,
            Long.MIN_VALUE + 1,
            -1L,
            0L,
            1L,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
            0x0102030405060708L,
            0x7FFFFFFFFFFFFFFFL,
            -0x7FFFFFFFFFFFFFFFL,
            0x5555555555555555L,
            -0x5555555555555556L // 0xAAAAAAAAAAAAAAAA
        )
        for (v in testLongs) {
            streamOut.writeLong(v)
        }

        // Int boundaries
        val testInts = listOf(
            Int.MIN_VALUE,
            Int.MIN_VALUE + 1,
            -1,
            0,
            1,
            Int.MAX_VALUE - 1,
            Int.MAX_VALUE,
            0x01020304,
            0x7FFFFFFF,
            -0x7FFFFFFF,
            0x55555555,
            -0x55555556 // 0xAAAAAAAA
        )
        for (v in testInts) {
            streamOut.writeInt(v)
        }

        // Short boundaries
        val testShorts = listOf(
            Short.MIN_VALUE,
            (Short.MIN_VALUE + 1).toShort(),
            (-1).toShort(),
            0.toShort(),
            1.toShort(),
            (Short.MAX_VALUE - 1).toShort(),
            Short.MAX_VALUE,
            0x1234.toShort(),
            0x7FFF.toShort(),
            0x5555.toShort(),
            0xAAAA.toShort()
        )
        for (v in testShorts) {
            streamOut.writeShort(v)
        }

        // Byte boundaries
        val testBytes = listOf(
            Byte.MIN_VALUE,
            (Byte.MIN_VALUE + 1).toByte(),
            (-1).toByte(),
            0.toByte(),
            1.toByte(),
            (Byte.MAX_VALUE - 1).toByte(),
            Byte.MAX_VALUE,
            0x55.toByte(),
            0xAA.toByte()
        )
        for (v in testBytes) {
            streamOut.writeByte(v)
        }

        // Boolean values
        streamOut.writeBoolean(true)
        streamOut.writeBoolean(false)
        streamOut.writeBoolean(true)

        streamOut.flush()

        // Read back and verify exact byte-for-byte fidelity
        val rawBytes = bos.toByteArray()
        val streamIn = QuickShareStream(ByteArrayInputStream(rawBytes), ByteArrayOutputStream())

        for (expected in testLongs) {
            assertEquals("Long boundary mismatch", expected, streamIn.readLong())
        }
        for (expected in testInts) {
            assertEquals("Int boundary mismatch", expected, streamIn.readInt())
        }
        for (expected in testShorts) {
            assertEquals("Short boundary mismatch", expected, streamIn.readShort())
        }
        for (expected in testBytes) {
            assertEquals("Byte boundary mismatch", expected, streamIn.readByte())
        }
        assertTrue(streamIn.readBoolean())
        assertFalse(streamIn.readBoolean())
        assertTrue(streamIn.readBoolean())
    }

    @Test
    fun testBooleanTruthyRawByteHandling() {
        // Wire compatibility: any non-zero raw byte read as boolean should evaluate to true
        val rawBytes = byteArrayOf(0, 1, 2, 42, 127, (-1).toByte(), (-128).toByte())
        val streamIn = QuickShareStream(ByteArrayInputStream(rawBytes), ByteArrayOutputStream())

        assertFalse(streamIn.readBoolean()) // 0 -> false
        assertTrue(streamIn.readBoolean())  // 1 -> true
        assertTrue(streamIn.readBoolean())  // 2 -> true
        assertTrue(streamIn.readBoolean())  // 42 -> true
        assertTrue(streamIn.readBoolean())  // 127 -> true
        assertTrue(streamIn.readBoolean())  // -1 (0xFF) -> true
        assertTrue(streamIn.readBoolean())  // -128 (0x80) -> true
    }

    // ==========================================
    // 2. UTF-8 STRING BOUNDARY & MULTI-BYTE TESTS
    // ==========================================

    @Test
    fun testMultiByteUtf8FuzzAndBoundary() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        val complexStrings = listOf(
            "", // Empty string
            "A", // 1 byte
            "Hello, World! 1234567890", // ASCII
            "Café Münsterländer Straße €", // 2-byte & 3-byte Latin / Currency
            "中文测试：混合文件传输系统，支持多网卡绑定并发传输", // CJK 3-byte characters
            "日本語テスト：高速ファイル転送システム", // Japanese Hiragana/Katakana/Kanji
            "한국어 테스트: 하이브리드 파일 전송", // Korean Hangul
            "Русский текст: Протокол передачи файлов", // Cyrillic 2-byte
            "עִבְרִית וְעַרְבִיא: שלום עולם مرحبا بالعالم", // RTL Hebrew & Arabic
            "🚀🔥🎉📁⚡💻📱✨🌐📦🔑🔒💡🧪🎮🛰️", // 4-byte UTF-16 surrogate pairs / Emojis
            "Control chars:\u0000\u0001\u0002\t\r\n\u001F \"quote\" 'single' \\backslash /slash <xml>&json;",
            "Special math: ∑ ∫ ∏ √ ∞ ≈ ≠ ≤ ≥ ⊂ ⊃ ⊕ ⊗"
        )

        for (str in complexStrings) {
            streamOut.writeUTF(str)
        }
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        for ((idx, expected) in complexStrings.withIndex()) {
            val actual = streamIn.readUTF()
            assertEquals("Mismatch at string index $idx ($expected)", expected, actual)
        }
    }

    @Test
    fun testExact65535ByteUtf8Limit() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Exact 65,535 ASCII bytes (must succeed)
        val exact65535Str = "X".repeat(65535)
        assertEquals(65535, exact65535Str.toByteArray(StandardCharsets.UTF_8).size)
        streamOut.writeUTF(exact65535Str)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        val readBack = streamIn.readUTF()
        assertEquals(exact65535Str.length, readBack.length)
        assertEquals(exact65535Str, readBack)
    }

    @Test
    fun testExact65535ByteMultiByteLimit() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // 3-byte CJK character '中' repeated 21,845 times = 65,535 bytes
        val cjkChar = "中"
        assertEquals(3, cjkChar.toByteArray(StandardCharsets.UTF_8).size)
        val exact65535Cjk = cjkChar.repeat(21845)
        assertEquals(65535, exact65535Cjk.toByteArray(StandardCharsets.UTF_8).size)

        streamOut.writeUTF(exact65535Cjk)
        streamOut.flush()

        val streamIn = QuickShareStream(ByteArrayInputStream(bos.toByteArray()), ByteArrayOutputStream())
        val readBack = streamIn.readUTF()
        assertEquals(exact65535Cjk, readBack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOverflowByOneByteAscii() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        val overflowStr = "A".repeat(65536) // 65,536 bytes
        streamOut.writeUTF(overflowStr)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOverflowByMultiByteCharacter() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        // 65534 bytes of ASCII + 2-byte character 'é' = 65536 bytes (should throw)
        val str = "A".repeat(65534) + "é"
        assertEquals(65536, str.toByteArray(StandardCharsets.UTF_8).size)
        streamOut.writeUTF(str)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOverflowByEmojiSurrogatePair() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        // 65532 bytes of ASCII + 4-byte emoji '🚀' = 65536 bytes (should throw)
        val str = "A".repeat(65532) + "🚀"
        assertEquals(65536, str.toByteArray(StandardCharsets.UTF_8).size)
        streamOut.writeUTF(str)
    }

    // ==========================================
    // 3. ADVERSARIAL FRAGMENTED NETWORK STREAM
    // ==========================================

    @Test
    fun testFragmentedStreamReads() {
        // Serialize a diverse sequence of data types
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        streamOut.writeShort(0x1234.toShort())
        streamOut.writeInt(0x56789ABC)
        streamOut.writeLong(0x1122334455667788L)
        streamOut.writeUTF("Fast multi-channel streaming with Android & PC!")
        streamOut.writeBoolean(true)
        streamOut.writeByte(0xFE.toByte())
        val payload = ByteArray(2048) { (it % 251).toByte() }
        streamOut.write(payload, 0, payload.size)
        streamOut.flush()

        val rawWireBytes = bos.toByteArray()

        // Wrap raw bytes in a 1-byte-at-a-time fragmented stream
        val fragmentedIn = FragmentedInputStream(rawWireBytes, maxBytesPerRead = 1)
        val streamIn = QuickShareStream(fragmentedIn, ByteArrayOutputStream())

        assertEquals(0x1234.toShort(), streamIn.readShort())
        assertEquals(0x56789ABC, streamIn.readInt())
        assertEquals(0x1122334455667788L, streamIn.readLong())
        assertEquals("Fast multi-channel streaming with Android & PC!", streamIn.readUTF())
        assertTrue(streamIn.readBoolean())
        assertEquals(0xFE.toByte(), streamIn.readByte())

        val readPayload = ByteArray(2048)
        streamIn.readFully(readPayload, 0, 2048)
        assertArrayEquals(payload, readPayload)
    }

    @Test
    fun testTruncatedStreamExceptions() {
        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)
        streamOut.writeLong(123456789L)
        streamOut.writeUTF("Hello")
        streamOut.flush()

        val rawBytes = bos.toByteArray()

        // Case 1: Truncated during 8-byte Long read (only 7 bytes available)
        val partial7Bytes = rawBytes.copyOfRange(0, 7)
        val streamIn1 = QuickShareStream(ByteArrayInputStream(partial7Bytes), ByteArrayOutputStream())
        try {
            streamIn1.readLong()
            fail("Expected EOFException on truncated Long")
        } catch (_: EOFException) {}

        // Case 2: Truncated during UTF header read (only 9 bytes = 8 bytes long + 1 byte of 2-byte length)
        val partial9Bytes = rawBytes.copyOfRange(0, 9)
        val streamIn2 = QuickShareStream(ByteArrayInputStream(partial9Bytes), ByteArrayOutputStream())
        assertEquals(123456789L, streamIn2.readLong())
        try {
            streamIn2.readUTF()
            fail("Expected EOFException on truncated UTF length")
        } catch (_: EOFException) {}

        // Case 3: Truncated during UTF payload read (length says 5 bytes, but only 3 bytes follow)
        val partial13Bytes = rawBytes.copyOfRange(0, 13) // 8 (long) + 2 (utf len = 5) + 3 (only 'Hel')
        val streamIn3 = QuickShareStream(ByteArrayInputStream(partial13Bytes), ByteArrayOutputStream())
        assertEquals(123456789L, streamIn3.readLong())
        try {
            streamIn3.readUTF()
            fail("Expected EOFException on truncated UTF payload")
        } catch (_: EOFException) {}
    }

    // ==========================================
    // 4. HIGH-VOLUME RANDOMIZED FUZZING HARNESS
    // ==========================================

    @Test
    fun testHighVolumeRandomizedFuzzing() {
        val random = Random(42L) // Deterministic seed for repeatability
        val operationCount = 10000

        val bos = ByteArrayOutputStream()
        val streamOut = QuickShareStream(ByteArrayInputStream(ByteArray(0)), bos)

        // Generate random sequence of operations
        val ops = mutableListOf<Any>()

        for (i in 0 until operationCount) {
            when (random.nextInt(6)) {
                0 -> {
                    val s = random.nextInt().toShort()
                    ops.add(s)
                    streamOut.writeShort(s)
                }
                1 -> {
                    val n = random.nextInt()
                    ops.add(n)
                    streamOut.writeInt(n)
                }
                2 -> {
                    val l = random.nextLong()
                    ops.add(l)
                    streamOut.writeLong(l)
                }
                3 -> {
                    val b = random.nextBoolean()
                    ops.add(b)
                    streamOut.writeBoolean(b)
                }
                4 -> {
                    val byt = (random.nextInt(256) - 128).toByte()
                    ops.add(byt)
                    streamOut.writeByte(byt)
                }
                5 -> {
                    val len = random.nextInt(128)
                    val codePoints = intArrayOf(
                        'a'.code, 'b'.code, 'c'.code, 'd'.code, 'e'.code, 'f'.code, 'g'.code, 'h'.code,
                        'i'.code, 'j'.code, 'k'.code, 'l'.code, 'm'.code, 'n'.code, 'o'.code, 'p'.code,
                        'q'.code, 'r'.code, 's'.code, 't'.code, 'u'.code, 'v'.code, 'w'.code, 'x'.code,
                        'y'.code, 'z'.code, 'A'.code, 'Z'.code, '0'.code, '9'.code, '_'.code, '-'.code,
                        '中'.code, '文'.code, '字'.code, '符'.code, '测'.code, '试'.code, "🚀".codePointAt(0), "🔥".codePointAt(0)
                    )
                    val sb = StringBuilder()
                    for (c in 0 until len) {
                        sb.appendCodePoint(codePoints[random.nextInt(codePoints.size)])
                    }
                    val str = sb.toString()
                    ops.add(str)
                    streamOut.writeUTF(str)
                }
            }
        }
        streamOut.flush()

        val rawBytes = bos.toByteArray()
        val streamIn = QuickShareStream(ByteArrayInputStream(rawBytes), ByteArrayOutputStream())

        for ((idx, op) in ops.withIndex()) {
            when (op) {
                is Short -> assertEquals("Op $idx Short mismatch", op, streamIn.readShort())
                is Int -> assertEquals("Op $idx Int mismatch", op, streamIn.readInt())
                is Long -> assertEquals("Op $idx Long mismatch", op, streamIn.readLong())
                is Boolean -> assertEquals("Op $idx Boolean mismatch", op, streamIn.readBoolean())
                is Byte -> assertEquals("Op $idx Byte mismatch", op, streamIn.readByte())
                is String -> assertEquals("Op $idx String mismatch", op, streamIn.readUTF())
            }
        }
    }
}

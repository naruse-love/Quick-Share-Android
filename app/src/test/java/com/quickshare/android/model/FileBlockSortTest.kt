package com.quickshare.android.model

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.PriorityQueue

class FileBlockSortTest {

    @Test
    fun testSingleFileBlockOrder() {
        val blocks = listOf(
            FileBlock(isFile = true, fileIndex = 0, path = "test.dat", lastModified = 0L, totalSize = 5242880L, index = 4),
            FileBlock(isFile = true, fileIndex = 0, path = "test.dat", lastModified = 0L, totalSize = 5242880L, index = 0),
            FileBlock(isFile = true, fileIndex = 0, path = "test.dat", lastModified = 0L, totalSize = 5242880L, index = 3),
            FileBlock(isFile = true, fileIndex = 0, path = "test.dat", lastModified = 0L, totalSize = 5242880L, index = 1),
            FileBlock(isFile = true, fileIndex = 0, path = "test.dat", lastModified = 0L, totalSize = 5242880L, index = 2)
        )

        val sorted = blocks.sorted()
        for (i in sorted.indices) {
            assertEquals(i, sorted[i].index)
            assertEquals(0, sorted[i].fileIndex)
        }
    }

    @Test
    fun testMultiFileBlockOrder() {
        val blocks = listOf(
            FileBlock(isFile = true, fileIndex = 1, path = "b.dat", lastModified = 0L, totalSize = 2097152L, index = 0),
            FileBlock(isFile = true, fileIndex = 0, path = "a.dat", lastModified = 0L, totalSize = 3145728L, index = 2),
            FileBlock(isFile = true, fileIndex = 0, path = "a.dat", lastModified = 0L, totalSize = 3145728L, index = 0),
            FileBlock(isFile = true, fileIndex = 1, path = "b.dat", lastModified = 0L, totalSize = 2097152L, index = 1),
            FileBlock(isFile = true, fileIndex = 0, path = "a.dat", lastModified = 0L, totalSize = 3145728L, index = 1)
        )

        val sorted = blocks.sorted()
        assertEquals(0, sorted[0].fileIndex); assertEquals(0, sorted[0].index)
        assertEquals(0, sorted[1].fileIndex); assertEquals(1, sorted[1].index)
        assertEquals(0, sorted[2].fileIndex); assertEquals(2, sorted[2].index)
        assertEquals(1, sorted[3].fileIndex); assertEquals(0, sorted[3].index)
        assertEquals(1, sorted[4].fileIndex); assertEquals(1, sorted[4].index)
    }

    @Test
    fun testPriorityQueuePolling() {
        val pq = PriorityQueue<FileBlock>()
        pq.add(FileBlock(isFile = true, fileIndex = 2, path = "c", lastModified = 0L, totalSize = 0L, index = 0))
        pq.add(FileBlock(isFile = true, fileIndex = 0, path = "a", lastModified = 0L, totalSize = 0L, index = 1))
        pq.add(FileBlock(isFile = true, fileIndex = 1, path = "b", lastModified = 0L, totalSize = 0L, index = 0))
        pq.add(FileBlock(isFile = true, fileIndex = 0, path = "a", lastModified = 0L, totalSize = 0L, index = 0))

        val first = pq.poll()
        assertNotNull(first)
        assertEquals(0, first?.fileIndex); assertEquals(0, first?.index)
        val second = pq.poll()
        assertNotNull(second)
        assertEquals(0, second?.fileIndex); assertEquals(1, second?.index)
        val third = pq.poll()
        assertNotNull(third)
        assertEquals(1, third?.fileIndex); assertEquals(0, third?.index)
        val fourth = pq.poll()
        assertNotNull(fourth)
        assertEquals(2, fourth?.fileIndex); assertEquals(0, fourth?.index)
        assertTrue(pq.isEmpty())
    }

    @Test
    fun testMultiDequeMinHeadSimulation() {
        // Simulate 3 network channels receiving chunks concurrently into 3 separate deques
        val ch0 = ArrayDeque<FileBlock>().apply {
            add(FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 5242880, index = 0))
            add(FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 5242880, index = 3))
        }
        val ch1 = ArrayDeque<FileBlock>().apply {
            add(FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 5242880, index = 1))
            add(FileBlock(isFile = true, fileIndex = 1, path = "f1", lastModified = 0, totalSize = 1048576, index = 0))
        }
        val ch2 = ArrayDeque<FileBlock>().apply {
            add(FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 5242880, index = 2))
            add(FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 5242880, index = 4))
        }

        val channels = listOf(ch0, ch1, ch2)
        val outputSequence = mutableListOf<FileBlock>()

        while (channels.any { it.isNotEmpty() }) {
            var minDeque: ArrayDeque<FileBlock>? = null
            var minBlock: FileBlock? = null

            for (dq in channels) {
                val head = dq.peekFirst() ?: continue
                if (minBlock == null || head < minBlock) {
                    minBlock = head
                    minDeque = dq
                }
            }

            if (minDeque != null) {
                outputSequence.add(minDeque.pollFirst()!!)
            }
        }

        assertEquals(6, outputSequence.size)
        assertEquals(0, outputSequence[0].fileIndex); assertEquals(0, outputSequence[0].index)
        assertEquals(0, outputSequence[1].fileIndex); assertEquals(1, outputSequence[1].index)
        assertEquals(0, outputSequence[2].fileIndex); assertEquals(2, outputSequence[2].index)
        assertEquals(0, outputSequence[3].fileIndex); assertEquals(3, outputSequence[3].index)
        assertEquals(0, outputSequence[4].fileIndex); assertEquals(4, outputSequence[4].index)
        assertEquals(1, outputSequence[5].fileIndex); assertEquals(0, outputSequence[5].index)
    }

    @Test
    fun testStartPositionCalculation() {
        val b0 = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0L, totalSize = 10000000L, index = 0)
        assertEquals(0L, b0.getStartPosition())

        val b1 = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0L, totalSize = 10000000L, index = 1)
        assertEquals(1048576L, b1.getStartPosition())

        val b50 = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0L, totalSize = 100000000L, index = 50)
        assertEquals(52428800L, b50.getStartPosition())
    }

    @Test
    fun testCalcBlockCount() {
        // 0-byte file -> 1 block
        val emptyFile = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0, totalSize = 0L, index = 0)
        assertEquals(1L, emptyFile.calcBlockCount())

        // 100-byte file -> 1 block
        val smallFile = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0, totalSize = 100L, index = 0)
        assertEquals(1L, smallFile.calcBlockCount())

        // 1MB exact -> 1 block
        val exact1Mb = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0, totalSize = 1048576L, index = 0)
        assertEquals(1L, exact1Mb.calcBlockCount())

        // 1MB + 1 byte -> 2 blocks
        val over1Mb = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0, totalSize = 1048577L, index = 0)
        assertEquals(2L, over1Mb.calcBlockCount())

        // 5GB file -> 5000 * 1024 * 1024 / 1048576 = 5000 blocks (or 5 * 10^9 = 4769 blocks)
        val large5Gb = FileBlock(isFile = true, fileIndex = 0, path = "", lastModified = 0, totalSize = 5_000_000_000L, index = 0)
        assertEquals(4769L, large5Gb.calcBlockCount())
    }

    @Test
    fun testSentinelTokens() {
        assertEquals(-1, FileBlock.END_POINT.fileIndex)
        assertEquals(-1, FileBlock.END_POINT.index)
        assertEquals("END_POINT", FileBlock.END_POINT.path)

        assertEquals(-1, FileBlock.INTERRUPT.fileIndex)
        assertEquals(-1, FileBlock.INTERRUPT.index)
        assertEquals("INTERRUPT", FileBlock.INTERRUPT.path)

        assertEquals(-1, FileBlock.READ_ERROR.fileIndex)
        assertEquals(-1, FileBlock.READ_ERROR.index)
        assertEquals("READ_ERROR", FileBlock.READ_ERROR.path)

        assertEquals(-1, FileBlock.WRITE_ERROR.fileIndex)
        assertEquals(-1, FileBlock.WRITE_ERROR.index)
        assertEquals("WRITE_ERROR", FileBlock.WRITE_ERROR.path)
    }
}

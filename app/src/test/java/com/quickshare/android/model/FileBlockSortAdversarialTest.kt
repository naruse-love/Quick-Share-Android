package com.quickshare.android.model

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections
import java.util.PriorityQueue
import java.util.Random

/**
 * Adversarial and empirical stress test suite for [FileBlock] sorting,
 * priority queue ordering, and multi-channel slicing pipeline stability.
 */
class FileBlockSortAdversarialTest {

    // ==========================================
    // 1. MASSIVE RANDOM PERMUTATION STABILITY
    // ==========================================

    @Test
    fun testMassiveRandomizedListSortingStability() {
        val random = Random(1337L)
        val fileCount = 50
        val blocksPerFile = 100
        val totalBlocks = fileCount * blocksPerFile

        val originalList = mutableListOf<FileBlock>()
        for (f in 0 until fileCount) {
            for (b in 0 until blocksPerFile) {
                originalList.add(
                    FileBlock(
                        isFile = true,
                        fileIndex = f,
                        path = "file_$f.bin",
                        lastModified = 1700000000000L + f,
                        totalSize = blocksPerFile.toLong() * FileBlock.BLOCK_SIZE,
                        index = b,
                        dataLength = FileBlock.BLOCK_SIZE
                    )
                )
            }
        }

        assertEquals(totalBlocks, originalList.size)

        // Repeat shuffle and sort 10 times with different permutations
        for (round in 1..10) {
            val shuffled = ArrayList(originalList)
            shuffled.shuffle(random)

            val sorted = shuffled.sorted()
            assertEquals(totalBlocks, sorted.size)

            var lastFile = -1
            var lastIndex = -1
            for (i in 0 until totalBlocks) {
                val block = sorted[i]
                if (block.fileIndex == lastFile) {
                    assertEquals(
                        "Round $round: In-file slice monotonicity violated at item $i",
                        lastIndex + 1,
                        block.index
                    )
                } else {
                    assertEquals(
                        "Round $round: File order monotonicity violated at item $i",
                        lastFile + 1,
                        block.fileIndex
                    )
                    assertEquals(
                        "Round $round: First slice of new file must be 0 at item $i",
                        0,
                        block.index
                    )
                }
                lastFile = block.fileIndex
                lastIndex = block.index
            }
        }
    }

    // ==========================================
    // 2. PRIORITY QUEUE EXTRACT-ORDER INVARIANT
    // ==========================================

    @Test
    fun testPriorityQueueConcurrentStyleInterleaving() {
        val random = Random(9999L)
        val pq = PriorityQueue<FileBlock>()

        val expectedSequence = mutableListOf<FileBlock>()
        for (f in 0 until 20) {
            for (b in 0 until 50) {
                expectedSequence.add(
                    FileBlock(
                        isFile = true,
                        fileIndex = f,
                        path = "file_$f.dat",
                        lastModified = 0L,
                        totalSize = 50L * FileBlock.BLOCK_SIZE,
                        index = b
                    )
                )
            }
        }

        // Shuffle all blocks
        val inputList = ArrayList(expectedSequence)
        inputList.shuffle(random)

        // Push all into PriorityQueue
        for (block in inputList) {
            pq.add(block)
        }

        // Poll one by one and check against strictly ordered expected sequence
        val outputSequence = mutableListOf<FileBlock>()
        while (pq.isNotEmpty()) {
            outputSequence.add(pq.poll()!!)
        }

        assertEquals(expectedSequence.size, outputSequence.size)
        for (i in expectedSequence.indices) {
            assertEquals(
                "PQ extraction mismatch at index $i",
                expectedSequence[i].fileIndex,
                outputSequence[i].fileIndex
            )
            assertEquals(
                "PQ extraction mismatch at index $i",
                expectedSequence[i].index,
                outputSequence[i].index
            )
        }
    }

    // ==========================================
    // 3. MULTI-CHANNEL REORDERING SIMULATION (2, 4, 8, 16, 32 CHANNELS)
    // ==========================================

    @Test
    fun testMultiChannelReorderingAcrossVariousChannelCounts() {
        val channelConfigs = listOf(1, 2, 4, 8, 16, 32)
        val random = Random(777L)

        for (numChannels in channelConfigs) {
            val totalFiles = 10
            val chunksPerFile = 30
            val totalBlocks = totalFiles * chunksPerFile

            // Create all blocks
            val allBlocks = mutableListOf<FileBlock>()
            for (f in 0 until totalFiles) {
                for (c in 0 until chunksPerFile) {
                    allBlocks.add(
                        FileBlock(
                            isFile = true,
                            fileIndex = f,
                            path = "stream_$f.mkv",
                            lastModified = 0L,
                            totalSize = chunksPerFile.toLong() * FileBlock.BLOCK_SIZE,
                            index = c
                        )
                    )
                }
            }

            // Distribute blocks across channels (simulating round-robin or dynamic socket assignment)
            val channels = List(numChannels) { ArrayDeque<FileBlock>() }
            for (i in 0 until totalBlocks) {
                val targetChannel = i % numChannels
                channels[targetChannel].add(allBlocks[i])
            }

            // Multi-queue Min-Head Assembler simulation (matching WriteFileCall logic)
            val assembledList = mutableListOf<FileBlock>()
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

                assertNotNull("Min deque must not be null when items remain", minDeque)
                assembledList.add(minDeque!!.pollFirst()!!)
            }

            assertEquals("Channel count $numChannels output size mismatch", totalBlocks, assembledList.size)
            for (i in 0 until totalBlocks) {
                val expectedFile = i / chunksPerFile
                val expectedChunk = i % chunksPerFile
                assertEquals("Channel count $numChannels at index $i fileIndex mismatch", expectedFile, assembledList[i].fileIndex)
                assertEquals("Channel count $numChannels at index $i chunkIndex mismatch", expectedChunk, assembledList[i].index)
            }
        }
    }

    // ==========================================
    // 4. BOUNDARY VALUES & 64-BIT OFFSET OVERFLOW TESTS
    // ==========================================

    @Test
    fun testStartPositionNo32BitIntegerOverflow() {
        // Test index = 2048 (2048 * 1MB = 2,147,483,648 bytes, which exceeds Int.MAX_VALUE = 2,147,483,647)
        val b2048 = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "huge.bin",
            lastModified = 0L,
            totalSize = 100L * 1024 * 1024 * 1024, // 100 GB
            index = 2048
        )
        val expectedPos2048 = 2048L * 1024 * 1024 // 2,147,483,648L
        assertEquals(expectedPos2048, b2048.getStartPosition())
        assertTrue("getStartPosition must be > 0 and not overflow to negative", b2048.getStartPosition() > 0)

        // Test index = 100,000 (100,000 * 1MB = 104,857,600,000 bytes ~ 100GB)
        val b100k = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "huge.bin",
            lastModified = 0L,
            totalSize = 100L * 1024 * 1024 * 1024,
            index = 100000
        )
        val expectedPos100k = 100000L * 1024 * 1024
        assertEquals(expectedPos100k, b100k.getStartPosition())
    }

    @Test
    fun testCalcBlockCountBoundaries() {
        fun makeBlock(size: Long) = FileBlock(
            isFile = true,
            fileIndex = 0,
            path = "",
            lastModified = 0L,
            totalSize = size,
            index = 0
        )

        assertEquals(1L, makeBlock(0L).calcBlockCount())
        assertEquals(1L, makeBlock(1L).calcBlockCount())
        assertEquals(1L, makeBlock(1048575L).calcBlockCount()) // 1MB - 1
        assertEquals(1L, makeBlock(1048576L).calcBlockCount()) // 1MB exact
        assertEquals(2L, makeBlock(1048577L).calcBlockCount()) // 1MB + 1
        assertEquals(10L, makeBlock(10L * 1048576L).calcBlockCount())
        assertEquals(11L, makeBlock(10L * 1048576L + 1).calcBlockCount())
    }

    @Test
    fun testSentinelOrdering() {
        val normal0 = FileBlock(isFile = true, fileIndex = 0, path = "f0", lastModified = 0, totalSize = 0, index = 0)
        val normal1 = FileBlock(isFile = true, fileIndex = 1, path = "f1", lastModified = 0, totalSize = 0, index = 0)

        // Sentinels have fileIndex = -1, index = -1
        assertTrue(FileBlock.END_POINT < normal0)
        assertTrue(FileBlock.END_POINT < normal1)
        assertTrue(FileBlock.INTERRUPT < normal0)
        assertTrue(FileBlock.READ_ERROR < normal0)
        assertTrue(FileBlock.WRITE_ERROR < normal0)

        // Equal sentinels comparison
        assertEquals(0, FileBlock.END_POINT.compareTo(FileBlock.INTERRUPT))
    }
}

package com.andreacovelli.whispertranscriber.media

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EncodedSampleQueueTest {
    @Test
    fun commit_splitsAppendedBytesIntoAccessUnits() {
        val queue = EncodedSampleQueue()
        queue.appendBytes(byteArrayOf(1, 2, 3, 4, 5))

        queue.commit(timeUs = 0L, size = 2, offset = 3)
        queue.appendBytes(byteArrayOf(6, 7))
        queue.commit(timeUs = 20L, size = 5, offset = 0)

        assertArrayEquals(byteArrayOf(1, 2), queue.takeHead(expectedTimeUs = 0L))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), queue.takeHead(expectedTimeUs = 20L))
        assertNull(queue.head)
    }

    @Test
    fun commit_excludesBytesAlreadyWrittenForTheNextAccessUnit() {
        val queue = EncodedSampleQueue()
        queue.appendBytes(byteArrayOf(1, 2, 3, 9, 9))

        // The extractor wrote two bytes of the next unit before committing this one.
        queue.commit(timeUs = 0L, size = 3, offset = 2)
        queue.appendBytes(byteArrayOf(9))
        queue.commit(timeUs = 10L, size = 3, offset = 0)

        assertArrayEquals(byteArrayOf(1, 2, 3), queue.takeHead(expectedTimeUs = 0L))
        assertArrayEquals(byteArrayOf(9, 9, 9), queue.takeHead(expectedTimeUs = 10L))
    }

    @Test
    fun append_keepsUnconsumedUnitsIntactWhileTheBufferCompactsAndGrows() {
        val queue = EncodedSampleQueue(maximumBufferedBytes = 64)
        var nextValue = 0
        var expectedValue = 0
        repeat(200) { index ->
            val unit = ByteArray(7) { (nextValue++).toByte() }
            queue.appendBytes(unit)
            queue.commit(timeUs = index.toLong(), size = unit.size, offset = 0)
            // Consume the previous unit only, so compaction always has a waiting unit to move.
            if (index > 0) {
                val consumed = queue.takeHead(expectedTimeUs = index - 1L)
                assertArrayEquals(ByteArray(7) { (expectedValue++).toByte() }, consumed)
            }
        }
    }

    @Test
    fun append_rejectsAnAccessUnitLargerThanTheBufferLimit() {
        val queue = EncodedSampleQueue(maximumBufferedBytes = 16)
        queue.appendBytes(ByteArray(10))

        assertThrows(IOException::class.java) { queue.appendBytes(ByteArray(10)) }
    }

    @Test
    fun commit_rejectsBytesThatWereNeverAppended() {
        val queue = EncodedSampleQueue()
        queue.appendBytes(ByteArray(4))

        assertThrows(IllegalStateException::class.java) { queue.commit(timeUs = 0L, size = 5, offset = 0) }
    }

    @Test
    fun commit_ignoresEmptyAccessUnits() {
        val queue = EncodedSampleQueue()

        queue.commit(timeUs = 0L, size = 0, offset = 0)

        assertNull(queue.head)
    }

    private fun EncodedSampleQueue.appendBytes(bytes: ByteArray) {
        val written = append(bytes.size) { array, offset, length ->
            bytes.copyInto(array, offset, 0, length)
            length
        }
        assertEquals(bytes.size, written)
    }

    private fun EncodedSampleQueue.takeHead(expectedTimeUs: Long): ByteArray {
        val sample = requireNotNull(head)
        assertEquals(expectedTimeUs, sample.timeUs)
        val buffer = ByteBuffer.allocate(sample.size)
        copyHead(buffer)
        removeHead()
        return buffer.array()
    }
}

package com.andreacovelli.whispertranscriber.media

import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Encoded bytes written by an extractor, split into access units as their metadata arrives. An extractor may
 * already have written the start of the next access unit when it commits one, which the commit offset counts.
 */
internal class EncodedSampleQueue(private val maximumBufferedBytes: Int = DEFAULT_MAXIMUM_BUFFERED_BYTES) {
    class Sample(val timeUs: Long, val size: Int, val start: Long)

    private var data = ByteArray(min(INITIAL_CAPACITY, maximumBufferedBytes))

    /** Stream positions count every byte ever appended; [dataStart] is the position of data[0]. */
    private var dataStart = 0L
    private var consumedEnd = 0L
    private var writeEnd = 0L
    private var committedEnd = 0L
    private val samples = ArrayDeque<Sample>()

    /** The oldest committed access unit that has not been removed, or null if none is waiting. */
    val head: Sample? get() = samples.firstOrNull()

    /**
     * Lets [fill] write at most [length] bytes after the bytes already appended, and returns what it returned:
     * the number of bytes written, or a negative value if it wrote none.
     */
    fun append(length: Int, fill: (array: ByteArray, offset: Int, length: Int) -> Int): Int {
        ensureWritable(length)
        val written = fill(data, (writeEnd - dataStart).toInt(), length)
        if (written > 0) writeEnd += written
        return written
    }

    /** Marks [size] bytes ending [offset] bytes before the end of the appended data as one access unit. */
    fun commit(timeUs: Long, size: Int, offset: Int) {
        val start = writeEnd - offset - size
        check(size >= 0 && offset >= 0 && start >= committedEnd) { "Access unit overlaps data already committed" }
        if (size == 0) return
        samples.addLast(Sample(timeUs, size, start))
        committedEnd = start + size
    }

    /** Copies the head access unit into [destination] at its position. */
    fun copyHead(destination: ByteBuffer) {
        val sample = checkNotNull(head)
        destination.put(data, (sample.start - dataStart).toInt(), sample.size)
    }

    fun removeHead() {
        val sample = samples.removeFirst()
        consumedEnd = sample.start + sample.size
    }

    private fun ensureWritable(length: Int) {
        val writeIndex = (writeEnd - dataStart).toInt()
        if (length <= data.size - writeIndex) return
        val retainedBytes = (writeEnd - consumedEnd).toInt()
        val requiredBytes = retainedBytes.toLong() + length
        if (requiredBytes > maximumBufferedBytes) {
            throw IOException("An access unit exceeds $maximumBufferedBytes buffered bytes")
        }
        val target = if (requiredBytes > data.size) {
            ByteArray(max(requiredBytes, data.size * 2L).coerceAtMost(maximumBufferedBytes.toLong()).toInt())
        } else {
            data
        }
        // Consumed bytes are dropped; the rest moves to the start of the array.
        System.arraycopy(data, (consumedEnd - dataStart).toInt(), target, 0, retainedBytes)
        data = target
        dataStart = consumedEnd
    }

    private companion object {
        const val INITIAL_CAPACITY = 256 * 1024

        // Audio access units are kilobytes; anything near this size is a damaged or hostile container.
        const val DEFAULT_MAXIMUM_BUFFERED_BYTES = 16 * 1024 * 1024
    }
}

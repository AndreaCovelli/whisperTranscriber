package com.andreacovelli.whispertranscriber.media

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A half-open range of samples, `[startSample, endSample)`, in a 16 kHz mono PCM16 WAV file. */
internal data class AudioChunk(val startSample: Long, val endSample: Long) {
    val sampleCount: Long get() = endSample - startSample
}

private const val CHUNK_MICROSECONDS_PER_SECOND = 1_000_000L

internal fun samplesForDurationUs(durationUs: Long): Long =
    durationUs * CONVERTED_AUDIO_SAMPLE_RATE / CHUNK_MICROSECONDS_PER_SECOND

/** Energy frames are 100 ms; a cut lands in the middle of the quietest 500 ms stretch. */
internal const val CHUNK_ENERGY_FRAME_SAMPLES = CONVERTED_AUDIO_SAMPLE_RATE / 10
private const val QUIET_STRETCH_FRAMES = 5

/**
 * Splits [totalSamples] into the fewest balanced chunks of at most [maxChunkSamples]. Each cut is placed at
 * the quietest point within [searchWindowSamples] of its ideal position, so words are rarely split.
 * [frameEnergies] returns one energy value per [CHUNK_ENERGY_FRAME_SAMPLES] frame from its start sample.
 */
internal fun planAudioChunks(
    totalSamples: Long,
    maxChunkSamples: Long,
    searchWindowSamples: Long,
    frameEnergies: (startSample: Long, endSample: Long) -> FloatArray,
): List<AudioChunk> {
    require(totalSamples > 0)
    require(searchWindowSamples >= 0 && maxChunkSamples > 2 * searchWindowSamples)
    if (totalSamples <= maxChunkSamples) return listOf(AudioChunk(0, totalSamples))

    // Shrinking the target by the window keeps the final chunk, which can only grow by one window, in bounds.
    val effectiveChunkSamples = maxChunkSamples - searchWindowSamples
    val chunkCount = ((totalSamples + effectiveChunkSamples - 1) / effectiveChunkSamples).toInt()
    val chunks = ArrayList<AudioChunk>(chunkCount)
    var previousCut = 0L
    for (index in 1 until chunkCount) {
        val idealCut = totalSamples * index / chunkCount
        val lowest = maxOf(idealCut - searchWindowSamples, previousCut + 1)
        val highest = minOf(idealCut + searchWindowSamples, previousCut + maxChunkSamples, totalSamples - 1)
        val cut = if (lowest >= highest) {
            idealCut.coerceIn(previousCut + 1, totalSamples - 1)
        } else {
            quietestCut(lowest, highest, frameEnergies(lowest, highest))
        }
        chunks += AudioChunk(previousCut, cut)
        previousCut = cut
    }
    chunks += AudioChunk(previousCut, totalSamples)
    return chunks
}

internal fun quietestCut(lowest: Long, highest: Long, energies: FloatArray): Long {
    if (energies.isEmpty()) return (lowest + highest) / 2
    val stretch = minOf(QUIET_STRETCH_FRAMES, energies.size)
    var windowSum = 0.0
    for (frame in 0 until stretch) windowSum += energies[frame]
    var bestSum = windowSum
    var bestStart = 0
    for (frame in stretch until energies.size) {
        windowSum += energies[frame] - energies[frame - stretch]
        if (windowSum < bestSum) {
            bestSum = windowSum
            bestStart = frame - stretch + 1
        }
    }
    val middleSample = lowest + bestStart.toLong() * CHUNK_ENERGY_FRAME_SAMPLES +
        stretch.toLong() * CHUNK_ENERGY_FRAME_SAMPLES / 2
    return middleSample.coerceIn(lowest, highest)
}

/** Reads the canonical 44-byte-header WAV files written by [Pcm16WavWriter]. */
internal class Pcm16MonoWavFile(private val file: File) {
    val totalSamples: Long = ((file.length() - WAV_DATA_OFFSET).coerceAtLeast(0)) / PCM16_BYTES_PER_SAMPLE

    /** Mean squared amplitude per [CHUNK_ENERGY_FRAME_SAMPLES] frame in `[startSample, endSample)`. */
    fun frameEnergies(startSample: Long, endSample: Long): FloatArray {
        require(startSample in 0..endSample && endSample <= totalSamples)
        val frameCount = ((endSample - startSample) / CHUNK_ENERGY_FRAME_SAMPLES).toInt()
        val energies = FloatArray(frameCount)
        val frameBytes = ByteArray(CHUNK_ENERGY_FRAME_SAMPLES * PCM16_BYTES_PER_SAMPLE)
        val samples = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(file, "r").use { input ->
            input.seek(WAV_DATA_OFFSET + startSample * PCM16_BYTES_PER_SAMPLE)
            for (frame in 0 until frameCount) {
                input.readFully(frameBytes)
                var sum = 0.0
                for (index in 0 until CHUNK_ENERGY_FRAME_SAMPLES) {
                    val sample = samples.getShort(index * PCM16_BYTES_PER_SAMPLE).toDouble()
                    sum += sample * sample
                }
                energies[frame] = (sum / CHUNK_ENERGY_FRAME_SAMPLES).toFloat()
            }
        }
        return energies
    }

    /** Copies [chunk] into [writer], which must be a fresh writer for the chunk's output file. */
    fun copyChunk(chunk: AudioChunk, writer: Pcm16WavWriter) {
        require(chunk.startSample in 0 until chunk.endSample && chunk.endSample <= totalSamples)
        val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
        RandomAccessFile(file, "r").use { input ->
            val channel = input.channel
            var position = WAV_DATA_OFFSET + chunk.startSample * PCM16_BYTES_PER_SAMPLE
            var remaining = chunk.sampleCount * PCM16_BYTES_PER_SAMPLE
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(remaining, COPY_BUFFER_BYTES.toLong()).toInt())
                val read = channel.read(buffer, position)
                if (read <= 0) throw EOFException("WAV chunk ends before its data")
                if (read % PCM16_BYTES_PER_SAMPLE != 0) throw IOException("Partial PCM16 sample read")
                buffer.flip()
                writer.writePcm16Buffer(buffer, read)
                position += read
                remaining -= read
            }
        }
    }

    private companion object {
        const val WAV_DATA_OFFSET = 44L
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}

package com.andreacovelli.whispertranscriber.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkingTest {
    private val second = CONVERTED_AUDIO_SAMPLE_RATE.toLong()
    private val minute = 60 * second

    @Test
    fun planAudioChunks_keepsRecordingWithinLimitWhole() {
        val chunks = planAudioChunks(
            totalSamples = 50 * minute,
            maxChunkSamples = 60 * minute,
            searchWindowSamples = 2 * minute,
        ) { _, _ -> error("No cut should be searched") }

        assertEquals(listOf(AudioChunk(0, 50 * minute)), chunks)
    }

    @Test
    fun planAudioChunks_coversRecordingWithBalancedChunksWithinLimit() {
        val total = 250 * minute + 17 * second
        val maxChunk = 60 * minute

        val chunks = planAudioChunks(total, maxChunk, 2 * minute) { start, end ->
            FloatArray(((end - start) / CHUNK_ENERGY_FRAME_SAMPLES).toInt()) { 1f }
        }

        assertEquals(5, chunks.size)
        assertEquals(0L, chunks.first().startSample)
        assertEquals(total, chunks.last().endSample)
        chunks.zipWithNext().forEach { (previous, next) -> assertEquals(previous.endSample, next.startSample) }
        chunks.forEach { chunk -> assertTrue("$chunk exceeds the limit", chunk.sampleCount in 1..maxChunk) }
    }

    @Test
    fun planAudioChunks_neverExceedsLimitWhenEveryCutLandsLate() {
        val total = 119 * minute
        val maxChunk = 60 * minute

        // A silent last frame pulls every cut to the end of its search window.
        val chunks = planAudioChunks(total, maxChunk, 2 * minute) { start, end ->
            val frames = ((end - start) / CHUNK_ENERGY_FRAME_SAMPLES).toInt()
            FloatArray(frames) { frame -> if (frame >= frames - 5) 0f else 1f }
        }

        chunks.forEach { chunk -> assertTrue("$chunk exceeds the limit", chunk.sampleCount <= maxChunk) }
        assertEquals(total, chunks.last().endSample)
    }

    @Test
    fun planAudioChunks_cutsInsideThePause() {
        val total = 100 * minute
        val pauseStart = 49 * minute + 30 * second
        val pauseEnd = pauseStart + 2 * second

        val chunks = planAudioChunks(total, 60 * minute, 2 * minute) { start, end ->
            val frames = ((end - start) / CHUNK_ENERGY_FRAME_SAMPLES).toInt()
            FloatArray(frames) { frame ->
                val sample = start + frame.toLong() * CHUNK_ENERGY_FRAME_SAMPLES
                if (sample in pauseStart until pauseEnd) 0f else 1f
            }
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks.first().endSample in pauseStart until pauseEnd)
    }

    @Test
    fun quietestCut_returnsMiddleOfQuietestStretch() {
        val energies = floatArrayOf(5f, 5f, 5f, 0f, 0f, 0f, 0f, 0f, 5f, 5f)

        val cut = quietestCut(lowest = 1_000, highest = 1_000 + 10L * CHUNK_ENERGY_FRAME_SAMPLES, energies = energies)

        assertEquals(1_000 + 3L * CHUNK_ENERGY_FRAME_SAMPLES + 5L * CHUNK_ENERGY_FRAME_SAMPLES / 2, cut)
    }

    @Test
    fun pcm16MonoWavFile_copiesChunkAndMeasuresFrameEnergy() {
        val source = File.createTempFile("chunk-source", ".wav")
        val output = File.createTempFile("chunk-output", ".wav")
        try {
            val samples = ShortArray(4 * CHUNK_ENERGY_FRAME_SAMPLES) { index ->
                if (index < 2 * CHUNK_ENERGY_FRAME_SAMPLES) 0 else (index % 100).toShort()
            }
            writeWav(source, samples)
            val wav = Pcm16MonoWavFile(source)

            val energies = wav.frameEnergies(0, wav.totalSamples)
            Pcm16WavWriter(output, 3L * CHUNK_ENERGY_FRAME_SAMPLES * PCM16_BYTES_PER_SAMPLE).use { writer ->
                wav.copyChunk(AudioChunk(CHUNK_ENERGY_FRAME_SAMPLES.toLong(), 4L * CHUNK_ENERGY_FRAME_SAMPLES), writer)
                writer.finish()
            }

            assertEquals(samples.size.toLong(), wav.totalSamples)
            assertEquals(0f, energies[0])
            assertEquals(0f, energies[1])
            assertTrue(energies[2] > 0f)
            assertArrayEquals(samples.copyOfRange(CHUNK_ENERGY_FRAME_SAMPLES, samples.size), readSamples(output))
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        Pcm16WavWriter(file).use { writer ->
            val buffer = ByteBuffer.allocate(samples.size * PCM16_BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(buffer::putShort)
            buffer.flip()
            writer.writePcm16Buffer(buffer, samples.size * PCM16_BYTES_PER_SAMPLE)
            writer.finish()
        }
    }

    private fun readSamples(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - 44) / PCM16_BYTES_PER_SAMPLE) { buffer.getShort() }
    }
}

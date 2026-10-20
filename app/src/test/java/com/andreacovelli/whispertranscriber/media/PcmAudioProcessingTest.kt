package com.andreacovelli.whispertranscriber.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioProcessingTest {
    @Test
    fun monoResampler_preservesSpeechBandTone() {
        val output = resampleSine(
            frequencyHz = 3_000.0,
            inputSampleRate = 48_000,
            outputSampleRate = 16_000,
        )

        assertTrue(rms(output) in 0.55..0.58)
    }

    @Test
    fun monoResampler_rejectsToneAboveOutputNyquist() {
        val output = resampleSine(
            frequencyHz = 9_000.0,
            inputSampleRate = 48_000,
            outputSampleRate = 16_000,
        )

        assertTrue("Aliased output RMS was ${rms(output)}", rms(output) < 0.001)
    }

    @Test
    fun monoResampler_preservesDurationAcrossChunkBoundaries() {
        val input = FloatArray(44_100) { index ->
            sin(2.0 * PI * 440.0 * index / 44_100.0).toFloat()
        }

        val output = resample(input, inputSampleRate = 44_100, outputSampleRate = 16_000, chunkSize = 337)

        assertEquals(16_000, output.size)
    }

    @Test
    fun monoDownmixer_prioritizesCenterSpeechAndExcludesLfe() {
        val destination = FloatArray(2)
        val source = floatArrayOf(
            0f,
            0f,
            1f,
            1f,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
            0f,
        )

        PcmMonoDownmixer.mixFloatToMono(
            source = source,
            frames = 2,
            sourceChannels = 6,
            channelMask = PcmChannelMask.SURROUND_5_1,
            destination = destination,
        )

        assertEquals(0.9f * PcmMonoDownmixer.CENTER_GAIN, destination[0], 0.0001f)
        assertEquals(0f, destination[1], 0.0001f)
    }

    @Test
    fun monoDownmixer_acceptsChannelPositionsAddedByNewerAndroidVersions() {
        val gains = PcmMonoDownmixer.createChannelGains(
            sourceChannels = 2,
            channelMask = 0x4 or 0x10_0000,
        )

        assertEquals(0.5f, gains[0], 0.0001f)
        assertEquals(0.25f, gains[1], 0.0001f)
    }

    @Test
    fun wavWriter_rejectsEmptyAndOversizedPcmPayloads() {
        val emptyFile = File.createTempFile("empty_pcm_", ".wav")
        val boundedFile = File.createTempFile("bounded_pcm_", ".wav")
        try {
            Pcm16WavWriter(emptyFile).use { writer ->
                assertThrows(IOException::class.java, writer::finish)
            }

            Pcm16WavWriter(boundedFile, maximumDataBytes = 4).use { writer ->
                writer.writePcm16Buffer(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(1)
                        putShort(2)
                        flip()
                    },
                    byteCount = 4,
                )
                assertThrows(IOException::class.java) {
                    writer.writePcm16Buffer(
                        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putShort(3)
                            flip()
                        },
                        byteCount = 2,
                    )
                }
            }
        } finally {
            emptyFile.delete()
            boundedFile.delete()
        }
    }

    @Test
    fun wavWriter_finalizesHeaderAndExactOutputLength() {
        val outputFile = File.createTempFile("finalized_pcm_", ".wav")
        try {
            val durationUs = Pcm16WavWriter(outputFile).use { writer ->
                writer.writePcm16Buffer(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(1)
                        putShort(2)
                        flip()
                    },
                    byteCount = 4,
                )
                writer.finish()
            }

            RandomAccessFile(outputFile, "r").use { input ->
                assertEquals(125L, durationUs)
                assertEquals(48L, input.length())
                input.seek(4)
                assertEquals(40, Integer.reverseBytes(input.readInt()))
                input.seek(40)
                assertEquals(4, Integer.reverseBytes(input.readInt()))
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun wavWriter_writesNanSamplesAsSilence() {
        val outputFile = File.createTempFile("nan_pcm_", ".wav")
        try {
            Pcm16WavWriter(outputFile).use { writer ->
                writer.writeMonoFloats(floatArrayOf(Float.NaN, 0.5f, -2f), sampleCount = 3)
                writer.finish()
            }

            RandomAccessFile(outputFile, "r").use { input ->
                input.seek(44)
                assertEquals(0, java.lang.Short.reverseBytes(input.readShort()).toInt())
                assertEquals(16_384, java.lang.Short.reverseBytes(input.readShort()).toInt())
                assertEquals(-32_767, java.lang.Short.reverseBytes(input.readShort()).toInt())
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun integerSampleReader_normalizes8BitUnsignedSamples() {
        val samples = readIntegerSamples(PcmIntegerSampleReader.ENCODING_PCM_8BIT, byteArrayOf(0, 0x80.toByte(), -1))

        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128f), samples, 0f)
    }

    @Test
    fun integerSampleReader_normalizesSignExtended24BitPackedSamples() {
        val samples = readIntegerSamples(
            PcmIntegerSampleReader.ENCODING_PCM_24BIT_PACKED,
            byteArrayOf(0, 0, 0x80.toByte(), -1, -1, 0x7f, 0, 0x40, 0),
        )

        assertArrayEquals(floatArrayOf(-1f, 8_388_607f / 8_388_608f, 16_384f / 8_388_608f), samples, 0f)
    }

    @Test
    fun integerSampleReader_normalizes32BitSamplesAndAdvancesBuffer() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(Int.MIN_VALUE)
            putInt(1 shl 30)
            flip()
        }
        val samples = FloatArray(2)

        PcmIntegerSampleReader.readAsFloat(buffer, PcmIntegerSampleReader.ENCODING_PCM_32BIT, samples, 2)

        assertArrayEquals(floatArrayOf(-1f, 0.5f), samples, 0f)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun maximumPcmDataBytesForDuration_keepsStrictBoundaryBelowLimit() {
        val twoHoursUs = 2L * 60L * 60L * 1_000_000L

        assertEquals(
            230_399_998L,
            maximumPcm16MonoDataBytesForDurationExclusive(twoHoursUs),
        )
    }

    private fun readIntegerSamples(encoding: Int, bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / requireNotNull(PcmIntegerSampleReader.bytesPerSample(encoding)))
        PcmIntegerSampleReader.readAsFloat(
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            encoding,
            samples,
            samples.size,
        )
        return samples
    }

    private fun resampleSine(frequencyHz: Double, inputSampleRate: Int, outputSampleRate: Int): FloatArray {
        val input = FloatArray(inputSampleRate) { index ->
            (0.8 * sin(2.0 * PI * frequencyHz * index / inputSampleRate)).toFloat()
        }
        return resample(input, inputSampleRate, outputSampleRate, chunkSize = 1_003)
    }

    private fun resample(input: FloatArray, inputSampleRate: Int, outputSampleRate: Int, chunkSize: Int): FloatArray {
        val resampler = MonoPcmResampler(inputSampleRate, outputSampleRate)
        val output = ArrayList<Float>()
        var inputOffset = 0
        while (inputOffset < input.size) {
            val count = minOf(chunkSize, input.size - inputOffset)
            val chunk = input.copyOfRange(inputOffset, inputOffset + count)
            val outputCount = resampler.process(chunk, count)
            repeat(outputCount) { index -> output.add(resampler.outputBuffer[index]) }
            inputOffset += count
        }
        val flushCount = resampler.finish()
        repeat(flushCount) { index -> output.add(resampler.outputBuffer[index]) }
        return output.toFloatArray()
    }

    private fun rms(samples: FloatArray): Double {
        val stableSamples = samples.drop(256).dropLast(256)
        return kotlin.math.sqrt(stableSamples.sumOf { sample -> sample.toDouble() * sample } / stableSamples.size)
    }
}

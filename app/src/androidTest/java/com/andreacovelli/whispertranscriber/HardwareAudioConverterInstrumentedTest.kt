package com.andreacovelli.whispertranscriber

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareAudioConverterInstrumentedTest {

    private companion object {
        private const val OUTPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_CHANNELS = 1
        private const val AAC_INPUT_SAMPLE_RATE = 48_000
        private const val AAC_INPUT_CHANNELS = 2
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun convertTo16kMonoWav_convertsAacM4aInputTo16kMonoWav() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".m4a", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            val pcmData = generatePcm16SineWave(
                sampleRate = AAC_INPUT_SAMPLE_RATE,
                channelCount = AAC_INPUT_CHANNELS,
                durationMs = 600,
            )
            writeAacM4a(file = inputFile, pcm16 = pcmData)

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
            )
            val wavInfo = readWavInfo(outputFile)

            assertEquals(OUTPUT_SAMPLE_RATE, result.sampleRate)
            assertEquals(OUTPUT_CHANNELS, result.channelCount)
            assertTrue(result.durationUs > 0)
            assertEquals(OUTPUT_SAMPLE_RATE, wavInfo.sampleRate)
            assertEquals(OUTPUT_CHANNELS, wavInfo.channelCount)
            assertEquals(16, wavInfo.bitsPerSample)
            assertTrue(wavInfo.pcmData.isNotEmpty())
            assertEquals(wavInfo.durationUs, result.durationUs)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_preservesMono16kPcmPayload() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".wav", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            val pcmData =
                generatePcm16SineWave(sampleRate = OUTPUT_SAMPLE_RATE, channelCount = OUTPUT_CHANNELS, durationMs = 500)
            writePcm16Wav(file = inputFile, pcm16 = pcmData)

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
            )
            val wavInfo = readWavInfo(outputFile)

            assertEquals(OUTPUT_SAMPLE_RATE, result.sampleRate)
            assertEquals(OUTPUT_CHANNELS, result.channelCount)
            assertEquals(OUTPUT_SAMPLE_RATE, wavInfo.sampleRate)
            assertEquals(OUTPUT_CHANNELS, wavInfo.channelCount)
            assertEquals(16, wavInfo.bitsPerSample)
            assertArrayEquals(pcmData, wavInfo.pcmData)
            assertEquals(wavInfo.durationUs, result.durationUs)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    private fun generatePcm16SineWave(sampleRate: Int, channelCount: Int, durationMs: Int): ByteArray {
        val frameCount = sampleRate * durationMs / 1000
        val buffer = ByteBuffer.allocate(frameCount * channelCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / sampleRate.toDouble()
            for (channel in 0 until channelCount) {
                val frequency = if (channel == 0) 440.0 else 660.0
                val amplitude = if (channel == 0) 0.75 else 0.25
                val sample = (sin(2.0 * PI * frequency * time) * amplitude * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer.putShort(sample.toShort())
            }
        }
        return buffer.array()
    }

    private fun writePcm16Wav(file: File, pcm16: ByteArray) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm16.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(OUTPUT_CHANNELS.toShort())
            putInt(OUTPUT_SAMPLE_RATE)
            putInt(OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2)
            putShort((OUTPUT_CHANNELS * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm16.size)
        }.array()
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm16)
        }
    }

    private fun writeAacM4a(file: File, pcm16: ByteArray) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AAC_INPUT_SAMPLE_RATE,
            AAC_INPUT_CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val bytesPerFrame = AAC_INPUT_CHANNELS * 2
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputOffset = 0
            var inputDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(encoder.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val remaining = pcm16.size - inputOffset
                        if (remaining > 0) {
                            val chunkSize = minOf(inputBuffer.capacity(), remaining)
                            inputBuffer.put(pcm16, inputOffset, chunkSize)
                            val presentationTimeUs =
                                ((inputOffset / bytesPerFrame).toLong() * 1_000_000L) / AAC_INPUT_SAMPLE_RATE.toLong()
                            encoder.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                            inputOffset += chunkSize
                        } else {
                            val endPresentationTimeUs =
                                ((pcm16.size / bytesPerFrame).toLong() * 1_000_000L) / AAC_INPUT_SAMPLE_RATE.toLong()
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                endPresentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = requireNotNull(encoder.getOutputBuffer(outputIndex))
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun readWavInfo(file: File): WavInfo {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV file too small: ${bytes.size}" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return WavInfo(
            channelCount = header.getShort(22).toInt(),
            sampleRate = header.getInt(24),
            bitsPerSample = header.getShort(34).toInt(),
            pcmData = bytes.copyOfRange(44, bytes.size),
        )
    }

    private class WavInfo(val channelCount: Int, val sampleRate: Int, val bitsPerSample: Int, val pcmData: ByteArray) {
        val durationUs: Long
            get() = pcmData.size.toLong() / (channelCount * 2) * 1_000_000L / sampleRate
    }
}

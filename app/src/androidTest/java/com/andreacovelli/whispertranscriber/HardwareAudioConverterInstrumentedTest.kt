package com.andreacovelli.whispertranscriber

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareAudioConverterInstrumentedTest {

    private companion object {
        private const val OUTPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_CHANNELS = 1
        private const val AAC_INPUT_SAMPLE_RATE = 48_000
        private const val AAC_INPUT_CHANNELS = 2
        private const val OPUS_INPUT_SAMPLE_RATE = 48_000
        private const val AMR_INPUT_SAMPLE_RATE = 8_000
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
                ioDispatcher = Dispatchers.IO,
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
                ioDispatcher = Dispatchers.IO,
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

    @Test
    fun convertTo16kMonoWav_converts24BitStereoWavInput() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".wav", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            writePcm24StereoWav(file = inputFile, sampleRate = AAC_INPUT_SAMPLE_RATE, durationMs = 500)

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
            )
            val wavInfo = readWavInfo(outputFile)
            val samples = ByteBuffer.wrap(wavInfo.pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val peak = (0 until samples.limit()).maxOf { index -> abs(samples.get(index).toInt()) }

            assertEquals(OUTPUT_SAMPLE_RATE, wavInfo.sampleRate)
            assertEquals(OUTPUT_CHANNELS, wavInfo.channelCount)
            assertEquals(500_000L, result.durationUs)
            assertTrue("Converted 24-bit audio peak was $peak", peak > Short.MAX_VALUE / 4)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_keepsEverySecondOfLongAacInputInOrder() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".m4a", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            // Several seconds span many batched decoder buffers; each second is louder than the last.
            val amplitudes = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8)
            writeAacM4a(
                file = inputFile,
                pcm16 = generateSteppedSine(AAC_INPUT_SAMPLE_RATE, AAC_INPUT_CHANNELS, amplitudes),
            )

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
            )
            val wavInfo = readWavInfo(outputFile)

            assertEquals(OUTPUT_SAMPLE_RATE, wavInfo.sampleRate)
            assertEquals(OUTPUT_CHANNELS, wavInfo.channelCount)
            val expectedDurationUs = amplitudes.size * 1_000_000L
            assertTrue(
                "Converted ${result.durationUs} us of $expectedDurationUs us",
                abs(result.durationUs - expectedDurationUs) <= 100_000L,
            )
            assertSecondsGetLouder(wavInfo, amplitudes.size)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_convertsOggOpusInput() = runBlocking {
        assumeTrue("Ogg muxing needs API 29", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val inputFile = File.createTempFile("converter_input_", ".ogg", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            val amplitudes = doubleArrayOf(0.2, 0.4, 0.6, 0.8)
            writeEncodedAudio(
                file = inputFile,
                pcm16 = generateSteppedSine(OPUS_INPUT_SAMPLE_RATE, OUTPUT_CHANNELS, amplitudes),
                mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
                muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                sampleRate = OPUS_INPUT_SAMPLE_RATE,
                channelCount = OUTPUT_CHANNELS,
            )

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
            )
            val wavInfo = readWavInfo(outputFile)

            assertEquals(OUTPUT_SAMPLE_RATE, wavInfo.sampleRate)
            val expectedDurationUs = amplitudes.size * 1_000_000L
            assertTrue(
                "Converted ${result.durationUs} us of $expectedDurationUs us",
                abs(result.durationUs - expectedDurationUs) <= 100_000L,
            )
            assertSecondsGetLouder(wavInfo, amplitudes.size)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_reportsProgressForRawAdtsAacInput() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".aac", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            val amplitudes = doubleArrayOf(0.2, 0.4, 0.6, 0.8)
            writeAdtsAac(inputFile, generateSteppedSine(AAC_INPUT_SAMPLE_RATE, AAC_INPUT_CHANNELS, amplitudes))
            val progress = mutableListOf<Float>()

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
                onProgress = { value -> progress += value },
            )

            // Progress needs the duration, which ADTS only reveals by scanning every frame.
            assertEquals(0f, progress.first())
            assertTrue("Progress $progress", progress.any { value -> value > 0f && value < 1f })
            assertEquals(1f, progress.last())
            val expectedDurationUs = amplitudes.size * 1_000_000L
            assertTrue(
                "Converted ${result.durationUs} us of $expectedDurationUs us",
                abs(result.durationUs - expectedDurationUs) <= 100_000L,
            )
            assertSecondsGetLouder(readWavInfo(outputFile), amplitudes.size)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_rejectsTooLongRawAdtsAacBeforeDecoding() {
        val inputFile = File.createTempFile("converter_input_", ".aac", context.cacheDir)
        try {
            writeAdtsAac(
                inputFile,
                generatePcm16SineWave(AAC_INPUT_SAMPLE_RATE, AAC_INPUT_CHANNELS, durationMs = 4_000),
            )

            assertRejectedAsTooLong(inputFile, maximumDurationUsExclusive = 2_000_000L)
        } finally {
            inputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_convertsRawAmrNbInputWithProgress() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".amr", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            writeAmrNb(inputFile, generatePcm16SineWave(AMR_INPUT_SAMPLE_RATE, OUTPUT_CHANNELS, durationMs = 3_000))
            val progress = mutableListOf<Float>()

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
                onProgress = { value -> progress += value },
            )

            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertEquals(OUTPUT_SAMPLE_RATE, readWavInfo(outputFile).sampleRate)
            assertTrue("Converted ${result.durationUs} us", abs(result.durationUs - 3_000_000L) <= 100_000L)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_rejectsTooLongRawAmrNbBeforeDecoding() {
        val inputFile = File.createTempFile("converter_input_", ".amr", context.cacheDir)
        try {
            writeAmrNb(inputFile, generatePcm16SineWave(AMR_INPUT_SAMPLE_RATE, OUTPUT_CHANNELS, durationMs = 4_000))

            assertRejectedAsTooLong(inputFile, maximumDurationUsExclusive = 2_000_000L)
        } finally {
            inputFile.delete()
        }
    }

    @Test
    fun convertTo16kMonoWav_keepsTheAudioBeforeTheCutInATruncatedRecording() = runBlocking {
        val inputFile = File.createTempFile("converter_input_", ".aac", context.cacheDir)
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            writeAdtsAac(
                inputFile,
                generatePcm16SineWave(AAC_INPUT_SAMPLE_RATE, AAC_INPUT_CHANNELS, durationMs = 4_000),
            )
            // Cut a few bytes into a frame, like a recording interrupted mid-write.
            val bytes = inputFile.readBytes()
            inputFile.writeBytes(bytes.copyOf(bytes.size / 2 + 3))

            val result = HardwareAudioConverter.convertTo16kMonoWav(
                context = context,
                inputUri = inputFile.toUri(),
                outputFile = outputFile,
                ioDispatcher = Dispatchers.IO,
            )

            assertTrue("Converted ${result.durationUs} us", result.durationUs in 1_700_000L..2_300_000L)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    /** Without a duration the converter would decode until the output cap and report a different error. */
    private fun assertRejectedAsTooLong(inputFile: File, maximumDurationUsExclusive: Long) {
        val outputFile = File.createTempFile("converter_output_", ".wav", context.cacheDir)
        try {
            val error = assertThrows(UserFacingIllegalArgumentException::class.java) {
                runBlocking {
                    HardwareAudioConverter.convertTo16kMonoWav(
                        context = context,
                        inputUri = inputFile.toUri(),
                        outputFile = outputFile,
                        ioDispatcher = Dispatchers.IO,
                        maximumDurationUsExclusive = maximumDurationUsExclusive,
                    )
                }
            }

            assertEquals(R.string.conversion_audio_too_long, error.userMessage.id)
            assertFalse(outputFile.exists())
        } finally {
            outputFile.delete()
        }
    }

    /** A 440 Hz tone whose amplitude steps to the next value of [amplitudes] every second. */
    private fun generateSteppedSine(sampleRate: Int, channelCount: Int, amplitudes: DoubleArray): ByteArray {
        val frameCount = sampleRate * amplitudes.size
        val buffer = ByteBuffer.allocate(frameCount * channelCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frameCount) {
            val amplitude = amplitudes[frame / sampleRate]
            val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * amplitude * Short.MAX_VALUE).toInt().toShort()
            repeat(channelCount) { buffer.putShort(sample) }
        }
        return buffer.array()
    }

    /** Asserts that the middle of each second is louder than the one before, so no audio was lost or reordered. */
    private fun assertSecondsGetLouder(wavInfo: WavInfo, seconds: Int) {
        val samples = ByteBuffer.wrap(wavInfo.pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val levels = (0 until seconds).map { second ->
            // Skip the edges of each second, where codec delay and the step itself blur the level.
            val start = second * OUTPUT_SAMPLE_RATE + OUTPUT_SAMPLE_RATE / 4
            val end = start + OUTPUT_SAMPLE_RATE / 2
            val sumOfSquares = (start until end).sumOf { index ->
                val sample = samples.get(index).toDouble()
                sample * sample
            }
            kotlin.math.sqrt(sumOfSquares / (end - start))
        }
        levels.zipWithNext().forEachIndexed { second, (previous, next) ->
            assertTrue("Second ${second + 1} level $next is not above $previous; levels $levels", next > previous * 1.1)
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

    private fun writePcm24StereoWav(file: File, sampleRate: Int, durationMs: Int) {
        val channelCount = 2
        val bytesPerSample = 3
        val frameCount = sampleRate * durationMs / 1000
        val dataSize = frameCount * channelCount * bytesPerSample
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channelCount * bytesPerSample)
            putShort((channelCount * bytesPerSample).toShort())
            putShort(24)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
            for (frame in 0 until frameCount) {
                val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 0.75 * 8_388_607).toInt()
                repeat(channelCount) {
                    put(sample.toByte())
                    put((sample shr 8).toByte())
                    put((sample shr 16).toByte())
                }
            }
        }.array()
        file.writeBytes(wav)
    }

    private fun writeAacM4a(file: File, pcm16: ByteArray) {
        writeEncodedAudio(
            file = file,
            pcm16 = pcm16,
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            sampleRate = AAC_INPUT_SAMPLE_RATE,
            channelCount = AAC_INPUT_CHANNELS,
        )
    }

    /** Raw ADTS AAC, as some recorders save .aac files: each access unit behind its own 7-byte header. */
    private fun writeAdtsAac(file: File, pcm16: ByteArray) {
        FileOutputStream(file).use { output ->
            encodeAudio(
                pcm16 = pcm16,
                mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate = AAC_INPUT_SAMPLE_RATE,
                channelCount = AAC_INPUT_CHANNELS,
                onOutputFormat = {},
                onAccessUnit = { accessUnit, _ ->
                    output.write(adtsHeader(accessUnit.size))
                    output.write(accessUnit)
                },
            )
        }
    }

    private fun adtsHeader(payloadBytes: Int): ByteArray {
        val frameBytes = payloadBytes + 7
        val profile = 1 // AAC LC
        val sampleRateIndex = 3 // 48 kHz
        val channels = AAC_INPUT_CHANNELS
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (sampleRateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (frameBytes shr 11)).toByte(),
            (frameBytes shr 3).toByte(),
            (((frameBytes and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** Raw AMR-NB storage format: the magic number, then the encoder's frames with their header bytes. */
    private fun writeAmrNb(file: File, pcm16: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write("#!AMR\n".toByteArray(Charsets.US_ASCII))
            encodeAudio(
                pcm16 = pcm16,
                mimeType = MediaFormat.MIMETYPE_AUDIO_AMR_NB,
                sampleRate = AMR_INPUT_SAMPLE_RATE,
                channelCount = OUTPUT_CHANNELS,
                onOutputFormat = {},
                onAccessUnit = { accessUnit, _ -> output.write(accessUnit) },
            )
        }
    }

    private fun writeEncodedAudio(
        file: File,
        pcm16: ByteArray,
        mimeType: String,
        muxerFormat: Int,
        sampleRate: Int,
        channelCount: Int,
    ) {
        val muxer = MediaMuxer(file.absolutePath, muxerFormat)
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            encodeAudio(
                pcm16 = pcm16,
                mimeType = mimeType,
                sampleRate = sampleRate,
                channelCount = channelCount,
                onOutputFormat = { format ->
                    trackIndex = muxer.addTrack(format)
                    muxer.start()
                },
                onAccessUnit = { accessUnit, presentationTimeUs ->
                    bufferInfo.set(0, accessUnit.size, presentationTimeUs, 0)
                    muxer.writeSampleData(trackIndex, ByteBuffer.wrap(accessUnit), bufferInfo)
                },
            )
        } finally {
            if (trackIndex >= 0) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun encodeAudio(
        pcm16: ByteArray,
        mimeType: String,
        sampleRate: Int,
        channelCount: Int,
        onOutputFormat: (MediaFormat) -> Unit,
        onAccessUnit: (accessUnit: ByteArray, presentationTimeUs: Long) -> Unit,
    ) {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount).apply {
            if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val bitRate = when (mimeType) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> 128_000
                MediaFormat.MIMETYPE_AUDIO_AMR_NB -> 12_200
                else -> 64_000
            }
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(mimeType)
        val bytesPerFrame = channelCount * 2
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
                                ((inputOffset / bytesPerFrame).toLong() * 1_000_000L) / sampleRate.toLong()
                            encoder.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                            inputOffset += chunkSize
                        } else {
                            val endPresentationTimeUs =
                                ((pcm16.size / bytesPerFrame).toLong() * 1_000_000L) / sampleRate.toLong()
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
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(encoder.outputFormat)
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = requireNotNull(encoder.getOutputBuffer(outputIndex))
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val accessUnit = ByteArray(bufferInfo.size).also(outputBuffer::get)
                                onAccessUnit(accessUnit, bufferInfo.presentationTimeUs)
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

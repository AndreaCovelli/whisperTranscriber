package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.storage.StorageManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

object HardwareAudioConverter {

    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS_PER_SAMPLE = 16
    private const val BYTES_PER_PCM16_SAMPLE = 2
    private const val MICROSECONDS_IN_SECOND = 1_000_000.0
    private const val BYTES_IN_MEGABYTE = 1024L * 1024L
    private const val CONVERSION_SIZE_BUFFER_BYTES = 10 * BYTES_IN_MEGABYTE // 10 MiB
    private const val DEFAULT_CONVERSION_SIZE_BYTES = 50 * BYTES_IN_MEGABYTE // 50 MiB
    private const val CODEC_BUFFER_TIMEOUT_US = 10_000L
    private const val PROGRESS_STEP = 0.05f
    private const val PROGRESS_INTERVAL_NS = 250_000_000L

    data class Result(val sampleRate: Int, val channelCount: Int, val durationUs: Long)

    private data class DecoderState(
        var sampleRate: Int,
        var sourceChannels: Int,
        var pcmEncoding: Int,
        var writer: WavWriter? = null,
        var resampler: MonoResampler? = null,
    )

    private class ScratchBuffers {
        var shortScratch = ShortArray(0)
        var floatScratch = FloatArray(0)
        var monoScratch = FloatArray(0)

        fun ensureShortCapacity(minSize: Int) {
            if (shortScratch.size < minSize) {
                shortScratch = ShortArray(max(minSize, shortScratch.size.coerceAtLeast(1024) * 2))
            }
        }

        fun ensureFloatCapacity(minSize: Int) {
            if (floatScratch.size < minSize) {
                floatScratch = FloatArray(max(minSize, floatScratch.size.coerceAtLeast(2048) * 2))
            }
        }

        fun ensureMonoCapacity(minSize: Int) {
            if (monoScratch.size < minSize) {
                monoScratch = FloatArray(max(minSize, monoScratch.size.coerceAtLeast(2048) * 2))
            }
        }
    }

    private data class ProgressState(val lastProgress: Float, val lastDispatchNs: Long)

    private data class InputFeedResult(val inputEos: Boolean, val progressState: ProgressState)

    suspend fun convertTo16kMonoWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        onProgress: (suspend (Float) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var state: DecoderState? = null
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        var storageUuid: UUID? = null

        try {
            val extraction = openExtractorAndFormat(context, inputUri)
            extractor = extraction.first
            val srcFormat = extraction.second
            val durationUs = srcFormat.longValueOr(MediaFormat.KEY_DURATION, -1L)
            storageUuid = reserveOutputStorage(
                storageManager = storageManager,
                outputFile = outputFile,
                srcFormat = srcFormat,
            )
            if (durationUs > 0) {
                onProgress?.invoke(0f)
            }

            decoder = MediaCodec.createDecoderByType(requireNotNull(srcFormat.getString(MediaFormat.KEY_MIME)))
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            state = createDecoderState(srcFormat)
            decodeAudioStream(
                extractor = extractor,
                decoder = decoder,
                outputFile = outputFile,
                durationUs = durationUs,
                state = state,
                scratchBuffers = ScratchBuffers(),
                onProgress = onProgress,
            )

            flushResampler(state, outputFile)
            if (durationUs > 0) onProgress?.invoke(1f)

            Result(
                sampleRate = TARGET_SAMPLE_RATE,
                channelCount = TARGET_CHANNELS,
                durationUs = durationUs,
            )
        } finally {
            if (storageUuid != null) {
                runCatching {
                    storageManager.allocateBytes(storageUuid, 0L)
                }.onFailure {
                    android.util.Log.w("HardwareAudioConverter", "Failed to release storage reservation", it)
                }
            }
            listOf(
                "Decoder stop" to { decoder?.stop() },
                "Decoder release" to { decoder?.release() },
                "Extractor release" to { extractor?.release() },
                "Writer close" to { state?.writer?.close() },
            ).forEach { (name, action) ->
                runCatching(action).onFailure {
                    android.util.Log.w("HardwareAudioConverter", "$name failed", it)
                }
            }
        }
    }

    private fun openExtractorAndFormat(context: Context, inputUri: Uri): Pair<MediaExtractor, MediaFormat> {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            ?: throw FileNotFoundException("Unable to open input URI: $inputUri")
        afd.use { descriptor ->
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        }
        val audioTrack = (0 until extractor.trackCount).asSequence()
            .map { index -> index to extractor.getTrackFormat(index) }
            .firstOrNull { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio track found")
        extractor.selectTrack(audioTrack.first)
        return extractor to audioTrack.second
    }

    private fun reserveOutputStorage(storageManager: StorageManager, outputFile: File, srcFormat: MediaFormat): UUID? {
        val bytesPerSecond = TARGET_SAMPLE_RATE.toLong() * TARGET_CHANNELS.toLong() * BYTES_PER_PCM16_SAMPLE.toLong()
        val durationUs = srcFormat.longValueOr(MediaFormat.KEY_DURATION, -1L)
        val estimatedBytes = if (durationUs > 0) {
            ((durationUs / MICROSECONDS_IN_SECOND) * bytesPerSecond).toLong() + CONVERSION_SIZE_BUFFER_BYTES
        } else {
            DEFAULT_CONVERSION_SIZE_BYTES
        }
        val parentDir = outputFile.parentFile
        val storageUuid = resolveStorageUuid(storageManager, parentDir)
        if (storageUuid != null) {
            ensureAllocatableSpace(storageManager, storageUuid, estimatedBytes)
            return storageUuid
        }
        ensureUsableSpace(parentDir, estimatedBytes)
        return null
    }

    private fun resolveStorageUuid(storageManager: StorageManager, parentDir: File?): UUID? {
        if (parentDir == null) return null
        return try {
            storageManager.getUuidForPath(parentDir)
        } catch (e: IOException) {
            android.util.Log.w(
                "HardwareAudioConverter",
                "Failed to get storage UUID for path $parentDir, falling back to usableSpace",
                e,
            )
            null
        } catch (e: IllegalArgumentException) {
            android.util.Log.w(
                "HardwareAudioConverter",
                "Failed to get storage UUID for path $parentDir, falling back to usableSpace",
                e,
            )
            null
        }
    }

    private fun ensureAllocatableSpace(storageManager: StorageManager, storageUuid: UUID, estimatedBytes: Long) {
        val allocatableBytes = storageManager.getAllocatableBytes(storageUuid)
        if (allocatableBytes < estimatedBytes) {
            throw IOException(
                "Insufficient disk space for conversion. Needed at least ${estimatedBytes / BYTES_IN_MEGABYTE} MiB, but only ${allocatableBytes / BYTES_IN_MEGABYTE} MiB is allocatable.",
            )
        }
        storageManager.allocateBytes(storageUuid, estimatedBytes)
    }

    private fun ensureUsableSpace(parentDir: File?, estimatedBytes: Long) {
        val usableSpace = parentDir?.usableSpace ?: 0L
        if (usableSpace < estimatedBytes) {
            throw IOException(
                "Insufficient disk space for conversion. Needed at least ${estimatedBytes / BYTES_IN_MEGABYTE} MiB, but only ${usableSpace / BYTES_IN_MEGABYTE} MiB is available.",
            )
        }
    }

    private fun createDecoderState(srcFormat: MediaFormat): DecoderState {
        val sampleRate = srcFormat.intValueOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        return DecoderState(
            sampleRate = sampleRate,
            sourceChannels = srcFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, 2),
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
            resampler = if (sampleRate != TARGET_SAMPLE_RATE) MonoResampler(sampleRate, TARGET_SAMPLE_RATE) else null,
        )
    }

    private suspend fun decodeAudioStream(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        outputFile: File,
        durationUs: Long,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
        onProgress: (suspend (Float) -> Unit)?,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var progressState = ProgressState(lastProgress = -1f, lastDispatchNs = 0L)

        while (!outputEos && currentCoroutineContext().isActive) {
            if (!inputEos) {
                val feedResult = queueNextInputBuffer(
                    decoder = decoder,
                    extractor = extractor,
                    inputTimeoutUs = CODEC_BUFFER_TIMEOUT_US,
                    durationUs = durationUs,
                    progressState = progressState,
                    onProgress = onProgress,
                )
                inputEos = feedResult.inputEos
                progressState = feedResult.progressState
            }
            outputEos = handleDecoderOutput(
                decoder = decoder,
                info = info,
                outputFile = outputFile,
                state = state,
                scratchBuffers = scratchBuffers,
            )
        }
    }

    private suspend fun queueNextInputBuffer(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        inputTimeoutUs: Long,
        durationUs: Long,
        progressState: ProgressState,
        onProgress: (suspend (Float) -> Unit)?,
    ): InputFeedResult {
        val inputIndex = decoder.dequeueInputBuffer(inputTimeoutUs)
        if (inputIndex < 0) return InputFeedResult(inputEos = false, progressState = progressState)

        val inputBuffer = requireNotNull(decoder.getInputBuffer(inputIndex))
        inputBuffer.clear()
        val size = extractor.readSampleData(inputBuffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return InputFeedResult(inputEos = true, progressState = progressState)
        }

        val presentationTimeUs = extractor.sampleTime
        decoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
        val updatedProgress = updateProgress(durationUs, presentationTimeUs, progressState, onProgress)
        extractor.advance()
        return InputFeedResult(inputEos = false, progressState = updatedProgress)
    }

    private suspend fun updateProgress(
        durationUs: Long,
        presentationTimeUs: Long,
        state: ProgressState,
        onProgress: (suspend (Float) -> Unit)?,
    ): ProgressState {
        if (durationUs <= 0 || presentationTimeUs < 0) return state
        val progress = (presentationTimeUs.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val nowNs = System.nanoTime()
        val largeStepReached = progress - state.lastProgress >= PROGRESS_STEP
        val timeElapsed = nowNs - state.lastDispatchNs >= PROGRESS_INTERVAL_NS
        if (progress == 1f || largeStepReached || timeElapsed) {
            onProgress?.invoke(progress)
            return ProgressState(progress, nowNs)
        }
        return state
    }

    private fun handleDecoderOutput(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ): Boolean = when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_BUFFER_TIMEOUT_US)) {
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            updateDecoderState(decoder.outputFormat, state)
            false
        }
        MediaCodec.INFO_TRY_AGAIN_LATER -> false
        else -> {
            if (outputIndex >= 0) {
                processOutputBuffer(decoder, outputIndex, info, outputFile, state, scratchBuffers)
            }
            outputIndex >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        }
    }

    private fun updateDecoderState(outputFormat: MediaFormat, state: DecoderState) {
        state.sampleRate = outputFormat.intValueOr(MediaFormat.KEY_SAMPLE_RATE, state.sampleRate)
        state.sourceChannels = outputFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, state.sourceChannels)
        state.pcmEncoding = outputFormat.intValueOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        state.resampler = if (state.sampleRate != TARGET_SAMPLE_RATE) {
            MonoResampler(state.sampleRate, TARGET_SAMPLE_RATE)
        } else {
            null
        }
    }

    private fun processOutputBuffer(
        decoder: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val outputBuffer = requireNotNull(decoder.getOutputBuffer(outputIndex))
        if (info.size > 0) {
            outputBuffer.position(info.offset)
            outputBuffer.limit(info.offset + info.size)
            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            when {
                state.pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ->
                    processFloatOutput(outputBuffer, info, outputFile, state, scratchBuffers)
                canWritePcm16Direct(state) ->
                    writePcm16Direct(outputBuffer, info, outputFile, state)
                state.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ->
                    processPcm16Output(outputBuffer, info, outputFile, state, scratchBuffers)
                else ->
                    error("Unsupported PCM encoding: ${state.pcmEncoding}")
            }
        }
        decoder.releaseOutputBuffer(outputIndex, false)
    }

    private fun canWritePcm16Direct(state: DecoderState): Boolean =
        state.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT &&
            state.sampleRate == TARGET_SAMPLE_RATE &&
            state.sourceChannels == TARGET_CHANNELS

    private fun writePcm16Direct(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
    ) {
        if (state.writer == null) {
            state.writer = WavWriter(outputFile)
        }
        val writer = requireNotNull(state.writer)
        writer.writePcm16Buffer(outputBuffer, info.size)
    }

    private fun processFloatOutput(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val floatsCount = info.size / 4
        val frames = floatsCount / state.sourceChannels
        scratchBuffers.ensureMonoCapacity(frames)
        if (state.sourceChannels == 1) {
            outputBuffer.asFloatBuffer().get(scratchBuffers.monoScratch, 0, frames)
        } else {
            scratchBuffers.ensureFloatCapacity(floatsCount)
            outputBuffer.asFloatBuffer().get(scratchBuffers.floatScratch, 0, floatsCount)
            downmixFloatToMono(
                src = scratchBuffers.floatScratch,
                frames = frames,
                srcChannels = state.sourceChannels,
                dst = scratchBuffers.monoScratch,
            )
        }
        writeDecodedFrames(scratchBuffers.monoScratch, frames, outputFile, state)
    }

    private fun processPcm16Output(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val shortsCount = info.size / BYTES_PER_PCM16_SAMPLE
        val frames = shortsCount / state.sourceChannels
        scratchBuffers.ensureShortCapacity(shortsCount)
        scratchBuffers.ensureMonoCapacity(frames)
        outputBuffer.asShortBuffer().get(scratchBuffers.shortScratch, 0, shortsCount)
        convertPcm16ToMonoFloats(
            shorts = scratchBuffers.shortScratch,
            frames = frames,
            srcChannels = state.sourceChannels,
            destination = scratchBuffers.monoScratch,
        )
        writeDecodedFrames(scratchBuffers.monoScratch, frames, outputFile, state)
    }

    private fun convertPcm16ToMonoFloats(shorts: ShortArray, frames: Int, srcChannels: Int, destination: FloatArray) {
        val multiplier = 1.0f / 32768.0f
        if (srcChannels == 1) {
            var index = 0
            while (index < frames) {
                // Preserve mono amplitude. The 0.9f headroom is only for summed downmix paths.
                destination[index] = shorts[index].toInt() * multiplier
                index++
            }
            return
        }

        val downmixScale = 0.9f / srcChannels.toFloat()
        var srcIndex = 0
        var dstIndex = 0
        while (dstIndex < frames) {
            var sum = 0f
            repeat(srcChannels) {
                sum += shorts[srcIndex++].toInt() * multiplier
            }
            destination[dstIndex++] = sum * downmixScale
        }
    }

    private fun downmixFloatToMono(src: FloatArray, frames: Int, srcChannels: Int, dst: FloatArray) {
        val scale = 0.9f / srcChannels
        var srcIndex = 0
        var dstIndex = 0
        while (dstIndex < frames) {
            var sum = 0f
            repeat(srcChannels) {
                sum += src[srcIndex++]
            }
            dst[dstIndex++] = sum * scale
        }
    }

    private fun writeDecodedFrames(monoSamples: FloatArray, frameCount: Int, outputFile: File, state: DecoderState) {
        val resampler = state.resampler
        if (resampler != null) {
            val resampledCount = resampler.process(monoSamples, frameCount)
            if (resampledCount > 0) {
                writeFloats(outputFile, state, resampler.outputBuffer, resampledCount)
            }
        } else {
            writeFloats(outputFile, state, monoSamples, frameCount)
        }
    }

    private fun flushResampler(state: DecoderState, outputFile: File) {
        state.resampler?.let { resampler ->
            val flushCount = resampler.finish()
            if (flushCount > 0) {
                writeFloats(outputFile, state, resampler.outputBuffer, flushCount)
            }
        }
    }

    private fun writeFloats(outputFile: File, state: DecoderState, buffer: FloatArray, count: Int) {
        if (state.writer == null) {
            state.writer = WavWriter(outputFile)
        }
        requireNotNull(state.writer).writeMonoFloats(buffer, count)
    }

    private fun MediaFormat.intValueOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.longValueOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback

    private class MonoResampler(
        private val inRate: Int,
        private val outRate: Int,
        taps: Int = 16,
        phases: Int = 1024,
    ) {
        private val ntaps = taps
        private val nphases = phases
        private val center = ntaps / 2 - 1
        private val step = inRate.toDouble() / outRate.toDouble()
        private val kernel: Array<FloatArray>
        private val tail = FloatArray(ntaps)
        private var workBuffer = FloatArray(0)
        private var totalFramesSeen = 0L
        private var srcPosGlobal = 0.0

        init {
            val cutoff = 0.5 * min(1.0, outRate.toDouble() / inRate.toDouble()) * 0.95
            kernel = Array(nphases) { phaseIndex ->
                val frac = phaseIndex.toDouble() / nphases.toDouble()
                val h = DoubleArray(ntaps)
                var sum = 0.0
                for (tap in 0 until ntaps) {
                    val t = (tap - center) - frac
                    val x = 2.0 * cutoff * t
                    val sinc = if (abs(x) < 1e-8) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                    val value = (2.0 * cutoff) * sinc * blackmanWindow(tap, ntaps)
                    h[tap] = value
                    sum += value
                }
                val scale = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
                FloatArray(ntaps) { tap -> (h[tap] * scale).toFloat() }
            }
        }

        var outputBuffer: FloatArray = FloatArray(0)
            private set

        fun process(input: FloatArray, framesIn: Int): Int {
            val workFrames = ntaps + framesIn
            ensureWorkCapacity(workFrames)
            System.arraycopy(tail, 0, workBuffer, 0, ntaps)
            if (framesIn > 0) {
                System.arraycopy(input, 0, workBuffer, ntaps, framesIn)
            }

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal
            val approxOutFrames = ((framesIn * outRate.toLong()) / inRate.toLong() + 32L).toInt().coerceAtLeast(0)
            ensureOutCapacity(approxOutFrames)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                val beforeInput = leftIndex < 0
                val pastInput = leftIndex + ntaps > workFrames
                if (pastInput) break

                if (!beforeInput) {
                    val frac = posRel - base
                    var phase = (frac * nphases).roundToInt()
                    if (phase == nphases) phase = 0
                    val filter = kernel[phase]

                    if (outIndex >= outputBuffer.size) {
                        ensureOutCapacity(outputBuffer.size * 2)
                    }

                    var acc = 0.0f
                    var tap = 0
                    var inputIndex = leftIndex
                    while (tap < ntaps) {
                        acc += workBuffer[inputIndex++] * filter[tap++]
                    }
                    outputBuffer[outIndex++] = acc
                }

                posRel += step
                srcPosGlobal += step
            }

            val copyStart = max(0, workFrames - ntaps)
            System.arraycopy(workBuffer, copyStart, tail, 0, ntaps)
            totalFramesSeen += framesIn

            return outIndex
        }

        fun finish(): Int {
            val workFrames = ntaps * 2
            ensureWorkCapacity(workFrames)
            System.arraycopy(tail, 0, workBuffer, 0, ntaps)
            workBuffer.fill(0f, ntaps, workFrames)

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal
            ensureOutCapacity(ntaps * 2)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                val beforeInput = leftIndex < 0
                val pastTail = leftIndex >= ntaps || leftIndex + ntaps > workFrames
                if (pastTail) break

                if (!beforeInput) {
                    val frac = posRel - base
                    var phase = (frac * nphases).roundToInt()
                    if (phase == nphases) phase = 0
                    val filter = kernel[phase]

                    if (outIndex >= outputBuffer.size) {
                        ensureOutCapacity(outputBuffer.size * 2)
                    }

                    var acc = 0.0f
                    var tap = 0
                    var inputIndex = leftIndex
                    while (tap < ntaps) {
                        acc += workBuffer[inputIndex++] * filter[tap++]
                    }
                    outputBuffer[outIndex++] = acc
                }

                posRel += step
                srcPosGlobal += step
            }

            return outIndex
        }

        private fun ensureWorkCapacity(minSize: Int) {
            if (workBuffer.size < minSize) {
                workBuffer = FloatArray(max(minSize, workBuffer.size.coerceAtLeast(8192) * 2))
            }
        }

        private fun ensureOutCapacity(minSize: Int) {
            if (outputBuffer.size < minSize) {
                outputBuffer = FloatArray(max(minSize, outputBuffer.size.coerceAtLeast(8192) * 2))
            }
        }

        private fun blackmanWindow(n: Int, ntaps: Int): Double {
            if (ntaps <= 1) return 1.0
            val a0 = 0.42
            val a1 = 0.5
            val a2 = 0.08
            val twoPi = 2.0 * Math.PI
            val fourPi = 4.0 * Math.PI
            return a0 - a1 * cos(twoPi * n / (ntaps - 1)) + a2 * cos(fourPi * n / (ntaps - 1))
        }
    }

    private class WavWriter(private val file: File) : AutoCloseable {

        private val out = BufferedOutputStream(FileOutputStream(file))
        private var dataBytesWritten = 0L
        private var byteScratch = ByteArray(0)
        private var byteBuffer: ByteBuffer? = null

        init {
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(0)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(TARGET_CHANNELS.toShort())
            bb.putInt(TARGET_SAMPLE_RATE)
            val byteRate = TARGET_SAMPLE_RATE * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8
            bb.putInt(byteRate)
            val blockAlign = TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8
            bb.putShort(blockAlign.toShort())
            bb.putShort(TARGET_BITS_PER_SAMPLE.toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(0)
            out.write(header)
        }

        fun writeMonoFloats(samples: FloatArray, sampleCount: Int) {
            if (sampleCount <= 0) return
            val byteCount = sampleCount * BYTES_PER_PCM16_SAMPLE
            ensureByteCapacity(byteCount)

            val bb = byteBuffer!!
            bb.clear()
            val shortBuffer = bb.asShortBuffer()
            var index = 0
            while (index < sampleCount) {
                val scaled = (samples[index].coerceIn(-1f, 1f) * 32767f).roundToInt()
                shortBuffer.put(scaled.coerceIn(-32768, 32767).toShort())
                index++
            }
            out.write(byteScratch, 0, byteCount)
            dataBytesWritten += byteCount
        }

        fun writePcm16Buffer(buffer: ByteBuffer, byteCount: Int) {
            if (byteCount <= 0) return
            ensureByteCapacity(byteCount)
            buffer.get(byteScratch, 0, byteCount)
            out.write(byteScratch, 0, byteCount)
            dataBytesWritten += byteCount
        }

        override fun close() {
            out.use { stream ->
                stream.flush()
            }
            RandomAccessFile(file, "rw").use { raf ->
                val intBuf = ByteArray(4)
                raf.seek(4)
                writeIntLE(raf, (36 + dataBytesWritten).toInt(), intBuf)
                raf.seek(40)
                writeIntLE(raf, dataBytesWritten.toInt(), intBuf)
            }
        }

        private fun ensureByteCapacity(minBytes: Int) {
            if (byteScratch.size < minBytes) {
                byteScratch = ByteArray(max(minBytes, byteScratch.size.coerceAtLeast(16384) * 2))
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            } else if (byteBuffer == null) {
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        private fun writeIntLE(raf: RandomAccessFile, value: Int, buf: ByteArray) {
            buf[0] = (value and 0xff).toByte()
            buf[1] = ((value shr 8) and 0xff).toByte()
            buf[2] = ((value shr 16) and 0xff).toByte()
            buf[3] = ((value shr 24) and 0xff).toByte()
            raf.write(buf)
        }
    }
}

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

    private const val BYTES_PER_SAMPLE_16_BIT = 2L
    private const val MICROSECONDS_IN_SECOND = 1_000_000.0
    private const val BYTES_IN_MEGABYTE = 1024L * 1024L
    private const val CONVERSION_SIZE_BUFFER_BYTES = 10 * BYTES_IN_MEGABYTE // 10 MiB
    private const val DEFAULT_CONVERSION_SIZE_BYTES = 50 * BYTES_IN_MEGABYTE // 50 MiB
    private const val CODEC_BUFFER_TIMEOUT_US = 10000L

    data class Result(val sampleRate: Int, val channelCount: Int, val durationUs: Long)

    private data class DecoderState(
        var sampleRate: Int,
        var channelCount: Int,
        var pcmEncoding: Int,
        val forceMono: Boolean,
        val targetSampleRate: Int,
        var outputChannels: Int,
        var writer: WavWriter? = null,
        var resampler: PolyphaseResampler? = null,
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

    suspend fun convertToWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        forceMono: Boolean = true,
        targetSampleRate: Int = 16_000,
        onProgress: (suspend (Float) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        require(targetSampleRate > 0)

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
                forceMono = forceMono,
                targetSampleRate = targetSampleRate,
            )
            if (durationUs > 0) {
                onProgress?.invoke(0f)
            }

            decoder = MediaCodec.createDecoderByType(requireNotNull(srcFormat.getString(MediaFormat.KEY_MIME)))
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            state = createDecoderState(srcFormat, forceMono, targetSampleRate)
            val scratchBuffers = ScratchBuffers()
            decodeAudioStream(
                extractor = extractor,
                decoder = decoder,
                outputFile = outputFile,
                durationUs = durationUs,
                state = state,
                scratchBuffers = scratchBuffers,
                onProgress = onProgress,
            )

            flushResampler(state, outputFile)
            if (durationUs > 0) onProgress?.invoke(1f)

            Result(
                sampleRate = targetSampleRate,
                channelCount = state.outputChannels,
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

    private fun reserveOutputStorage(
        storageManager: StorageManager,
        outputFile: File,
        srcFormat: MediaFormat,
        forceMono: Boolean,
        targetSampleRate: Int,
    ): UUID? {
        val outputChannels = if (forceMono) 1 else srcFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val bytesPerSecond = targetSampleRate * outputChannels * BYTES_PER_SAMPLE_16_BIT
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

    private fun createDecoderState(srcFormat: MediaFormat, forceMono: Boolean, targetSampleRate: Int): DecoderState {
        val sampleRate = srcFormat.intValueOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
        val channelCount = srcFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val outputChannels = if (forceMono) 1 else channelCount
        return DecoderState(
            sampleRate = sampleRate,
            channelCount = channelCount,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
            forceMono = forceMono,
            targetSampleRate = targetSampleRate,
            outputChannels = outputChannels,
            resampler = if (sampleRate != targetSampleRate) {
                PolyphaseResampler(sampleRate, targetSampleRate, outputChannels)
            } else {
                null
            },
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
        var lastProgress = -1f

        while (!outputEos && currentCoroutineContext().isActive) {
            if (!inputEos) {
                inputEos = queueNextInputBuffer(
                    decoder = decoder,
                    extractor = extractor,
                    inputTimeoutUs = CODEC_BUFFER_TIMEOUT_US,
                    durationUs = durationUs,
                    lastProgress = lastProgress,
                    onProgress = onProgress,
                ).also { result ->
                    lastProgress = result.lastProgress
                }.inputEos
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

    private data class InputFeedResult(val inputEos: Boolean, val lastProgress: Float)

    private suspend fun queueNextInputBuffer(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        inputTimeoutUs: Long,
        durationUs: Long,
        lastProgress: Float,
        onProgress: (suspend (Float) -> Unit)?,
    ): InputFeedResult {
        val inputIndex = decoder.dequeueInputBuffer(inputTimeoutUs)
        if (inputIndex < 0) return InputFeedResult(inputEos = false, lastProgress = lastProgress)

        val inputBuffer = requireNotNull(decoder.getInputBuffer(inputIndex))
        inputBuffer.clear()
        val size = extractor.readSampleData(inputBuffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return InputFeedResult(inputEos = true, lastProgress = lastProgress)
        }

        val presentationTimeUs = extractor.sampleTime
        decoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
        val updatedProgress = updateProgress(durationUs, presentationTimeUs, lastProgress, onProgress)
        extractor.advance()
        return InputFeedResult(inputEos = false, lastProgress = updatedProgress)
    }

    private suspend fun updateProgress(
        durationUs: Long,
        presentationTimeUs: Long,
        lastProgress: Float,
        onProgress: (suspend (Float) -> Unit)?,
    ): Float {
        if (durationUs <= 0 || presentationTimeUs < 0) return lastProgress
        val progress = (presentationTimeUs.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        if (progress - lastProgress >= 0.01f || progress == 1f) {
            onProgress?.invoke(progress)
            return progress
        }
        return lastProgress
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
        state.channelCount = outputFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, state.channelCount)
        state.pcmEncoding = outputFormat.intValueOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        state.outputChannels = if (state.forceMono) 1 else state.channelCount
        state.resampler = if (state.sampleRate != state.targetSampleRate) {
            PolyphaseResampler(state.sampleRate, state.targetSampleRate, state.outputChannels)
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
            if (state.pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                processFloatOutput(outputBuffer, info, outputFile, state, scratchBuffers)
            } else {
                processPcm16Output(outputBuffer, info, outputFile, state, scratchBuffers)
            }
        }
        decoder.releaseOutputBuffer(outputIndex, false)
    }

    private fun processFloatOutput(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val floatsCount = info.size / 4
        val frames = floatsCount / state.channelCount
        scratchBuffers.ensureFloatCapacity(floatsCount)
        outputBuffer.asFloatBuffer().get(scratchBuffers.floatScratch, 0, floatsCount)
        writeDecodedFrames(
            interleaved = if (state.outputChannels == state.channelCount) {
                scratchBuffers.floatScratch
            } else {
                scratchBuffers.ensureMonoCapacity(frames)
                downmixToMonoInterleavedInto(
                    src = scratchBuffers.floatScratch,
                    frames = frames,
                    srcChannels = state.channelCount,
                    dst = scratchBuffers.monoScratch,
                )
                scratchBuffers.monoScratch
            },
            interleavedSamples = if (state.outputChannels == state.channelCount) floatsCount else frames,
            framesAfterMix = frames,
            outputFile = outputFile,
            state = state,
        )
    }

    private fun processPcm16Output(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        outputFile: File,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val shortsCount = info.size / 2
        val frames = shortsCount / state.channelCount
        scratchBuffers.ensureShortCapacity(shortsCount)
        scratchBuffers.ensureFloatCapacity(frames * state.channelCount)
        outputBuffer.asShortBuffer().get(scratchBuffers.shortScratch, 0, shortsCount)
        convertShortsToFloats(scratchBuffers.shortScratch, shortsCount, scratchBuffers.floatScratch)
        val interleaved = if (state.outputChannels == state.channelCount) {
            scratchBuffers.floatScratch
        } else {
            scratchBuffers.ensureMonoCapacity(frames)
            downmixToMonoInterleavedInto(
                src = scratchBuffers.floatScratch,
                frames = frames,
                srcChannels = state.channelCount,
                dst = scratchBuffers.monoScratch,
            )
            scratchBuffers.monoScratch
        }
        val interleavedSamples = if (state.outputChannels == state.channelCount) frames * state.channelCount else frames
        writeDecodedFrames(interleaved, interleavedSamples, frames, outputFile, state)
    }

    private fun convertShortsToFloats(shorts: ShortArray, count: Int, destination: FloatArray) {
        val multiplier = 1.0f / 32768.0f
        var sourceIndex = 0
        while (sourceIndex < count) {
            destination[sourceIndex] = shorts[sourceIndex].toInt() * multiplier
            sourceIndex++
        }
    }

    private fun writeDecodedFrames(
        interleaved: FloatArray,
        interleavedSamples: Int,
        framesAfterMix: Int,
        outputFile: File,
        state: DecoderState,
    ) {
        val resampled = state.resampler?.process(interleaved, framesAfterMix)
        if (resampled != null) {
            writeFloats(outputFile, state, resampled.second, resampled.first)
        } else if (state.resampler == null) {
            writeFloats(outputFile, state, interleaved, interleavedSamples)
        }
    }

    private fun flushResampler(state: DecoderState, outputFile: File) {
        state.resampler?.finish()?.let { (flushCount, flushBuffer) ->
            if (flushCount > 0) {
                writeFloats(outputFile, state, flushBuffer, flushCount)
            }
        }
    }

    private fun writeFloats(outputFile: File, state: DecoderState, buffer: FloatArray, count: Int) {
        if (state.writer == null) {
            state.writer = WavWriter(outputFile, state.targetSampleRate, state.outputChannels, 16)
        }
        state.writer?.writeFloatsInterleaved(buffer, state.outputChannels, count)
    }

    private fun MediaFormat.intValueOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.longValueOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback

    // Downmix to mono with 0.9f headroom into a provided buffer
    // src: interleaved float with srcChannels, frames total
    // dst: must have capacity >= frames
    // returns the number of output samples written (equals frames for mono)
    private fun downmixToMonoInterleavedInto(src: FloatArray, frames: Int, srcChannels: Int, dst: FloatArray): Int {
        val scale = 0.9f / srcChannels
        var i = 0
        var o = 0
        val total = frames * srcChannels
        while (i < total) {
            var sum = 0f
            for (c in 0 until srcChannels) sum += src[i + c]
            dst[o++] = sum * scale
            i += srcChannels
        }
        return frames
    }

    // OPTIMIZED: Uses reusable FloatArray buffers instead of ArrayList.
    private class PolyphaseResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val channels: Int,
        taps: Int = 16,
        phases: Int = 1024,
    ) {
        private val ntaps = taps
        private val nphases = phases
        private val center = ntaps / 2 - 1
        private val step = inRate.toDouble() / outRate.toDouble()
        private val kernel: Array<FloatArray>
        private var tail: FloatArray = FloatArray(ntaps * channels)
        private var totalFramesSeen: Long = 0L
        private var srcPosGlobal = 0.0

        // Reusable output buffer
        private var outBuffer = FloatArray(0)
        private fun ensureOutCapacity(minSize: Int) {
            if (outBuffer.size < minSize) {
                outBuffer = FloatArray(max(minSize, outBuffer.size.coerceAtLeast(8192) * 2))
            }
        }

        init {
            val cutoff = 0.5 * min(1.0, outRate.toDouble() / inRate.toDouble()) * 0.95
            kernel = Array(nphases) { p ->
                val frac = p.toDouble() / nphases.toDouble()
                val h = DoubleArray(ntaps)
                var sum = 0.0
                for (j in 0 until ntaps) {
                    val t = (j - center) - frac
                    val x = 2.0 * cutoff * t
                    val sinc = if (abs(x) < 1e-8) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                    val blackman = blackmanWindow(j, ntaps)
                    val v = (2.0 * cutoff) * sinc * blackman
                    h[j] = v
                    sum += v
                }
                val scale = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
                FloatArray(ntaps) { j -> (h[j] * scale).toFloat() }
            }
        }

        // Returns Pair<Count, Buffer>
        fun process(inputInterleaved: FloatArray, framesIn: Int): Pair<Int, FloatArray>? {
            val workFrames = ntaps + framesIn
            // Allocating work buffer here is acceptable as it assembles the input stream
            val work = FloatArray(workFrames * channels)
            System.arraycopy(tail, 0, work, 0, tail.size)
            if (framesIn > 0) {
                System.arraycopy(inputInterleaved, 0, work, tail.size, framesIn * channels)
            }

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            val approxOutFrames = ((framesIn * outRate.toLong()) / inRate.toLong() + 32L).toInt().coerceAtLeast(0)

            ensureOutCapacity(approxOutFrames * channels)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                val shouldAdvance = leftIndex < 0
                val shouldStop = leftIndex + ntaps > workFrames

                if (shouldStop) break
                if (shouldAdvance) {
                    posRel += step
                    srcPosGlobal += step
                } else {
                    val frac = posRel - base
                    var phase = (frac * nphases).roundToInt()
                    if (phase == nphases) phase = 0
                    val k = kernel[phase]

                    if (outIndex + channels > outBuffer.size) {
                        ensureOutCapacity(outBuffer.size * 2)
                    }

                    for (ch in 0 until channels) {
                        var acc = 0.0f
                        var j = 0
                        var idx = (leftIndex * channels) + ch
                        while (j < ntaps) {
                            acc += work[idx] * k[j]
                            idx += channels
                            j++
                        }
                        outBuffer[outIndex++] = acc
                    }

                    posRel += step
                    srcPosGlobal += step
                }
            }

            // Update tail buffer
            val copyStartFrames = max(0, workFrames - ntaps)
            val copyStart = copyStartFrames * channels
            if (copyStart < work.size) {
                // Ensure we don't copy beyond the bounds
                val lenToCopy = min(ntaps * channels, work.size - copyStart)
                if (lenToCopy > 0) {
                    System.arraycopy(work, copyStart, tail, 0, lenToCopy)
                }
            }
            totalFramesSeen += framesIn

            if (outIndex == 0) return null
            return Pair(outIndex, outBuffer)
        }

        fun finish(): Pair<Int, FloatArray>? {
            val (count, buffer) = finishInternal()
            if (count == 0) return null
            return Pair(count, buffer)
        }

        private fun finishInternal(): Pair<Int, FloatArray> {
            val workFrames = ntaps + ntaps // Tail + zero padding
            val work = FloatArray(workFrames * channels)
            System.arraycopy(tail, 0, work, 0, tail.size)
            // Padding is implicitly zeros

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            val approxOutFrames = ntaps * 2 // Heuristic
            ensureOutCapacity(approxOutFrames * channels)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                val shouldAdvance = leftIndex < 0
                val shouldStop = leftIndex >= ntaps || leftIndex + ntaps > workFrames

                if (shouldStop) break
                if (shouldAdvance) {
                    posRel += step
                    srcPosGlobal += step
                } else {
                    val frac = posRel - base
                    var phase = (frac * nphases).roundToInt()
                    if (phase == nphases) phase = 0
                    val k = kernel[phase]

                    if (outIndex + channels > outBuffer.size) {
                        ensureOutCapacity(outBuffer.size * 2)
                    }

                    for (ch in 0 until channels) {
                        var acc = 0.0f
                        var j = 0
                        var idx = (leftIndex * channels) + ch
                        while (j < ntaps) {
                            acc += work[idx] * k[j]
                            idx += channels
                            j++
                        }
                        outBuffer[outIndex++] = acc
                    }

                    posRel += step
                    srcPosGlobal += step
                }
            }

            return Pair(outIndex, outBuffer)
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

    // OPTIMIZED: Uses ByteBuffer and ShortBuffer for efficient writing.
    private class WavWriter(private val file: File, sampleRate: Int, private val channels: Int, bitsPerSample: Int) :
        AutoCloseable {

        private val out = BufferedOutputStream(FileOutputStream(file))
        private var dataBytesWritten = 0L
        private var byteScratch = ByteArray(0)

        // Added ByteBuffer wrapper
        private var byteBuffer: ByteBuffer? = null

        private fun ensureByteCapacity(minBytes: Int) {
            if (byteScratch.size < minBytes) {
                val newSize = max(minBytes, byteScratch.size.coerceAtLeast(16384) * 2)
                byteScratch = ByteArray(newSize)
                // Re-wrap with the new array
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            } else if (byteBuffer == null) {
                // Initialize if array existed but buffer didn't
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        init {
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(0) // placeholder
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // PCM
            bb.putShort(1) // format PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            bb.putInt(byteRate)
            val blockAlign = channels * bitsPerSample / 8
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(0) // placeholder for data size
            out.write(header)
        }

        // Optimized implementation
        fun writeFloatsInterleaved(samples: FloatArray, channels: Int, sampleCount: Int = samples.size) {
            require(channels == this.channels)
            if (sampleCount <= 0) return
            val byteCount = sampleCount * 2 // 16-bit
            ensureByteCapacity(byteCount)

            val bb = byteBuffer!!
            bb.clear()
            // Use ShortBuffer view for bulk conversion
            val shortBuffer = bb.asShortBuffer()

            var i = 0
            while (i < sampleCount) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                val s = v.coerceIn(-32768, 32767).toShort()
                shortBuffer.put(s)
                i++
            }
            out.write(byteScratch, 0, byteCount)
            dataBytesWritten += byteCount
        }

        // Optimized close() using a reusable buffer for RandomAccessFile writes
        override fun close() {
            out.use { out ->
                out.flush()
            }
            RandomAccessFile(file, "rw").use { raf ->
                val intBuf = ByteArray(4) // Reusable buffer
                raf.seek(4)
                writeIntLE(raf, (36 + dataBytesWritten).toInt(), intBuf)
                raf.seek(40)
                writeIntLE(raf, dataBytesWritten.toInt(), intBuf)
            }
        }

        // New helper function (Replaces the old extension function for efficiency)
        private fun writeIntLE(raf: RandomAccessFile, value: Int, buf: ByteArray) {
            buf[0] = (value and 0xff).toByte()
            buf[1] = ((value shr 8) and 0xff).toByte()
            buf[2] = ((value shr 16) and 0xff).toByte()
            buf[3] = ((value shr 24) and 0xff).toByte()
            raf.write(buf)
        }
    }
}

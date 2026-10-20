package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.storage.StorageManager
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.requireUserFacing
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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

object HardwareAudioConverter {

    private const val MICROSECONDS_IN_SECOND = 1_000_000L
    private const val CODEC_BUFFER_TIMEOUT_US = 10_000L
    private const val PROGRESS_STEP = 0.05f
    private const val PROGRESS_INTERVAL_NS = 250_000_000L
    private const val END_OF_STREAM_STALL_TIMEOUT_NS = 5_000_000_000L

    data class Result(val sampleRate: Int, val channelCount: Int, val durationUs: Long)

    private data class DecoderState(
        var sampleRate: Int,
        var sourceChannels: Int,
        var sourceChannelMask: Int?,
        var pcmEncoding: Int,
        var downmixGains: FloatArray,
        var writer: Pcm16WavWriter? = null,
        var resampler: MonoPcmResampler? = null,
    )

    private data class ProgressState(val lastProgress: Float, val lastDispatchNs: Long)

    private data class InputFeedResult(val inputEos: Boolean, val progressState: ProgressState)

    private enum class DecoderOutput { NONE, PRODUCED, END_OF_STREAM }

    suspend fun convertTo16kMonoWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        ioDispatcher: CoroutineDispatcher,
        maximumDurationUsExclusive: Long? = null,
        onProgress: (suspend (Float) -> Unit)? = null,
    ): Result = withContext(ioDispatcher) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var state: DecoderState? = null
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        var outputFinalized = false

        try {
            val extraction = openExtractorAndFormat(context, inputUri)
            extractor = extraction.first
            val srcFormat = extraction.second
            val durationUs = srcFormat.longValueOr(MediaFormat.KEY_DURATION, -1L)
            validateSourceDuration(durationUs, maximumDurationUsExclusive)
            val maximumDataBytes = maximumDurationUsExclusive
                ?.let(::maximumPcm16MonoDataBytesForDurationExclusive)
                ?: MAX_CLASSIC_WAV_DATA_BYTES
            state = createDecoderState(srcFormat).apply {
                writer = Pcm16WavWriter(outputFile, maximumDataBytes)
            }
            ConversionOutputStorage.reserve(
                storageManager = storageManager,
                fileDescriptor = requireNotNull(state.writer).fileDescriptor,
                outputFile = outputFile,
                durationUs = durationUs,
                maximumOutputDataBytes = maximumDataBytes,
            )
            if (durationUs > 0) {
                onProgress?.invoke(0f)
            }

            val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(srcFormat)
                ?: throw UserFacingIOException(UserMessage(R.string.conversion_no_decoder))
            decoder = MediaCodec.createByCodecName(decoderName)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            decodeAudioStream(
                extractor = extractor,
                decoder = decoder,
                durationUs = durationUs,
                state = state,
                scratchBuffers = ScratchBuffers(),
                onProgress = onProgress,
            )

            currentCoroutineContext().ensureActive()
            flushResampler(state)
            val outputDurationUs = requireNotNull(state.writer).finish()
            outputFinalized = true
            if (durationUs > 0) onProgress?.invoke(1f)

            Result(
                sampleRate = CONVERTED_AUDIO_SAMPLE_RATE,
                channelCount = CONVERTED_AUDIO_CHANNELS,
                durationUs = outputDurationUs,
            )
        } finally {
            closeBestEffort("Decoder stop") { decoder?.stop() }
            closeBestEffort("Decoder release") { decoder?.release() }
            closeBestEffort("Extractor release") { extractor?.release() }
            closeBestEffort("Writer close") { state?.writer?.close() }
            if (!outputFinalized && outputFile.exists() && !outputFile.delete()) {
                android.util.Log.w("HardwareAudioConverter", "Failed to delete incomplete converted audio")
            }
        }
    }

    // MediaExtractor.release() documents no narrower runtime failure contract.
    @Suppress("TooGenericExceptionCaught")
    private fun openExtractorAndFormat(context: Context, inputUri: Uri): Pair<MediaExtractor, MediaFormat> {
        val extractor = MediaExtractor()
        var succeeded = false
        try {
            val descriptor = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
                ?: throw FileNotFoundException("Unable to open input URI: $inputUri")
            descriptor.use { assetFileDescriptor ->
                extractor.setDataSource(
                    assetFileDescriptor.fileDescriptor,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.length,
                )
            }
            val audioTrack = (0 until extractor.trackCount).asSequence()
                .map { index -> index to extractor.getTrackFormat(index) }
                .firstOrNull { (_, format) ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?: throw UserFacingIllegalStateException(UserMessage(R.string.conversion_no_audio_track))
            extractor.selectTrack(audioTrack.first)
            succeeded = true
            return extractor to audioTrack.second
        } finally {
            if (!succeeded) {
                try {
                    extractor.release()
                } catch (error: RuntimeException) {
                    android.util.Log.w("HardwareAudioConverter", "Extractor cleanup failed", error)
                }
            }
        }
    }

    // Android media cleanup APIs expose a mix of checked and runtime exceptions; none should mask the
    // conversion result, but fatal JVM Errors must continue to propagate.
    @Suppress("TooGenericExceptionCaught")
    private inline fun closeBestEffort(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            android.util.Log.w("HardwareAudioConverter", "$name failed", error)
        }
    }

    private fun validateSourceDuration(durationUs: Long, maximumDurationUsExclusive: Long?) {
        if (maximumDurationUsExclusive == null || durationUs <= 0) return
        requireUserFacing(durationUs < maximumDurationUsExclusive) {
            val maximumSeconds = (maximumDurationUsExclusive / MICROSECONDS_IN_SECOND).toInt()
            UserMessage(
                R.string.conversion_audio_too_long,
                listOf(QuantityText(R.plurals.duration_seconds, maximumSeconds)),
            )
        }
    }

    private fun createDecoderState(srcFormat: MediaFormat): DecoderState {
        val sampleRate = srcFormat.intValueOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        val sourceChannels = srcFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val sourceChannelMask = srcFormat.intValueOrNull(MediaFormat.KEY_CHANNEL_MASK)
        return DecoderState(
            sampleRate = sampleRate,
            sourceChannels = sourceChannels,
            sourceChannelMask = sourceChannelMask,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
            downmixGains = PcmMonoDownmixer.createChannelGains(
                sourceChannels = sourceChannels,
                channelMask = sourceChannelMask,
            ),
            resampler = createResampler(sampleRate),
        )
    }

    private fun createResampler(sampleRate: Int): MonoPcmResampler? = if (sampleRate != CONVERTED_AUDIO_SAMPLE_RATE) {
        MonoPcmResampler(sampleRate, CONVERTED_AUDIO_SAMPLE_RATE)
    } else {
        null
    }

    private suspend fun decodeAudioStream(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        durationUs: Long,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
        onProgress: (suspend (Float) -> Unit)?,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var progressState = ProgressState(lastProgress = -1f, lastDispatchNs = 0L)
        val drainWatchdog = DecoderDrainWatchdog(END_OF_STREAM_STALL_TIMEOUT_NS)

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
            when (handleDecoderOutput(decoder, info, state, scratchBuffers)) {
                DecoderOutput.END_OF_STREAM -> outputEos = true
                DecoderOutput.PRODUCED -> drainWatchdog.onOutput()
                DecoderOutput.NONE -> if (inputEos && drainWatchdog.isStalled()) {
                    // Some decoders never flag end of stream; keep the audio decoded so far.
                    android.util.Log.w("HardwareAudioConverter", "Decoder stalled after end of input")
                    outputEos = true
                }
            }
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
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ): DecoderOutput = when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_BUFFER_TIMEOUT_US)) {
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            updateDecoderState(decoder.outputFormat, state)
            DecoderOutput.PRODUCED
        }
        MediaCodec.INFO_TRY_AGAIN_LATER -> DecoderOutput.NONE
        else -> if (outputIndex < 0) {
            DecoderOutput.NONE
        } else {
            processOutputBuffer(decoder, outputIndex, info, state, scratchBuffers)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                DecoderOutput.END_OF_STREAM
            } else {
                DecoderOutput.PRODUCED
            }
        }
    }

    private fun updateDecoderState(outputFormat: MediaFormat, state: DecoderState) {
        val pcmEncoding = outputFormat.intValueOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        if (!isSupportedPcmEncoding(pcmEncoding)) {
            throw UserFacingIOException(UserMessage(R.string.conversion_unsupported_pcm_encoding))
        }
        val sampleRate = outputFormat.intValueOr(MediaFormat.KEY_SAMPLE_RATE, state.sampleRate)
        if (sampleRate != state.sampleRate) {
            // Emit the old rate's buffered tail before switching; a mid-stream change must not drop samples.
            flushResampler(state)
            state.sampleRate = sampleRate
            state.resampler = createResampler(sampleRate)
        }
        state.sourceChannels = outputFormat.intValueOr(MediaFormat.KEY_CHANNEL_COUNT, state.sourceChannels)
        state.sourceChannelMask = outputFormat.intValueOrNull(MediaFormat.KEY_CHANNEL_MASK) ?: state.sourceChannelMask
        state.pcmEncoding = pcmEncoding
        state.downmixGains = PcmMonoDownmixer.createChannelGains(state.sourceChannels, state.sourceChannelMask)
    }

    private fun isSupportedPcmEncoding(encoding: Int): Boolean = encoding == AudioFormat.ENCODING_PCM_16BIT ||
        encoding == AudioFormat.ENCODING_PCM_FLOAT ||
        PcmIntegerSampleReader.bytesPerSample(encoding) != null

    private fun processOutputBuffer(
        decoder: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
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
                    processFloatOutput(outputBuffer, info, state, scratchBuffers)
                canWritePcm16Direct(state) ->
                    writePcm16Direct(outputBuffer, info, state)
                state.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ->
                    processPcm16Output(outputBuffer, info, state, scratchBuffers)
                else ->
                    processIntegerPcmOutput(outputBuffer, info, state, scratchBuffers)
            }
        }
        decoder.releaseOutputBuffer(outputIndex, false)
    }

    private fun canWritePcm16Direct(state: DecoderState): Boolean =
        state.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT &&
            state.sampleRate == CONVERTED_AUDIO_SAMPLE_RATE &&
            state.sourceChannels == CONVERTED_AUDIO_CHANNELS

    private fun writePcm16Direct(outputBuffer: ByteBuffer, info: MediaCodec.BufferInfo, state: DecoderState) {
        requireNotNull(state.writer).writePcm16Buffer(outputBuffer, info.size)
    }

    private fun processFloatOutput(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
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
            PcmMonoDownmixer.mixFloatToMono(
                source = scratchBuffers.floatScratch,
                frames = frames,
                sourceChannels = state.sourceChannels,
                channelMask = state.sourceChannelMask,
                destination = scratchBuffers.monoScratch,
                channelGains = state.downmixGains,
            )
        }
        writeDecodedFrames(scratchBuffers.monoScratch, frames, state)
    }

    private fun processPcm16Output(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val shortsCount = info.size / PCM16_BYTES_PER_SAMPLE
        val frames = shortsCount / state.sourceChannels
        scratchBuffers.ensureShortCapacity(shortsCount)
        scratchBuffers.ensureMonoCapacity(frames)
        outputBuffer.asShortBuffer().get(scratchBuffers.shortScratch, 0, shortsCount)
        PcmMonoDownmixer.mixPcm16ToMono(
            source = scratchBuffers.shortScratch,
            frames = frames,
            sourceChannels = state.sourceChannels,
            channelMask = state.sourceChannelMask,
            destination = scratchBuffers.monoScratch,
            channelGains = state.downmixGains,
        )
        writeDecodedFrames(scratchBuffers.monoScratch, frames, state)
    }

    private fun processIntegerPcmOutput(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ) {
        val bytesPerSample = requireNotNull(PcmIntegerSampleReader.bytesPerSample(state.pcmEncoding))
        val samplesCount = info.size / bytesPerSample
        val frames = samplesCount / state.sourceChannels
        scratchBuffers.ensureFloatCapacity(samplesCount)
        scratchBuffers.ensureMonoCapacity(frames)
        PcmIntegerSampleReader.readAsFloat(outputBuffer, state.pcmEncoding, scratchBuffers.floatScratch, samplesCount)
        PcmMonoDownmixer.mixFloatToMono(
            source = scratchBuffers.floatScratch,
            frames = frames,
            sourceChannels = state.sourceChannels,
            channelMask = state.sourceChannelMask,
            destination = scratchBuffers.monoScratch,
            channelGains = state.downmixGains,
        )
        writeDecodedFrames(scratchBuffers.monoScratch, frames, state)
    }

    private fun writeDecodedFrames(monoSamples: FloatArray, frameCount: Int, state: DecoderState) {
        val resampler = state.resampler
        if (resampler != null) {
            val resampledCount = resampler.process(monoSamples, frameCount)
            if (resampledCount > 0) {
                writeFloats(state, resampler.outputBuffer, resampledCount)
            }
        } else {
            writeFloats(state, monoSamples, frameCount)
        }
    }

    private fun flushResampler(state: DecoderState) {
        state.resampler?.let { resampler ->
            val flushCount = resampler.finish()
            if (flushCount > 0) {
                writeFloats(state, resampler.outputBuffer, flushCount)
            }
        }
    }

    private fun writeFloats(state: DecoderState, buffer: FloatArray, count: Int) {
        requireNotNull(state.writer).writeMonoFloats(buffer, count)
    }

    private fun MediaFormat.intValueOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.intValueOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longValueOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback
}

/** Detects decoders that stop producing output after the end of input without ever flagging end of stream. */
internal class DecoderDrainWatchdog(private val timeoutNs: Long, private val nanoTime: () -> Long = System::nanoTime) {
    private var idleSinceNs: Long? = null

    fun onOutput() {
        idleSinceNs = null
    }

    /** Records an empty poll and reports whether the decoder has been idle for at least the timeout. */
    fun isStalled(): Boolean {
        val nowNs = nanoTime()
        val idleSince = idleSinceNs ?: nowNs.also { idleSinceNs = it }
        return nowNs - idleSince >= timeoutNs
    }
}

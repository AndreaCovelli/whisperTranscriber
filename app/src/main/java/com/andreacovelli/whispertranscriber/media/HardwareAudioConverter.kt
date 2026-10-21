package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.requireUserFacing
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
    private const val PROGRESS_STEP = 0.05f
    private const val PROGRESS_INTERVAL_NS = 250_000_000L
    private const val END_OF_STREAM_STALL_TIMEOUT_NS = 5_000_000_000L
    private const val DRAIN_POLL_INTERVAL_MS = 250L

    // Each codec round trip costs milliseconds, so batching access units is what makes long files fast.
    private const val MAX_ACCESS_UNITS_PER_INPUT = 64
    private const val BATCH_INPUT_BYTES = 256 * 1024
    private const val BATCH_OUTPUT_BYTES = 256 * 1024

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

    /** Codec callbacks, delivered in order to the converting coroutine. */
    private sealed interface DecoderEvent {
        class InputAvailable(val index: Int) : DecoderEvent

        /** One output buffer holding one or, with batched input, several decoded access units. */
        class OutputAvailable(val index: Int, val infos: List<MediaCodec.BufferInfo>) : DecoderEvent

        class FormatChanged(val format: MediaFormat) : DecoderEvent

        class Failed(val error: MediaCodec.CodecException) : DecoderEvent
    }

    suspend fun convertTo16kMonoWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        ioDispatcher: CoroutineDispatcher,
        maximumDurationUsExclusive: Long? = null,
        onProgress: (suspend (Float) -> Unit)? = null,
    ): Result = withContext(ioDispatcher) {
        var source: EncodedAudioSource? = null
        var decoder: MediaCodec? = null
        var callbackThread: HandlerThread? = null
        var state: DecoderState? = null
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        var outputFinalized = false

        try {
            val decodable = EncodedAudioSources.openDecodable(context, inputUri)
            source = decodable.source
            val srcFormat = source.format
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

            decoder = MediaCodec.createByCodecName(decodable.decoderName)
            val batchedInput = supportsBatchedInput(decoder, srcFormat)
            callbackThread = HandlerThread("AudioDecoderCallbacks").apply { start() }
            val events = Channel<DecoderEvent>(Channel.UNLIMITED)
            decoder.setCallback(DecoderEventForwarder(events), Handler(callbackThread.looper))
            decoder.configure(decoderFormat(srcFormat, batchedInput), null, null, 0)
            decoder.start()

            decodeAudioStream(
                input = InputFeeder(source, decoder, batchedInput),
                decoder = decoder,
                events = events,
                durationUs = durationUs,
                state = state,
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
            callbackThread?.quitSafely()
            closeBestEffort("Extractor release") { source?.release() }
            closeBestEffort("Writer close") { state?.writer?.close() }
            if (!outputFinalized && outputFile.exists() && !outputFile.delete()) {
                android.util.Log.w("HardwareAudioConverter", "Failed to delete incomplete converted audio")
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

    /** Whether [decoder] accepts several access units per input buffer (Android 15 large audio frames). */
    private fun supportsBatchedInput(decoder: MediaCodec, format: MediaFormat): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: return false
        return try {
            decoder.codecInfo.getCapabilitiesForType(mimeType)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun decoderFormat(sourceFormat: MediaFormat, batchedInput: Boolean): MediaFormat {
        if (!batchedInput || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return sourceFormat
        return MediaFormat(sourceFormat).apply {
            setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                max(intValueOr(MediaFormat.KEY_MAX_INPUT_SIZE, 0), BATCH_INPUT_BYTES),
            )
            setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
            setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
        }
    }

    private suspend fun decodeAudioStream(
        input: InputFeeder,
        decoder: MediaCodec,
        events: ReceiveChannel<DecoderEvent>,
        durationUs: Long,
        state: DecoderState,
        onProgress: (suspend (Float) -> Unit)?,
    ) {
        val scratchBuffers = ScratchBuffers()
        var progressState = ProgressState(lastProgress = -1f, lastDispatchNs = 0L)
        val drainWatchdog = DecoderDrainWatchdog(END_OF_STREAM_STALL_TIMEOUT_NS)
        var outputEos = false

        while (!outputEos) {
            // receive() does not check cancellation while events are already queued.
            currentCoroutineContext().ensureActive()
            val event = if (input.endOfStreamQueued) receiveOrNullAfterPoll(events) else events.receive()
            when (event) {
                null -> if (drainWatchdog.isStalled()) {
                    // Some decoders never flag end of stream; keep the audio decoded so far.
                    android.util.Log.w("HardwareAudioConverter", "Decoder stalled after end of input")
                    outputEos = true
                }
                is DecoderEvent.InputAvailable -> {
                    val presentationTimeUs = input.queue(event.index)
                    progressState = updateProgress(durationUs, presentationTimeUs, progressState, onProgress)
                }
                is DecoderEvent.OutputAvailable -> {
                    drainWatchdog.onOutput()
                    outputEos = processOutputBuffer(decoder, event, state, scratchBuffers)
                }
                is DecoderEvent.FormatChanged -> {
                    drainWatchdog.onOutput()
                    updateDecoderState(event.format, state)
                }
                is DecoderEvent.Failed -> throw event.error
            }
        }
    }

    /** Waits briefly for the next event; unlike a timeout around receive(), select never drops one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun receiveOrNullAfterPoll(events: ReceiveChannel<DecoderEvent>): DecoderEvent? = select {
        events.onReceive { it }
        onTimeout(DRAIN_POLL_INTERVAL_MS) { null }
    }

    /** Forwards codec callbacks without touching codec state, which stays owned by the converting coroutine. */
    private class DecoderEventForwarder(private val events: SendChannel<DecoderEvent>) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            events.trySend(DecoderEvent.InputAvailable(index))
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            events.trySend(DecoderEvent.OutputAvailable(index, listOf(info.copy())))
        }

        override fun onOutputBuffersAvailable(
            codec: MediaCodec,
            index: Int,
            infos: java.util.ArrayDeque<MediaCodec.BufferInfo>,
        ) {
            events.trySend(DecoderEvent.OutputAvailable(index, infos.map { info -> info.copy() }))
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            events.trySend(DecoderEvent.FormatChanged(format))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            events.trySend(DecoderEvent.Failed(e))
        }

        // The codec may reuse its BufferInfo objects once the callback returns.
        private fun MediaCodec.BufferInfo.copy() = MediaCodec.BufferInfo().also { copy ->
            copy.set(offset, size, presentationTimeUs, flags)
        }
    }

    /** Fills decoder input buffers from [source], several access units per buffer when [batched]. */
    private class InputFeeder(
        private val source: EncodedAudioSource,
        private val decoder: MediaCodec,
        private val batched: Boolean,
    ) {
        private val batchInfos = java.util.ArrayDeque<MediaCodec.BufferInfo>()

        var endOfStreamQueued = false
            private set

        /** Queues input into buffer [index]; returns the last presentation time queued, or -1 if none. */
        fun queue(index: Int): Long {
            // The codec keeps offering its remaining buffers after the end of stream; they stay unused.
            if (endOfStreamQueued) return -1L
            val buffer = requireNotNull(decoder.getInputBuffer(index))
            buffer.clear()
            return if (batched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                queueBatch(index, buffer)
            } else {
                queueSingle(index, buffer)
            }
        }

        private fun queueSingle(index: Int, buffer: ByteBuffer): Long {
            val size = source.readSampleData(buffer, 0)
            if (size < 0) {
                queueEndOfStream(index)
                return -1L
            }
            val presentationTimeUs = source.sampleTime
            decoder.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
            source.advance()
            return presentationTimeUs
        }

        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        private fun queueBatch(index: Int, buffer: ByteBuffer): Long {
            batchInfos.clear()
            while (batchInfos.size < MAX_ACCESS_UNITS_PER_INPUT && appendNextUnit(buffer)) {
                source.advance()
            }
            if (batchInfos.isEmpty()) {
                queueEndOfStream(index)
                return -1L
            }
            decoder.queueInputBuffers(index, batchInfos)
            return batchInfos.last.presentationTimeUs
        }

        /** Appends the current access unit after the bytes already in [buffer]; false if it ended or cannot fit. */
        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        private fun appendNextUnit(buffer: ByteBuffer): Boolean {
            val sampleSize = source.sampleSize
            val offset = buffer.position()
            // An oversized first unit is still read, so it fails exactly as it would unbatched.
            val fits = sampleSize >= 0 && (batchInfos.isEmpty() || sampleSize <= buffer.capacity() - offset)
            val size = if (fits) source.readSampleData(buffer, offset) else -1
            if (size < 0) return false
            batchInfos.add(MediaCodec.BufferInfo().apply { set(offset, size, source.sampleTime, 0) })
            buffer.limit(buffer.capacity())
            buffer.position(offset + size)
            return true
        }

        private fun queueEndOfStream(index: Int) {
            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            endOfStreamQueued = true
        }
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

    /** Writes every access unit in the output buffer, releases it, and reports whether it ended the stream. */
    private fun processOutputBuffer(
        decoder: MediaCodec,
        output: DecoderEvent.OutputAvailable,
        state: DecoderState,
        scratchBuffers: ScratchBuffers,
    ): Boolean {
        var endOfStream = false
        val outputBuffer = requireNotNull(decoder.getOutputBuffer(output.index))
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (info in output.infos) {
            if (info.size > 0) {
                outputBuffer.limit(info.offset + info.size)
                outputBuffer.position(info.offset)
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
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) endOfStream = true
        }
        decoder.releaseOutputBuffer(output.index, false)
        return endOfStream
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

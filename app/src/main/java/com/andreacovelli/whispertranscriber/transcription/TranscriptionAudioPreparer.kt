package com.andreacovelli.whispertranscriber.transcription

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.media.AudioChunk
import com.andreacovelli.whispertranscriber.media.CONVERTED_AUDIO_SAMPLE_RATE
import com.andreacovelli.whispertranscriber.media.ConversionOutputStorage
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import com.andreacovelli.whispertranscriber.media.PCM16_BYTES_PER_SAMPLE
import com.andreacovelli.whispertranscriber.media.Pcm16MonoWavFile
import com.andreacovelli.whispertranscriber.media.Pcm16WavWriter
import com.andreacovelli.whispertranscriber.media.planAudioChunks
import com.andreacovelli.whispertranscriber.media.samplesForDurationUs
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal sealed interface PreparedAudioInput {
    val tempFileToClean: File?

    /** Duration of the whole input in microseconds, or a negative value when unknown. */
    val durationUs: Long

    data class MicrosoftSpeech(
        val file: File,
        val mimeType: String,
        val inspection: AudioInspection,
        override val tempFileToClean: File?,
    ) : PreparedAudioInput {
        override val durationUs: Long get() = inspection.durationUs
    }

    data class Gemini(
        val file: File,
        val mimeType: String,
        override val tempFileToClean: File?,
        override val durationUs: Long,
    ) : PreparedAudioInput

    /** A converted WAV too long for one request; each part is extracted with [TranscriptionAudioPreparer.preparePart]. */
    data class Chunked(val file: File, val chunks: List<AudioChunk>, override val tempFileToClean: File?) :
        PreparedAudioInput {
        override val durationUs: Long
            get() = chunks.last().endSample * MICROSECONDS_PER_SECOND / CONVERTED_AUDIO_SAMPLE_RATE
    }
}

private const val MICROSECONDS_PER_SECOND = 1_000_000L

/** Receives job progress while audio is prepared. A null [progress] keeps the stored progress. */
internal interface AudioPreparationListener {
    suspend fun onStatus(status: UserMessage, progress: Float?)

    suspend fun onNotification(text: UserMessage)
}

/** Turns staged audio into a provider-ready local file, converting or copying it when needed. */
@Singleton
class TranscriptionAudioPreparer @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    internal suspend fun prepare(
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        listener: AudioPreparationListener,
    ): PreparedAudioInput {
        // Checked on every run, so jobs queued under a mislabeled type are corrected on retry.
        val mimeType = TranscriptionMimeSupport.resolveUploadMimeType(inputMimeType, readContainerHeader(uri))
        val conversionNeeded = mimeType == null ||
            (usingMicrosoftSpeech && shouldConvertForMicrosoftSpeech(mimeType)) ||
            (!usingMicrosoftSpeech && mimeType !in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        val policy = AudioChunkingPolicy.forProvider(usingMicrosoftSpeech)
        // Splitting works on decoded PCM, so long recordings are converted even when the format is supported.
        val chunkingNeeded = !conversionNeeded && policy.requiresChunking(
            reusableLocalFileForUri(uri)?.let { file -> withContext(ioDispatcher) { inspectDurationUsOrUnknown(file) } }
                ?: -1L,
        )
        return if (conversionNeeded || chunkingNeeded) {
            prepareConvertedAudio(jobId, uri, mimeType, usingMicrosoftSpeech, chunkingNeeded, listener)
        } else {
            prepareNativeAudio(jobId, uri, mimeType, usingMicrosoftSpeech, listener)
        }
    }

    /** Reads the first bytes of [uri]; an unreadable input yields none and fails later with its real error. */
    private suspend fun readContainerHeader(uri: Uri): ByteArray = withContext(ioDispatcher) {
        try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(TranscriptionMimeSupport.CONTAINER_SIGNATURE_BYTES)
                var bytesRead = 0
                while (bytesRead < header.size) {
                    val count = input.read(header, bytesRead, header.size - bytesRead)
                    if (count < 0) break
                    bytesRead += count
                }
                header.copyOf(bytesRead)
            } ?: ByteArray(0)
        } catch (_: IOException) {
            ByteArray(0)
        } catch (_: SecurityException) {
            ByteArray(0)
        }
    }

    /** Deletes a temporary file created by [prepare]; failures are logged and otherwise ignored. */
    internal fun deleteTemporaryFile(file: File) {
        val deleted = try {
            file.delete()
        } catch (error: SecurityException) {
            Log.w(TAG, "Temporary audio cleanup was denied", error)
            false
        }
        if (!deleted && file.exists()) {
            Log.w(TAG, "Failed to delete temporary audio")
        }
    }

    private fun shouldConvertForMicrosoftSpeech(inputMimeType: String): Boolean =
        inputMimeType !in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes ||
            inputMimeType in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes

    /**
     * Writes part [index] of [input] to its own temporary WAV file. The caller owns the returned input's
     * [PreparedAudioInput.tempFileToClean] and must delete it once the part is transcribed.
     */
    internal suspend fun preparePart(
        jobId: Int,
        input: PreparedAudioInput.Chunked,
        index: Int,
        usingMicrosoftSpeech: Boolean,
    ): PreparedAudioInput = withContext(ioDispatcher) {
        val chunk = input.chunks[index]
        val dataBytes = chunk.sampleCount * PCM16_BYTES_PER_SAMPLE
        val durationUs = chunk.sampleCount * MICROSECONDS_PER_SECOND / CONVERTED_AUDIO_SAMPLE_RATE
        val outputFile = File.createTempFile(
            "${QueuedAudioStore.CONVERTED_FILE_PREFIX}${jobId}_part",
            ".wav",
            applicationContext.cacheDir,
        )
        var written = false
        try {
            Pcm16WavWriter(outputFile, dataBytes).use { writer ->
                ConversionOutputStorage.reserve(
                    storageManager = applicationContext.getSystemService(StorageManager::class.java),
                    fileDescriptor = writer.fileDescriptor,
                    outputFile = outputFile,
                    durationUs = durationUs,
                    maximumOutputDataBytes = dataBytes,
                )
                Pcm16MonoWavFile(input.file).copyChunk(chunk, writer)
                writer.finish()
            }
            val prepared = if (usingMicrosoftSpeech) {
                val inspection = AudioInspection(sizeBytes = outputFile.length(), durationUs = durationUs)
                validateMicrosoftSpeechAudio(inspection)
                PreparedAudioInput.MicrosoftSpeech(outputFile, "audio/wav", inspection, tempFileToClean = outputFile)
            } else {
                PreparedAudioInput.Gemini(
                    outputFile,
                    "audio/wav",
                    tempFileToClean = outputFile,
                    durationUs = durationUs,
                )
            }
            written = true
            prepared
        } finally {
            if (!written) {
                withContext(NonCancellable) { deleteTemporaryFile(outputFile) }
            }
        }
    }

    private suspend fun prepareConvertedAudio(
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        convertingForChunking: Boolean,
        listener: AudioPreparationListener,
    ): PreparedAudioInput {
        val policy = AudioChunkingPolicy.forProvider(usingMicrosoftSpeech)
        val reasonSuffix = inputMimeType?.let { "($it)" }
            ?: UserMessage(R.string.transcription_status_unknown_format_suffix)
        listener.onStatus(
            if (convertingForChunking) {
                UserMessage(
                    R.string.transcription_status_conversion_for_chunking,
                    listOf(uploadProviderName(usingMicrosoftSpeech)),
                )
            } else {
                UserMessage(
                    R.string.transcription_status_conversion_required,
                    listOf(uploadProviderName(usingMicrosoftSpeech), reasonSuffix),
                )
            },
            0f,
        )
        listener.onNotification(UserMessage(R.string.transcription_notification_converting))
        var createdFile: File? = null
        var converted = false
        var lastReportedProgressBucket = -1
        try {
            val outputFile = withContext(ioDispatcher) {
                File.createTempFile(
                    "${QueuedAudioStore.CONVERTED_FILE_PREFIX}$jobId",
                    ".wav",
                    applicationContext.cacheDir,
                ).also { createdFile = it }
            }
            val conversionResult = HardwareAudioConverter.convertTo16kMonoWav(
                context = applicationContext,
                inputUri = uri,
                outputFile = outputFile,
                ioDispatcher = ioDispatcher,
                maximumDurationUsExclusive = CHUNKED_AUDIO_DURATION_LIMIT_US_EXCLUSIVE,
                onProgress = { progress ->
                    val normalizedProgress = progress.coerceIn(0f, 1f)
                    val progressBucket = if (normalizedProgress >= 1f) {
                        100
                    } else {
                        ((normalizedProgress * 100).toInt() / 5) * 5
                    }
                    if (progressBucket > lastReportedProgressBucket) {
                        lastReportedProgressBucket = progressBucket
                        listener.onStatus(
                            UserMessage(
                                R.string.transcription_status_converting_progress,
                                listOf(normalizedProgress * 100),
                            ),
                            normalizedProgress,
                        )
                    }
                },
            )
            val inspection = AudioInspection(
                sizeBytes = outputFile.length(),
                durationUs = conversionResult.durationUs,
            )
            val preparedInput = if (policy.requiresChunking(inspection.durationUs)) {
                val chunks = withContext(ioDispatcher) { planChunks(outputFile, policy) }
                listener.onStatus(
                    UserMessage(
                        R.string.transcription_status_split_into_parts,
                        listOf(QuantityText(R.plurals.transcription_part_count, chunks.size)),
                    ),
                    null,
                )
                PreparedAudioInput.Chunked(file = outputFile, chunks = chunks, tempFileToClean = outputFile)
            } else if (usingMicrosoftSpeech) {
                validateMicrosoftSpeechAudio(inspection)
                listener.onStatus(UserMessage(R.string.transcription_status_converted_ready_microsoft), null)
                PreparedAudioInput.MicrosoftSpeech(
                    file = outputFile,
                    mimeType = "audio/wav",
                    inspection = inspection,
                    tempFileToClean = outputFile,
                )
            } else {
                listener.onStatus(UserMessage(R.string.transcription_status_converted_ready_gemini), null)
                PreparedAudioInput.Gemini(
                    file = outputFile,
                    mimeType = "audio/wav",
                    tempFileToClean = outputFile,
                    durationUs = inspection.durationUs,
                )
            }
            converted = true
            return preparedInput
        } finally {
            if (!converted) {
                withContext(NonCancellable + ioDispatcher) { createdFile?.let(::deleteTemporaryFile) }
            }
        }
    }

    private fun planChunks(wavFile: File, policy: AudioChunkingPolicy): List<AudioChunk> {
        val wav = Pcm16MonoWavFile(wavFile)
        return planAudioChunks(
            totalSamples = wav.totalSamples,
            maxChunkSamples = samplesForDurationUs(policy.maxChunkDurationUs),
            searchWindowSamples = samplesForDurationUs(CHUNK_SEARCH_WINDOW_US),
            frameEnergies = wav::frameEnergies,
        )
    }

    private suspend fun prepareNativeAudio(
        jobId: Int,
        uri: Uri,
        inputMimeType: String,
        usingMicrosoftSpeech: Boolean,
        listener: AudioPreparationListener,
    ): PreparedAudioInput = if (usingMicrosoftSpeech) {
        val reusableLocalFile = reusableLocalFileForUri(uri)
        if (reusableLocalFile != null) {
            listener.onStatus(UserMessage(R.string.transcription_status_preparing_local_microsoft), 1f)
            PreparedAudioInput.MicrosoftSpeech(
                file = reusableLocalFile,
                mimeType = inputMimeType,
                inspection = inspectAudioFile(reusableLocalFile).also(::validateMicrosoftSpeechAudio),
                tempFileToClean = null,
            )
        } else {
            listener.onStatus(
                UserMessage(R.string.transcription_status_copying_supported_microsoft, listOf(inputMimeType)),
                0.5f,
            )
            listener.onNotification(UserMessage(R.string.transcription_notification_copying))
            val tempFile = copyInputToTempFile(jobId, uri)
            listener.onStatus(UserMessage(R.string.transcription_status_preparing_upload_microsoft), 1f)
            PreparedAudioInput.MicrosoftSpeech(
                file = tempFile,
                mimeType = inputMimeType,
                inspection = inspectAudioFile(tempFile).also(::validateMicrosoftSpeechAudio),
                tempFileToClean = tempFile,
            )
        }
    } else {
        val reusableLocalFile = reusableLocalFileForUri(uri)
        if (reusableLocalFile != null) {
            listener.onStatus(UserMessage(R.string.transcription_status_preparing_local_gemini), 1f)
            PreparedAudioInput.Gemini(
                file = reusableLocalFile.also(::validateMeasurableGeminiAudio),
                mimeType = inputMimeType,
                tempFileToClean = null,
                durationUs = inspectDurationUsOrUnknown(reusableLocalFile),
            )
        } else {
            listener.onStatus(
                UserMessage(R.string.transcription_status_copying_supported_gemini, listOf(inputMimeType)),
                0.5f,
            )
            listener.onNotification(UserMessage(R.string.transcription_notification_copying))
            val tempFile = copyInputToTempFile(jobId, uri)
            listener.onStatus(UserMessage(R.string.transcription_status_preparing_upload_gemini), 1f)
            PreparedAudioInput.Gemini(
                file = tempFile.also(::validateMeasurableGeminiAudio),
                mimeType = inputMimeType,
                tempFileToClean = tempFile,
                durationUs = inspectDurationUsOrUnknown(tempFile),
            )
        }
    }

    private suspend fun copyInputToTempFile(jobId: Int, uri: Uri): File = withContext(ioDispatcher) {
        val tempFile = File.createTempFile(
            "${QueuedAudioStore.DIRECT_FILE_PREFIX}$jobId",
            ".tmp",
            applicationContext.cacheDir,
        )
        var copied = false
        try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use(input::copyTo)
            } ?: throw IOException("Failed to read input audio file.")
            copied = true
            tempFile
        } finally {
            if (!copied) {
                deleteTemporaryFile(tempFile)
            }
        }
    }

    private fun reusableLocalFileForUri(uri: Uri): File? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return null

        val path = uri.path?.takeIf(String::isNotBlank) ?: return null
        val candidate = File(path)
        return candidate.takeIf { it.isAbsolute && it.exists() && it.canRead() }
    }

    private fun uploadProviderName(usingMicrosoftSpeech: Boolean): UserMessage = if (usingMicrosoftSpeech) {
        UserMessage(R.string.provider_option_microsoft_speech)
    } else {
        UserMessage(R.string.transcription_provider_gemini)
    }

    private companion object {
        const val TAG = "TranscriptionAudioPrep"
    }
}

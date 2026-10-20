package com.andreacovelli.whispertranscriber.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
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

    data class MicrosoftSpeech(
        val file: File,
        val mimeType: String,
        val inspection: AudioInspection,
        override val tempFileToClean: File?,
    ) : PreparedAudioInput

    data class Gemini(val file: File, val mimeType: String, override val tempFileToClean: File?) : PreparedAudioInput
}

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
        val conversionNeeded = inputMimeType == null ||
            (usingMicrosoftSpeech && shouldConvertForMicrosoftSpeech(inputMimeType)) ||
            (!usingMicrosoftSpeech && inputMimeType !in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        return if (conversionNeeded) {
            prepareConvertedAudio(jobId, uri, inputMimeType, usingMicrosoftSpeech, listener)
        } else {
            prepareNativeAudio(jobId, uri, inputMimeType, usingMicrosoftSpeech, listener)
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

    private suspend fun prepareConvertedAudio(
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        listener: AudioPreparationListener,
    ): PreparedAudioInput {
        val reasonSuffix = inputMimeType?.let { "($it)" }
            ?: UserMessage(R.string.transcription_status_unknown_format_suffix)
        listener.onStatus(
            UserMessage(
                R.string.transcription_status_conversion_required,
                listOf(uploadProviderName(usingMicrosoftSpeech), reasonSuffix),
            ),
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
                maximumDurationUsExclusive = if (usingMicrosoftSpeech) {
                    MICROSOFT_SPEECH_MAX_DURATION_US
                } else {
                    GEMINI_CONVERSION_DURATION_LIMIT_US_EXCLUSIVE
                },
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
            if (usingMicrosoftSpeech) {
                validateMicrosoftSpeechAudio(inspection)
            }
            val preparedInput = if (usingMicrosoftSpeech) {
                listener.onStatus(UserMessage(R.string.transcription_status_converted_ready_microsoft), null)
                PreparedAudioInput.MicrosoftSpeech(
                    file = outputFile,
                    mimeType = "audio/wav",
                    inspection = inspection,
                    tempFileToClean = outputFile,
                )
            } else {
                listener.onStatus(UserMessage(R.string.transcription_status_converted_ready_gemini), null)
                PreparedAudioInput.Gemini(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
            }
            converted = true
            return preparedInput
        } finally {
            if (!converted) {
                withContext(NonCancellable + ioDispatcher) { createdFile?.let(::deleteTemporaryFile) }
            }
        }
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

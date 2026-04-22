package com.andreacovelli.whispertranscriber

import android.app.Application
import android.content.Context
import android.database.SQLException
import android.net.Uri
import androidx.core.net.toUri
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal sealed interface JobRunOutcome {
    data object Success : JobRunOutcome
    data object Skipped : JobRunOutcome
    data object Retry : JobRunOutcome
    data object Failure : JobRunOutcome
}

internal sealed interface QueuedAudioInputResolution {
    data class Ready(val uri: Uri) : QueuedAudioInputResolution

    data class MissingStagedFile(val message: String) : QueuedAudioInputResolution
}

private sealed interface PreparedAudioInput {
    val tempFileToClean: File?

    data class MicrosoftSpeech(val file: File, val mimeType: String, override val tempFileToClean: File?) :
        PreparedAudioInput

    data class Gemini(val file: File, val mimeType: String, override val tempFileToClean: File?) : PreparedAudioInput
}

@Singleton
class TranscriptionExecutionRunner @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val summarizationService: SummarizationService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    internal suspend fun runJob(
        jobId: Int,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): JobRunOutcome {
        val existingJob = repository.getJob(jobId) ?: return JobRunOutcome.Failure
        val displayName = existingJob.displayName

        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        if (existingJob.status == "Cancelled") return JobRunOutcome.Success
        if (!repository.claimJobForExecution(jobId, "Starting...")) return JobRunOutcome.Skipped

        val job = repository.getJob(jobId) ?: return JobRunOutcome.Failure
        val postTranscriptionWorkSpec = job.postTranscriptionWorkSpec()

        return try {
            onProgressNotification(jobId, displayName, "Processing: ${job.displayName}")

            val requestedProvider = decodeStoredTranscriptionProvider(job.providerName)
            val usingMicrosoftSpeech = requestedProvider == TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE
            val geminiModelName = when (requestedProvider) {
                TranscriptionProvider.ELEVEN_LABS, TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE -> null
                else -> job.requestedModel ?: requestedProvider.modelName
            }

            val stagedUri = when (val resolution = QueuedAudioInputResolver.resolve(job)) {
                is QueuedAudioInputResolution.Ready -> resolution.uri
                is QueuedAudioInputResolution.MissingStagedFile -> {
                    repository.updateError(jobId, resolution.message, false, "Error")
                    notifyPostTranscriptionFailure(
                        postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                        jobId = jobId,
                        displayName = job.displayName,
                        message = resolution.message,
                    )
                    return JobRunOutcome.Failure
                }
            }

            processSingleJob(
                jobId = jobId,
                uri = stagedUri,
                displayName = job.displayName,
                inputMimeType = job.sourceMimeType?.lowercase(),
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                geminiModel = geminiModelName,
                speakerDiarizationEnabled = job.speakerDiarizationEnabled,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                onProgressNotification = onProgressNotification,
            )
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
            JobRunOutcome.Success
        } catch (e: CancellationException) {
            repository.resetRunningToQueued(jobId)
            throw e
        } catch (e: TransientWorkException) {
            val message = e.message ?: e::class.java.simpleName
            val retryMessage = applicationContext.getString(R.string.transcription_retry_notification_body, message)
            repository.markRetryScheduled(jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
            backgroundShareNotifier.showRetryScheduled(jobId, displayName, retryMessage)
            JobRunOutcome.Retry
        } catch (e: IOException) {
            handleTerminalFailure(jobId, displayName, postTranscriptionWorkSpec, e.message ?: e::class.java.simpleName)
            JobRunOutcome.Failure
        } catch (e: IllegalStateException) {
            handleTerminalFailure(jobId, displayName, postTranscriptionWorkSpec, e.message ?: e::class.java.simpleName)
            JobRunOutcome.Failure
        } catch (e: IllegalArgumentException) {
            handleTerminalFailure(jobId, displayName, postTranscriptionWorkSpec, e.message ?: e::class.java.simpleName)
            JobRunOutcome.Failure
        } catch (e: SQLException) {
            handleTerminalFailure(jobId, displayName, postTranscriptionWorkSpec, e.message ?: e::class.java.simpleName)
            JobRunOutcome.Failure
        }
    }

    private suspend fun handleTerminalFailure(
        jobId: Int,
        displayName: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        message: String,
    ) {
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        repository.updateError(jobId, message, false, "Error")
        notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, displayName, message)
    }

    private suspend fun processSingleJob(
        jobId: Int,
        uri: Uri,
        displayName: String,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ) {
        val preparedInput = prepareAudioInput(
            jobId = jobId,
            uri = uri,
            inputMimeType = inputMimeType,
            usingMicrosoftSpeech = usingMicrosoftSpeech,
            displayName = displayName,
            onProgressNotification = onProgressNotification,
        )
        try {
            val result = runProviderTranscription(
                jobId = jobId,
                displayName = displayName,
                preparedInput = preparedInput,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                geminiModel = geminiModel,
                speakerDiarizationEnabled = speakerDiarizationEnabled,
                onProgressNotification = onProgressNotification,
            )
            persistCompletedTranscription(jobId, displayName, result)
            if (postTranscriptionWorkSpec?.summarizeAfterTranscription == true) {
                val summarizationResult = summarizationService.summarize(
                    jobId = jobId,
                    settings = postTranscriptionWorkSpec.summarizationSettings,
                    allowResummarize = false,
                    fallbackStatusMessage = "Completed",
                )
                if (postTranscriptionWorkSpec.notifyResult) {
                    if (summarizationResult.errorMessage == null) {
                        val completedJob = repository.getJob(jobId)
                        backgroundShareNotifier.showSummary(
                            jobId = jobId,
                            displayName = displayName,
                            summary = completedJob?.summary.orEmpty().ifBlank {
                                summarizationResult.statusMessage
                            },
                        )
                    } else {
                        backgroundShareNotifier.showFailure(
                            jobId = jobId,
                            displayName = displayName,
                            message = summarizationResult.errorMessage,
                        )
                    }
                }
            } else if (postTranscriptionWorkSpec?.notifyResult == true) {
                backgroundShareNotifier.showTranscription(
                    jobId = jobId,
                    displayName = displayName,
                    transcript = result.text,
                )
            }
        } finally {
            preparedInput.tempFileToClean?.let { file ->
                runCatching { file.delete() }
                    .onFailure { throwable ->
                        android.util.Log.w(
                            "TranscriptionExecutionRunner",
                            "Failed to delete temp file: ${file.absolutePath}",
                            throwable,
                        )
                    }
            }
        }
    }

    private suspend fun prepareAudioInput(
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): PreparedAudioInput {
        val app = applicationContext as Application
        val conversionNeeded = inputMimeType == null ||
            (usingMicrosoftSpeech && shouldConvertForMicrosoftSpeech(inputMimeType)) ||
            (!usingMicrosoftSpeech && inputMimeType !in TranscriptionMimeSupport.geminiNativeUploadMimeTypes)
        return if (conversionNeeded) {
            prepareConvertedAudio(
                app = app,
                jobId = jobId,
                uri = uri,
                inputMimeType = inputMimeType,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                displayName = displayName,
                onProgressNotification = onProgressNotification,
            )
        } else {
            prepareNativeAudio(
                app = app,
                jobId = jobId,
                uri = uri,
                inputMimeType = inputMimeType,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                displayName = displayName,
                onProgressNotification = onProgressNotification,
            )
        }
    }

    private fun shouldConvertForMicrosoftSpeech(inputMimeType: String): Boolean =
        inputMimeType !in TranscriptionMimeSupport.microsoftSpeechNativeUploadMimeTypes ||
            inputMimeType in TranscriptionMimeSupport.microsoftSpeechWavReencodeMimeTypes

    private suspend fun prepareConvertedAudio(
        app: Application,
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): PreparedAudioInput {
        val reasonSuffix = inputMimeType?.let { "($it)" } ?: "(Unknown format)"
        val providerName = if (usingMicrosoftSpeech) "Microsoft Speech" else "Gemini"
        repository.updateStatusAndProgress(
            jobId,
            "Format not natively supported by $providerName $reasonSuffix. Converting to 16 kHz WAV...",
            0f,
        )
        onProgressNotification(jobId, displayName, "Converting audio...")
        val outputFile = withContext(ioDispatcher) {
            File.createTempFile("${QueuedAudioStore.CONVERTED_FILE_PREFIX}$jobId", ".wav", app.cacheDir)
        }
        try {
            HardwareAudioConverter.convertTo16kMonoWav(
                context = app,
                inputUri = uri,
                outputFile = outputFile,
                onProgress = { progress ->
                    repository.updateStatusAndProgress(
                        jobId,
                        "Converting to 16 kHz WAV: ${"%.1f".format(progress * 100)}%",
                        progress,
                    )
                },
            )
        } catch (e: IOException) {
            runCatching { outputFile.delete() }
            throw e
        } catch (e: IllegalArgumentException) {
            runCatching { outputFile.delete() }
            throw e
        } catch (e: IllegalStateException) {
            runCatching { outputFile.delete() }
            throw e
        }
        return if (usingMicrosoftSpeech) {
            repository.updateStatus(jobId, "Converted audio ready for Microsoft Speech...")
            PreparedAudioInput.MicrosoftSpeech(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
        } else {
            repository.updateStatus(jobId, "Converted audio ready for Gemini...")
            PreparedAudioInput.Gemini(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
        }
    }

    private suspend fun prepareNativeAudio(
        app: Application,
        jobId: Int,
        uri: Uri,
        inputMimeType: String,
        usingMicrosoftSpeech: Boolean,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): PreparedAudioInput = if (usingMicrosoftSpeech) {
        val reusableLocalFile = reusableLocalFileForUri(uri)
        if (reusableLocalFile != null) {
            repository.updateStatusAndProgress(jobId, "Preparing local audio for Microsoft Speech upload...", 1f)
            PreparedAudioInput.MicrosoftSpeech(
                file = reusableLocalFile,
                mimeType = inputMimeType,
                tempFileToClean = null,
            )
        } else {
            repository.updateStatusAndProgress(
                jobId,
                "Format supported ($inputMimeType). Copying audio for Microsoft Speech...",
                0.5f,
            )
            onProgressNotification(jobId, displayName, "Copying audio...")
            val tempFile = copyInputToTempFile(app, jobId, uri)
            repository.updateStatusAndProgress(jobId, "Preparing for Microsoft Speech upload...", 1f)
            PreparedAudioInput.MicrosoftSpeech(file = tempFile, mimeType = inputMimeType, tempFileToClean = tempFile)
        }
    } else {
        repository.updateStatusAndProgress(
            jobId,
            "Format supported ($inputMimeType). Copying audio for Gemini...",
            0.5f,
        )
        onProgressNotification(jobId, displayName, "Copying audio...")
        val tempFile = copyInputToTempFile(app, jobId, uri)
        repository.updateStatusAndProgress(jobId, "Preparing for Gemini upload...", 1f)
        PreparedAudioInput.Gemini(file = tempFile, mimeType = inputMimeType, tempFileToClean = tempFile)
    }

    private suspend fun copyInputToTempFile(app: Application, jobId: Int, uri: Uri): File = withContext(ioDispatcher) {
        val contentResolver = app.contentResolver
        val tempFile = File.createTempFile("${QueuedAudioStore.DIRECT_FILE_PREFIX}$jobId", ".tmp", app.cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use(input::copyTo)
            } ?: throw IOException("Failed to read input audio file.")
            tempFile
        } catch (e: IOException) {
            runCatching { tempFile.delete() }
            throw e
        }
    }

    private fun reusableLocalFileForUri(uri: Uri): File? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return null

        val path = uri.path?.takeIf(String::isNotBlank) ?: return null
        val candidate = File(path)
        return candidate.takeIf { it.isAbsolute && it.exists() && it.canRead() }
    }

    private suspend fun runProviderTranscription(
        jobId: Int,
        displayName: String,
        preparedInput: PreparedAudioInput,
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): TranscriptionData {
        val transcriber = buildTranscriber(preparedInput, usingMicrosoftSpeech, geminiModel, speakerDiarizationEnabled)
        var resultData: TranscriptionData? = null
        transcriber.transcribe().collect { result ->
            when (result) {
                is TranscriptionResult.Loading -> {
                    val providerPrefix = if (usingMicrosoftSpeech) "Microsoft Speech" else "Gemini"
                    val providerMessage = "$providerPrefix: ${result.message}"
                    repository.updateStatus(jobId, providerMessage)
                    onProgressNotification(jobId, displayName, providerMessage)
                }
                is TranscriptionResult.Success -> resultData = result.data
                is TranscriptionResult.Error -> throw result.exception
            }
        }
        return checkNotNull(resultData) { "Transcription failed without error." }
    }

    private suspend fun buildTranscriber(
        preparedInput: PreparedAudioInput,
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
    ): ProviderTranscriber = if (usingMicrosoftSpeech) {
        check(!speakerDiarizationEnabled) { "Speaker diarization is not supported by Microsoft MAI Transcribe." }
        val microsoftSpeechInput = checkNotNull(preparedInput as? PreparedAudioInput.MicrosoftSpeech) {
            "Internal error: Microsoft Speech audio data missing."
        }
        val keyChars = requireNotNull(settings.getMicrosoftSpeechApiKey()) {
            "Azure Speech key for Microsoft MAI Transcribe missing or corrupted."
        }
        val region = settings.microsoftSpeechRegionFlow.first()
            .takeIf(String::isNotBlank)
            ?: error("Azure Speech region for Microsoft MAI Transcribe is not set.")
        MicrosoftSpeechTranscriber(
            apiKey = keyChars,
            audioFile = microsoftSpeechInput.file,
            mimeType = microsoftSpeechInput.mimeType,
            region = region,
            okHttpClient = okHttpClient,
            ioDispatcher = ioDispatcher,
        )
    } else {
        val geminiInput = checkNotNull(preparedInput as? PreparedAudioInput.Gemini) {
            "Internal error: Gemini audio data missing."
        }
        val modelName = requireNotNull(geminiModel) { "Gemini model not specified." }
        val keyChars = requireNotNull(settings.getGeminiApiKey()) { "API key missing or corrupted" }
        GeminiTranscriber(
            audioFile = geminiInput.file,
            mimeType = geminiInput.mimeType,
            apiKey = keyChars,
            modelName = modelName,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            ioDispatcher = ioDispatcher,
        )
    }

    private suspend fun persistCompletedTranscription(jobId: Int, displayName: String, data: TranscriptionData) {
        val historyId = repository.insertHistory(
            TranscriptionHistory(
                text = data.text,
                language = data.language.ifBlank { "Unknown" },
                model = data.model,
                displayName = displayName,
            ),
        )
        repository.updateResult(
            jobId,
            data.text,
            data.language.ifBlank { "Unknown" },
            data.model,
            historyId,
            1f,
            false,
            "Completed",
            null,
        )
    }

    private fun notifyPostTranscriptionFailure(
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        jobId: Int,
        displayName: String,
        message: String,
    ) {
        if (postTranscriptionWorkSpec?.notifyResult != true) return
        backgroundShareNotifier.showFailure(
            jobId = jobId,
            displayName = displayName,
            message = message,
        )
    }
}

internal object QueuedAudioInputResolver {
    fun resolve(job: TranscriptionJob): QueuedAudioInputResolution {
        val stagedPath = job.stagedFilePath?.takeIf { it.isNotBlank() }
        if (stagedPath == null) {
            return QueuedAudioInputResolution.Ready(job.uri.toUri())
        }

        val stagedFile = File(stagedPath)
        return if (stagedFile.exists()) {
            QueuedAudioInputResolution.Ready(stagedFile.toUri())
        } else {
            QueuedAudioInputResolution.MissingStagedFile(
                "Queued audio is no longer available. Please share it again.",
            )
        }
    }
}

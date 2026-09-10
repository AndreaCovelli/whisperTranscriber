package com.andreacovelli.whispertranscriber.transcription

import android.app.Application
import android.content.Context
import android.database.SQLException
import android.net.Uri
import androidx.core.net.toUri
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.queue.postTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.security.GenAiClientFactory
import com.andreacovelli.whispertranscriber.services.SummarizationService
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal sealed interface JobRunOutcome {
    data object Success : JobRunOutcome
    data object Skipped : JobRunOutcome
    data object Retry : JobRunOutcome
    data object Failure : JobRunOutcome
    data object Superseded : JobRunOutcome
}

private class SupersededExecutionException : Exception()

private sealed interface ProviderExecution {
    data object MicrosoftSpeech : ProviderExecution

    data class Gemini(val modelName: String) : ProviderExecution
}

internal sealed interface QueuedAudioInputResolution {
    data class Ready(val uri: Uri) : QueuedAudioInputResolution

    data class MissingStagedFile(val message: String) : QueuedAudioInputResolution
}

private sealed interface PreparedAudioInput {
    val tempFileToClean: File?

    data class MicrosoftSpeech(
        val file: File,
        val mimeType: String,
        val inspection: AudioInspection,
        override val tempFileToClean: File?,
    ) : PreparedAudioInput

    data class Gemini(val file: File, val mimeType: String, override val tempFileToClean: File?) : PreparedAudioInput
}

@Singleton
// One lifecycle coordinator owns preparation, provider execution, persistence, and follow-up cleanup.
@Suppress("LargeClass")
class TranscriptionExecutionRunner @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val summarizationService: SummarizationService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val queuedAudioStore: QueuedAudioStore,
    private val genAiClientFactory: GenAiClientFactory,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val MAX_AUTOMATIC_RETRY_ATTEMPTS = 3
    }

    internal suspend fun runJob(
        jobId: Int,
        executionOwnerId: String,
        speakerLabelPrefix: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): JobRunOutcome {
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        val job =
            repository.claimJobForExecution(
                jobId,
                executionOwnerId,
                applicationContext.getString(R.string.transcription_status_starting),
            )
                ?: return when (val current = repository.getJob(jobId)) {
                    null -> JobRunOutcome.Failure
                    else -> when {
                        current.executionOwnerId.isNotEmpty() && current.executionOwnerId != executionOwnerId ->
                            JobRunOutcome.Superseded
                        current.status == JOB_STATUS_CANCELLED -> JobRunOutcome.Success
                        else -> JobRunOutcome.Skipped
                    }
                }
        val displayName = job.displayName
        val postTranscriptionWorkSpec = job.postTranscriptionWorkSpec()

        return try {
            executeClaimedJob(
                job = job,
                executionOwnerId = executionOwnerId,
                speakerLabelPrefix = speakerLabelPrefix,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                onProgressNotification = onProgressNotification,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                repository.resetRunningToQueued(jobId, executionOwnerId)
            }
            throw e
        } catch (_: SupersededExecutionException) {
            JobRunOutcome.Superseded
        } catch (e: TransientWorkException) {
            val message = e.message ?: e::class.java.simpleName
            if (job.retryCount >= MAX_AUTOMATIC_RETRY_ATTEMPTS) {
                val exhaustedMessage =
                    "Automatic retries exhausted after $MAX_AUTOMATIC_RETRY_ATTEMPTS attempts. $message"
                handleTerminalFailure(jobId, executionOwnerId, displayName, postTranscriptionWorkSpec, exhaustedMessage)
                JobRunOutcome.Failure
            } else {
                val retryMessage = applicationContext.getString(R.string.transcription_retry_notification_body, message)
                withContext(NonCancellable + ioDispatcher) {
                    requireOwnedUpdate(repository.markRetryScheduled(jobId, executionOwnerId))
                }
                backgroundShareNotifier.cancelBackgroundProcessing(jobId)
                backgroundShareNotifier.showRetryScheduled(jobId, displayName, retryMessage)
                JobRunOutcome.Retry
            }
        } catch (e: IOException) {
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                e.message ?: e::class.java.simpleName,
            )
            JobRunOutcome.Failure
        } catch (e: SecurityException) {
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                e.message ?: e::class.java.simpleName,
            )
            JobRunOutcome.Failure
        } catch (e: IllegalStateException) {
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                e.message ?: e::class.java.simpleName,
            )
            JobRunOutcome.Failure
        } catch (e: IllegalArgumentException) {
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                e.message ?: e::class.java.simpleName,
            )
            JobRunOutcome.Failure
        } catch (e: SQLException) {
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                e.message ?: e::class.java.simpleName,
            )
            JobRunOutcome.Failure
        }
    }

    private suspend fun executeClaimedJob(
        job: TranscriptionJob,
        executionOwnerId: String,
        speakerLabelPrefix: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): JobRunOutcome {
        onProgressNotification(
            job.id,
            job.displayName,
            applicationContext.getString(R.string.transcription_notification_processing, job.displayName),
        )

        val durableTranscript = if (job.historyId != null) {
            repository.getTranscriptForJob(job.id)
        } else {
            job.transcript
        }
        if (durableTranscript.isNotBlank()) {
            queuedAudioStore.deleteStagedFile(job.stagedFilePath)
            requireOwnedUpdate(repository.clearStagedFilePath(job.id, executionOwnerId))
            resumePostTranscriptionWork(
                jobId = job.id,
                executionOwnerId = executionOwnerId,
                displayName = job.displayName,
                transcript = durableTranscript,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                onProgressNotification = onProgressNotification,
            )
            backgroundShareNotifier.cancelBackgroundProcessing(job.id)
            return JobRunOutcome.Success
        }

        val providerExecution = resolveProviderExecution(job.providerName, job.requestedModel)
        if (providerExecution is ProviderExecution.MicrosoftSpeech) {
            validatePersistedMicrosoftSpeechEndpoint()
        }
        val stagedUri = when (val resolution = QueuedAudioInputResolver.resolve(job)) {
            is QueuedAudioInputResolution.Ready -> resolution.uri
            is QueuedAudioInputResolution.MissingStagedFile -> {
                requireOwnedUpdate(
                    repository.updateError(
                        job.id,
                        executionOwnerId,
                        resolution.message,
                        JOB_STATUS_ERROR,
                    ),
                )
                notifyPostTranscriptionFailure(
                    postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                    jobId = job.id,
                    displayName = job.displayName,
                    message = resolution.message,
                )
                return JobRunOutcome.Failure
            }
        }

        processSingleJob(
            jobId = job.id,
            executionOwnerId = executionOwnerId,
            uri = stagedUri,
            displayName = job.displayName,
            inputMimeType = job.sourceMimeType?.lowercase(),
            providerExecution = providerExecution,
            speakerDiarizationEnabled = job.speakerDiarizationEnabled,
            speakerLabelPrefix = speakerLabelPrefix,
            postTranscriptionWorkSpec = postTranscriptionWorkSpec,
            stagedFilePath = job.stagedFilePath,
            onProgressNotification = onProgressNotification,
        )
        backgroundShareNotifier.cancelBackgroundProcessing(job.id)
        return JobRunOutcome.Success
    }

    private suspend fun handleTerminalFailure(
        jobId: Int,
        executionOwnerId: String,
        displayName: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        message: String,
    ) {
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        if (repository.updateError(jobId, executionOwnerId, message, JOB_STATUS_ERROR)) {
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, displayName, message)
        }
    }

    private suspend fun processSingleJob(
        jobId: Int,
        executionOwnerId: String,
        uri: Uri,
        displayName: String,
        inputMimeType: String?,
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        stagedFilePath: String?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ) {
        val usingMicrosoftSpeech = providerExecution is ProviderExecution.MicrosoftSpeech
        var preparedInput: PreparedAudioInput? = null
        try {
            preparedInput = prepareAudioInput(
                jobId = jobId,
                executionOwnerId = executionOwnerId,
                uri = uri,
                inputMimeType = inputMimeType,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                displayName = displayName,
                onProgressNotification = onProgressNotification,
            )
            val result = runProviderTranscription(
                jobId = jobId,
                executionOwnerId = executionOwnerId,
                displayName = displayName,
                preparedInput = preparedInput,
                providerExecution = providerExecution,
                speakerDiarizationEnabled = speakerDiarizationEnabled,
                speakerLabelPrefix = speakerLabelPrefix,
                onProgressNotification = onProgressNotification,
            )
            requireOwnedUpdate(repository.persistCompletedTranscription(jobId, executionOwnerId, displayName, result))
            queuedAudioStore.deleteStagedFile(stagedFilePath)
            requireOwnedUpdate(repository.clearStagedFilePath(jobId, executionOwnerId))
            resumePostTranscriptionWork(
                jobId = jobId,
                executionOwnerId = executionOwnerId,
                displayName = displayName,
                transcript = result.text,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                onProgressNotification = onProgressNotification,
            )
        } finally {
            preparedInput?.tempFileToClean?.let { file ->
                deleteTemporaryFile(file)
            }
        }
    }

    private suspend fun prepareAudioInput(
        jobId: Int,
        executionOwnerId: String,
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
                executionOwnerId = executionOwnerId,
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
                executionOwnerId = executionOwnerId,
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
        executionOwnerId: String,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): PreparedAudioInput {
        val reasonSuffix = inputMimeType?.let { "($it)" } ?: "(Unknown format)"
        val providerName = uploadProviderName(usingMicrosoftSpeech)
        requireOwnedUpdate(
            repository.updateStatusAndProgress(
                jobId,
                executionOwnerId,
                applicationContext.getString(
                    R.string.transcription_status_conversion_required,
                    providerName,
                    reasonSuffix,
                ),
                0f,
            ),
        )
        onProgressNotification(
            jobId,
            displayName,
            applicationContext.getString(R.string.transcription_notification_converting),
        )
        val outputFile = withContext(ioDispatcher) {
            File.createTempFile("${QueuedAudioStore.CONVERTED_FILE_PREFIX}$jobId", ".wav", app.cacheDir)
        }
        var converted = false
        var lastReportedProgressBucket = -1
        try {
            val conversionResult = HardwareAudioConverter.convertTo16kMonoWav(
                context = app,
                inputUri = uri,
                outputFile = outputFile,
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
                        requireOwnedUpdate(
                            repository.updateStatusAndProgress(
                                jobId,
                                executionOwnerId,
                                applicationContext.getString(
                                    R.string.transcription_status_converting_progress,
                                    normalizedProgress * 100,
                                ),
                                normalizedProgress,
                            ),
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
                requireOwnedUpdate(
                    repository.updateStatus(
                        jobId,
                        executionOwnerId,
                        applicationContext.getString(R.string.transcription_status_converted_ready_microsoft),
                    ),
                )
                PreparedAudioInput.MicrosoftSpeech(
                    file = outputFile,
                    mimeType = "audio/wav",
                    inspection = inspection,
                    tempFileToClean = outputFile,
                )
            } else {
                requireOwnedUpdate(
                    repository.updateStatus(
                        jobId,
                        executionOwnerId,
                        applicationContext.getString(R.string.transcription_status_converted_ready_gemini),
                    ),
                )
                PreparedAudioInput.Gemini(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
            }
            converted = true
            return preparedInput
        } finally {
            if (!converted) {
                deleteTemporaryFile(outputFile)
            }
        }
    }

    private suspend fun prepareNativeAudio(
        app: Application,
        jobId: Int,
        executionOwnerId: String,
        uri: Uri,
        inputMimeType: String,
        usingMicrosoftSpeech: Boolean,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): PreparedAudioInput = if (usingMicrosoftSpeech) {
        val reusableLocalFile = reusableLocalFileForUri(uri)
        if (reusableLocalFile != null) {
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(R.string.transcription_status_preparing_local_microsoft),
                    1f,
                ),
            )
            PreparedAudioInput.MicrosoftSpeech(
                file = reusableLocalFile,
                mimeType = inputMimeType,
                inspection = inspectAudioFile(reusableLocalFile).also(::validateMicrosoftSpeechAudio),
                tempFileToClean = null,
            )
        } else {
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(
                        R.string.transcription_status_copying_supported_microsoft,
                        inputMimeType,
                    ),
                    0.5f,
                ),
            )
            onProgressNotification(
                jobId,
                displayName,
                applicationContext.getString(R.string.transcription_notification_copying),
            )
            val tempFile = copyInputToTempFile(app, jobId, uri)
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(R.string.transcription_status_preparing_upload_microsoft),
                    1f,
                ),
            )
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
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(R.string.transcription_status_preparing_local_gemini),
                    1f,
                ),
            )
            PreparedAudioInput.Gemini(
                file = reusableLocalFile,
                mimeType = inputMimeType,
                tempFileToClean = null,
            )
        } else {
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(
                        R.string.transcription_status_copying_supported_gemini,
                        inputMimeType,
                    ),
                    0.5f,
                ),
            )
            onProgressNotification(
                jobId,
                displayName,
                applicationContext.getString(R.string.transcription_notification_copying),
            )
            val tempFile = copyInputToTempFile(app, jobId, uri)
            requireOwnedUpdate(
                repository.updateStatusAndProgress(
                    jobId,
                    executionOwnerId,
                    applicationContext.getString(R.string.transcription_status_preparing_upload_gemini),
                    1f,
                ),
            )
            PreparedAudioInput.Gemini(file = tempFile, mimeType = inputMimeType, tempFileToClean = tempFile)
        }
    }

    private suspend fun copyInputToTempFile(app: Application, jobId: Int, uri: Uri): File = withContext(ioDispatcher) {
        val contentResolver = app.contentResolver
        val tempFile = File.createTempFile("${QueuedAudioStore.DIRECT_FILE_PREFIX}$jobId", ".tmp", app.cacheDir)
        var copied = false
        try {
            contentResolver.openInputStream(uri)?.use { input ->
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

    private fun deleteTemporaryFile(file: File) {
        val deleted = try {
            file.delete()
        } catch (error: SecurityException) {
            android.util.Log.w("TranscriptionExecutionRunner", "Temporary audio cleanup was denied", error)
            false
        }
        if (!deleted && file.exists()) {
            android.util.Log.w("TranscriptionExecutionRunner", "Failed to delete temporary audio")
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
        executionOwnerId: String,
        displayName: String,
        preparedInput: PreparedAudioInput,
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ): TranscriptionData {
        val transcriber = buildTranscriber(
            preparedInput = preparedInput,
            providerExecution = providerExecution,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            speakerLabelPrefix = speakerLabelPrefix,
        )
        var resultData: TranscriptionData? = null
        transcriber.transcribe().collect { result ->
            when (result) {
                is TranscriptionResult.Loading -> {
                    val providerMessage = applicationContext.getString(
                        R.string.transcription_status_provider_message,
                        providerDisplayName(providerExecution),
                        result.message,
                    )
                    requireOwnedUpdate(repository.updateStatus(jobId, executionOwnerId, providerMessage))
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
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
    ): ProviderTranscriber = when (providerExecution) {
        ProviderExecution.MicrosoftSpeech -> {
            check(!speakerDiarizationEnabled) { "Speaker diarization is not supported by Microsoft MAI Transcribe." }
            val microsoftSpeechInput = checkNotNull(preparedInput as? PreparedAudioInput.MicrosoftSpeech) {
                "Internal error: Microsoft Speech audio data missing."
            }
            val keyChars = requireNotNull(settings.getMicrosoftSpeechApiKey()) {
                "Azure Speech key for Microsoft MAI Transcribe missing or corrupted."
            }
            var keyOwnershipTransferred = false
            try {
                val endpoint = MicrosoftSpeechEndpoint.parse(settings.microsoftSpeechRegionFlow.first())
                val transcriber = MicrosoftSpeechTranscriber(
                    apiKey = keyChars,
                    audioFile = microsoftSpeechInput.file,
                    mimeType = microsoftSpeechInput.mimeType,
                    endpoint = endpoint,
                    audioInspection = microsoftSpeechInput.inspection,
                    okHttpClient = okHttpClient,
                    ioDispatcher = ioDispatcher,
                )
                keyOwnershipTransferred = true
                transcriber
            } finally {
                if (!keyOwnershipTransferred) {
                    keyChars.fill('\u0000')
                }
            }
        }
        is ProviderExecution.Gemini -> {
            val geminiInput = checkNotNull(preparedInput as? PreparedAudioInput.Gemini) {
                "Internal error: Gemini audio data missing."
            }
            val keyChars = requireNotNull(settings.getGeminiApiKey()) { "API key missing or corrupted" }
            var keyOwnershipTransferred = false
            try {
                val transcriber = GeminiTranscriber(
                    audioFile = geminiInput.file,
                    mimeType = geminiInput.mimeType,
                    apiKey = keyChars,
                    modelName = providerExecution.modelName,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    speakerLabelPrefix = speakerLabelPrefix,
                    clientFactory = genAiClientFactory,
                    ioDispatcher = ioDispatcher,
                )
                keyOwnershipTransferred = true
                transcriber
            } finally {
                if (!keyOwnershipTransferred) {
                    keyChars.fill('\u0000')
                }
            }
        }
    }

    private fun resolveProviderExecution(providerName: String?, requestedModel: String?): ProviderExecution =
        when (val provider = decodeTranscriptionProvider(providerName)) {
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            -> ProviderExecution.MicrosoftSpeech
            TranscriptionProvider.GEMINI_FLASH_LITE,
            TranscriptionProvider.GEMINI_FLASH,
            TranscriptionProvider.GEMINI_PRO,
            -> ProviderExecution.Gemini(requestedModel ?: requireNotNull(provider.modelName))
            TranscriptionProvider.GEMINI_CUSTOM -> ProviderExecution.Gemini(
                modelName = requestedModel?.takeIf(String::isNotBlank)
                    ?: error("Gemini model not specified."),
            )
        }

    private suspend fun validatePersistedMicrosoftSpeechEndpoint(): MicrosoftSpeechEndpoint {
        val persistedEndpoint = settings.microsoftSpeechRegionFlow.first()
        return MicrosoftSpeechEndpoint.parseOrNull(persistedEndpoint) ?: run {
            settings.clearInvalidMicrosoftSpeechConfiguration()
            error(
                "The saved Azure Speech region or endpoint was invalid. " +
                    "Reconfigure Microsoft Speech and rotate its API key.",
            )
        }
    }

    private fun uploadProviderName(usingMicrosoftSpeech: Boolean): String = if (usingMicrosoftSpeech) {
        applicationContext.getString(R.string.provider_option_microsoft_speech)
    } else {
        applicationContext.getString(R.string.transcription_provider_gemini)
    }

    private fun providerDisplayName(providerExecution: ProviderExecution): String = when (providerExecution) {
        ProviderExecution.MicrosoftSpeech -> applicationContext.getString(R.string.provider_option_microsoft_speech)
        is ProviderExecution.Gemini -> applicationContext.getString(R.string.transcription_provider_gemini)
    }

    private suspend fun resumePostTranscriptionWork(
        jobId: Int,
        executionOwnerId: String,
        displayName: String,
        transcript: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: String) -> Unit,
    ) {
        if (postTranscriptionWorkSpec?.summarizeAfterTranscription == true) {
            val summarizationResult = summarizationService.summarize(
                jobId = jobId,
                settings = postTranscriptionWorkSpec.summarizationSettings,
                allowResummarize = false,
                executionOwnerId = executionOwnerId,
                fallbackStatusMessage = displayJobStatus(JOB_STATUS_COMPLETED),
                onStatusUpdate = { statusMessage ->
                    if (postTranscriptionWorkSpec.notifyResult) {
                        onProgressNotification(jobId, displayName, statusMessage)
                    }
                },
            )
            requireOwnedUpdate(repository.markRunningAsCompleted(jobId, executionOwnerId))
            if (postTranscriptionWorkSpec.notifyResult) {
                if (summarizationResult.errorMessage == null) {
                    val completedHistory = repository.getHistoryForJob(jobId)
                    backgroundShareNotifier.showSummary(
                        jobId = jobId,
                        displayName = displayName,
                        summary = completedHistory?.summary.orEmpty().ifBlank {
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
        } else {
            requireOwnedUpdate(repository.markRunningAsCompleted(jobId, executionOwnerId))
            if (postTranscriptionWorkSpec?.notifyResult == true) {
                backgroundShareNotifier.showTranscription(
                    jobId = jobId,
                    displayName = displayName,
                    transcript = transcript,
                )
            }
        }
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

    private fun requireOwnedUpdate(updated: Boolean) {
        if (!updated) throw SupersededExecutionException()
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

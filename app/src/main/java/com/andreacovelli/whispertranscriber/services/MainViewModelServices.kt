package com.andreacovelli.whispertranscriber.services

import android.content.Context
import android.net.Uri
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.HistoryJobCleanup
import com.andreacovelli.whispertranscriber.data.HistoryRepository
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.JobExecutionBackend
import com.andreacovelli.whispertranscriber.queue.JobOperationRegistry
import com.andreacovelli.whispertranscriber.queue.PersistedPostTranscriptionConfig
import com.andreacovelli.whispertranscriber.queue.QueueExecutionCoordinator
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.queue.RetryResult
import com.andreacovelli.whispertranscriber.queue.SharedAudioIntake
import com.andreacovelli.whispertranscriber.queue.executionBackendOrDefault
import com.andreacovelli.whispertranscriber.queue.resolveShareExecutionBackend
import com.andreacovelli.whispertranscriber.queue.toPersistedConfig
import com.andreacovelli.whispertranscriber.security.GenAiClientFactory
import com.andreacovelli.whispertranscriber.security.useGenAiClient
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.ApiKeyState
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.TranscriptionSummarizationRepository
import com.andreacovelli.whispertranscriber.transcription.JOB_STATUS_QUEUED
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechEndpoint
import com.andreacovelli.whispertranscriber.transcription.ProviderResponseException
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import com.andreacovelli.whispertranscriber.transcription.awaitResult
import com.andreacovelli.whispertranscriber.transcription.validatedGeminiText
import com.google.genai.errors.ApiException
import com.google.genai.errors.GenAiIOException
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

data class QueuedShareResult(val jobsCreated: Int, val statusMessage: String, val firstCreatedJobId: Int? = null)

@Singleton
class TranscriptionQueueService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val queuedAudioStore: QueuedAudioStore,
    private val scheduler: com.andreacovelli.whispertranscriber.queue.QueueScheduler,
    private val historyRepository: HistoryRepository,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val executionCoordinator: QueueExecutionCoordinator,
    private val operations: JobOperationRegistry,
) {
    private val schedulingMutex = Mutex()

    companion object {
        internal const val QUEUED_JOB_ID_INPUT = "jobId"
        internal const val QUEUED_JOB_DISPLAY_NAME_INPUT = "displayName"
        internal const val SPEAKER_LABEL_PREFIX_INPUT = "speakerLabelPrefix"
    }

    suspend fun enqueueSharedAudio(
        uris: List<Uri>,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        preferImmediateBackgroundStart: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
    ): QueuedShareResult {
        repository.requireQueueCapacity(uris.size)
        return queuedAudioStore.withPreparedAudio(uris) { intake ->
            enqueuePreparedSharedAudio(
                intake,
                provider,
                customModelName,
                speakerDiarizationEnabled,
                preferImmediateBackgroundStart,
                postTranscriptionWorkSpec,
            )
        }
    }

    private suspend fun enqueuePreparedSharedAudio(
        intake: SharedAudioIntake,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        preferImmediateBackgroundStart: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
    ): QueuedShareResult {
        val preparedAudio = intake.preparedAudio
        if (preparedAudio.isEmpty()) {
            return QueuedShareResult(
                jobsCreated = 0,
                statusMessage = context.getString(R.string.main_waiting_for_audios),
            )
        }

        val batchId = UUID.randomUUID().toString()
        val requestedModel = resolveRequestedModel(provider, customModelName)
        val executionBackend = resolveShareExecutionBackend(preferImmediateBackgroundStart)
        val persistedPostTranscriptionConfig = postTranscriptionWorkSpec?.toPersistedConfig()
            ?: PersistedPostTranscriptionConfig(
                summarizeAfterTranscription = false,
                notifyResult = false,
                queuedSummarizerProviderName = "",
                queuedSummarizerCustomModelName = "",
            )

        val jobs = preparedAudio.map { prepared ->
            TranscriptionJob(
                uri = prepared.originalUri.toString(),
                displayName = prepared.displayName,
                sourceMimeType = prepared.mimeType,
                stagedFilePath = prepared.stagedFile.absolutePath,
                providerName = provider.name,
                requestedModel = requestedModel,
                executionBackend = executionBackend.name,
                summarizeAfterTranscription = persistedPostTranscriptionConfig.summarizeAfterTranscription,
                notifyResult = persistedPostTranscriptionConfig.notifyResult,
                queuedSummarizerProviderName = persistedPostTranscriptionConfig.queuedSummarizerProviderName,
                queuedSummarizerCustomModelName =
                persistedPostTranscriptionConfig.queuedSummarizerCustomModelName,
                batchId = batchId,
                speakerDiarizationEnabled = provider.isGemini() && speakerDiarizationEnabled,
                status = JOB_STATUS_QUEUED,
                progress = 0f,
                isRunning = false,
            )
        }
        // Finish the short database handoff even if the receiving Activity stops.
        // Ownership must move before crossing a cancellable suspension boundary.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val jobIds = repository.addJobsWithinCapacity(jobs)
            intake.transferOwnership()
            preparedAudio.zip(jobIds).forEach { (prepared, rawJobId) ->
                try {
                    backgroundShareNotifier.showBackgroundProcessing(
                        jobId = rawJobId.toInt(),
                        displayName = prepared.displayName,
                        message = context.getString(R.string.background_share_waiting_to_start_notification_body),
                    )
                } catch (_: RuntimeException) {
                    // Notification failures must not prevent scheduling committed jobs.
                }
            }
            scheduleNextQueuedJobIfIdle()
            QueuedShareResult(
                jobsCreated = preparedAudio.size,
                statusMessage = context.getString(R.string.main_queued_files, preparedAudio.size),
                firstCreatedJobId = jobIds.firstOrNull()?.toInt(),
            )
        }
    }

    suspend fun cancelJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.cancelJob(jobId)
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelResult(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun clearCompletedJobs() {
        repository.clearCompletedJobs().forEach { completedJob ->
            backgroundShareNotifier.cancelResult(completedJob.jobId)
            backgroundShareNotifier.cancelRetryScheduled(completedJob.jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(completedJob.jobId)
            completedJob.stagedFilePath?.let(queuedAudioStore::deleteStagedFile)
        }
    }

    suspend fun deleteJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.invalidateOwners(jobId)
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelResult(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        queuedAudioStore.deleteStagedFile(repository.deleteJob(jobId))
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun cancelRemovedHistoryJobs(jobs: List<HistoryJobCleanup>) {
        jobs.forEach { job ->
            operations.cancel(job.jobId)
            cancelScheduledExecution(job.jobId)
            backgroundShareNotifier.cancelResult(job.jobId)
            backgroundShareNotifier.cancelRetryScheduled(job.jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(job.jobId)
            queuedAudioStore.deleteStagedFile(job.stagedFilePath)
        }
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun clearJobTranscription(jobId: Int) = clearJobContent(jobId, HistoryContentField.TRANSCRIPT)

    suspend fun clearJobContent(jobId: Int, field: HistoryContentField) = operations.withClaimLock {
        val job = repository.getJob(jobId) ?: return@withClaimLock
        if (repository.clearJobContent(jobId, field)) {
            cancelRemovedHistoryJobs(listOf(HistoryJobCleanup(jobId, job.stagedFilePath)))
        }
    }

    suspend fun clearHistoryContent(historyId: Int, field: HistoryContentField) = operations.withClaimLock {
        cancelRemovedHistoryJobs(historyRepository.clearContent(historyId, field))
    }

    suspend fun retryJob(jobId: Int): RetryResult {
        executionCoordinator.awaitRecovery()
        val snapshot = settings.getTranscriptionQueueSettings()
        val persistedConfig = resolveAutomaticSummarizationWorkSpec()?.toPersistedConfig()
            ?: PersistedPostTranscriptionConfig(false, false, "", "")
        val result = schedulingMutex.withLock {
            val retry = repository.retryJob(
                jobId = jobId,
                providerName = snapshot.provider.name,
                requestedModel = resolveRequestedModel(snapshot.provider, snapshot.customModelName),
                executionBackend = JobExecutionBackend.WORK_MANAGER.name,
                summarizeAfterTranscription = persistedConfig.summarizeAfterTranscription,
                notifyResult = persistedConfig.notifyResult,
                queuedSummarizerProviderName = persistedConfig.queuedSummarizerProviderName,
                queuedSummarizerCustomModelName = persistedConfig.queuedSummarizerCustomModelName,
                speakerDiarizationEnabled = snapshot.speakerDiarizationEnabled,
            )
            if (retry == RetryResult.ACCEPTED) {
                operations.cancel(jobId)
                scheduler.cancel(jobId)
            }
            retry
        }
        if (result == RetryResult.ACCEPTED) {
            backgroundShareNotifier.cancelResult(jobId)
            backgroundShareNotifier.cancelRetryScheduled(jobId)
            backgroundShareNotifier.cancelBackgroundProcessing(jobId)
            scheduleNextQueuedJobIfIdle()
        }
        return result
    }

    private fun resolveRequestedModel(provider: TranscriptionProvider, customModelName: String): String? =
        when (provider) {
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            TranscriptionProvider.GEMINI_FLASH_LITE,
            TranscriptionProvider.GEMINI_FLASH,
            TranscriptionProvider.GEMINI_PRO,
            -> provider.modelName
            TranscriptionProvider.GEMINI_CUSTOM -> customModelName.ifBlank { null }
        }

    suspend fun resumePendingQueue() {
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun recoverAndMaintainQueue() {
        executionCoordinator.awaitRecovery()
        val expiredCards = repository.deleteExpiredTerminalCards()
        expiredCards.forEach { expired ->
            backgroundShareNotifier.cancelResult(expired.jobId)
            queuedAudioStore.deleteStagedFile(expired.stagedFilePath)
        }

        val referencedPaths = repository.getReferencedStagedFilePaths()
        val cleanup = queuedAudioStore.cleanupManagedFiles(referencedPaths)
        android.util.Log.i(
            "QueueMaintenance",
            "Queue maintenance removed ${expiredCards.size} expired terminal cards, " +
                "${cleanup.tempFilesDeleted} temporary and " +
                "${cleanup.orphanFilesDeleted} orphaned staged files.",
        )
        scheduleNextQueuedJobIfIdle()
    }

    suspend fun cancelBackgroundSummaryIfRunning(jobId: Int): Boolean {
        val job = repository.getJob(jobId) ?: return false
        if (!job.isRunning || !job.isSummarizing) return false
        repository.cancelJob(jobId)
        operations.cancel(jobId)
        cancelScheduledExecution(job)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        scheduleNextQueuedJobIfIdle()
        return true
    }

    suspend fun onForegroundPromotionDenied(jobId: Int): Boolean {
        executionCoordinator.awaitRecovery()
        val job = repository.getJob(jobId) ?: return false
        if (scheduler.supportsUserInitiatedJobs) {
            repository.updateExecutionBackend(jobId, JobExecutionBackend.USER_INITIATED_JOB.name)
            val userInitiatedJob = job.copy(executionBackend = JobExecutionBackend.USER_INITIATED_JOB.name)
            if (scheduler.enqueueUserInitiatedJob(userInitiatedJob)) {
                backgroundShareNotifier.showBackgroundProcessing(
                    jobId,
                    job.displayName,
                    context.getString(R.string.transcription_foreground_denied_switching_backend),
                )
                return true
            }
        }

        failBackgroundStart(jobId)
        return false
    }

    suspend fun failBackgroundStart(jobId: Int) {
        executionCoordinator.awaitRecovery()
        val job = repository.getJob(jobId) ?: return
        if (!repository.failUnstartedJob(
                jobId,
                context.getString(R.string.transcription_foreground_denied_reopen),
            )
        ) {
            return
        }
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        backgroundShareNotifier.showFailure(
            jobId = jobId,
            displayName = job.displayName,
            message = context.getString(R.string.transcription_foreground_denied_reopen),
        )
        scheduleNextQueuedJobIfIdle()
    }

    private suspend fun resolveAutomaticSummarizationWorkSpec(): PostTranscriptionWorkSpec? {
        if (!settings.automaticSummarizationFlow.first()) return null

        val keyChars = settings.getGeminiApiKey() ?: return null
        val hasGeminiKey = try {
            keyChars.isNotEmpty()
        } finally {
            keyChars.fill('\u0000')
        }
        if (!hasGeminiKey) return null

        return PostTranscriptionWorkSpec(
            summarizeAfterTranscription = true,
            notifyResult = false,
            summarizationSettings = settings.getSummarizationSettings(),
        )
    }

    suspend fun scheduleNextQueuedJobIfIdle(): Boolean {
        executionCoordinator.awaitRecovery()
        return schedulingMutex.withLock {
            if (repository.countBlockingJobs() > 0) return@withLock false
            val queuedJob = repository.getOldestQueuedJob() ?: return@withLock false
            if (scheduler.hasActiveEntry(queuedJob)) return@withLock true
            scheduleExecutionForJob(queuedJob)
        }
    }

    suspend fun onExecutionFinished(jobId: Int) {
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        scheduleNextQueuedJobIfIdle()
    }

    private suspend fun scheduleExecutionForJob(job: TranscriptionJob): Boolean {
        val backend = job.executionBackendOrDefault()
        if (backend == JobExecutionBackend.USER_INITIATED_JOB && !scheduler.supportsUserInitiatedJobs) {
            repository.updateExecutionBackend(job.id, JobExecutionBackend.WORK_MANAGER.name)
            return scheduler.enqueueWorker(job.copy(executionBackend = JobExecutionBackend.WORK_MANAGER.name))
        }

        return when (backend) {
            JobExecutionBackend.WORK_MANAGER -> scheduler.enqueueWorker(job)
            JobExecutionBackend.USER_INITIATED_JOB -> {
                if (scheduler.enqueueUserInitiatedJob(job)) {
                    true
                } else {
                    repository.updateExecutionBackend(job.id, JobExecutionBackend.WORK_MANAGER.name)
                    scheduler.enqueueWorker(job.copy(executionBackend = JobExecutionBackend.WORK_MANAGER.name))
                }
            }
        }
    }

    private suspend fun cancelScheduledExecution(job: TranscriptionJob?) {
        if (job != null) scheduler.cancel(job.id)
    }

    private suspend fun cancelScheduledExecution(jobId: Int) = scheduler.cancel(jobId)
}

data class KeyValidationResult(val errorMessage: String? = null) {
    val isValid: Boolean get() = errorMessage == null
}

data class RegionValidationResult(val normalizedRegion: String? = null, val errorMessage: String? = null) {
    val isValid: Boolean get() = errorMessage == null
}

data class CustomModelValidationResult(val displayName: String? = null, val errorMessage: String? = null) {
    val isValid: Boolean get() = errorMessage == null
}

internal fun resolveMicrosoftSpeechKeyValidationResult(code: Int): KeyValidationResult = when (code) {
    200 -> KeyValidationResult()
    401, 403 -> KeyValidationResult(
        "Invalid key for Microsoft MAI Transcribe. Use the Speech resource key from your Foundry resource, not a project API key.",
    )
    404 -> KeyValidationResult(
        "Microsoft MAI Transcribe endpoint not found. Check the configured Speech region or custom endpoint.",
    )
    else -> KeyValidationResult("Validation failed: HTTP $code")
}

internal fun resolveMicrosoftSpeechKeyValidationResults(codes: List<Int>): KeyValidationResult {
    if (codes.isEmpty()) return KeyValidationResult("Validation failed")
    if (codes.any { it == 200 }) return KeyValidationResult()

    val authCode = codes.firstOrNull { it == 401 || it == 403 }
    if (authCode != null) return resolveMicrosoftSpeechKeyValidationResult(authCode)

    val reportedCode = codes.firstOrNull { it == 404 || it == 405 || it == 400 || it == 429 || it in 500..599 }
        ?: codes.first()
    return KeyValidationResult("Validation failed: HTTP $reportedCode")
}

internal fun resolveMicrosoftSpeechRegionValidationResults(
    codes: List<Int>,
    normalizedEndpoint: String,
): RegionValidationResult {
    if (codes.any { it in 200..299 || it == 401 || it == 403 }) {
        return RegionValidationResult(normalizedRegion = normalizedEndpoint)
    }

    val reportedCode = codes.firstOrNull { it == 404 || it == 405 || it == 400 || it == 429 || it in 500..599 }
        ?: codes.firstOrNull()
        ?: return RegionValidationResult(errorMessage = "Validation failed")
    return RegionValidationResult(
        errorMessage = "Could not validate Azure Speech region or endpoint: HTTP $reportedCode",
    )
}

internal fun resolveMicrosoftSpeechRegionIOExceptionMessage(normalizedRegion: String, error: IOException): String {
    val message = error.message.orEmpty()
    return if (error is UnknownHostException || message.contains("Unable to resolve host", ignoreCase = true)) {
        if (normalizedRegion.startsWith("https://")) {
            "Could not reach Azure Speech endpoint \"$normalizedRegion\". Check the endpoint URL."
        } else {
            "Could not reach Azure Speech for region \"$normalizedRegion\". " +
                "Check that the region name is correct, for example eastus or westus."
        }
    } else {
        error.message ?: "Validation failed"
    }
}

@Singleton
class ValidationService @Inject constructor(
    private val settings: ApiKeyManager,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val GEMINI_MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val ERR_ENTER_GEMINI_KEY = "Enter your Gemini API key first"
        private const val MICROSOFT_SPEECH_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        private const val MICROSOFT_SPEECH_LOCALES_API_VERSION = "2025-10-15"
        private const val MICROSOFT_SPEECH_LOCALES_FALLBACK_API_VERSION = "2024-11-15"
    }

    suspend fun validateGeminiKey(rawKey: String): KeyValidationResult = try {
        val request = Request.Builder()
            .url(GEMINI_MODELS_BASE_URL)
            .header(GEMINI_API_KEY_HEADER, rawKey)
            .build()
        val result = withContext(ioDispatcher) {
            okHttpClient.newCall(request).awaitResult { response ->
                response.isSuccessful to response.code
            }
        }
        if (result.first) {
            KeyValidationResult()
        } else {
            KeyValidationResult(
                if (result.second == 400) "Invalid API key" else "Validation failed: HTTP ${result.second}",
            )
        }
    } catch (e: IOException) {
        KeyValidationResult(e.message ?: "Validation failed")
    }

    suspend fun validateCustomModel(modelName: String): CustomModelValidationResult {
        val trimmed = modelName.trim()
        val keyChars = settings.getGeminiApiKey()
        if (keyChars == null || keyChars.isEmpty()) {
            val keyState = settings.geminiApiKeyStateFlow.first()
            val error = if (keyState is ApiKeyState.Error) {
                "Keystore error: ${keyState.message}. Please re-enter your key."
            } else {
                ERR_ENTER_GEMINI_KEY
            }
            return CustomModelValidationResult(errorMessage = error)
        }

        return try {
            val request = Request.Builder()
                .url("$GEMINI_MODELS_BASE_URL/$trimmed")
                .header(GEMINI_API_KEY_HEADER, String(keyChars))
                .build()
            val (isSuccess, code, bodyText) = withContext(ioDispatcher) {
                okHttpClient.newCall(request).awaitResult { response ->
                    Triple(
                        response.isSuccessful,
                        response.code,
                        runCatching { response.body.string() }.getOrDefault(""),
                    )
                }
            }
            if (!isSuccess) {
                CustomModelValidationResult(
                    errorMessage = if (code == 404) {
                        "Model \"$trimmed\" not found"
                    } else {
                        "HTTP $code: could not verify model"
                    },
                )
            } else {
                CustomModelValidationResult(displayName = parseDisplayName(bodyText, trimmed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CustomModelValidationResult(errorMessage = e.message ?: "Validation failed")
        } finally {
            keyChars.fill('\u0000')
        }
    }

    private fun parseDisplayName(bodyText: String, fallback: String): String = try {
        JSONObject(bodyText).optString("displayName", fallback)
    } catch (_: JSONException) {
        fallback
    }

    suspend fun validateMicrosoftSpeechKey(rawKey: String, region: String): KeyValidationResult {
        val validatedEndpoint = MicrosoftSpeechEndpoint.parseOrNull(region)
            ?: return KeyValidationResult("Enter a valid Azure Speech region or HTTPS endpoint first")

        return try {
            val codes = probeMicrosoftSpeechKey(validatedEndpoint, rawKey)
            resolveMicrosoftSpeechKeyValidationResults(codes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            KeyValidationResult(resolveMicrosoftSpeechRegionIOExceptionMessage(validatedEndpoint.value, e))
        }
    }

    suspend fun validateMicrosoftSpeechRegion(region: String): RegionValidationResult {
        val validatedEndpoint = try {
            MicrosoftSpeechEndpoint.parse(region)
        } catch (error: IllegalArgumentException) {
            return RegionValidationResult(
                errorMessage = error.message ?: "Enter a valid Azure Speech region or HTTPS endpoint",
            )
        }

        return try {
            val codes = probeMicrosoftSpeechRegion(validatedEndpoint)
            resolveMicrosoftSpeechRegionValidationResults(codes, validatedEndpoint.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            RegionValidationResult(
                errorMessage = resolveMicrosoftSpeechRegionIOExceptionMessage(validatedEndpoint.value, e),
            )
        }
    }

    private fun buildMicrosoftSpeechValidationUrls(endpoint: MicrosoftSpeechEndpoint) = listOf(
        endpoint.buildEndpoint(
            encodedPath = "speechtotext/transcriptions/locales",
            apiVersion = MICROSOFT_SPEECH_LOCALES_API_VERSION,
        ),
        endpoint.buildEndpoint(
            encodedPath = "speechtotext/transcriptions/locales",
            apiVersion = MICROSOFT_SPEECH_LOCALES_FALLBACK_API_VERSION,
        ),
    )

    private suspend fun probeMicrosoftSpeechRegion(endpoint: MicrosoftSpeechEndpoint): List<Int> =
        probeMicrosoftSpeech(endpoint = endpoint, rawKey = null)

    private suspend fun probeMicrosoftSpeechKey(endpoint: MicrosoftSpeechEndpoint, rawKey: String): List<Int> =
        probeMicrosoftSpeech(endpoint = endpoint, rawKey = rawKey)

    private suspend fun probeMicrosoftSpeech(endpoint: MicrosoftSpeechEndpoint, rawKey: String?): List<Int> =
        withContext(ioDispatcher) {
            coroutineScope {
                buildMicrosoftSpeechValidationUrls(endpoint).map { url ->
                    async {
                        val requestBuilder = Request.Builder()
                            .url(url)
                            .get()
                        if (rawKey != null) {
                            requestBuilder.header(MICROSOFT_SPEECH_KEY_HEADER, rawKey)
                        }
                        okHttpClient.newCall(requestBuilder.build()).awaitResult { response -> response.code }
                    }
                }.awaitAll()
            }
        }
}

data class SummarizationResult(val statusMessage: String, val errorMessage: String? = null)

data class SummaryData(val text: String, val model: String)

private data class SummarizerSelection(val provider: TranscriptionProvider, val modelName: String)

@Singleton
class SummarizationService @Inject constructor(
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val summarizationRepository: TranscriptionSummarizationRepository,
    private val genAiClientFactory: GenAiClientFactory,
    private val executionCoordinator: QueueExecutionCoordinator,
    private val operations: JobOperationRegistry,
    @param:ApplicationContext private val applicationContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun summarize(
        jobId: Int,
        settings: SummarizationSettings,
        allowResummarize: Boolean,
        executionOwnerId: String? = null,
        fallbackStatusMessage: String = "",
        onStatusUpdate: suspend (String) -> Unit = {},
    ): SummarizationResult = operations.track(jobId) {
        summarizeAttempt(jobId, settings, allowResummarize, executionOwnerId, fallbackStatusMessage, onStatusUpdate)
    }

    private suspend fun summarizeAttempt(
        jobId: Int,
        settings: SummarizationSettings,
        allowResummarize: Boolean,
        executionOwnerId: String? = null,
        fallbackStatusMessage: String = "",
        onStatusUpdate: suspend (String) -> Unit = {},
    ): SummarizationResult {
        val summarizerSelection = try {
            resolveSummarizerSelection(settings)
        } catch (e: IllegalStateException) {
            return SummarizationResult(statusMessage = "Error: ${e.message}", errorMessage = e.message)
        }

        executionCoordinator.awaitRecovery()
        val attemptId = executionCoordinator.ownerId()
        var summarizingCleared = false
        return try {
            val claimed = operations.withClaimLock {
                if (allowResummarize) {
                    summarizationRepository.claimManualSummary(
                        jobId,
                        attemptId,
                        summarizerSelection.provider.name,
                        summarizerSelection.modelName,
                    )
                } else {
                    summarizationRepository.claimAutoSummary(
                        jobId,
                        attemptId,
                        summarizerSelection.provider.name,
                        summarizerSelection.modelName,
                        executionOwnerId,
                    )
                }
            }
            if (!claimed) return SummarizationResult(statusMessage = fallbackStatusMessage)

            val job = repository.getJob(jobId)
            if (job == null) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                summarizingCleared = true
                return SummarizationResult(statusMessage = fallbackStatusMessage)
            }

            val text = repository.getTranscriptForJob(jobId)
            if (text.isBlank()) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                summarizingCleared = true
                SummarizationResult(
                    statusMessage = applicationContext.getString(
                        R.string.summarization_status_nothing_to_summarize,
                        job.displayName,
                    ),
                )
            } else {
                val summarizingMessage = applicationContext.getString(R.string.transcription_notification_summarizing)
                if (!summarizationRepository.updateSummaryStatus(jobId, attemptId, SUMMARY_STATUS_SUMMARIZING)) {
                    return SummarizationResult(statusMessage = fallbackStatusMessage)
                }
                onStatusUpdate(summarizingMessage)
                val apiKey = requireGeminiApiKey()
                val summary = try {
                    requestSummary(text, apiKey, summarizerSelection.modelName)
                } finally {
                    apiKey.fill('\u0000')
                }
                val saved = summarizationRepository.succeedSummarization(
                    jobId,
                    attemptId,
                    summary.text,
                    summarizerSelection.provider.name,
                    summary.model,
                )
                summarizingCleared = true
                if (!saved) return SummarizationResult(statusMessage = fallbackStatusMessage)
                SummarizationResult(
                    statusMessage = applicationContext.getString(R.string.summarization_status_generated),
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
            }
            summarizingCleared = true
            throw e
        } catch (_: ApiException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult("Summarization error: $message", message)
        } catch (_: GenAiIOException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult("Summarization error: $message", message)
        } catch (_: IOException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(message, message)
        } catch (e: IllegalStateException) {
            val message = applicationContext.getString(
                (e as? ProviderResponseException)?.messageId ?: R.string.provider_request_failed,
            )
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(message, message)
        } finally {
            if (!summarizingCleared) {
                withContext(NonCancellable + ioDispatcher) {
                    summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                }
            }
        }
    }

    suspend fun cancelSummary(jobId: Int): String {
        val cancelled = summarizationRepository.cancelSummarization(jobId)
        operations.cancel(jobId)
        return if (cancelled) {
            applicationContext.getString(R.string.summarization_status_cancelled)
        } else {
            applicationContext.getString(R.string.summarization_status_nothing_to_stop)
        }
    }

    private suspend fun requireGeminiApiKey(): CharArray {
        val keyChars = settings.getGeminiApiKey()
        if (keyChars == null || keyChars.isEmpty()) {
            error("Gemini API Key is not set (Settings) or Keystore error.")
        }
        return keyChars
    }

    private fun resolveSummarizerSelection(settings: SummarizationSettings): SummarizerSelection {
        val provider = settings.provider
        return when {
            provider == TranscriptionProvider.GEMINI_CUSTOM -> {
                val modelName = settings.customModelName.ifBlank {
                    throw IllegalStateException("No custom Gemini model set for summarization.")
                }
                SummarizerSelection(provider = provider, modelName = modelName)
            }
            provider.isGemini() -> {
                SummarizerSelection(provider = provider, modelName = requireNotNull(provider.modelName))
            }
            else -> {
                val defaultProvider = TranscriptionProvider.GEMINI_FLASH
                SummarizerSelection(
                    provider = defaultProvider,
                    modelName = requireNotNull(defaultProvider.modelName),
                )
            }
        }
    }

    private suspend fun requestSummary(text: String, apiKey: CharArray, modelName: String): SummaryData =
        apiKey.useGenAiClient(genAiClientFactory, ioDispatcher) { client ->
            val prompt = """
                You are an expert summarizer.
                Provide a concise, easy-to-read summary of the following text, in the same language.
                Use bullet points (-) for key takeaways if appropriate. The output should be plain text,
                no markdown.
            """.trimIndent()

            val content = Content.fromParts(
                Part.fromText(prompt),
                Part.fromText(text),
            )
            val config = GenerateContentConfig.builder()
                .responseMimeType("text/plain")
                .build()
            val response = client.call { it.models.generateContent(modelName, content, config) }
            SummaryData(
                text = validatedGeminiText(response),
                model = response.modelVersion().orElse(null)?.takeIf { it.isNotBlank() } ?: modelName,
            )
        }
}

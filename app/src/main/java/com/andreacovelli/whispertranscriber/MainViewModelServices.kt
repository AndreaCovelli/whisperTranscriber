package com.andreacovelli.whispertranscriber

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.security.useGenAiClient
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

data class QueuedShareResult(val jobsCreated: Int, val statusMessage: String)

data class PreparedSharedAudio(
    val originalUri: Uri,
    val displayName: String,
    val stagedFile: File,
    val mimeType: String?,
)

@Singleton
class TranscriptionQueueService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val workManager: WorkManager,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TRANSCRIPTION_QUEUE = "TranscriptionQueue"
        private const val SPEAKER_LABEL_PREFIX_INPUT = "speakerLabelPrefix"
        private const val STAGED_FILE_PREFIX = "staged_"
        private const val STAGED_AUDIO_DIR = "queued_audio"
        private const val CONVERTED_FILE_PREFIX = "converted_"
        private const val DIRECT_FILE_PREFIX = "direct_"
    }

    suspend fun enqueueSharedAudio(
        uris: List<Uri>,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        expedite: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
    ): QueuedShareResult {
        val preparedAudio = prepareSharedAudio(uris)
        return enqueuePreparedSharedAudio(
            preparedAudio = preparedAudio,
            provider = provider,
            customModelName = customModelName,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            expedite = expedite,
            postTranscriptionWorkSpec = postTranscriptionWorkSpec,
        )
    }

    private suspend fun prepareSharedAudio(uris: List<Uri>): List<PreparedSharedAudio> {
        if (uris.isEmpty()) return emptyList()

        val preparedAudio = mutableListOf<PreparedSharedAudio>()
        try {
            uris.forEachIndexed { index, uri ->
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val (stagedFile, mimeType) = stageSharedAudio(uri, index)
                preparedAudio += PreparedSharedAudio(
                    originalUri = uri,
                    displayName = displayName,
                    stagedFile = stagedFile,
                    mimeType = mimeType,
                )
            }
            return preparedAudio
        } catch (e: IOException) {
            deleteStagedFiles(preparedAudio)
            throw e
        } catch (e: SecurityException) {
            deleteStagedFiles(preparedAudio)
            throw e
        } catch (e: SQLException) {
            deleteStagedFiles(preparedAudio)
            throw e
        }
    }

    private suspend fun enqueuePreparedSharedAudio(
        preparedAudio: List<PreparedSharedAudio>,
        provider: TranscriptionProvider,
        customModelName: String,
        speakerDiarizationEnabled: Boolean,
        expedite: Boolean = false,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
    ): QueuedShareResult {
        if (preparedAudio.isEmpty()) {
            return QueuedShareResult(0, context.getString(R.string.main_waiting_for_audios))
        }

        val batchId = UUID.randomUUID().toString()
        val speakerLabelPrefix = context.getString(R.string.speaker_label_prefix)
        val requestedModel = resolveRequestedModel(provider, customModelName)
        val pendingPreparedAudio = preparedAudio.toMutableList()

        try {
            preparedAudio.forEach { prepared ->
                val jobId = repository.addJob(
                    TranscriptionJob(
                        uri = prepared.originalUri.toString(),
                        displayName = prepared.displayName,
                        sourceMimeType = prepared.mimeType,
                        stagedFilePath = prepared.stagedFile.absolutePath,
                        providerName = provider.name,
                        requestedModel = requestedModel,
                        batchId = batchId,
                        speakerDiarizationEnabled = speakerDiarizationEnabled,
                        status = "Queued",
                        progress = 0f,
                        isRunning = false,
                    ),
                ).toInt()
                backgroundShareNotifier.showBackgroundProcessing(
                    jobId = jobId,
                    displayName = prepared.displayName,
                    message = context.getString(R.string.background_share_waiting_to_start_notification_body),
                )
                pendingPreparedAudio.remove(prepared)
                enqueueWorker(jobId, speakerLabelPrefix, postTranscriptionWorkSpec, expedite)
            }
        } catch (e: IOException) {
            deleteStagedFiles(pendingPreparedAudio)
            throw e
        } catch (e: SecurityException) {
            deleteStagedFiles(pendingPreparedAudio)
            throw e
        } catch (e: IllegalArgumentException) {
            deleteStagedFiles(pendingPreparedAudio)
            throw e
        } catch (e: IllegalStateException) {
            deleteStagedFiles(pendingPreparedAudio)
            throw e
        } catch (e: SQLException) {
            deleteStagedFiles(pendingPreparedAudio)
            throw e
        }

        return QueuedShareResult(
            preparedAudio.size,
            context.getString(R.string.main_queued_files, preparedAudio.size),
        )
    }

    suspend fun cancelJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.updateError(jobId, "Cancelled", false, "Cancelled")
        workManager.cancelAllWorkByTag(jobTag(jobId))
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        if (job?.isRunning == true) {
            restartQueuedJobs()
        }
    }

    suspend fun clearCompletedJobs() {
        repository.clearCompletedJobs().forEach(::deleteStagedFile)
    }

    suspend fun deleteJob(jobId: Int) {
        val job = repository.getJob(jobId)
        if (job?.isRunning == true || job?.status == "Queued") {
            workManager.cancelAllWorkByTag(jobTag(jobId))
        }
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        deleteStagedFile(repository.deleteJob(jobId))
        if (job?.isRunning == true) {
            restartQueuedJobs()
        }
    }

    suspend fun retryJob(
        jobId: Int,
        provider: TranscriptionProvider,
        customModelName: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec? = null,
    ) {
        workManager.cancelAllWorkByTag(jobTag(jobId))
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        val requestedModel = resolveRequestedModel(provider, customModelName)
        if (repository.retryJob(jobId, provider.name, requestedModel)) {
            enqueueWorker(
                jobId,
                context.getString(R.string.speaker_label_prefix),
                postTranscriptionWorkSpec ?: resolveAutomaticSummarizationWorkSpec(),
            )
        }
    }

    private fun resolveRequestedModel(provider: TranscriptionProvider, customModelName: String): String? =
        when (provider) {
            TranscriptionProvider.ELEVEN_LABS,
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            TranscriptionProvider.GEMINI_FLASH_LITE,
            TranscriptionProvider.GEMINI_FLASH,
            TranscriptionProvider.GEMINI_PRO,
            -> provider.modelName
            TranscriptionProvider.GEMINI_CUSTOM -> customModelName.ifBlank { null }
        }

    private suspend fun restartQueuedJobs() {
        val speakerLabelPrefix = context.getString(R.string.speaker_label_prefix)
        val automaticSummarizationWorkSpec = resolveAutomaticSummarizationWorkSpec()
        repository.getQueuedJobs().forEach { queuedJob ->
            enqueueWorker(queuedJob.id, speakerLabelPrefix, automaticSummarizationWorkSpec)
        }
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

    suspend fun cleanupOldTempFiles(
        tempFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(3),
        stagedFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    ) = withContext(ioDispatcher) {
        val cache = context.cacheDir ?: return@withContext
        val tempCutoff = System.currentTimeMillis() - tempFileMaxAgeMs
        cache.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { it.lastModified() < tempCutoff }
            .filter(::isManagedTempFile)
            .forEach(::deleteQuietly)

        val stagedCutoff = System.currentTimeMillis() - stagedFileMaxAgeMs
        stagedAudioDir().listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { it.lastModified() < stagedCutoff }
            .forEach(::deleteQuietly)
    }

    private fun enqueueWorker(
        jobId: Int,
        speakerLabelPrefix: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        expedite: Boolean = false,
    ) {
        val workRequestBuilder = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                postTranscriptionWorkSpec?.toInputData(jobId, speakerLabelPrefix) ?: workDataOf(
                    "jobId" to jobId,
                    SPEAKER_LABEL_PREFIX_INPUT to speakerLabelPrefix,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(jobTag(jobId))

        if (expedite) {
            workRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        val workRequest = workRequestBuilder.build()

        workManager.beginUniqueWork(
            TRANSCRIPTION_QUEUE,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest,
        ).enqueue()
    }

    private fun jobTag(jobId: Int): String = "TranscriptionJob_$jobId"

    private fun stagedAudioDir(): File = File(context.filesDir, STAGED_AUDIO_DIR).apply { mkdirs() }

    private suspend fun stageSharedAudio(uri: Uri, index: Int): Pair<File, String?> = withContext(ioDispatcher) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)?.lowercase()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf(String::isNotBlank)
            ?: "bin"
        val stagedFile = File.createTempFile("${STAGED_FILE_PREFIX}${index}_", ".$extension", stagedAudioDir())
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use(input::copyTo)
            } ?: throw IOException("Failed to read shared audio: $uri")
        } catch (e: IOException) {
            deleteQuietly(stagedFile)
            throw e
        }
        stagedFile to mimeType
    }

    private suspend fun resolveDisplayName(uri: Uri): String = withContext(ioDispatcher) {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun deleteStagedFile(path: String?) {
        if (path.isNullOrBlank()) return
        deleteQuietly(File(path))
    }

    private fun deleteStagedFiles(preparedAudio: List<PreparedSharedAudio>) {
        preparedAudio.forEach { deleteQuietly(it.stagedFile) }
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    private fun isManagedTempFile(file: File): Boolean {
        val name = file.name
        return (name.startsWith(CONVERTED_FILE_PREFIX) && name.endsWith(".wav")) ||
            (name.startsWith(DIRECT_FILE_PREFIX) && name.endsWith(".tmp"))
    }
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
    404 -> KeyValidationResult("Microsoft MAI Transcribe endpoint not found. Check the configured Speech region.")
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
    normalizedRegion: String,
): RegionValidationResult {
    if (codes.isEmpty()) return RegionValidationResult(errorMessage = "Validation failed")
    if (codes.any { it == 200 || it == 400 || it == 401 || it == 403 || it == 405 }) {
        return RegionValidationResult(normalizedRegion = normalizedRegion)
    }
    if (codes.any { it == 404 }) {
        return RegionValidationResult(errorMessage = "Region \"$normalizedRegion\" not found")
    }

    val reportedCode = codes.firstOrNull { it == 429 || it in 500..599 } ?: codes.first()
    return RegionValidationResult(errorMessage = "Could not verify region: HTTP $reportedCode")
}

internal fun resolveMicrosoftSpeechRegionIOExceptionMessage(normalizedRegion: String, error: IOException): String {
    val message = error.message.orEmpty()
    return if (error is UnknownHostException || message.contains("Unable to resolve host", ignoreCase = true)) {
        "Could not reach Azure Speech for region \"$normalizedRegion\". Check that the region name is correct, for example eastus or westus."
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
            okHttpClient.newCall(request).execute().use { response ->
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
                okHttpClient.newCall(request).execute().use { response ->
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
        val normalizedRegion = region.trim().lowercase()
        if (normalizedRegion.isBlank()) {
            return KeyValidationResult("Enter an Azure Speech region first")
        }

        return try {
            val codes = probeMicrosoftSpeechRegion(normalizedRegion, rawKey)
            resolveMicrosoftSpeechKeyValidationResults(codes)
        } catch (e: IOException) {
            KeyValidationResult(resolveMicrosoftSpeechRegionIOExceptionMessage(normalizedRegion, e))
        }
    }

    suspend fun validateMicrosoftSpeechRegion(region: String): RegionValidationResult {
        val normalizedRegion = region.trim().lowercase()
        if (normalizedRegion.isBlank()) {
            return RegionValidationResult(errorMessage = "Enter an Azure Speech region")
        }

        val keyChars = settings.getMicrosoftSpeechApiKey()
        val savedKey = try {
            keyChars
                ?.takeIf { it.isNotEmpty() }
                ?.concatToString()
        } finally {
            keyChars?.fill('\u0000')
        }

        return try {
            val codes = probeMicrosoftSpeechRegion(normalizedRegion, savedKey)
            resolveMicrosoftSpeechRegionValidationResults(codes, normalizedRegion)
        } catch (e: IOException) {
            RegionValidationResult(errorMessage = resolveMicrosoftSpeechRegionIOExceptionMessage(normalizedRegion, e))
        }
    }

    private fun buildMicrosoftSpeechValidationUrls(normalizedRegion: String): List<String> = listOf(
        "https://$normalizedRegion.api.cognitive.microsoft.com/speechtotext/transcriptions/locales" +
            "?api-version=$MICROSOFT_SPEECH_LOCALES_API_VERSION",
        "https://$normalizedRegion.api.cognitive.microsoft.com/speechtotext/transcriptions/locales" +
            "?api-version=$MICROSOFT_SPEECH_LOCALES_FALLBACK_API_VERSION",
        "https://$normalizedRegion.api.cognitive.microsoft.com/speechtotext/v3.2-preview.2/transcriptions/locales",
    )

    private suspend fun probeMicrosoftSpeechRegion(normalizedRegion: String, rawKey: String? = null): List<Int> =
        withContext(ioDispatcher) {
            coroutineScope {
                buildMicrosoftSpeechValidationUrls(normalizedRegion).map { url ->
                    async {
                        val requestBuilder = Request.Builder()
                            .url(url)
                            .get()
                        if (!rawKey.isNullOrBlank()) {
                            requestBuilder.header(MICROSOFT_SPEECH_KEY_HEADER, rawKey)
                        }
                        okHttpClient.newCall(requestBuilder.build()).execute().use { response -> response.code }
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
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun summarize(
        jobId: Int,
        settings: SummarizationSettings,
        allowResummarize: Boolean,
        fallbackStatusMessage: String = "",
    ): SummarizationResult {
        val summarizerSelection = try {
            resolveSummarizerSelection(settings)
        } catch (e: IllegalStateException) {
            return SummarizationResult(statusMessage = "Error: ${e.message}", errorMessage = e.message)
        }

        val claimed = if (allowResummarize) {
            repository.claimManualSummary(jobId, summarizerSelection.provider.name, summarizerSelection.modelName)
        } else {
            repository.claimAutoSummary(jobId, summarizerSelection.provider.name, summarizerSelection.modelName)
        }
        if (!claimed) return SummarizationResult(statusMessage = fallbackStatusMessage)

        var summarizingCleared = false
        return try {
            val job = repository.getJob(jobId)
            if (job == null) {
                repository.setSummarizing(jobId, false)
                summarizingCleared = true
                return SummarizationResult(statusMessage = fallbackStatusMessage)
            }

            val text = job.transcript
            if (text.isBlank()) {
                repository.setSummarizing(jobId, false)
                repository.updateSummaryStatus(jobId, "")
                summarizingCleared = true
                SummarizationResult(statusMessage = "Nothing to summarize for ${job.displayName}.")
            } else {
                repository.updateSummaryStatus(jobId, "Summarizing...")
                val apiKey = requireGeminiApiKey()
                val summary = try {
                    requestSummary(text, apiKey, summarizerSelection.modelName)
                } finally {
                    apiKey.fill('\u0000')
                }
                repository.succeedSummarization(
                    jobId,
                    summary.text,
                    summarizerSelection.provider.name,
                    summary.model,
                )
                summarizingCleared = true
                repository.upsertHistoryForSummarization(
                    job = job,
                    summary = summary.text,
                    summaryProviderName = summarizerSelection.provider.name,
                    summaryModel = summary.model,
                )
                SummarizationResult(statusMessage = "Summary generated.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            repository.failSummarization(jobId, e.message)
            summarizingCleared = true
            SummarizationResult("Summarization error: ${e.message}", e.message)
        } catch (e: IllegalStateException) {
            repository.failSummarization(jobId, e.message)
            summarizingCleared = true
            val message = e.message ?: "Summarization failed"
            SummarizationResult("Error: $message", message)
        } finally {
            if (!summarizingCleared) {
                withContext(NonCancellable + ioDispatcher) {
                    repository.setSummarizing(jobId, false)
                }
            }
        }
    }

    suspend fun cancelSummary(jobId: Int): String {
        val cancelled = repository.cancelSummarization(jobId)
        return if (cancelled) "Summary cancelled." else "Nothing to stop."
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
        apiKey.useGenAiClient { client ->
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
            val response = withContext(ioDispatcher) {
                val config = GenerateContentConfig.builder()
                    .responseMimeType("text/plain")
                    .build()
                client.models.generateContent(modelName, content, config)
            }
            SummaryData(
                text = response.text()?.trim().orEmpty().ifBlank { "No summary generated." },
                model = response.modelVersion().orElse(null)?.takeIf { it.isNotBlank() } ?: modelName,
            )
        }
}

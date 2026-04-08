package com.andreacovelli.whispertranscriber

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import retrofit2.HttpException

data class QueuedShareResult(val jobsCreated: Int, val statusMessage: String)

@Singleton
class TranscriptionQueueService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: TranscriptionRepository,
    private val workManager: WorkManager,
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
    ): QueuedShareResult {
        if (uris.isEmpty()) {
            return QueuedShareResult(0, context.getString(R.string.main_waiting_for_audios))
        }

        val batchId = UUID.randomUUID().toString()
        val speakerLabelPrefix = context.getString(R.string.speaker_label_prefix)
        val requestedModel = resolveRequestedModel(provider, customModelName)

        uris.forEachIndexed { index, uri ->
            val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
            val (stagedFile, mimeType) = stageSharedAudio(uri, index)
            val jobId = repository.addJob(
                TranscriptionJob(
                    uri = uri.toString(),
                    displayName = displayName,
                    sourceMimeType = mimeType,
                    stagedFilePath = stagedFile.absolutePath,
                    providerName = provider.name,
                    requestedModel = requestedModel,
                    batchId = batchId,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    status = "Queued",
                    progress = 0f,
                    isRunning = false,
                ),
            ).toInt()
            enqueueWorker(jobId, speakerLabelPrefix)
        }

        return QueuedShareResult(uris.size, context.getString(R.string.main_queued_files, uris.size))
    }

    suspend fun cancelJob(jobId: Int) {
        val job = repository.getJob(jobId)
        repository.updateError(jobId, "Cancelled", false, "Cancelled")
        workManager.cancelAllWorkByTag(jobTag(jobId))
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
        deleteStagedFile(repository.deleteJob(jobId))
        if (job?.isRunning == true) {
            restartQueuedJobs()
        }
    }

    suspend fun retryJob(jobId: Int, provider: TranscriptionProvider, customModelName: String) {
        workManager.cancelAllWorkByTag(jobTag(jobId))
        val requestedModel = resolveRequestedModel(provider, customModelName)
        if (repository.retryJob(jobId, provider.name, requestedModel)) {
            enqueueWorker(jobId, context.getString(R.string.speaker_label_prefix))
        }
    }

    private fun resolveRequestedModel(provider: TranscriptionProvider, customModelName: String): String? =
        when (provider) {
            TranscriptionProvider.ELEVEN_LABS -> null
            TranscriptionProvider.GEMINI_CUSTOM -> customModelName.ifBlank { null }
            else -> provider.modelName
        }

    private suspend fun restartQueuedJobs() {
        val speakerLabelPrefix = context.getString(R.string.speaker_label_prefix)
        repository.getQueuedJobs().forEach { queuedJob ->
            enqueueWorker(queuedJob.id, speakerLabelPrefix)
        }
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

    private fun enqueueWorker(jobId: Int, speakerLabelPrefix: String) {
        val workRequest = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
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
            .build()

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

data class CustomModelValidationResult(val displayName: String? = null, val errorMessage: String? = null) {
    val isValid: Boolean get() = errorMessage == null
}

@Singleton
class GeminiValidationService @Inject constructor(
    private val settings: ApiKeyManager,
    private val elevenLabsApi: ElevenLabsApi,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val GEMINI_MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        private const val ERR_ENTER_GEMINI_KEY = "Enter your Gemini API key first"
    }

    suspend fun validateElevenLabsKey(rawKey: String): KeyValidationResult = try {
        val response = withContext(ioDispatcher) { elevenLabsApi.getUser(rawKey) }
        if (response.isSuccessful) {
            KeyValidationResult()
        } else {
            KeyValidationResult(
                if (response.code() == 401) "Invalid API key" else "Validation failed: HTTP ${response.code()}",
            )
        }
    } catch (e: IOException) {
        KeyValidationResult(e.message ?: "Validation failed")
    } catch (e: HttpException) {
        KeyValidationResult("Validation failed: HTTP ${e.code()}")
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
        } catch (e: HttpException) {
            CustomModelValidationResult(errorMessage = "HTTP ${e.code()}: could not verify model")
        } finally {
            keyChars.fill('\u0000')
        }
    }

    private fun parseDisplayName(bodyText: String, fallback: String): String = try {
        JSONObject(bodyText).optString("displayName", fallback)
    } catch (_: JSONException) {
        fallback
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
    suspend fun summarize(jobId: Int, state: UiState, allowResummarize: Boolean): SummarizationResult {
        val summarizerSelection = try {
            resolveSummarizerSelection(state)
        } catch (e: IllegalStateException) {
            return SummarizationResult(statusMessage = "Error: ${e.message}", errorMessage = e.message)
        }

        val claimed = if (allowResummarize) {
            repository.claimManualSummary(jobId, summarizerSelection.provider.name, summarizerSelection.modelName)
        } else {
            repository.claimAutoSummary(jobId, summarizerSelection.provider.name, summarizerSelection.modelName)
        }
        if (!claimed) return SummarizationResult(statusMessage = state.status)

        var summarizingCleared = false
        return try {
            val job = repository.getJob(jobId)
            if (job == null) {
                repository.setSummarizing(jobId, false)
                summarizingCleared = true
                return SummarizationResult(statusMessage = state.status)
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
                job.historyId?.let {
                    repository.updateHistorySummary(
                        it,
                        summary.text,
                        summarizerSelection.provider.name,
                        summary.model,
                    )
                }
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

    private fun resolveSummarizerSelection(state: UiState): SummarizerSelection {
        val provider = state.summarizerProvider
        return when {
            provider == TranscriptionProvider.GEMINI_CUSTOM -> {
                val modelName = state.summarizerCustomGeminiModelName.ifBlank {
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

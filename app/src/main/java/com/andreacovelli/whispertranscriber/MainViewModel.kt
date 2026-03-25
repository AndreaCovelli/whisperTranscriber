// app/src/main/java/com/andreacovelli/whispertranscriber/MainViewModel.kt
package com.andreacovelli.whispertranscriber

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

sealed class CustomModelValidation {
    object Idle : CustomModelValidation()
    object Validating : CustomModelValidation()
    data class Valid(val displayName: String) : CustomModelValidation()
    data class Error(val message: String) : CustomModelValidation()
}

sealed class KeyValidationState {
    object Idle : KeyValidationState()
    object Validating : KeyValidationState()
    data class Error(val message: String) : KeyValidationState()
}

data class UiState(
    val status: String = "Waiting for audios...",
    val provider: TranscriptionProvider = TranscriptionProvider.ELEVEN_LABS,
    val summarizerProvider: TranscriptionProvider = TranscriptionProvider.GEMINI_FLASH,
    val elevenLabsApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val elevenLabsKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val geminiApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val geminiKeyValidation: KeyValidationState = KeyValidationState.Idle,
    val transcriptionCustomGeminiModelName: String = "",
    val summarizerCustomGeminiModelName: String = "",
    val transcriptionCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val summarizerCustomModelValidation: CustomModelValidation = CustomModelValidation.Idle,
    val automaticSummarizationEnabled: Boolean = false,
    val jobs: List<TranscriptionJobState> = emptyList()
)

data class TranscriptionJobState(
    val id: Int = 0,
    val uri: Uri? = null,
    val displayName: String = "",
    val status: String = "Queued",
    val progress: Float = 0f, // 0..1 conversion progress
    val transcript: String = "",
    val language: String = "",
    val model: String = "",
    val summary: String = "",
    val historyId: Int? = null,
    val error: String? = null,
    val isRunning: Boolean = false,
    val isSummarizing: Boolean = false
)

fun TranscriptionJob.toUiState(): TranscriptionJobState = TranscriptionJobState(
    id = id,
    uri = uri.toUri(),
    displayName = displayName,
    status = status,
    progress = progress,
    transcript = transcript,
    language = language,
    model = model,
    summary = summary,
    historyId = historyId,
    error = error,
    isRunning = isRunning,
    isSummarizing = isSummarizing
)

data class TranscriptionData(
    val text: String,
    val language: String,
    val model: String
)

sealed interface TranscriptionResult {
    data class Loading(val message: String) : TranscriptionResult
    data class Success(val data: TranscriptionData) : TranscriptionResult
    data class Error(val exception: Exception) : TranscriptionResult
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val GEMINI_MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"

        private const val ERR_ENTER_MODEL_NAME = "Enter a model name"
        private const val ERR_ENTER_GEMINI_KEY = "Enter your Gemini API key first"
        private const val ERR_MODEL_NOT_FOUND = "Model \"%s\" not found"
        private const val ERR_HTTP_VERIFY = "HTTP %d: could not verify model"
        
        private const val CONVERTED_FILE_PREFIX = "converted_"
        private const val DIRECT_FILE_PREFIX = "direct_"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val settings = ApiKeyManager(application)
    private val historyDao: TranscriptionHistoryDao
    private val repository = TranscriptionRepository(application)

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    init {
        viewModelScope.launch {
            launch {
                settings.providerFlow.collect { p ->
                    _uiState.update { it.copy(provider = p) }
                }
            }
            launch {
                settings.summarizerProviderFlow.collect { p ->
                    _uiState.update { it.copy(summarizerProvider = p) }
                }
            }
            launch {
                settings.elevenLabsApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(elevenLabsApiKeyState = state) }
                }
            }
            launch {
                settings.geminiApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(geminiApiKeyState = state) }
                }
            }
            launch {
                settings.transcriptionCustomGeminiModelNameFlow.collect { name ->
                    _uiState.update { it.copy(transcriptionCustomGeminiModelName = name.orEmpty()) }
                }
            }
            launch {
                settings.summarizerCustomGeminiModelNameFlow.collect { name ->
                    _uiState.update { it.copy(summarizerCustomGeminiModelName = name.orEmpty()) }
                }
            }
            launch {
                settings.automaticSummarizationFlow.collect { enabled ->
                    _uiState.update { it.copy(automaticSummarizationEnabled = enabled) }
                }
            }
            launch {
                repository.jobsState.collect { jobsList ->
                    _uiState.update { it.copy(jobs = jobsList.map { it.toUiState() }) }
                    
                    // Auto-summarization logic
                    if (_uiState.value.automaticSummarizationEnabled && _uiState.value.geminiApiKeyState is ApiKeyState.Present) {
                        jobsList.forEach { job ->
                            if (!job.isRunning && !job.isSummarizing && job.transcript.isNotBlank() && job.summary.isBlank() && job.error == null) {
                                summarizeJob(job.id)
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch(ioDispatcher) {
            cleanupOldTempWavs(maxAgeMs = TimeUnit.DAYS.toMillis(3))
        }

        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()
    }

    private fun enqueueWorker(jobId: Int) {
        val workRequest = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf("jobId" to jobId))
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "TranscriptionJob_$jobId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun setProvider(p: TranscriptionProvider) {
        _uiState.update { it.copy(provider = p) }
        viewModelScope.launch {
            settings.saveProvider(p)
        }
    }

    fun setSummarizerProvider(p: TranscriptionProvider) {
        _uiState.update { it.copy(summarizerProvider = p) }
        viewModelScope.launch {
            settings.saveSummarizerProvider(p)
        }
    }

    fun setAutomaticSummarization(enabled: Boolean) {
        _uiState.update { it.copy(automaticSummarizationEnabled = enabled) }
        viewModelScope.launch {
            settings.saveAutomaticSummarization(enabled)
        }
    }

    fun setElevenLabsKey(k: String) {
        val trimmed = k.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Validating) }
        viewModelScope.launch {
            try {
                val response = withContext(ioDispatcher) {
                    RetrofitClient.api.getUser(trimmed)
                }
                if (response.isSuccessful) {
                    settings.saveElevenLabsApiKey(trimmed)
                    _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
                } else {
                    val errorMessage = if (response.code() == 401) {
                        "Invalid API key"
                    } else {
                        "Validation failed: HTTP ${response.code()}"
                    }
                    _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Error(errorMessage)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Error(e.message ?: "Validation failed")) }
            }
        }
    }

    fun setGeminiKey(k: String) {
        val trimmed = k.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Validating) }
        viewModelScope.launch {
            try {
                val request = Request.Builder()
                    .url(GEMINI_MODELS_BASE_URL)
                    .header(GEMINI_API_KEY_HEADER, trimmed)
                    .build()
                val (isSuccess, code) = withContext(ioDispatcher) {
                    RetrofitClient.okHttpClient.newCall(request).execute().use { resp ->
                        resp.isSuccessful to resp.code
                    }
                }
                if (isSuccess) {
                    settings.saveGeminiApiKey(trimmed)
                    _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
                } else {
                    val errorMessage = if (code == 400) {
                        "Invalid API key"
                    } else {
                        "Validation failed: HTTP $code"
                    }
                    _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Error(errorMessage)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Error(e.message ?: "Validation failed")) }
            }
        }
    }

    fun resetCustomModelValidation(isSummarizer: Boolean = false) {
        _uiState.update {
            if (isSummarizer) {
                it.copy(summarizerCustomModelValidation = CustomModelValidation.Idle)
            } else {
                it.copy(transcriptionCustomModelValidation = CustomModelValidation.Idle)
            }
        }
    }

    fun resetElevenLabsKeyValidation() {
        _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
    }

    fun resetGeminiKeyValidation() {
        _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
    }

    fun validateCustomGeminiModel(modelName: String, isSummarizer: Boolean = false) {
        val trimmed = modelName.trim()
        if (trimmed.isBlank()) {
            if (isSummarizer) _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Error(ERR_ENTER_MODEL_NAME)) }
            else _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Error(ERR_ENTER_MODEL_NAME)) }
            return
        }
        if (isSummarizer) _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Validating) }
        else _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Validating) }

        viewModelScope.launch {
            val keyChars = settings.getGeminiApiKey()
            if (keyChars == null || keyChars.isEmpty()) {
                val keyState = _uiState.value.geminiApiKeyState
                val errMsg = if (keyState is ApiKeyState.Error)
                    "Keystore error: ${keyState.message}. Please re-enter your key."
                else ERR_ENTER_GEMINI_KEY
                
                if (isSummarizer) _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Error(errMsg)) }
                else _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Error(errMsg)) }
                return@launch
            }

            try {
                val url = "$GEMINI_MODELS_BASE_URL/$trimmed"
                val request = Request.Builder()
                    .url(url)
                    .header(GEMINI_API_KEY_HEADER, String(keyChars).also { keyChars.fill('\u0000') })
                    .build()
                val (isSuccess, code, bodyText) = withContext(ioDispatcher) {
                    RetrofitClient.okHttpClient.newCall(request).execute().use { resp ->
                        Triple(resp.isSuccessful, resp.code, resp.body.string())
                    }
                }
                if (!isSuccess) {
                    val msg = if (code == 404) ERR_MODEL_NOT_FOUND.format(trimmed)
                              else ERR_HTTP_VERIFY.format(code)
                    if (isSummarizer) _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Error(msg)) }
                    else _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Error(msg)) }
                    return@launch
                }
                val displayName = try {
                    JSONObject(bodyText).optString("displayName", trimmed)
                } catch (_: Exception) { trimmed }
                
                if (isSummarizer) {
                    settings.saveSummarizerCustomGeminiModelName(trimmed)
                    _uiState.update {
                        it.copy(
                            summarizerCustomGeminiModelName = trimmed,
                            summarizerCustomModelValidation = CustomModelValidation.Valid(displayName)
                        )
                    }
                } else {
                    settings.saveTranscriptionCustomGeminiModelName(trimmed)
                    _uiState.update {
                        it.copy(
                            transcriptionCustomGeminiModelName = trimmed,
                            transcriptionCustomModelValidation = CustomModelValidation.Valid(displayName)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isSummarizer) _uiState.update { it.copy(summarizerCustomModelValidation = CustomModelValidation.Error(e.message ?: "Validation failed")) }
                else _uiState.update { it.copy(transcriptionCustomModelValidation = CustomModelValidation.Error(e.message ?: "Validation failed")) }
            }
        }
    }

    fun forgetElevenLabsKey() {
        viewModelScope.launch {
            settings.forgetElevenLabsApiKey()
            _uiState.update { it.copy(elevenLabsKeyValidation = KeyValidationState.Idle) }
        }
    }
    fun forgetGeminiKey() {
        viewModelScope.launch {
            settings.forgetGeminiApiKey()
            _uiState.update { it.copy(geminiKeyValidation = KeyValidationState.Idle) }
        }
    }

    fun cancelJob(jobId: Int) {
        viewModelScope.launch {
            repository.updateError(jobId, "Cancelled", false, "Cancelled")
            WorkManager.getInstance(getApplication()).cancelUniqueWork("TranscriptionJob_$jobId")
        }
    }

    fun clearCompletedJobs() {
        viewModelScope.launch {
            repository.clearCompletedJobs()
        }
    }

    fun deleteJob(jobId: Int) {
        viewModelScope.launch {
            repository.deleteJob(jobId)
        }
    }

    fun retryJob(jobId: Int) {
        viewModelScope.launch {
            val job = repository.getJob(jobId) ?: return@launch
            val resetJob = job.copy(
                status = "Queued",
                progress = 0f,
                error = null,
                isRunning = false,
                transcript = "",
                summary = "",
                historyId = null,
                language = "",
                model = ""
            )
            repository.updateJob(resetJob)
            enqueueWorker(jobId)
        }
    }

    private fun cleanupOldTempWavs(maxAgeMs: Long = TimeUnit.DAYS.toMillis(3)) {
        val app = getApplication<Application>()
        val cache = app.cacheDir ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMs

        val files = cache.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (!f.isFile) continue
            if (f.lastModified() >= cutoff) continue

            val isConvertedWav = name.startsWith(CONVERTED_FILE_PREFIX) && name.endsWith(".wav")
            val isDirectTmp = name.startsWith(DIRECT_FILE_PREFIX) && name.endsWith(".tmp")

            if (isConvertedWav || isDirectTmp) {
                runCatching { f.delete() }
            }
        }
    }

    fun handleAudioShareMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(status = "Queuing ${uris.size} file(s)...") }

            for ((index, uri) in uris.withIndex()) {
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val job = TranscriptionJob(
                    uri = uri.toString(),
                    displayName = displayName,
                    status = "Queued",
                    progress = 0f,
                    isRunning = false
                )
                val jobId = repository.addJob(job).toInt()
                enqueueWorker(jobId)
            }
        }
    }

    private suspend fun resolveDisplayName(uri: Uri): String {
        return withContext(ioDispatcher) {
            val cr = getApplication<Application>().contentResolver
            val proj = arrayOf(OpenableColumns.DISPLAY_NAME)
            try {
                cr.query(uri, proj, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        return@withContext c.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) { }
            ""
        }
    }

    fun clearSummary(jobId: Int) {
        viewModelScope.launch {
            repository.clearJobSummary(jobId)
        }
    }

    fun clearTranscription(jobId: Int) {
        viewModelScope.launch {
            repository.updateJobById(jobId) { it.copy(transcript = "") }
        }
    }

    fun summarizeJob(jobId: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val job = repository.getJob(jobId) ?: return@launch
            val text = job.transcript
            if (text.isBlank()) {
                _uiState.update { it.copy(status = "Nothing to summarize for ${job.displayName}.") }
                return@launch
            }
            val keyChars = settings.getGeminiApiKey()
            if (keyChars == null || keyChars.isEmpty()) {
                _uiState.update { it.copy(status = "Error: Gemini API Key is not set (Settings) or Keystore error.") }
                return@launch
            }
            val apiKey = String(keyChars)
            keyChars.fill('\u0000')

            val providerForSummary = state.summarizerProvider
            val modelName = when {
                providerForSummary == TranscriptionProvider.GEMINI_CUSTOM -> {
                    val customName = state.summarizerCustomGeminiModelName
                    if (customName.isBlank()) {
                        _uiState.update { it.copy(status = "Error: No custom Gemini model set for summarization.") }
                        repository.setSummarizing(jobId, false)
                        return@launch
                    }
                    customName
                }
                providerForSummary.isGemini() -> providerForSummary.modelName!!
                else -> TranscriptionProvider.GEMINI_FLASH.modelName!!
            }

            repository.setSummarizing(jobId, true)
            _uiState.update { it.copy(status = "Summarizing with $modelName...") }
            try {
                val client = Client.builder().apiKey(apiKey).build()
                val prompt = """
                    You are an expert summarizer.
                    Provide a concise, easy-to-read summary of the following text, in the same language.
                    Use bullet points (-) for key takeaways if appropriate. The output should be plain text,
                    no markdown.
                """.trimIndent()

                val content = Content.fromParts(
                    Part.fromText(prompt),
                    Part.fromText(text)
                )

                val response = withContext(ioDispatcher) {
                    val config = GenerateContentConfig.builder()
                        .responseMimeType("text/plain")
                        .build()
                    client.models.generateContent(modelName, content, config)
                }

                val summary = response.text()?.trim().orEmpty().ifBlank { "No summary generated." }

                repository.updateJobById(jobId) { it.copy(isSummarizing = false, summary = summary) }
                _uiState.update { it.copy(status = "Summary generated.") }

                withContext(ioDispatcher) {
                    val hid = job.historyId
                    if (hid != null) {
                        historyDao.updateSummary(hid, summary)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                repository.setSummarizing(jobId, false)
                _uiState.update {
                    it.copy(status = "Summarization error: ${e.message ?: e::class.java.simpleName}")
                }
            }
        }
    }
}

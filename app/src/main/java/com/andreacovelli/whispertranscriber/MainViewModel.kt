package com.andreacovelli.whispertranscriber

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.google.genai.types.Schema
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UiState(
    val status: String = "Waiting for audios...",
    val provider: TranscriptionProvider = TranscriptionProvider.ASSEMBLY_AI,
    val assemblyApiKey: String = "",
    val geminiApiKey: String = "",
    val jobs: List<TranscriptionJobState> = emptyList()
)

data class TranscriptionJobState(
    val id: String = UUID.randomUUID().toString(),
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

private data class TranscriptionData(
    val text: String,
    val language: String,
    val model: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val settings = ApiKeyManager(application)
    private val historyDao: TranscriptionHistoryDao

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var currentJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { settings.migrateToEncryptedIfNeeded() }

            launch {
                settings.providerFlow.collect { p ->
                    _uiState.update { it.copy(provider = p) }
                }
            }
            launch {
                settings.assemblyApiKeyFlow.collect { key ->
                    _uiState.update { it.copy(assemblyApiKey = key.orEmpty()) }
                }
            }
            launch {
                settings.geminiApiKeyFlow.collect { key ->
                    _uiState.update { it.copy(geminiApiKey = key.orEmpty()) }
                }
            }
        }

        // Cleanup old temp WAVs on a background thread
        viewModelScope.launch(ioDispatcher) {
            cleanupOldTempWavs(maxAgeMs = TimeUnit.DAYS.toMillis(3))
        }

        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()
    }

    // Settings mutations
    fun setProvider(p: TranscriptionProvider) {
        _uiState.update { it.copy(provider = p) }
    }
    fun setAssemblyKey(k: String) {
        _uiState.update { it.copy(assemblyApiKey = k) }
    }
    fun setGeminiKey(k: String) {
        _uiState.update { it.copy(geminiApiKey = k) }
    }
    fun saveSettings() {
        viewModelScope.launch {
            val s = _uiState.value
            settings.saveProvider(s.provider)
            settings.saveAssemblyApiKey(s.assemblyApiKey)
            settings.saveGeminiApiKey(s.geminiApiKey)
        }
    }
    fun forgetAssemblyKey() {
        viewModelScope.launch {
            settings.forgetAssemblyApiKey()
            _uiState.update { it.copy(assemblyApiKey = "") }
        }
    }
    fun forgetGeminiKey() {
        viewModelScope.launch {
            settings.forgetGeminiApiKey()
            _uiState.update { it.copy(geminiApiKey = "") }
        }
    }

    private fun cleanupOldTempWavs(maxAgeMs: Long = TimeUnit.DAYS.toMillis(3)) {
        val app = getApplication<Application>()
        val cache = app.cacheDir ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val prefix = "converted_"
        val suffix = ".wav"

        val files = cache.listFiles() ?: return
        for (f in files) {
            val name = f.name
            if (
                f.isFile &&
                name.startsWith(prefix) &&
                name.endsWith(suffix) &&
                f.lastModified() < cutoff
            ) {
                runCatching { f.delete() }
            }
        }
    }

    fun handleAudioShareMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val persistedProvider = settings.providerFlow.firstOrNull() ?: _uiState.value.provider
            val persistedAssemblyKey = settings.assemblyApiKeyFlow.firstOrNull().orEmpty()
            val persistedGeminiKey = settings.geminiApiKeyFlow.firstOrNull().orEmpty()

            _uiState.update {
                it.copy(
                    provider = persistedProvider,
                    assemblyApiKey = persistedAssemblyKey,
                    geminiApiKey = persistedGeminiKey,
                    status = "Queued ${uris.size} file(s)..."
                )
            }

            val usingAssembly = persistedProvider == TranscriptionProvider.ASSEMBLY_AI
            val neededKey = if (usingAssembly) persistedAssemblyKey else persistedGeminiKey
            if (neededKey.isBlank()) {
                _uiState.update {
                    it.copy(status = "Error: ${if (usingAssembly) "AssemblyAI" else "Gemini"} API Key is not set.")
                }
                return@launch
            }

            // Create jobs and start processing sequentially
            for ((index, uri) in uris.withIndex()) {
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val jobState = TranscriptionJobState(
                    uri = uri,
                    displayName = displayName,
                    status = "Starting...",
                    progress = 0f,
                    isRunning = true
                )
                addJob(jobState)
                _uiState.update { it.copy(status = "Processing ${index + 1}/${uris.size}: $displayName") }

                try {
                    processSingleJob(
                        jobId = jobState.id,
                        uri = uri,
                        usingAssembly = usingAssembly,
                        apiKey = neededKey
                    )
                } catch (e: CancellationException) {
                    updateJob(jobState.id) { it.copy(status = "Cancelled", isRunning = false, error = "Cancelled") }
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.java.simpleName
                    updateJob(jobState.id) { it.copy(status = "Error", isRunning = false, error = msg) }
                }
            }

            _uiState.update { it.copy(status = "Done processing ${uris.size} file(s).") }
        }
    }

    private suspend fun processSingleJob(jobId: String, uri: Uri, usingAssembly: Boolean, apiKey: String) {
        val app = getApplication<Application>()

        val outputFile = withContext(ioDispatcher) {
            File.createTempFile("converted_${jobId.take(6)}", ".wav", app.cacheDir)
        }
        try {
            // Convert
            updateJob(jobId) { it.copy(status = "Converting to 16 kHz WAV...", progress = 0f) }
            HardwareAudioConverter.convertToWav(
                context = app,
                inputUri = uri,
                outputFile = outputFile,
                forceMono = true,
                targetSampleRate = 16_000,
                onProgress = { p ->
                    updateJob(jobId) {
                        it.copy(
                            status = "Converting to 16 kHz WAV: ${"%.1f".format(p * 100)}%",
                            progress = p
                        )
                    }
                }
            )

            // Transcribe
            val data = if (usingAssembly) {
                transcribeWithAssemblyReturn(
                    file = outputFile,
                    apiKey = apiKey,
                    onStatus = { s -> updateJob(jobId) { it.copy(status = "AssemblyAI: $s") } }
                )
            } else {
                transcribeWithGeminiReturn(
                    file = outputFile,
                    apiKey = apiKey,
                    onStatus = { s -> updateJob(jobId) { it.copy(status = "Gemini: $s") } }
                )
            }

            // Persist in history
            val historyId = withContext(ioDispatcher) {
                historyDao.insert(
                    TranscriptionHistory(
                        text = data.text,
                        language = data.language.ifBlank { "Unknown" },
                        model = data.model
                    )
                )
            }.toInt()

            updateJob(jobId) {
                it.copy(
                    transcript = data.text,
                    language = data.language.ifBlank { "Unknown" },
                    model = data.model,
                    historyId = historyId,
                    progress = 1f,
                    isRunning = false,
                    error = null
                )
            }
        } finally {
            runCatching { outputFile.delete() }
        }
    }

    private fun addJob(job: TranscriptionJobState) {
        _uiState.update { it.copy(jobs = it.jobs + job) }
    }

    private fun updateJob(jobId: String, transform: (TranscriptionJobState) -> TranscriptionJobState) {
        _uiState.update { state ->
            state.copy(jobs = state.jobs.map { if (it.id == jobId) transform(it) else it })
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

    // New "returning" version for AssemblyAI — used by multi-job pipeline
    private suspend fun transcribeWithAssemblyReturn(
        file: File,
        apiKey: String,
        onStatus: (String) -> Unit
    ): TranscriptionData {
        try {
            onStatus("1/4 Uploading file...")
            val api = RetrofitClient.api

            val requestFileBody = file.asRequestBody("application/octet-stream".toMediaType())
            val uploadResponse = api.uploadFile(apiKey, requestFileBody)
            val audioUrl = uploadResponse.uploadUrl

            onStatus("2/4 Creating transcript...")
            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(apiKey, transcriptRequest)
            val transcriptId = transcriptResponse.id

            onStatus("3/4 Waiting for result...")
            repeat(36) {
                transcriptResponse = api.getTranscript(apiKey, transcriptId)
                when (transcriptResponse.status) {
                    "completed" -> {
                        val detectedLanguage = transcriptResponse.languageCode ?: "Unknown"
                        val newTranscription = transcriptResponse.text ?: "No text found."
                        // Cleanup remote transcript
                        runCatching { api.deleteTranscript(apiKey, transcriptId) }
                        onStatus("4/4 Success")
                        return TranscriptionData(
                            text = newTranscription,
                            language = detectedLanguage,
                            model = "AssemblyAI"
                        )
                    }
                    "error" -> {
                        runCatching { api.deleteTranscript(apiKey, transcriptId) }
                        throw IllegalStateException("Transcription failed. ${transcriptResponse.error.orEmpty()}")
                    }
                    else -> { /* continue waiting */ }
                }
                delay(5_000L)
            }
            throw IllegalStateException("Timed out waiting for transcription.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is HttpException) {
                val msg = if (e.code() == 401) "Unauthorized. Check your AssemblyAI API key." else "HTTP ${e.code()} ${e.message()}"
                throw IllegalStateException(msg, e)
            } else {
                throw IllegalStateException("Network or API error: ${e.message}", e)
            }
        }
    }

    // New "returning" version for Gemini — used by multi-job pipeline
    private suspend fun transcribeWithGeminiReturn(
        file: File,
        apiKey: String,
        onStatus: (String) -> Unit
    ): TranscriptionData {
        try {
            val sizeBytes = file.length()
            val maxBytes = 19L * 1024L * 1024L
            if (sizeBytes > maxBytes) {
                throw IllegalArgumentException("Audio is ${"%.1f".format(sizeBytes / (1024f * 1024f))} MB. Gemini inline limit is ~20 MB.")
            }

            onStatus("Uploading audio and transcribing...")
            val bytes = withContext(ioDispatcher) { file.readBytes() }

            val responseSchema = Schema.builder()
                .type(Object::class.java.simpleName)
                .properties(
                    mapOf(
                        "language_code" to Schema.builder().type(String::class.java.simpleName).build(),
                        "text" to Schema.builder().type(String::class.java.simpleName).build()
                    )
                )
                .required(listOf("language_code", "text"))
                .build()

            val config: GenerateContentConfig = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(responseSchema)
                .build()

            val content: Content = Content.fromParts(
                Part.fromText(
                    "Transcribe the provided audio. " +
                            "Return only a compact JSON object with fields: " +
                            "language_code (BCP-47 like en-US) and text (full transcript). " +
                            "Do not include any extra commentary."
                ),
                Part.fromBytes(bytes, "audio/wav")
            )

            val client = Client.builder().apiKey(apiKey).build()
            val response: GenerateContentResponse = withContext(ioDispatcher) {
                client.models.generateContent("gemini-2.5-flash", content, config)
            }

            val json = response.text() ?: "{}"
            val obj = JSONObject(json)
            val transcript = obj.optString("text").ifBlank { "No text found." }
            val language = obj.optString("language_code", "und")

            onStatus("Success")
            return TranscriptionData(
                text = transcript,
                language = language,
                model = "gemini-2.5-flash"
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("Gemini error: ${e.message ?: e::class.java.simpleName}", e)
        }
    }

    // Per-job summarization for multi-file flow
    fun summarizeJob(jobId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val job = state.jobs.find { it.id == jobId } ?: return@launch
            val text = job.transcript
            if (text.isBlank()) {
                _uiState.update { it.copy(status = "Nothing to summarize for ${job.displayName}.") }
                return@launch
            }
            val apiKey = state.geminiApiKey
            if (apiKey.isBlank()) {
                _uiState.update { it.copy(status = "Error: Gemini API Key is not set (Settings).") }
                return@launch
            }

            updateJob(jobId) { it.copy(isSummarizing = true) }
            _uiState.update { it.copy(status = "Summarizing with Gemini 2.5 Flash...") }
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
                    client.models.generateContent("gemini-2.5-flash", content, config)
                }

                val summary = response.text()?.trim().orEmpty().ifBlank { "No summary generated." }

                updateJob(jobId) { it.copy(isSummarizing = false, summary = summary) }
                _uiState.update { it.copy(status = "Summary generated.") }

                // Persist per-job summary
                withContext(ioDispatcher) {
                    val hid = job.historyId
                    if (hid != null) {
                        historyDao.updateSummary(hid, summary)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateJob(jobId) { it.copy(isSummarizing = false) }
                _uiState.update {
                    it.copy(status = "Summarization error: ${e.message ?: e::class.java.simpleName}")
                }
            }
        }
    }
}
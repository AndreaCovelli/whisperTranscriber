package com.andreacovelli.whispertranscriber

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.io.FileOutputStream
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.google.genai.types.Schema
import org.json.JSONObject

data class UiState(
    val status: String = "Welcome! Share an audio file to begin.",
    val transcription: String = "",
    val summary: String = "",
    val provider: TranscriptionProvider = TranscriptionProvider.ASSEMBLY_AI,
    val assemblyApiKey: String = "",
    val geminiApiKey: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val settings = ApiKeyManager(application)
    private val historyDao: TranscriptionHistoryDao

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var currentJob: Job? = null
    @Volatile private var currentFfmpegSessionId: Long? = null

    // Track the most recently inserted history id to update summary later
    private var lastInsertedHistoryId: Int? = null

    init {
        viewModelScope.launch {
            // One-shot migration from plaintext -> encrypted
            runCatching { settings.migrateToEncryptedIfNeeded() }

            // Collect settings
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

    fun handleAudioShare(uri: Uri) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            // Load persisted settings synchronously to avoid race on cold start
            val persistedProvider = settings.providerFlow.firstOrNull() ?: _uiState.value.provider
            val persistedAssemblyKey = settings.assemblyApiKeyFlow.firstOrNull().orEmpty()
            val persistedGeminiKey = settings.geminiApiKeyFlow.firstOrNull().orEmpty()

            // Keep UI state in sync with what we actually use
            _uiState.update {
                it.copy(
                    provider = persistedProvider,
                    assemblyApiKey = persistedAssemblyKey,
                    geminiApiKey = persistedGeminiKey
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

            _uiState.update { it.copy(status = "Processing file...", transcription = "", summary = "") }
            lastInsertedHistoryId = null

            val inputFile = copyUriToCache(uri)
            if (inputFile == null) {
                _uiState.update { it.copy(status = "Error: Could not read shared file.") }
                return@launch
            }

            _uiState.update { it.copy(status = "Converting to WAV...") }
            val outputFile = withContext(ioDispatcher) {
                File.createTempFile("converted_", ".wav", getApplication<Application>().cacheDir)
            }

            val command =
                "-hide_banner -y -i \"${inputFile.absolutePath}\" -vn -ac 1 -ar 16000 -c:a pcm_s16le \"${outputFile.absolutePath}\""

            val sessionDeferred = CompletableDeferred<FFmpegSession>()
            val session = FFmpegKit.executeAsync(command) { s ->
                if (!sessionDeferred.isCompleted) sessionDeferred.complete(s)
            }
            currentFfmpegSessionId = session.sessionId

            try {
                val finished = sessionDeferred.await()
                if (ReturnCode.isSuccess(finished.returnCode)) {
                    if (outputFile.exists() && outputFile.length() > 0) {
                        val state = _uiState.value
                        try {
                            if (usingAssembly) {
                                transcribeWithAssembly(outputFile, state.assemblyApiKey)
                            } else {
                                transcribeWithGemini(outputFile, state.geminiApiKey)
                            }
                        } finally {
                            runCatching { outputFile.delete() }
                            runCatching { inputFile.delete() }
                        }
                    } else {
                        _uiState.update { it.copy(status = "Error: FFmpeg created an empty file.") }
                        runCatching { outputFile.delete() }
                        runCatching { inputFile.delete() }
                    }
                } else {
                    val logs = finished.allLogsAsString
                    _uiState.update {
                        it.copy(status = "Error: FFmpeg conversion failed.${if (logs.isNotBlank()) " Details: $logs" else ""}")
                    }
                    runCatching { outputFile.delete() }
                    runCatching { inputFile.delete() }
                }
            } catch (ce: CancellationException) {
                currentFfmpegSessionId?.let { FFmpegKit.cancel(it) }
                runCatching { outputFile.delete() }
                runCatching { inputFile.delete() }
                throw ce
            } finally {
                currentFfmpegSessionId = null
            }
        }
    }

    // AssemblyAI path unchanged except capturing inserted row id and resetting summary in UI
    private suspend fun transcribeWithAssembly(file: File, apiKey: String) {
        try {
            _uiState.update { it.copy(status = "AssemblyAI: 1/4 Uploading file...") }
            val api = RetrofitClient.api

            val requestFileBody = file.asRequestBody("application/octet-stream".toMediaType())
            val uploadResponse = api.uploadFile(apiKey, requestFileBody)
            val audioUrl = uploadResponse.uploadUrl

            _uiState.update { it.copy(status = "AssemblyAI: 2/4 Creating transcript...") }

            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(apiKey, transcriptRequest)
            val transcriptId = transcriptResponse.id

            _uiState.update { it.copy(status = "AssemblyAI: 3/4 Waiting for result...") }

            repeat(36) {
                transcriptResponse = api.getTranscript(apiKey, transcriptId)
                when (transcriptResponse.status) {
                    "completed" -> {
                        val detectedLanguage = transcriptResponse.languageCode ?: "Unknown"
                        val newTranscription = transcriptResponse.text ?: "No text found."

                        val insertedId = withContext(ioDispatcher) {
                            historyDao.insert(
                                TranscriptionHistory(
                                    text = newTranscription,
                                    language = detectedLanguage,
                                    model = "AssemblyAI"
                                )
                            )
                        }.toInt()
                        lastInsertedHistoryId = insertedId

                        _uiState.update {
                            it.copy(
                                status = "AssemblyAI: 4/4 Success! (Language: $detectedLanguage)",
                                transcription = newTranscription,
                                summary = ""
                            )
                        }
                        viewModelScope.launch {
                            runCatching { api.deleteTranscript(apiKey, transcriptId) }
                        }
                        return
                    }
                    "error" -> {
                        _uiState.update {
                            it.copy(status = "Error: Transcription failed. ${transcriptResponse.error}")
                        }
                        viewModelScope.launch {
                            runCatching { api.deleteTranscript(apiKey, transcriptId) }
                        }
                        return
                    }
                    else -> { }
                }
                delay(5_000L)
            }
            _uiState.update { it.copy(status = "Error: Timed out waiting for transcription.") }

        } catch (e: Exception) {
            val msg = when (e) {
                is HttpException -> when (e.code()) {
                    401 -> "Error: Unauthorized. Check your AssemblyAI API Key."
                    else -> "Error: HTTP ${e.code()} ${e.message()}"
                }
                is CancellationException -> throw e
                else -> "Error: Network request failed: ${e.message}"
            }
            _uiState.update { it.copy(status = msg) }
        }
    }

    private suspend fun transcribeWithGemini(file: File, apiKey: String) {
        try {
            val sizeBytes = file.length()
            val maxBytes = 19L * 1024L * 1024L
            if (sizeBytes > maxBytes) {
                _uiState.update {
                    it.copy(
                        status = "Error: Audio file is ${"%.1f".format(sizeBytes / (1024f * 1024f))} MB. Gemini inline limit is ~20 MB. Please share a shorter clip."
                    )
                }
                return
            }

            _uiState.update { it.copy(status = "Gemini: Uploading audio and transcribing...") }

            val bytes = withContext(ioDispatcher) { file.readBytes() }

            val responseSchema = Schema.builder()
                .type(Object::class.java.simpleName)
                .properties(
                    mapOf(
                        "language_code" to Schema.builder()
                            .type(String::class.java.simpleName)
                            .build(),
                        "text" to Schema.builder()
                            .type(String::class.java.simpleName)
                            .build()
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

            val insertedId = withContext(ioDispatcher) {
                historyDao.insert(
                    TranscriptionHistory(
                        text = transcript,
                        language = language,
                        model = "gemini-2.5-flash"
                    )
                )
            }.toInt()
            lastInsertedHistoryId = insertedId

            _uiState.update {
                it.copy(
                    status = "Gemini: Success! (Language: $language)",
                    transcription = transcript,
                    summary = ""
                )
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update {
                it.copy(status = "Gemini error: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    fun summarizeTranscription() {
        viewModelScope.launch {
            val current = _uiState.value
            val text = current.transcription
            if (text.isBlank()) {
                _uiState.update { it.copy(status = "Nothing to summarize. Transcribe audio first.") }
                return@launch
            }
            val apiKey = current.geminiApiKey
            if (apiKey.isBlank()) {
                _uiState.update { it.copy(status = "Error: Gemini API Key is not set (Settings).") }
                return@launch
            }

            _uiState.update { it.copy(status = "Summarizing with Gemini 2.5 Flash...") }

            try {
                val client = Client.builder().apiKey(apiKey).build()
                val prompt = "You are an expert summarizer. Provide a concise, easy-to-read summary of the following text, in the same language. Use bullet points for key takeaways if appropriate"

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

                // Update UI
                _uiState.update {
                    it.copy(
                        status = "Summary generated.",
                        summary = summary
                    )
                }

                // Persist summary for the most recent item (if available)
                lastInsertedHistoryId?.let { id ->
                    withContext(ioDispatcher) { historyDao.updateSummary(id, summary) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(status = "Summarization error: ${e.message ?: e::class.java.simpleName}")
                }
            }
        }
    }

    private suspend fun copyUriToCache(uri: Uri): File? = withContext(ioDispatcher) {
        try {
            val app = getApplication<Application>()
            val inputStream = app.contentResolver.openInputStream(uri) ?: return@withContext null
            val inputFile = File.createTempFile("shared_audio", null, app.cacheDir)
            inputStream.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            }
            inputFile
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        currentFfmpegSessionId?.let { FFmpegKit.cancel(it) }
        currentFfmpegSessionId = null
    }
}
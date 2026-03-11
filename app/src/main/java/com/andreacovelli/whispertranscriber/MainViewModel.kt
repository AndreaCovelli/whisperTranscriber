// app/src/main/java/com/andreacovelli/whispertranscriber/MainViewModel.kt
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
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

sealed class CustomModelValidation {
    object Idle : CustomModelValidation()
    object Validating : CustomModelValidation()
    data class Valid(val displayName: String) : CustomModelValidation()
    data class Error(val message: String) : CustomModelValidation()
}

data class UiState(
    val status: String = "Waiting for audios...",
    val provider: TranscriptionProvider = TranscriptionProvider.ASSEMBLY_AI,
    val assemblyApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val geminiApiKeyState: ApiKeyState = ApiKeyState.Missing,
    val customGeminiModelName: String = "",
    val customModelValidation: CustomModelValidation = CustomModelValidation.Idle,
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
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"

        private const val GEMINI_MODELS_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_API_KEY_HEADER = "x-goog-api-key"

        private const val GEMINI_MAX_INLINE_BYTES = 19L * 1024L * 1024L // 19 MiB

        private const val ERR_ENTER_MODEL_NAME = "Enter a model name"
        private const val ERR_ENTER_GEMINI_KEY = "Enter your Gemini API key first"
        private const val ERR_MODEL_NOT_FOUND = "Model \"%s\" not found"
        private const val ERR_HTTP_VERIFY = "HTTP %d: could not verify model"

        private val HTTP_STATUS_REGEX = Regex("""\b([45]\d{2})\b""")

        // Natively supported Gemini MIME types
        private val SUPPORTED_GEMINI_MIME_TYPES = setOf(
            "audio/wav",
            "audio/x-wav",
            "audio/mp3",
            "audio/mpeg", // Common for MP3
            "audio/aiff",
            "audio/x-aiff",
            "audio/aac",
            "audio/ogg",
            "audio/vorbis",
            "audio/flac",
            "audio/x-flac"
        )

        // Natively supported AssemblyAI MIME types (based on AssemblyAI docs and common Android MIME types)
        private val SUPPORTED_ASSEMBLYAI_MIME_TYPES = setOf(
            "audio/3gpp", // .3ga, often contains AMR
            "audio/aac",
            "audio/x-hx-aac-adts",
            "audio/ac3",
            "audio/aiff",
            "audio/x-aiff",
            // ALAC is tricky, often container dependent (e.g., audio/mp4)
            "audio/amr",
            "audio/amr-wb",
            "audio/ape", // Monkey's Audio
            "audio/basic", // .au
            // DSS/QCP/TTA/VOC/WV are generally non-standard MIME types on Android, but included if recognizable
            "audio/flac",
            "audio/x-flac",
            "video/x-flv", // FLV container
            "audio/mp4", // .m4a, .m4b, .m4p, .m4r
            "audio/x-m4a",
            "audio/mpeg", // .mp3, .mpga
            "audio/mp3",
            "audio/ogg", // .ogg, .oga, .mogg
            "application/ogg",
            "audio/opus",
            "audio/wav",
            "audio/x-wav",
            "audio/x-ms-wma", // .wma
            "audio/webm",
            "audio/wavpack", // .wv
            // Video containers (AssemblyAI extracts audio)
            "video/mp4",
            "video/3gpp",
            "video/webm",
            "video/quicktime",
            "video/x-msvideo"
        )
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val settings = ApiKeyManager(application)
    private val httpClient = OkHttpClient()
    private val historyDao: TranscriptionHistoryDao

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // Job Queuing implementation
    private val jobQueue = Channel<TranscriptionJobState>(Channel.UNLIMITED)
    private var processorJob: Job? = null

    init {
        viewModelScope.launch {
            // migrateToEncryptedIfNeeded() call removed.

            launch {
                settings.providerFlow.collect { p ->
                    _uiState.update { it.copy(provider = p) }
                }
            }
            launch {
                settings.assemblyApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(assemblyApiKeyState = state) }
                }
            }
            launch {
                settings.geminiApiKeyStateFlow.collect { state ->
                    _uiState.update { it.copy(geminiApiKeyState = state) }
                }
            }
            launch {
                settings.customGeminiModelNameFlow.collect { name ->
                    _uiState.update { it.copy(customGeminiModelName = name.orEmpty()) }
                }
            }
        }

        // Cleanup old temp WAVs on a background thread
        viewModelScope.launch(ioDispatcher) {
            cleanupOldTempWavs(maxAgeMs = TimeUnit.DAYS.toMillis(3))
        }

        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()

        // Start the job processor
        startJobProcessor()
    }

    // Job Processor Loop
    private fun startJobProcessor() {
        // Check if already running, but also check if the previous run failed due to missing API key and retry
        if (processorJob?.isActive == true && _uiState.value.status.startsWith("Error:").not()) return

        processorJob = viewModelScope.launch {
            // Fetch latest settings before processing
            val persistedProvider = settings.providerFlow.firstOrNull() ?: _uiState.value.provider
            val persistedAssemblyKey = settings.assemblyApiKeyStateFlow.firstOrNull() ?: _uiState.value.assemblyApiKeyState
            val persistedGeminiKey = settings.geminiApiKeyStateFlow.firstOrNull() ?: _uiState.value.geminiApiKeyState

            // Update keys in state
            _uiState.update {
                it.copy(
                    provider = persistedProvider,
                    assemblyApiKeyState = persistedAssemblyKey,
                    geminiApiKeyState = persistedGeminiKey
                )
            }

            val usingAssembly = persistedProvider == TranscriptionProvider.ASSEMBLY_AI
            val neededKey = if (usingAssembly) persistedAssemblyKey else persistedGeminiKey

            // Get the specific model name if using Gemini
            val geminiModelName = when (persistedProvider) {
                TranscriptionProvider.GEMINI_CUSTOM -> {
                    val customName = settings.customGeminiModelNameFlow.firstOrNull()
                    if (customName.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(status = "Error: No custom Gemini model set. Enter and validate one in Settings.")
                        }
                        return@launch
                    }
                    customName
                }
                else -> persistedProvider.modelName
            }

            if (neededKey != ApiKeyState.Present) {
                // Only show error if there are jobs queued/pending.
                if (_uiState.value.jobs.any { it.status == "Queued" } || _uiState.value.status.contains("Queuing")) {
                    val msg = if (neededKey is ApiKeyState.Error) {
                        "Error: Keystore decryption failed. Please re-save your API key."
                    } else {
                        "Error: ${if (usingAssembly) "AssemblyAI" else "Gemini"} API Key is not set. Please configure in Settings."
                    }
                    _uiState.update {
                        it.copy(status = msg)
                    }
                }
                // Stop processor; it will be restarted when settings change or new job arrives.
                return@launch
            }

            // Ensure Gemini model name is present if provider is Gemini
            if (!usingAssembly && geminiModelName == null) {
                _uiState.update {
                    it.copy(status = "Error: Invalid Gemini configuration. Please check Settings.")
                }
                return@launch
            }

            var processedCount = 0
            // Update status if the queue was empty previously or showing an error
            if (_uiState.value.status.startsWith("Waiting") || _uiState.value.status.startsWith("Error:")) {
                _uiState.update { it.copy(status = "Ready to process queue...") }
            }

            jobQueue.receiveAsFlow().collect { jobState ->
                _uiState.update { it.copy(status = "Processing: ${jobState.displayName}") }
                updateJob(jobState.id) { it.copy(isRunning = true, status = "Starting...") }

                try {
                    processSingleJob(
                        jobId = jobState.id,
                        uri = jobState.uri!!,
                        displayName = jobState.displayName,
                        usingAssembly = usingAssembly,
                        geminiModel = geminiModelName // Pass the specific model name string
                    )
                    processedCount++
                } catch (_: CancellationException) {
                    updateJob(jobState.id) { it.copy(status = "Cancelled", isRunning = false, error = "Cancelled") }
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.java.simpleName
                    updateJob(jobState.id) { it.copy(status = "Error", isRunning = false, error = msg) }
                }
                _uiState.update { it.copy(status = "Processed $processedCount file(s). Waiting for more...") }
            }
        }
    }

    // Settings mutations (Auto-Save)
    fun setProvider(p: TranscriptionProvider) {
        _uiState.update { it.copy(provider = p) }
        viewModelScope.launch {
            settings.saveProvider(p)
            startJobProcessor() // Restart processor to re-evaluate provider/keys
        }
    }
    fun setAssemblyKey(k: String) {
        viewModelScope.launch {
            settings.saveAssemblyApiKey(k)
            startJobProcessor() // Restart processor if it was waiting for key
        }
    }
    fun setGeminiKey(k: String) {
        viewModelScope.launch {
            settings.saveGeminiApiKey(k)
            startJobProcessor() // Restart processor if it was waiting for key
        }
    }

    fun resetCustomModelValidation() {
        _uiState.update { it.copy(customModelValidation = CustomModelValidation.Idle) }
    }

    fun validateCustomGeminiModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(customModelValidation = CustomModelValidation.Error(ERR_ENTER_MODEL_NAME)) }
            return
        }
        _uiState.update { it.copy(customModelValidation = CustomModelValidation.Validating) }
        viewModelScope.launch {
            val keyChars = settings.getGeminiApiKey()
            if (keyChars == null || keyChars.isEmpty()) {
                val keyState = _uiState.value.geminiApiKeyState
                val errMsg = if (keyState is ApiKeyState.Error)
                    "Keystore error: ${keyState.message}. Please re-enter your key."
                else ERR_ENTER_GEMINI_KEY
                _uiState.update { it.copy(customModelValidation = CustomModelValidation.Error(errMsg)) }
                return@launch
            }

            try {
                val url = "$GEMINI_MODELS_BASE_URL/$trimmed"
                val request = Request.Builder()
                    .url(url)
                    .header(GEMINI_API_KEY_HEADER, String(keyChars).also { keyChars.fill('\u0000') })
                    .build()
                val (isSuccess, code, bodyText) = withContext(ioDispatcher) {
                    httpClient.newCall(request).execute().use { resp ->
                        Triple(resp.isSuccessful, resp.code, resp.body.string())
                    }
                }
                if (!isSuccess) {
                    val msg = if (code == 404) ERR_MODEL_NOT_FOUND.format(trimmed)
                              else ERR_HTTP_VERIFY.format(code)
                    _uiState.update { it.copy(customModelValidation = CustomModelValidation.Error(msg)) }
                    return@launch
                }
                val displayName = try {
                    JSONObject(bodyText).optString("displayName", trimmed)
                } catch (_: Exception) { trimmed }
                settings.saveCustomGeminiModelName(trimmed)
                _uiState.update {
                    it.copy(
                        customGeminiModelName = trimmed,
                        customModelValidation = CustomModelValidation.Valid(displayName)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(customModelValidation = CustomModelValidation.Error(e.message ?: "Validation failed"))
                }
            }
        }
    }

    fun forgetAssemblyKey() {
        viewModelScope.launch {
            settings.forgetAssemblyApiKey()
        }
    }
    fun forgetGeminiKey() {
        viewModelScope.launch {
            settings.forgetGeminiApiKey()
        }
    }

    fun clearCompletedJobs() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(jobs = state.jobs.filter { job ->
                    job.isRunning || (job.transcript.isBlank() && job.error == null)
                })
            }
        }
    }

    fun retryJob(jobId: String) {
        viewModelScope.launch {
            val job = _uiState.value.jobs.find { it.id == jobId } ?: return@launch
            val resetJob = job.copy(
                status = "Queued",
                progress = 0f,
                error = null,
                isRunning = false
            )
            updateJob(jobId) { resetJob }
            jobQueue.send(resetJob)
            startJobProcessor()
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

    // Updated handleAudioShareMultiple (Queuing)
    fun handleAudioShareMultiple(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(status = "Queuing ${uris.size} file(s)...") }

            // Create jobs and add them to the queue
            for ((index, uri) in uris.withIndex()) {
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val jobState = TranscriptionJobState(
                    uri = uri,
                    displayName = displayName,
                    status = "Queued",
                    progress = 0f,
                    isRunning = false // Starts as false, processor sets to true
                )
                addJob(jobState)
                jobQueue.send(jobState) // Add to the processing channel
            }
            // Ensure the processor is running
            startJobProcessor()
        }
    }

    // Helper function to create a streaming RequestBody from a Uri
    private fun createRequestBodyFromUri(uri: Uri): RequestBody {
        val app = getApplication<Application>()
        val contentResolver = app.contentResolver

        // Determine the content type, handle potential parsing errors, and fallback to octet-stream
        val contentType = contentResolver.getType(uri)?.let { mime ->
            try {
                mime.toMediaType()
            } catch (_: IllegalArgumentException) {
                "application/octet-stream".toMediaType()
            }
        } ?: "application/octet-stream".toMediaType()


        return object : RequestBody() {
            override fun contentType(): MediaType = contentType

            // Return the actual length if available, otherwise -1L (OkHttp will use chunked encoding)
            override fun contentLength(): Long = try {
                // Using openFileDescriptor/statSize is generally more reliable for length than AssetFileDescriptor.length
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) { -1L }

            override fun writeTo(sink: BufferedSink) {
                // Efficiently stream data from the input stream to the network sink
                // Use okio.source for efficient copying
                contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
                    ?: throw IllegalStateException("Could not open input stream for Uri: $uri")
            }
        }
    }

    private suspend fun processSingleJob(jobId: String, uri: Uri, displayName: String, usingAssembly: Boolean, geminiModel: String?) {
        val app = getApplication<Application>()
        val contentResolver = app.contentResolver

        // 1. Determine MIME type (lowercase for reliable comparison)
        val inputMimeType = contentResolver.getType(uri)?.lowercase()

        // 2. Determine if conversion is needed.
        val conversionNeeded = when {
            inputMimeType == null -> true // Always convert if MIME type is unknown
            usingAssembly -> inputMimeType !in SUPPORTED_ASSEMBLYAI_MIME_TYPES
            else -> inputMimeType !in SUPPORTED_GEMINI_MIME_TYPES // Gemini
        }

        // This will hold the temp file if created, otherwise null. Used for cleanup.
        var tempFileToClean: File? = null

        try {
            // Data holders for transcription step
            var fileForGemini: File? = null
            var mimeTypeForGemini: String? = null
            // For AssemblyAI, we use either the converted File or the original Uri for streaming
            var sourceForAssembly: Any? = null // Can be File or Uri

            if (conversionNeeded) {
                // --- Conversion Path ---
                val reasonSuffix = if (inputMimeType == null) "(Unknown format)" else "($inputMimeType)"
                val providerName = if (usingAssembly) "AssemblyAI" else "Gemini"
                val conversionReason = "Format not natively supported by $providerName $reasonSuffix"

                updateJob(jobId) { it.copy(status = "$conversionReason. Converting to 16 kHz WAV...", progress = 0f) }

                val outputFile = withContext(ioDispatcher) {
                    File.createTempFile("converted_${jobId.take(6)}", ".wav", app.cacheDir)
                }
                tempFileToClean = outputFile // Mark for cleanup

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

                if (usingAssembly) {
                    sourceForAssembly = outputFile
                } else {
                    // If converted for Gemini, use the temp file directly.
                    updateJob(jobId) { it.copy(status = "Converted audio ready for Gemini...") }
                    fileForGemini = outputFile
                    mimeTypeForGemini = "audio/wav"
                }

            } else {
                // --- Direct Path (Supported formats) ---

                if (usingAssembly) {
                    updateJob(jobId) { it.copy(status = "Format supported ($inputMimeType). Preparing for direct upload...", progress = 1f) }
                    // Use the original URI for streaming upload
                    sourceForAssembly = uri
                } else {
                    // Gemini
                    updateJob(jobId) { it.copy(status = "Format supported ($inputMimeType). Copying audio for Gemini...", progress = 0.5f) }

                    // Copy bytes directly from the URI stream to a temporary file efficiently
                    val tempFile = withContext(ioDispatcher) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            File.createTempFile("direct_${jobId.take(6)}", ".tmp", app.cacheDir).apply {
                                outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    } ?: throw IllegalStateException("Failed to read input audio file.")
                    
                    tempFileToClean = tempFile
                    fileForGemini = tempFile
                    mimeTypeForGemini = inputMimeType // Guaranteed not null because conversionNeeded is false

                    updateJob(jobId) { it.copy(progress = 1f) }
                }
            }


            // Transcribe
            val flow = if (usingAssembly) {
                if (sourceForAssembly == null) {
                    throw IllegalStateException("Internal error: AssemblyAI source (File or Uri) is missing.")
                }
                val keyChars = settings.getAssemblyApiKey() ?: throw IllegalStateException("API key missing or corrupted")
                val providerFlow = transcribeWithAssemblyReturn(
                    source = sourceForAssembly,
                    apiKey = keyChars
                )
                kotlinx.coroutines.flow.flow {
                    try {
                        providerFlow.collect { emit(it) }
                    } finally {
                        keyChars.fill('\u0000')
                    }
                }
            } else {
                // Ensure geminiModel and data are not null
                if (geminiModel == null) throw IllegalStateException("Gemini model not specified.")
                if (fileForGemini == null || mimeTypeForGemini == null) throw IllegalStateException("Internal error: Gemini audio data is missing.")

                val keyChars = settings.getGeminiApiKey() ?: throw IllegalStateException("API key missing or corrupted")
                val providerFlow = transcribeWithGeminiReturn(
                    audioFile = fileForGemini!!,
                    mimeType = mimeTypeForGemini,
                    apiKey = keyChars,
                    modelName = geminiModel
                )
                kotlinx.coroutines.flow.flow {
                    try {
                        providerFlow.collect { emit(it) }
                    } finally {
                        keyChars.fill('\u0000')
                    }
                }
            }

            val data = flow.let { f ->
                var resultData: TranscriptionData? = null
                f.collect { result ->
                    when (result) {
                        is TranscriptionResult.Loading -> updateJob(jobId) { it.copy(status = "${if (usingAssembly) "AssemblyAI" else "Gemini"}: ${result.message}") }
                        is TranscriptionResult.Success -> resultData = result.data
                        is TranscriptionResult.Error -> throw result.exception
                    }
                }
                resultData ?: throw IllegalStateException("Transcription failed without error.")
            }

            // Persist in history
            val historyId = withContext(ioDispatcher) {
                historyDao.insert(
                    TranscriptionHistory(
                        text = data.text,
                        language = data.language.ifBlank { "Unknown" },
                        model = data.model,
                        displayName = displayName
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
            // Cleanup the temp file if it was created
            tempFileToClean?.let { runCatching { it.delete() } }
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

    // Updated transcribeWithAssemblyReturn (Increased Timeout, supports File or Uri via streaming)
    private fun transcribeWithAssemblyReturn(
        source: Any, // Accepts File (converted) or Uri (original)
        apiKey: CharArray
    ): kotlinx.coroutines.flow.Flow<TranscriptionResult> = kotlinx.coroutines.flow.flow {
        try {
            emit(TranscriptionResult.Loading("1/4 Uploading file..."))
            val api = RetrofitClient.api

            // Determine how to create the RequestBody
            val requestFileBody = when (source) {
                is File -> {
                    // If it's a File (likely a converted WAV), use the standard method.
                    // Use octet-stream as a safe fallback for converted files.
                    source.asRequestBody("application/octet-stream".toMediaType())
                }
                is Uri -> {
                    // If it's a Uri (original supported format), stream it efficiently.
                    createRequestBodyFromUri(source)
                }
                else -> throw IllegalArgumentException("Unsupported source type for AssemblyAI: ${source::class.java.name}")
            }

            // The Retrofit interface handles the upload regardless of the RequestBody implementation
            val uploadResponse = api.uploadFile(String(apiKey), requestFileBody)

            val audioUrl = uploadResponse.uploadUrl

            emit(TranscriptionResult.Loading("2/4 Creating transcript..."))
            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(String(apiKey), transcriptRequest)
            val transcriptId = transcriptResponse.id

            emit(TranscriptionResult.Loading("3/4 Waiting for result..."))
            // Increased timeout from 36 (3 min) to 120 (10 min) for longer files
            repeat(120) {
                transcriptResponse = api.getTranscript(String(apiKey), transcriptId)
                when (transcriptResponse.status) {
                    "completed" -> {
                        val detectedLanguage = transcriptResponse.languageCode ?: "Unknown"
                        val newTranscription = transcriptResponse.text ?: "No text found."
                        // Cleanup remote transcript
                        runCatching { api.deleteTranscript(String(apiKey), transcriptId) }
                        emit(TranscriptionResult.Loading("4/4 Success"))
                        emit(TranscriptionResult.Success(
                            TranscriptionData(
                                text = newTranscription,
                                language = detectedLanguage,
                                model = "AssemblyAI"
                            )
                        ))
                        return@flow
                    }
                    "error" -> {
                        runCatching { api.deleteTranscript(String(apiKey), transcriptId) }
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
                emit(TranscriptionResult.Error(IllegalStateException(friendlyHttpError(e.code(), "AssemblyAI"), e)))
            } else {
                emit(TranscriptionResult.Error(IllegalStateException("Network or API error: ${e.message}", e)))
            }
        }
    }

    // Updated transcribeWithGeminiReturn (Robustness, Model Selection, and File API for Large Files)
    private fun transcribeWithGeminiReturn(
        audioFile: File,
        mimeType: String,
        apiKey: CharArray,
        modelName: String // Accept the model name string
    ): kotlinx.coroutines.flow.Flow<TranscriptionResult> = kotlinx.coroutines.flow.flow {
        val client = Client.builder().apiKey(String(apiKey)).build()
        var uploadedFile: com.google.genai.types.File? = null

        try {
            val sizeBytes = audioFile.length()
            val maxInlineBytes = GEMINI_MAX_INLINE_BYTES
            val isLargeFile = sizeBytes > maxInlineBytes

            // Update status to include MIME type and upload method for clarity
            val methodMsg = if (isLargeFile) "via File API (>19MB)" else "inline"
            emit(TranscriptionResult.Loading("Uploading audio ($mimeType) $methodMsg..."))

            val audioPart = if (isLargeFile) {
                // File API Upload for files > 19MB
                uploadedFile = withContext(ioDispatcher) {
                    client.files.upload(audioFile.absolutePath, null)
                }
                Part.fromUri(
                    uploadedFile.uri().orElseThrow { IllegalStateException("Uploaded file is missing a URI.") },
                    uploadedFile.mimeType().orElse(mimeType)
                )

            } else {
                // Inline byte array for files <= 19MB
                val bytes = withContext(ioDispatcher) { audioFile.readBytes() }
                Part.fromBytes(bytes, mimeType)
            }

            emit(TranscriptionResult.Loading("Transcribing audio..."))

            // Use constants for schema types
            val responseSchema = Schema.builder()
                .type(TYPE_OBJECT)
                .properties(
                    mapOf(
                        "language_code" to Schema.builder().type(TYPE_STRING).build(),
                        "text" to Schema.builder().type(TYPE_STRING).build()
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
                audioPart
            )

            val response: GenerateContentResponse = withContext(ioDispatcher) {
                // Use the dynamic model name
                client.models.generateContent(modelName, content, config)
            }

            val json = response.text() ?: "{}"
            val obj = JSONObject(json)
            val transcript = obj.optString("text").ifBlank { "No text found." }
            val language = obj.optString("language_code", "und")

            emit(TranscriptionResult.Loading("Success"))
            emit(TranscriptionResult.Success(
                TranscriptionData(
                    text = transcript,
                    language = language,
                    model = modelName // Return the actual model used
                )
            ))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val httpCode = if (e is HttpException) e.code() else extractHttpStatusFromMessage(e)
            val msg = if (httpCode != null) {
                friendlyHttpError(httpCode, "Gemini")
            } else {
                "Gemini error: ${e.message ?: e::class.java.simpleName}"
            }
            emit(TranscriptionResult.Error(IllegalStateException(msg, e)))
        } finally {
            // Attempt to clean up the remote file after transcription
            uploadedFile?.let { remoteFile ->
                runCatching {
                    withContext(ioDispatcher) {
                        remoteFile.name().ifPresent { fileName ->
                            client.files.delete(fileName, null)
                        }
                    }
                }.onFailure {
                    // Log cleanup failures for debugging purposes. This can help track orphaned remote files.
                    android.util.Log.w("MainViewModel", "Failed to delete remote Gemini file: ${remoteFile.name()}", it)
                }
            }
        }
    }

    private fun friendlyHttpError(code: Int, provider: String): String = when {
        code == 401 -> "Unauthorized. Check your $provider API key."
        code == 429 -> "Rate limit exceeded. Please wait a moment and try again."
        code in 500..599 -> "Server error (HTTP $code). $provider's servers are experiencing issues. Please try again later."
        else -> "HTTP $code: unexpected error from $provider."
    }

    private fun extractHttpStatusFromMessage(e: Exception): Int? =
        HTTP_STATUS_REGEX.find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()

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
            val keyChars = settings.getGeminiApiKey()
            if (keyChars == null || keyChars.isEmpty()) {
                _uiState.update { it.copy(status = "Error: Gemini API Key is not set (Settings) or Keystore error.") }
                return@launch
            }
            val apiKey = String(keyChars)
            keyChars.fill('\u0000')

            // Determine which model to use for summarization.
            // We default to the currently selected provider if it's Gemini, otherwise use Flash as a sensible default if API key is present.
            val providerForSummary = state.provider
            val modelName = when {
                providerForSummary == TranscriptionProvider.GEMINI_CUSTOM -> {
                    val customName = state.customGeminiModelName
                    if (customName.isBlank()) {
                        _uiState.update { it.copy(status = "Error: No custom Gemini model set for summarization.") }
                        updateJob(jobId) { it.copy(isSummarizing = false) }
                        return@launch
                    }
                    customName
                }
                providerForSummary.isGemini() -> providerForSummary.modelName!!
                else -> TranscriptionProvider.GEMINI_FLASH.modelName!!
            }

            updateJob(jobId) { it.copy(isSummarizing = true) }
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
                    // Use the determined model name
                    client.models.generateContent(modelName, content, config)
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
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

    companion object {
        // GEMINI_MODEL constant removed.
        // Use explicit JSON schema type strings for Proguard safety
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"

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
            val persistedAssemblyKey = settings.assemblyApiKeyFlow.firstOrNull().orEmpty()
            val persistedGeminiKey = settings.geminiApiKeyFlow.firstOrNull().orEmpty()

            // Update keys in state
            _uiState.update {
                it.copy(
                    provider = persistedProvider,
                    assemblyApiKey = persistedAssemblyKey,
                    geminiApiKey = persistedGeminiKey
                )
            }

            val usingAssembly = persistedProvider == TranscriptionProvider.ASSEMBLY_AI
            val neededKey = if (usingAssembly) persistedAssemblyKey else persistedGeminiKey

            // Get the specific model name if using Gemini
            val geminiModelName = persistedProvider.modelName

            if (neededKey.isBlank()) {
                // Only show error if there are jobs queued/pending.
                if (_uiState.value.jobs.any { it.status == "Queued" } || _uiState.value.status.contains("Queuing")) {
                    _uiState.update {
                        it.copy(status = "Error: ${if (usingAssembly) "AssemblyAI" else "Gemini"} API Key is not set. Please configure in Settings.")
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
                        usingAssembly = usingAssembly,
                        apiKey = neededKey,
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
        _uiState.update { it.copy(assemblyApiKey = k) }
        viewModelScope.launch {
            settings.saveAssemblyApiKey(k)
            startJobProcessor() // Restart processor if it was waiting for key
        }
    }
    fun setGeminiKey(k: String) {
        _uiState.update { it.copy(geminiApiKey = k) }
        viewModelScope.launch {
            settings.saveGeminiApiKey(k)
            startJobProcessor() // Restart processor if it was waiting for key
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

    fun clearCompletedJobs() {
        _uiState.update { state ->
            state.copy(jobs = state.jobs.filter {
                it.isRunning || (it.transcript.isBlank() && it.error == null)
            })
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

    private suspend fun processSingleJob(jobId: String, uri: Uri, usingAssembly: Boolean, apiKey: String, geminiModel: String?) {
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

        // This will hold the converted file if created, otherwise null. Used for cleanup.
        var convertedTempFile: File? = null

        try {
            // Data holders for transcription step
            var bytesForGemini: ByteArray? = null
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
                convertedTempFile = outputFile // Mark for cleanup

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
                    // If converted for Gemini, load bytes from the temp file.
                    updateJob(jobId) { it.copy(status = "Loading converted audio...") }
                    bytesForGemini = withContext(ioDispatcher) { outputFile.readBytes() }
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
                    updateJob(jobId) { it.copy(status = "Format supported ($inputMimeType). Loading audio directly...", progress = 0.5f) }

                    // Read bytes directly from the URI stream efficiently
                    bytesForGemini = withContext(ioDispatcher) {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: throw IllegalStateException("Failed to read input audio file.")
                    mimeTypeForGemini =
                        inputMimeType // Guaranteed not null because conversionNeeded is false

                    updateJob(jobId) { it.copy(progress = 1f) }
                }
            }


            // Transcribe
            val data = if (usingAssembly) {
                if (sourceForAssembly == null) {
                    throw IllegalStateException("Internal error: AssemblyAI source (File or Uri) is missing.")
                }
                transcribeWithAssemblyReturn(
                    source = sourceForAssembly,
                    apiKey = apiKey,
                    onStatus = { s -> updateJob(jobId) { it.copy(status = "AssemblyAI: $s") } }
                )
            } else {
                // Ensure geminiModel and data are not null
                if (geminiModel == null) throw IllegalStateException("Gemini model not specified.")
                if (bytesForGemini == null || mimeTypeForGemini == null) throw IllegalStateException("Internal error: Gemini audio data is missing.")

                transcribeWithGeminiReturn(
                    audioBytes = bytesForGemini,
                    mimeType = mimeTypeForGemini,
                    apiKey = apiKey,
                    modelName = geminiModel,
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
            // Cleanup the temp file if it was created (i.e., if conversion happened)
            convertedTempFile?.let { runCatching { it.delete() } }
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
    private suspend fun transcribeWithAssemblyReturn(
        source: Any, // Accepts File (converted) or Uri (original)
        apiKey: String,
        onStatus: (String) -> Unit
    ): TranscriptionData {
        try {
            onStatus("1/4 Uploading file...")
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
            val uploadResponse = api.uploadFile(apiKey, requestFileBody)
            val audioUrl = uploadResponse.uploadUrl

            onStatus("2/4 Creating transcript...")
            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(apiKey, transcriptRequest)
            val transcriptId = transcriptResponse.id

            onStatus("3/4 Waiting for result...")
            // Increased timeout from 36 (3 min) to 120 (10 min) for longer files
            repeat(120) {
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

    // Updated transcribeWithGeminiReturn (Robustness, Model Selection, and Direct Bytes Input)
    // MODIFIED: Changed signature to accept ByteArray and mimeType instead of File.
    private suspend fun transcribeWithGeminiReturn(
        audioBytes: ByteArray,
        mimeType: String,
        apiKey: String,
        modelName: String, // Accept the model name string
        onStatus: (String) -> Unit
    ): TranscriptionData {
        try {
            // Use the size of the byte array
            val sizeBytes = audioBytes.size.toLong()
            // Adjusting limit slightly based on Gemini documentation (approx 20MB for inline data)
            val maxBytes = 19L * 1024L * 1024L
            if (sizeBytes > maxBytes) {
                // Clarified error message using MiB
                throw IllegalArgumentException("Audio is ${"%.1f".format(sizeBytes / (1024f * 1024f))} MiB. Gemini inline limit is ~20 MiB.")
            }

            // Update status to include MIME type for clarity
            onStatus("Uploading audio ($mimeType) and transcribing...")
            // Removed file reading logic as bytes are now passed in.

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
                // Use the provided bytes and the correct MIME type
                Part.fromBytes(audioBytes, mimeType)
            )

            val client = Client.builder().apiKey(apiKey).build()
            val response: GenerateContentResponse = withContext(ioDispatcher) {
                // Use the dynamic model name
                client.models.generateContent(modelName, content, config)
            }

            val json = response.text() ?: "{}"
            val obj = JSONObject(json)
            val transcript = obj.optString("text").ifBlank { "No text found." }
            val language = obj.optString("language_code", "und")

            onStatus("Success")
            return TranscriptionData(
                text = transcript,
                language = language,
                model = modelName // Return the actual model used
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

            // Determine which model to use for summarization.
            // We default to the currently selected provider if it's Gemini, otherwise use Flash as a sensible default if API key is present.
            val providerForSummary = state.provider
            val modelName = if (providerForSummary.isGemini()) {
                providerForSummary.modelName!!
            } else {
                // If using AssemblyAI for transcription, default to Flash for summarization
                TranscriptionProvider.GEMINI_FLASH.modelName!!
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
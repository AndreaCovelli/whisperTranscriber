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

data class UiState(
    val status: String = "Welcome! Share an audio file to begin.",
    val apiKey: String = "",
    val transcription: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val apiKeyManager = ApiKeyManager(application)
    private val historyDao: TranscriptionHistoryDao

    // New: control concurrency and cancellation
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var currentJob: Job? = null
    @Volatile private var currentFfmpegSessionId: Long? = null

    init {
        viewModelScope.launch {
            apiKeyManager.apiKeyFlow.collect { key ->
                _uiState.update { it.copy(apiKey = key ?: "") }
            }
        }
        val database = AppDatabase.getDatabase(application)
        historyDao = database.transcriptionHistoryDao()
    }

    fun onApiKeyChange(newKey: String) {
        _uiState.update { it.copy(apiKey = newKey) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            apiKeyManager.saveApiKey(_uiState.value.apiKey)
            _uiState.update { it.copy(status = "API Key Saved!") }
        }
    }

    fun handleAudioShare(uri: Uri) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val apiKey = apiKeyManager.apiKeyFlow.firstOrNull()
            if (apiKey.isNullOrBlank()) {
                _uiState.update { it.copy(status = "Error: API Key is not set.") }
                return@launch
            }

            _uiState.update { it.copy(status = "Processing file...", transcription = "") }

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
                        try {
                            transcribe(outputFile, apiKey)
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

    private suspend fun transcribe(file: File, apiKey: String) {
        try {
            _uiState.update { it.copy(status = "1/4: Uploading file...") }
            val api = RetrofitClient.api

            val requestFileBody = file.asRequestBody("application/octet-stream".toMediaType())
            val uploadResponse = api.uploadFile(apiKey, requestFileBody)
            val audioUrl = uploadResponse.uploadUrl

            _uiState.update { it.copy(status = "2/4: File uploaded. Creating transcript...") }

            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(apiKey, transcriptRequest)
            val transcriptId = transcriptResponse.id

            _uiState.update { it.copy(status = "3/4: Transcription queued. Waiting for result...") }

            repeat(36) {
                transcriptResponse = api.getTranscript(apiKey, transcriptId)
                when (transcriptResponse.status) {
                    "completed" -> {
                        val detectedLanguage = transcriptResponse.languageCode ?: "Unknown"
                        val newTranscription = transcriptResponse.text ?: "No text found."

                        withContext(ioDispatcher) {
                            historyDao.insert(
                                TranscriptionHistory(
                                    text = newTranscription,
                                    language = detectedLanguage
                                )
                            )
                        }

                        _uiState.update {
                            it.copy(
                                status = "4/4: Success! (Language: $detectedLanguage)",
                                transcription = newTranscription
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
                    else -> { /* queued or processing */ }
                }
                delay(5_000L)
            }
            _uiState.update { it.copy(status = "Error: Timed out waiting for transcription.") }

        } catch (e: Exception) {
            val errorMessage = when (e) {
                is HttpException -> when (e.code()) {
                    401 -> "Error: Unauthorized. Check your AssemblyAI API Key."
                    else -> "Error: HTTP ${e.code()} ${e.message()}"
                }
                is CancellationException -> throw e
                else -> "Error: Network request failed: ${e.message}"
            }
            _uiState.update { it.copy(status = errorMessage) }
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
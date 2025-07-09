package com.andreacovelli.whispertranscriber

import ApiKeyManager
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionHistoryDao
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        viewModelScope.launch {
            val apiKey = apiKeyManager.apiKeyFlow.firstOrNull()
            if (apiKey.isNullOrBlank()) {
                _uiState.update { it.copy(status = "Error: API Key is not set.") }
                return@launch
            }

            _uiState.update { it.copy(status = "Processing file...", transcription = "") }

            // 1. Copy the file from content URI to a local cache file
            val inputFile = copyUriToCache(uri)
            if (inputFile == null) {
                _uiState.update { it.copy(status = "Error: Could not read shared file.") }
                return@launch
            }

            // 2. Convert to MP3 using FFmpeg
            _uiState.update { it.copy(status = "Converting to MP3...") }
            val outputFile = File(getApplication<Application>().cacheDir, "converted.mp3")
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val command = "-i \"${inputFile.absolutePath}\" -c:a libmp3lame \"${outputFile.absolutePath}\""
            FFmpegKit.executeAsync(command) { session ->
                if (session.returnCode.isValueSuccess) {
                    // 3. Transcribe with Whisper API
                    if (outputFile.exists() && outputFile.length() > 0) {
                        // Pass inputFile for proper cleanup
                        transcribe(outputFile, apiKey, inputFile)
                    } else {
                        _uiState.update { it.copy(status = "Error: FFmpeg created an empty file.") }
                        inputFile.delete() // Clean up
                    }
                } else {
                    _uiState.update { it.copy(status = "Error: FFmpeg conversion failed.") }
                    // Clean up
                    inputFile.delete()
                }
            }
        }
    }

    private fun transcribe(file: File, apiKey: String, inputFile: File) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(status = "1/4: Uploading file...") }
                val api = RetrofitClient.create()

                // Step 1: Upload the file
                val requestFileBody = file.asRequestBody()
                val uploadResponse = api.uploadFile(apiKey, requestFileBody)
                val audioUrl = uploadResponse.upload_url

                _uiState.update { it.copy(status = "2/4: File uploaded. Creating transcript...") }

                // Step 2: Request the transcription
                val transcriptRequest = TranscriptRequest(audio_url = audioUrl)
                var transcriptResponse = api.createTranscript(apiKey, transcriptRequest)
                val transcriptId = transcriptResponse.id

                _uiState.update { it.copy(status = "3/4: Transcription queued. Waiting for result...") }

                // Step 3: Poll for the result
                for (i in 1..10) {
                    transcriptResponse = api.getTranscript(apiKey, transcriptId)
                    when (transcriptResponse.status) {
                        "completed" -> {
                            val detectedLanguage = transcriptResponse.language_code?.toString() ?: "Unknown"
                            val newTranscription = transcriptResponse.text ?: "No text found."

                            // Save to history
                            val historyItem = TranscriptionHistory(
                                text = newTranscription,
                                language = detectedLanguage
                            )
                            historyDao.insert(historyItem)

                            _uiState.update {
                                it.copy(
                                    status = "4/4: Success! (Language: $detectedLanguage)",
                                    transcription = newTranscription
                                )
                            }
                            viewModelScope.launch {
                                try {
                                    api.deleteTranscript(apiKey, transcriptId)
                                } catch (e: Exception) {
                                    // Log this error, but don't bother the user.
                                }
                            }
                            return@launch // Exit the coroutine on success
                        }
                        "error" -> {
                            _uiState.update {
                                it.copy(
                                    status = "Error: Transcription failed. ${transcriptResponse.error}"
                                )
                            }
                            return@launch // Exit on error
                        }
                        else -> {
                            // "queued" or "processing", continue polling
                        }
                    }
                    delay(5000)
                }
                _uiState.update { it.copy(status = "Error: Timed out waiting for transcription.") }

            } catch (e: Exception) {
                val errorMessage = when (e) {
                    is HttpException -> when (e.code()) {
                        401 -> "Error: Unauthorized. Check your AssemblyAI API Key."
                        else -> "Error: HTTP ${e.code()} ${e.message()}"
                    }
                    else -> "Error: Network request failed: ${e.message}"
                }
                _uiState.update { it.copy(status = errorMessage) }
            } finally {
                // Clean up both cache files regardless of outcome
                file.delete()
                inputFile.delete()
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val contentResolver = getApplication<Application>().contentResolver
            val inputFile = File.createTempFile("shared_audio", null, getApplication<Application>().cacheDir)
            val inputStream = contentResolver.openInputStream(uri)
            val outputStream = FileOutputStream(inputFile)
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            inputFile
        } catch (e: Exception) {
            null
        }
    }
}
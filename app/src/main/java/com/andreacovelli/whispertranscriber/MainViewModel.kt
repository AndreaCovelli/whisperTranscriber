package com.andreacovelli.whispertranscriber

import ApiKeyManager
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay

data class UiState(
    val status: String = "Welcome! Share an audio file to begin.",
    val apiKey: String = "",
    val transcription: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val apiKeyManager = ApiKeyManager(application)

    init {
        viewModelScope.launch {
            apiKeyManager.apiKeyFlow.collect { key ->
                _uiState.update { it.copy(apiKey = key ?: "") }
            }
        }
    }

    fun onApiKeyChange(newKey: String) {
        _uiState.update { it.copy(apiKey = newKey) }
    }

    // NEW: The save button will now have no parameters
    fun saveApiKey() {
        viewModelScope.launch {
            // It saves whatever key is currently in the state
            apiKeyManager.saveApiKey(_uiState.value.apiKey)

            // Optional: Give the user feedback that it's saved!
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
                    transcribe(outputFile, apiKey)
                } else {
                    _uiState.update { it.copy(status = "Error: FFmpeg conversion failed.") }
                    // Clean up
                    inputFile.delete()
                }
            }
        }
    }

    private fun transcribe(file: File, apiKey: String) {
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
                // We will check every 5 seconds for a result, up to 10 times.
                for (i in 1..10) {
                    transcriptResponse = api.getTranscript(apiKey, transcriptId)
                    when (transcriptResponse.status) {
                        "completed" -> {
                            val detectedLanguage = transcriptResponse.language_code?.toString() ?: "Unknown"
                            _uiState.update {
                                it.copy(
                                    status = "4/4: Success! (Language: $detectedLanguage)",
                                    transcription = transcriptResponse.text ?: "No text found."
                                )
                            }
                            viewModelScope.launch {
                                try {
                                    api.deleteTranscript(apiKey, transcriptId)
                                } catch (e: Exception) {
                                    // Log this error if you want, but don't bother the user
                                    // since they already have their transcription.
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
                    }
                    // If still processing, wait 5 seconds before checking again
                    delay(5000)
                }
                // If the loop finishes without a result, it timed out.
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
                // Clean up cache files regardless of outcome
                file.delete()
                File(getApplication<Application>().cacheDir, "shared_audio.opus").delete()
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val contentResolver = getApplication<Application>().contentResolver
            val inputFile = File(getApplication<Application>().cacheDir, "shared_audio.opus")
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
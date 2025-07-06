package com.andreacovelli.whispertranscriber

import ApiKeyManager
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
                if (session.returnCode.isValueSuccess()) {
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
                _uiState.update { it.copy(status = "Transcribing...") }

                val requestFile = file.asRequestBody("audio/mpeg".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val model = "whisper-1".toRequestBody("text/plain".toMediaTypeOrNull())

                val api = RetrofitClient.create(apiKey)
                val response = api.transcribeAudio("Bearer $apiKey", body, model)

                _uiState.update { it.copy(status = "Success!", transcription = response.text) }

            } catch (e: Exception) {
                _uiState.update { it.copy(status = "Error: API call failed: ${e.message}") }
            } finally {
                // Clean up cache files
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
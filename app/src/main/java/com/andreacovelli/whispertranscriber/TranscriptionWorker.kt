package com.andreacovelli.whispertranscriber

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andreacovelli.whispertranscriber.data.AppDatabase
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.File as GenAiFile
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.UploadFileConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import androidx.core.net.toUri

class TranscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settings = ApiKeyManager(appContext)
    private val repository = TranscriptionRepository(appContext)

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"
        
        private const val TYPE_OBJECT = "OBJECT"
        private const val TYPE_STRING = "STRING"

        private const val CONVERTED_FILE_PREFIX = "converted_"
        private const val DIRECT_FILE_PREFIX = "direct_"

        private val HTTP_STATUS_REGEX = Regex("""\b([45]\d{2})\b""")

        private val SUPPORTED_GEMINI_MIME_TYPES = setOf(
            "audio/wav", "audio/x-wav", "audio/mp3", "audio/mpeg", "audio/aiff", "audio/x-aiff",
            "audio/aac", "audio/ogg", "audio/vorbis", "audio/flac", "audio/x-flac"
        )

        private val SUPPORTED_ASSEMBLYAI_MIME_TYPES = setOf(
            "audio/3gpp", "audio/aac", "audio/x-hx-aac-adts", "audio/ac3", "audio/aiff", "audio/x-aiff",
            "audio/amr", "audio/amr-wb", "audio/ape", "audio/basic", "audio/flac", "audio/x-flac",
            "video/x-flv", "audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/mp3", "audio/ogg",
            "application/ogg", "audio/opus", "audio/wav", "audio/x-wav", "audio/x-ms-wma", "audio/webm",
            "audio/wavpack", "video/mp4", "video/3gpp", "video/webm", "video/quicktime", "video/x-msvideo"
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Starting transcription...")
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription Service",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Transcribing Audio")
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.my_launcher)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(progressText: String) {
        setForeground(createForegroundInfo(progressText))
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getInt("jobId", -1)
        if (jobId == -1) return Result.failure()

        val job = repository.getJob(jobId) ?: return Result.failure()
        if (job.status == "Cancelled") return Result.success()

        try {
            setForeground(getForegroundInfo())
            repository.updateRunning(jobId, true, "Starting...")
            updateNotification("Processing: ${job.displayName}")

            val persistedProvider = settings.providerFlow.firstOrNull() ?: TranscriptionProvider.ASSEMBLY_AI
            val usingAssembly = persistedProvider == TranscriptionProvider.ASSEMBLY_AI
            val geminiModelName = when (persistedProvider) {
                TranscriptionProvider.GEMINI_CUSTOM -> settings.customGeminiModelNameFlow.firstOrNull()
                else -> persistedProvider.modelName
            }

            processSingleJob(
                jobId = jobId,
                uri = job.uri.toUri(),
                displayName = job.displayName,
                usingAssembly = usingAssembly,
                geminiModel = geminiModelName
            )
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e::class.java.simpleName
            repository.updateError(jobId, msg, false, "Error")
            return Result.failure()
        }
    }

    private suspend fun processSingleJob(jobId: Int, uri: Uri, displayName: String, usingAssembly: Boolean, geminiModel: String?) {
        val app = applicationContext as Application
        val contentResolver = app.contentResolver
        val historyDao = AppDatabase.getDatabase(app).transcriptionHistoryDao()

        val inputMimeType = contentResolver.getType(uri)?.lowercase()

        val conversionNeeded = when {
            inputMimeType == null -> true
            usingAssembly -> inputMimeType !in SUPPORTED_ASSEMBLYAI_MIME_TYPES
            else -> inputMimeType !in SUPPORTED_GEMINI_MIME_TYPES
        }

        var tempFileToClean: File? = null

        try {
            var fileForGemini: File? = null
            var mimeTypeForGemini: String? = null
            var sourceForAssembly: Any? = null

            if (conversionNeeded) {
                val reasonSuffix = if (inputMimeType == null) "(Unknown format)" else "($inputMimeType)"
                val providerName = if (usingAssembly) "AssemblyAI" else "Gemini"
                val conversionReason = "Format not natively supported by $providerName $reasonSuffix"

                repository.updateStatusAndProgress(jobId, "$conversionReason. Converting to 16 kHz WAV...", 0f)
                updateNotification("Converting audio...")

                val outputFile = withContext(Dispatchers.IO) {
                    File.createTempFile("${CONVERTED_FILE_PREFIX}${jobId}", ".wav", app.cacheDir)
                }
                tempFileToClean = outputFile

                HardwareAudioConverter.convertToWav(
                    context = app,
                    inputUri = uri,
                    outputFile = outputFile,
                    forceMono = true,
                    targetSampleRate = 16_000,
                    onProgress = { p ->
                        repository.updateStatusAndProgress(
                            jobId,
                            "Converting to 16 kHz WAV: ${"%.1f".format(p * 100)}%",
                            p
                        )
                    }
                )

                if (usingAssembly) {
                    sourceForAssembly = outputFile
                } else {
                    repository.updateStatus(jobId, "Converted audio ready for Gemini...")
                    fileForGemini = outputFile
                    mimeTypeForGemini = "audio/wav"
                }
            } else {
                if (usingAssembly) {
                    repository.updateStatusAndProgress(jobId, "Format supported ($inputMimeType). Preparing for direct upload...", 1f)
                    sourceForAssembly = uri
                } else {
                    repository.updateStatusAndProgress(jobId, "Format supported ($inputMimeType). Copying audio for Gemini...", 0.5f)
                    updateNotification("Copying audio...")

                    val tempFile = contentResolver.openInputStream(uri)?.use { input ->
                        File.createTempFile("${DIRECT_FILE_PREFIX}${jobId}", ".tmp", app.cacheDir).apply {
                            outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } ?: throw IllegalStateException("Failed to read input audio file.")
                    
                    tempFileToClean = tempFile
                    fileForGemini = tempFile
                    mimeTypeForGemini = inputMimeType

                    repository.updateStatusAndProgress(jobId, "Preparing for Gemini upload...", 1f)
                }
            }

            val flow = if (usingAssembly) {
                if (sourceForAssembly == null) throw IllegalStateException("Internal error: AssemblyAI source is missing.")
                val keyChars = settings.getAssemblyApiKey() ?: throw IllegalStateException("API key missing or corrupted")
                transcribeWithAssemblyReturn(sourceForAssembly, keyChars, jobId)
            } else {
                if (geminiModel == null) throw IllegalStateException("Gemini model not specified.")
                if (fileForGemini == null || mimeTypeForGemini == null) throw IllegalStateException("Internal error: Gemini audio data missing.")
                val keyChars = settings.getGeminiApiKey() ?: throw IllegalStateException("API key missing or corrupted")
                transcribeWithGeminiReturn(fileForGemini, mimeTypeForGemini, keyChars, geminiModel, jobId)
            }

            var resultData: TranscriptionData? = null
            flow.collect { result ->
                when (result) {
                    is TranscriptionResult.Loading -> {
                        val providerMsg = "${if (usingAssembly) "AssemblyAI" else "Gemini"}: ${result.message}"
                        repository.updateStatus(jobId, providerMsg)
                        updateNotification(providerMsg)
                    }
                    is TranscriptionResult.Success -> resultData = result.data
                    is TranscriptionResult.Error -> throw result.exception
                }
            }

            val data = resultData ?: throw IllegalStateException("Transcription failed without error.")

            val historyId = historyDao.insert(
                TranscriptionHistory(
                    text = data.text,
                    language = data.language.ifBlank { "Unknown" },
                    model = data.model,
                    displayName = displayName
                )
            ).toInt()

            repository.updateResult(
                jobId,
                data.text,
                data.language.ifBlank { "Unknown" },
                data.model,
                historyId,
                1f,
                false,
                null
            )
        } finally {
            tempFileToClean?.let { runCatching { it.delete() } }
        }
    }

    private fun createRequestBodyFromUri(uri: Uri): RequestBody {
        val app = applicationContext as Application
        val contentResolver = app.contentResolver
        val contentType = contentResolver.getType(uri)?.let { mime ->
            try {
                mime.toMediaType()
            } catch (_: IllegalArgumentException) {
                "application/octet-stream".toMediaType()
            }
        } ?: "application/octet-stream".toMediaType()

        return object : RequestBody() {
            override fun contentType(): MediaType = contentType
            override fun contentLength(): Long = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) { -1L }
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
                    ?: throw IllegalStateException("Could not open input stream for Uri: $uri")
            }
        }
    }

    private fun transcribeWithAssemblyReturn(
        source: Any,
        apiKey: CharArray,
        jobId: Int
    ): kotlinx.coroutines.flow.Flow<TranscriptionResult> = kotlinx.coroutines.flow.flow {
        try {
            emit(TranscriptionResult.Loading("1/4 Uploading file..."))
            val api = RetrofitClient.api

            val requestFileBody = when (source) {
                is File -> source.asRequestBody("application/octet-stream".toMediaType())
                is Uri -> createRequestBodyFromUri(source)
                else -> throw IllegalArgumentException("Unsupported source type")
            }

            val uploadResponse = api.uploadFile(String(apiKey), requestFileBody)
            val audioUrl = uploadResponse.uploadUrl

            emit(TranscriptionResult.Loading("2/4 Creating transcript..."))
            val transcriptRequest = TranscriptRequest(audioUrl = audioUrl)
            var transcriptResponse = api.createTranscript(String(apiKey), transcriptRequest)
            val transcriptId = transcriptResponse.id

            emit(TranscriptionResult.Loading("3/4 Waiting for result..."))
            repeat(120) {
                transcriptResponse = api.getTranscript(String(apiKey), transcriptId)
                when (transcriptResponse.status) {
                    "completed" -> {
                        val detectedLanguage = transcriptResponse.languageCode ?: "Unknown"
                        val newTranscription = transcriptResponse.text ?: "No text found."
                        runCatching { api.deleteTranscript(String(apiKey), transcriptId) }
                        emit(TranscriptionResult.Loading("4/4 Success"))
                        emit(TranscriptionResult.Success(TranscriptionData(newTranscription, detectedLanguage, "AssemblyAI")))
                        return@flow
                    }
                    "error" -> {
                        runCatching { api.deleteTranscript(String(apiKey), transcriptId) }
                        throw IllegalStateException("Transcription failed. ${transcriptResponse.error.orEmpty()}")
                    }
                }
                delay(5_000L)
            }
            throw IllegalStateException("Timed out waiting for transcription.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is HttpException) emit(TranscriptionResult.Error(IllegalStateException(friendlyHttpError(e.code(), "AssemblyAI"), e)))
            else emit(TranscriptionResult.Error(IllegalStateException("Network or API error: ${e.message}", e)))
        } finally {
            apiKey.fill('\u0000')
        }
    }

    private fun transcribeWithGeminiReturn(
        audioFile: File,
        mimeType: String,
        apiKey: CharArray,
        modelName: String,
        jobId: Int
    ): kotlinx.coroutines.flow.Flow<TranscriptionResult> = kotlinx.coroutines.flow.flow {
        val client = Client.builder().apiKey(String(apiKey)).build()
        var uploadedFile: GenAiFile? = null

        try {
            emit(TranscriptionResult.Loading("Uploading audio ($mimeType) via File API..."))
            uploadedFile = withContext(Dispatchers.IO) {
                val uploadConfig = UploadFileConfig.builder().mimeType(mimeType).build()
                client.files.upload(audioFile.absolutePath, uploadConfig)
            }
            val currentUploadedFile = uploadedFile ?: throw IllegalStateException("File upload failed.")
            val audioPart = Part.fromUri(currentUploadedFile.uri().orElseThrow { IllegalStateException("Missing URI") }, currentUploadedFile.mimeType().orElse(mimeType))

            emit(TranscriptionResult.Loading("Transcribing audio..."))
            val responseSchema = Schema.builder().type(TYPE_OBJECT)
                .properties(mapOf("language_code" to Schema.builder().type(TYPE_STRING).build(), "text" to Schema.builder().type(TYPE_STRING).build()))
                .required(listOf("language_code", "text")).build()

            val config = GenerateContentConfig.builder().responseMimeType("application/json").responseSchema(responseSchema).build()
            val content = Content.fromParts(Part.fromText("Transcribe the provided audio. Return only a compact JSON object with fields: language_code (BCP-47 like en-US) and text (full transcript). Do not include any extra commentary."), audioPart)

            val response = withContext(Dispatchers.IO) { client.models.generateContent(modelName, content, config) }

            val json = response.text() ?: "{}"
            val obj = JSONObject(json)
            val transcript = obj.optString("text").ifBlank { "No text found." }
            val language = obj.optString("language_code", "und")
            val resolvedModel = response.modelVersion().filter(String::isNotBlank).orElse(modelName)

            emit(TranscriptionResult.Loading("Success"))
            emit(TranscriptionResult.Success(TranscriptionData(transcript, language, resolvedModel)))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val httpCode = if (e is HttpException) e.code() else extractHttpStatusFromMessage(e)
            val msg = if (httpCode != null) friendlyHttpError(httpCode, "Gemini") else "Gemini error: ${e.message ?: e::class.java.simpleName}"
            emit(TranscriptionResult.Error(IllegalStateException(msg, e)))
        } finally {
            apiKey.fill('\u0000')
            uploadedFile?.let { remoteFile ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        remoteFile.name().ifPresentOrElse({ fileName ->
                            client.files.delete(fileName, null)
                        }, {
                            android.util.Log.w("TranscriptionWorker", "Could not delete remote Gemini file as its name was not available.")
                        })
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    android.util.Log.w("TranscriptionWorker", "Failed to delete remote Gemini file: ${remoteFile.name().orElse("[name not available]")}", it)
                }
            }
        }
    }

    private fun friendlyHttpError(code: Int, provider: String): String = when (code) {
        401 -> "Unauthorized. Check your $provider API key."
        429 -> "Rate limit exceeded. Please wait a moment and try again."
        in 500..599 -> "Server error (HTTP $code). $provider's servers are experiencing issues."
        else -> "HTTP $code: unexpected error from $provider."
    }

    private fun extractHttpStatusFromMessage(e: Exception): Int? =
        HTTP_STATUS_REGEX.find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
}

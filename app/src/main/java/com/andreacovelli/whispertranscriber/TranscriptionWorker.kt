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
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

internal class TransientWorkException(message: String, cause: Throwable? = null) : IOException(message, cause)

private sealed interface PreparedAudioInput {
    val tempFileToClean: File?

    data class ElevenLabs(val source: Any, override val tempFileToClean: File?) : PreparedAudioInput

    data class Gemini(val file: File, val mimeType: String, override val tempFileToClean: File?) : PreparedAudioInput
}

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val elevenLabsApi: ElevenLabsApi,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"

        private const val CONVERTED_FILE_PREFIX = "converted_"
        private const val DIRECT_FILE_PREFIX = "direct_"

        private val SUPPORTED_GEMINI_MIME_TYPES = setOf(
            "audio/wav", "audio/x-wav", "audio/mp3", "audio/mpeg", "audio/aiff", "audio/x-aiff",
            "audio/aac", "audio/ogg", "audio/vorbis", "audio/flac", "audio/x-flac",
        )

        private val SUPPORTED_ELEVENLABS_MIME_TYPES = setOf(
            "audio/mp3", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/x-m4a", "audio/m4a",
            "audio/flac", "audio/x-flac", "audio/ogg", "audio/vorbis", "audio/opus", "audio/webm",
            "audio/aac", "audio/aiff", "audio/x-aiff",
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Starting transcription...")

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transcription Service",
            NotificationManager.IMPORTANCE_LOW,
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

            val requestedProvider = runCatching { TranscriptionProvider.valueOf(job.providerName) }
                .getOrDefault(TranscriptionProvider.ELEVEN_LABS)
            val usingElevenLabs = requestedProvider == TranscriptionProvider.ELEVEN_LABS
            val geminiModelName = when (requestedProvider) {
                TranscriptionProvider.ELEVEN_LABS -> null
                else -> job.requestedModel ?: requestedProvider.modelName
            }

            val stagedUri = job.stagedFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it).takeIf(File::exists)?.toUri() }
                ?: job.uri.toUri()

            processSingleJob(
                jobId = jobId,
                uri = stagedUri,
                displayName = job.displayName,
                inputMimeType = job.sourceMimeType?.lowercase(),
                usingElevenLabs = usingElevenLabs,
                geminiModel = geminiModelName,
            )
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientWorkException) {
            repository.updateError(jobId, e.message ?: e::class.java.simpleName, false, "Error")
            return Result.retry()
        } catch (e: IOException) {
            repository.updateError(jobId, e.message ?: e::class.java.simpleName, false, "Error")
            return Result.failure()
        } catch (e: IllegalStateException) {
            repository.updateError(jobId, e.message ?: e::class.java.simpleName, false, "Error")
            return Result.failure()
        } catch (e: IllegalArgumentException) {
            repository.updateError(jobId, e.message ?: e::class.java.simpleName, false, "Error")
            return Result.failure()
        }
    }

    private suspend fun processSingleJob(
        jobId: Int,
        uri: Uri,
        displayName: String,
        inputMimeType: String?,
        usingElevenLabs: Boolean,
        geminiModel: String?,
    ) {
        val preparedInput = prepareAudioInput(jobId, uri, inputMimeType, usingElevenLabs)
        try {
            val result = runProviderTranscription(jobId, preparedInput, usingElevenLabs, geminiModel)
            persistCompletedTranscription(jobId, displayName, result)
        } finally {
            preparedInput.tempFileToClean?.let { file ->
                runCatching { file.delete() }
                    .onFailure { throwable ->
                        android.util.Log.w(
                            "TranscriptionWorker",
                            "Failed to delete temp file: ${file.absolutePath}",
                            throwable,
                        )
                    }
            }
        }
    }

    private suspend fun prepareAudioInput(
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingElevenLabs: Boolean,
    ): PreparedAudioInput {
        val app = applicationContext as Application
        val conversionNeeded = inputMimeType == null ||
            (usingElevenLabs && inputMimeType !in SUPPORTED_ELEVENLABS_MIME_TYPES) ||
            (!usingElevenLabs && inputMimeType !in SUPPORTED_GEMINI_MIME_TYPES)
        return if (conversionNeeded) {
            prepareConvertedAudio(app, jobId, uri, inputMimeType, usingElevenLabs)
        } else {
            prepareNativeAudio(app, jobId, uri, requireNotNull(inputMimeType), usingElevenLabs)
        }
    }

    private suspend fun prepareConvertedAudio(
        app: Application,
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingElevenLabs: Boolean,
    ): PreparedAudioInput {
        val reasonSuffix = inputMimeType?.let { "($it)" } ?: "(Unknown format)"
        val providerName = if (usingElevenLabs) "ElevenLabs" else "Gemini"
        repository.updateStatusAndProgress(
            jobId,
            "Format not natively supported by $providerName $reasonSuffix. Converting to 16 kHz WAV...",
            0f,
        )
        updateNotification("Converting audio...")
        val outputFile = withContext(Dispatchers.IO) {
            File.createTempFile("${CONVERTED_FILE_PREFIX}$jobId", ".wav", app.cacheDir)
        }
        HardwareAudioConverter.convertToWav(
            context = app,
            inputUri = uri,
            outputFile = outputFile,
            forceMono = true,
            targetSampleRate = 16_000,
            onProgress = { progress ->
                repository.updateStatusAndProgress(
                    jobId,
                    "Converting to 16 kHz WAV: ${"%.1f".format(progress * 100)}%",
                    progress,
                )
            },
        )
        return if (usingElevenLabs) {
            PreparedAudioInput.ElevenLabs(source = outputFile, tempFileToClean = outputFile)
        } else {
            repository.updateStatus(jobId, "Converted audio ready for Gemini...")
            PreparedAudioInput.Gemini(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
        }
    }

    private suspend fun prepareNativeAudio(
        app: Application,
        jobId: Int,
        uri: Uri,
        inputMimeType: String,
        usingElevenLabs: Boolean,
    ): PreparedAudioInput = if (usingElevenLabs) {
        repository.updateStatusAndProgress(
            jobId,
            "Format supported ($inputMimeType). Preparing for direct upload...",
            1f,
        )
        PreparedAudioInput.ElevenLabs(source = uri, tempFileToClean = null)
    } else {
        repository.updateStatusAndProgress(
            jobId,
            "Format supported ($inputMimeType). Copying audio for Gemini...",
            0.5f,
        )
        updateNotification("Copying audio...")
        val tempFile = copyInputToTempFile(app, jobId, uri)
        repository.updateStatusAndProgress(jobId, "Preparing for Gemini upload...", 1f)
        PreparedAudioInput.Gemini(file = tempFile, mimeType = inputMimeType, tempFileToClean = tempFile)
    }

    private suspend fun copyInputToTempFile(app: Application, jobId: Int, uri: Uri): File {
        val contentResolver = app.contentResolver
        return contentResolver.openInputStream(uri)?.use { input ->
            File.createTempFile("${DIRECT_FILE_PREFIX}$jobId", ".tmp", app.cacheDir).apply {
                outputStream().use(input::copyTo)
            }
        } ?: error("Failed to read input audio file.")
    }

    private suspend fun runProviderTranscription(
        jobId: Int,
        preparedInput: PreparedAudioInput,
        usingElevenLabs: Boolean,
        geminiModel: String?,
    ): TranscriptionData {
        val transcriber = buildTranscriber(preparedInput, usingElevenLabs, geminiModel)
        var resultData: TranscriptionData? = null
        transcriber.transcribe().collect { result ->
            when (result) {
                is TranscriptionResult.Loading -> {
                    val providerPrefix = if (usingElevenLabs) "ElevenLabs" else "Gemini"
                    val providerMessage = "$providerPrefix: ${result.message}"
                    repository.updateStatus(jobId, providerMessage)
                    updateNotification(providerMessage)
                }
                is TranscriptionResult.Success -> resultData = result.data
                is TranscriptionResult.Error -> throw result.exception
            }
        }
        return checkNotNull(resultData) { "Transcription failed without error." }
    }

    private suspend fun buildTranscriber(
        preparedInput: PreparedAudioInput,
        usingElevenLabs: Boolean,
        geminiModel: String?,
    ): ProviderTranscriber = if (usingElevenLabs) {
        val source = checkNotNull((preparedInput as? PreparedAudioInput.ElevenLabs)?.source) {
            "Internal error: ElevenLabs source is missing."
        }
        val keyChars = requireNotNull(settings.getElevenLabsApiKey()) { "API key missing or corrupted" }
        ElevenLabsTranscriber(elevenLabsApi, source, keyChars, ::createRequestBodyFromUri)
    } else {
        val geminiInput = checkNotNull(preparedInput as? PreparedAudioInput.Gemini) {
            "Internal error: Gemini audio data missing."
        }
        val modelName = requireNotNull(geminiModel) { "Gemini model not specified." }
        val keyChars = requireNotNull(settings.getGeminiApiKey()) { "API key missing or corrupted" }
        GeminiTranscriber(geminiInput.file, geminiInput.mimeType, keyChars, modelName)
    }

    private suspend fun persistCompletedTranscription(jobId: Int, displayName: String, data: TranscriptionData) {
        val historyId = repository.insertHistory(
            TranscriptionHistory(
                text = data.text,
                language = data.language.ifBlank { "Unknown" },
                model = data.model,
                displayName = displayName,
            ),
        )
        repository.updateResult(
            jobId,
            data.text,
            data.language.ifBlank { "Unknown" },
            data.model,
            historyId,
            1f,
            false,
            "Completed",
            null,
        )
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
            } catch (_: Exception) {
                -1L
            }
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
                    ?: error("Could not open input stream for Uri: $uri")
            }
        }
    }
}

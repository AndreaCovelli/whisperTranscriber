package com.andreacovelli.whispertranscriber

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.media.HardwareAudioConverter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class TransientWorkException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal sealed interface QueuedAudioInputResolution {
    data class Ready(val uri: Uri) : QueuedAudioInputResolution

    data class MissingStagedFile(val message: String) : QueuedAudioInputResolution
}

internal object QueuedAudioInputResolver {
    fun resolve(job: TranscriptionJob): QueuedAudioInputResolution {
        val stagedPath = job.stagedFilePath?.takeIf { it.isNotBlank() }
        if (stagedPath == null) {
            return QueuedAudioInputResolution.Ready(job.uri.toUri())
        }

        val stagedFile = File(stagedPath)
        return if (stagedFile.exists()) {
            QueuedAudioInputResolution.Ready(stagedFile.toUri())
        } else {
            QueuedAudioInputResolution.MissingStagedFile(
                "Queued audio is no longer available. Please share it again.",
            )
        }
    }
}

private sealed interface PreparedAudioInput {
    val tempFileToClean: File?

    data class MicrosoftSpeech(val file: File, val mimeType: String, override val tempFileToClean: File?) :
        PreparedAudioInput

    data class Gemini(val file: File, val mimeType: String, override val tempFileToClean: File?) : PreparedAudioInput
}

internal val SUPPORTED_MICROSOFT_SPEECH_MIME_TYPES = setOf(
    "audio/mp3",
    "audio/mpeg",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/flac",
    "audio/x-flac",
)

@Suppress("unused")
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val summarizationService: SummarizationService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "transcription_channel"
        const val SPEAKER_LABEL_PREFIX_INPUT = "speakerLabelPrefix"

        private const val CONVERTED_FILE_PREFIX = "converted_"
        private const val DIRECT_FILE_PREFIX = "direct_"

        private val SUPPORTED_GEMINI_MIME_TYPES = setOf(
            "audio/wav", "audio/x-wav", "audio/mp3", "audio/mpeg", "audio/aiff", "audio/x-aiff",
            "audio/aac", "audio/ogg", "audio/vorbis", "audio/flac", "audio/x-flac",
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
            .setSmallIcon(R.drawable.ic_notification)
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
        val postTranscriptionWorkSpec = PostTranscriptionWorkSpec.fromInputData(inputData)

        try {
            setForeground(getForegroundInfo())
            repository.updateRunning(jobId, true, "Starting...")
            updateNotification("Processing: ${job.displayName}")

            val requestedProvider = decodeStoredTranscriptionProvider(job.providerName)
            val usingMicrosoftSpeech = requestedProvider == TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE
            val geminiModelName = when (requestedProvider) {
                TranscriptionProvider.ELEVEN_LABS, TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE -> null
                else -> job.requestedModel ?: requestedProvider.modelName
            }

            val stagedUri = when (val resolution = QueuedAudioInputResolver.resolve(job)) {
                is QueuedAudioInputResolution.Ready -> resolution.uri
                is QueuedAudioInputResolution.MissingStagedFile -> {
                    repository.updateError(jobId, resolution.message, false, "Error")
                    notifyPostTranscriptionFailure(
                        postTranscriptionWorkSpec,
                        jobId,
                        job.displayName,
                        resolution.message,
                    )
                    return Result.failure()
                }
            }

            processSingleJob(
                jobId = jobId,
                uri = stagedUri,
                displayName = job.displayName,
                inputMimeType = job.sourceMimeType?.lowercase(),
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                geminiModel = geminiModelName,
                speakerDiarizationEnabled = job.speakerDiarizationEnabled,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
            )
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientWorkException) {
            repository.updateError(jobId, e.message ?: e::class.java.simpleName, false, "Error")
            return Result.retry()
        } catch (e: IOException) {
            val message = e.message ?: e::class.java.simpleName
            repository.updateError(jobId, message, false, "Error")
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, job.displayName, message)
            return Result.failure()
        } catch (e: IllegalStateException) {
            val message = e.message ?: e::class.java.simpleName
            repository.updateError(jobId, message, false, "Error")
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, job.displayName, message)
            return Result.failure()
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: e::class.java.simpleName
            repository.updateError(jobId, message, false, "Error")
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, job.displayName, message)
            return Result.failure()
        } catch (e: SQLException) {
            val message = e.message ?: e::class.java.simpleName
            repository.updateError(jobId, message, false, "Error")
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, job.displayName, message)
            return Result.failure()
        }
    }

    private suspend fun processSingleJob(
        jobId: Int,
        uri: Uri,
        displayName: String,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
    ) {
        val preparedInput = prepareAudioInput(jobId, uri, inputMimeType, usingMicrosoftSpeech)
        try {
            val result = runProviderTranscription(
                jobId = jobId,
                preparedInput = preparedInput,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                geminiModel = geminiModel,
                speakerDiarizationEnabled = speakerDiarizationEnabled,
            )
            persistCompletedTranscription(jobId, displayName, result)
            if (postTranscriptionWorkSpec?.summarizeAfterTranscription == true) {
                val summarizationResult = summarizationService.summarize(
                    jobId = jobId,
                    settings = postTranscriptionWorkSpec.summarizationSettings,
                    allowResummarize = false,
                    fallbackStatusMessage = "Completed",
                )
                if (postTranscriptionWorkSpec.notifyResult) {
                    if (summarizationResult.errorMessage == null) {
                        val completedJob = repository.getJob(jobId)
                        backgroundShareNotifier.showSummary(
                            jobId = jobId,
                            displayName = displayName,
                            summary = completedJob?.summary.orEmpty().ifBlank {
                                summarizationResult.statusMessage
                            },
                        )
                    } else {
                        backgroundShareNotifier.showFailure(
                            jobId = jobId,
                            displayName = displayName,
                            message = summarizationResult.errorMessage,
                        )
                    }
                }
            } else if (postTranscriptionWorkSpec?.notifyResult == true) {
                backgroundShareNotifier.showTranscription(
                    jobId = jobId,
                    displayName = displayName,
                    transcript = result.text,
                )
            }
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
        usingMicrosoftSpeech: Boolean,
    ): PreparedAudioInput {
        val app = applicationContext as Application
        val conversionNeeded = inputMimeType == null ||
            (usingMicrosoftSpeech && inputMimeType !in SUPPORTED_MICROSOFT_SPEECH_MIME_TYPES) ||
            (!usingMicrosoftSpeech && inputMimeType !in SUPPORTED_GEMINI_MIME_TYPES)
        return if (conversionNeeded) {
            prepareConvertedAudio(app, jobId, uri, inputMimeType, usingMicrosoftSpeech)
        } else {
            prepareNativeAudio(app, jobId, uri, inputMimeType, usingMicrosoftSpeech)
        }
    }

    private suspend fun prepareConvertedAudio(
        app: Application,
        jobId: Int,
        uri: Uri,
        inputMimeType: String?,
        usingMicrosoftSpeech: Boolean,
    ): PreparedAudioInput {
        val reasonSuffix = inputMimeType?.let { "($it)" } ?: "(Unknown format)"
        val providerName = if (usingMicrosoftSpeech) "Microsoft Speech" else "Gemini"
        repository.updateStatusAndProgress(
            jobId,
            "Format not natively supported by $providerName $reasonSuffix. Converting to 16 kHz WAV...",
            0f,
        )
        updateNotification("Converting audio...")
        val outputFile = withContext(ioDispatcher) {
            File.createTempFile("${CONVERTED_FILE_PREFIX}$jobId", ".wav", app.cacheDir)
        }
        HardwareAudioConverter.convertTo16kMonoWav(
            context = app,
            inputUri = uri,
            outputFile = outputFile,
            onProgress = { progress ->
                repository.updateStatusAndProgress(
                    jobId,
                    "Converting to 16 kHz WAV: ${"%.1f".format(progress * 100)}%",
                    progress,
                )
            },
        )
        return if (usingMicrosoftSpeech) {
            repository.updateStatus(jobId, "Converted audio ready for Microsoft Speech...")
            PreparedAudioInput.MicrosoftSpeech(file = outputFile, mimeType = "audio/wav", tempFileToClean = outputFile)
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
        usingMicrosoftSpeech: Boolean,
    ): PreparedAudioInput = if (usingMicrosoftSpeech) {
        val reusableLocalFile = reusableLocalFileForUri(uri)
        if (reusableLocalFile != null) {
            repository.updateStatusAndProgress(jobId, "Preparing local audio for Microsoft Speech upload...", 1f)
            PreparedAudioInput.MicrosoftSpeech(
                file = reusableLocalFile,
                mimeType = inputMimeType,
                tempFileToClean = null,
            )
        } else {
            repository.updateStatusAndProgress(
                jobId,
                "Format supported ($inputMimeType). Copying audio for Microsoft Speech...",
                0.5f,
            )
            updateNotification("Copying audio...")
            val tempFile = copyInputToTempFile(app, jobId, uri)
            repository.updateStatusAndProgress(jobId, "Preparing for Microsoft Speech upload...", 1f)
            PreparedAudioInput.MicrosoftSpeech(file = tempFile, mimeType = inputMimeType, tempFileToClean = tempFile)
        }
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

    private fun copyInputToTempFile(app: Application, jobId: Int, uri: Uri): File {
        val contentResolver = app.contentResolver
        return contentResolver.openInputStream(uri)?.use { input ->
            File.createTempFile("${DIRECT_FILE_PREFIX}$jobId", ".tmp", app.cacheDir).apply {
                outputStream().use(input::copyTo)
            }
        } ?: error("Failed to read input audio file.")
    }

    private fun reusableLocalFileForUri(uri: Uri): File? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return null

        val path = uri.path?.takeIf(String::isNotBlank) ?: return null
        val candidate = File(path)
        return candidate.takeIf { it.isAbsolute && it.exists() && it.canRead() }
    }

    private suspend fun runProviderTranscription(
        jobId: Int,
        preparedInput: PreparedAudioInput,
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
    ): TranscriptionData {
        val transcriber = buildTranscriber(preparedInput, usingMicrosoftSpeech, geminiModel, speakerDiarizationEnabled)
        var resultData: TranscriptionData? = null
        transcriber.transcribe().collect { result ->
            when (result) {
                is TranscriptionResult.Loading -> {
                    val providerPrefix = if (usingMicrosoftSpeech) "Microsoft Speech" else "Gemini"
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
        usingMicrosoftSpeech: Boolean,
        geminiModel: String?,
        speakerDiarizationEnabled: Boolean,
    ): ProviderTranscriber = if (usingMicrosoftSpeech) {
        check(!speakerDiarizationEnabled) { "Speaker diarization is not supported by Microsoft MAI Transcribe." }
        val microsoftSpeechInput = checkNotNull(preparedInput as? PreparedAudioInput.MicrosoftSpeech) {
            "Internal error: Microsoft Speech audio data missing."
        }
        val keyChars = requireNotNull(settings.getMicrosoftSpeechApiKey()) {
            "Azure Speech key for Microsoft MAI Transcribe missing or corrupted."
        }
        val region = settings.microsoftSpeechRegionFlow.first()
            .takeIf(String::isNotBlank)
            ?: error("Azure Speech region for Microsoft MAI Transcribe is not set.")
        MicrosoftSpeechTranscriber(
            apiKey = keyChars,
            audioFile = microsoftSpeechInput.file,
            mimeType = microsoftSpeechInput.mimeType,
            region = region,
            okHttpClient = okHttpClient,
            ioDispatcher = ioDispatcher,
        )
    } else {
        val geminiInput = checkNotNull(preparedInput as? PreparedAudioInput.Gemini) {
            "Internal error: Gemini audio data missing."
        }
        val modelName = requireNotNull(geminiModel) { "Gemini model not specified." }
        val keyChars = requireNotNull(settings.getGeminiApiKey()) { "API key missing or corrupted" }
        GeminiTranscriber(
            audioFile = geminiInput.file,
            mimeType = geminiInput.mimeType,
            apiKey = keyChars,
            modelName = modelName,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            ioDispatcher = ioDispatcher,
        )
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

    private fun notifyPostTranscriptionFailure(
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        jobId: Int,
        displayName: String,
        message: String,
    ) {
        if (postTranscriptionWorkSpec?.notifyResult != true) return
        backgroundShareNotifier.showFailure(
            jobId = jobId,
            displayName = displayName,
            message = message,
        )
    }
}

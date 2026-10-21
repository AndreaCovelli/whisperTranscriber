package com.andreacovelli.whispertranscriber.transcription

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.andreacovelli.whispertranscriber.QuantityText
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.background.BackgroundShareNotifier
import com.andreacovelli.whispertranscriber.background.BackgroundShareResultKind
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.background.StoredResultPublisher
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.QueueExecutionCoordinator
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.queue.postTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.services.SummarizationService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal sealed interface JobRunOutcome {
    data object Success : JobRunOutcome
    data object Skipped : JobRunOutcome
    data object Retry : JobRunOutcome
    data object Failure : JobRunOutcome
    data object Superseded : JobRunOutcome
}

private class SupersededExecutionException : Exception()

internal sealed interface QueuedAudioInputResolution {
    data class Ready(val uri: Uri) : QueuedAudioInputResolution

    data object MissingStagedFile : QueuedAudioInputResolution
}

// Owns a claimed job's lifecycle: preparation and provider calls are delegated, persistence stays here.
@Singleton
class TranscriptionExecutionRunner @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val repository: TranscriptionRepository,
    private val executionCoordinator: QueueExecutionCoordinator,
    private val summarizationService: SummarizationService,
    private val backgroundShareNotifier: BackgroundShareNotifier,
    private val storedResultPublisher: StoredResultPublisher,
    private val queuedAudioStore: QueuedAudioStore,
    private val audioPreparer: TranscriptionAudioPreparer,
    private val transcriberFactory: ProviderTranscriberFactory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "TranscriptionExecutionRunner"
        private const val MAX_AUTOMATIC_RETRY_ATTEMPTS = 3
        private const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }

    internal suspend fun runJob(
        jobId: Int,
        executionOwnerId: String,
        speakerLabelPrefix: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ): JobRunOutcome = executionCoordinator.runSerialized(jobId) {
        val ownerId = executionCoordinator.ownerId(executionOwnerId)
        try {
            runAttempt(jobId, ownerId, speakerLabelPrefix, onProgressNotification)
        } catch (e: CancellationException) {
            // Claim acquisition may commit before Room resumes this coroutine.
            withContext(NonCancellable + ioDispatcher) {
                repository.resetRunningToQueued(jobId, ownerId)
            }
            throw e
        }
    }

    // The final catch-all is deliberate; see the comment there.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runAttempt(
        jobId: Int,
        executionOwnerId: String,
        speakerLabelPrefix: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ): JobRunOutcome {
        backgroundShareNotifier.cancelRetryScheduled(jobId)
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        val job =
            repository.claimJobForExecution(
                jobId,
                executionOwnerId,
                UserMessage(R.string.transcription_status_starting),
            )
                ?: return when (val current = repository.getJob(jobId)) {
                    null -> JobRunOutcome.Failure
                    else -> when {
                        current.executionOwnerId.isNotEmpty() && current.executionOwnerId != executionOwnerId ->
                            JobRunOutcome.Superseded
                        current.status == JOB_STATUS_CANCELLED -> JobRunOutcome.Success
                        else -> JobRunOutcome.Skipped
                    }
                }
        val displayName = job.displayName
        val postTranscriptionWorkSpec = job.postTranscriptionWorkSpec()

        return try {
            executeClaimedJob(
                job = job,
                executionOwnerId = executionOwnerId,
                speakerLabelPrefix = speakerLabelPrefix,
                postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                onProgressNotification = onProgressNotification,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                repository.resetRunningToQueued(jobId, executionOwnerId)
            }
            throw e
        } catch (_: SupersededExecutionException) {
            JobRunOutcome.Superseded
        } catch (e: TransientWorkException) {
            if (job.retryCount >= MAX_AUTOMATIC_RETRY_ATTEMPTS) {
                val exhaustedMessage = UserMessage(
                    R.string.transcription_retries_exhausted,
                    listOf(QuantityText(R.plurals.transcription_attempt_count, job.retryCount + 1), e.userMessage),
                )
                handleTerminalFailure(jobId, executionOwnerId, displayName, postTranscriptionWorkSpec, exhaustedMessage)
                JobRunOutcome.Failure
            } else {
                val retryMessage = UserMessage(R.string.transcription_retry_notification_body, listOf(e.userMessage))
                withContext(NonCancellable + ioDispatcher) {
                    requireOwnedUpdate(repository.markRetryScheduled(jobId, executionOwnerId))
                }
                backgroundShareNotifier.cancelBackgroundProcessing(jobId)
                backgroundShareNotifier.showRetryScheduled(jobId, displayName, retryMessage)
                JobRunOutcome.Retry
            }
        } catch (e: Exception) {
            // Every other failure is terminal. The claim must be released here: stale claims are only
            // recovered for previous processes, so a held claim would stall the queue until process death.
            Log.w(TAG, "Transcription failed with ${e::class.java.name}")
            handleTerminalFailure(
                jobId,
                executionOwnerId,
                displayName,
                postTranscriptionWorkSpec,
                terminalFailureMessage(e),
            )
            JobRunOutcome.Failure
        }
    }

    /** Shows app-written messages as is; other exception text may come from a provider or the platform. */
    private fun terminalFailureMessage(error: Exception): UserMessage =
        (error as? UserFacingException)?.userMessage ?: when (error) {
            is IOException -> UserMessage(R.string.transcription_audio_processing_failed)
            is SecurityException -> UserMessage(R.string.transcription_audio_access_denied)
            is SQLException -> UserMessage(R.string.transcription_storage_failed)
            else -> UserMessage(R.string.transcription_failed_unexpected)
        }

    private suspend fun executeClaimedJob(
        job: TranscriptionJob,
        executionOwnerId: String,
        speakerLabelPrefix: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ): JobRunOutcome {
        onProgressNotification(
            job.id,
            job.displayName,
            UserMessage(R.string.transcription_notification_processing, listOf(job.displayName)),
        )

        val durableTranscript = if (job.historyId != null) {
            repository.getTranscriptForJob(job.id)
        } else {
            job.transcript
        }
        if (job.historyId != null || durableTranscript.isNotBlank()) {
            queuedAudioStore.deleteStagedFile(job.stagedFilePath)
            requireOwnedUpdate(repository.clearStagedFilePath(job.id, executionOwnerId))
            resumePostTranscriptionWork(
                jobId = job.id,
                executionOwnerId = executionOwnerId,
                displayName = job.displayName,
                onProgressNotification = onProgressNotification,
            )
            backgroundShareNotifier.cancelBackgroundProcessing(job.id)
            return JobRunOutcome.Success
        }

        val providerExecution = transcriberFactory.resolveProviderExecution(job.providerName, job.requestedModel)
        if (providerExecution is ProviderExecution.MicrosoftSpeech) {
            transcriberFactory.validatePersistedMicrosoftSpeechEndpoint()
        }
        val stagedUri = when (val resolution = QueuedAudioInputResolver.resolve(job)) {
            is QueuedAudioInputResolution.Ready -> resolution.uri
            QueuedAudioInputResolution.MissingStagedFile -> {
                val message = UserMessage(R.string.queued_audio_unavailable)
                requireOwnedUpdate(
                    repository.updateError(
                        job.id,
                        executionOwnerId,
                        message,
                        JOB_STATUS_ERROR,
                    ),
                )
                notifyPostTranscriptionFailure(
                    postTranscriptionWorkSpec = postTranscriptionWorkSpec,
                    jobId = job.id,
                    displayName = job.displayName,
                    message = message,
                )
                return JobRunOutcome.Failure
            }
        }

        processSingleJob(
            jobId = job.id,
            executionOwnerId = executionOwnerId,
            uri = stagedUri,
            displayName = job.displayName,
            inputMimeType = job.sourceMimeType?.lowercase(),
            providerExecution = providerExecution,
            speakerDiarizationEnabled = job.speakerDiarizationEnabled,
            speakerLabelPrefix = speakerLabelPrefix,
            hints = job.transcriptionHints(),
            stagedFilePath = job.stagedFilePath,
            onProgressNotification = onProgressNotification,
        )
        backgroundShareNotifier.cancelBackgroundProcessing(job.id)
        return JobRunOutcome.Success
    }

    private suspend fun handleTerminalFailure(
        jobId: Int,
        executionOwnerId: String,
        displayName: String,
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        message: UserMessage,
    ) {
        backgroundShareNotifier.cancelBackgroundProcessing(jobId)
        if (repository.updateError(jobId, executionOwnerId, message, JOB_STATUS_ERROR)) {
            notifyPostTranscriptionFailure(postTranscriptionWorkSpec, jobId, displayName, message)
        }
    }

    private suspend fun processSingleJob(
        jobId: Int,
        executionOwnerId: String,
        uri: Uri,
        displayName: String,
        inputMimeType: String?,
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
        hints: TranscriptionHints,
        stagedFilePath: String?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ) {
        val usingMicrosoftSpeech = providerExecution is ProviderExecution.MicrosoftSpeech
        var preparedInput: PreparedAudioInput? = null
        try {
            preparedInput = audioPreparer.prepare(
                jobId = jobId,
                uri = uri,
                inputMimeType = inputMimeType,
                usingMicrosoftSpeech = usingMicrosoftSpeech,
                listener = object : AudioPreparationListener {
                    override suspend fun onStatus(status: UserMessage, progress: Float?) {
                        requireOwnedUpdate(
                            if (progress == null) {
                                repository.updateStatus(jobId, executionOwnerId, status)
                            } else {
                                repository.updateStatusAndProgress(jobId, executionOwnerId, status, progress)
                            },
                        )
                    }

                    override suspend fun onNotification(text: UserMessage) {
                        onProgressNotification(jobId, displayName, text)
                    }
                },
            )
            val transcribeInput: suspend (PreparedAudioInput, ChunkPosition?) -> TranscriptionData = { input, part ->
                runProviderTranscription(
                    jobId = jobId,
                    executionOwnerId = executionOwnerId,
                    displayName = displayName,
                    preparedInput = input,
                    providerExecution = providerExecution,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    speakerLabelPrefix = speakerLabelPrefix,
                    hints = hints,
                    part = part,
                    onProgressNotification = onProgressNotification,
                )
            }
            val result = when (val input = preparedInput) {
                is PreparedAudioInput.Chunked -> transcribeParts(
                    jobId = jobId,
                    input = input,
                    usingMicrosoftSpeech = usingMicrosoftSpeech,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    transcribeInput = transcribeInput,
                )
                else -> transcribeInput(input, null)
            }
            val transcript = result.copy(
                text = result.text.ifBlank { applicationContext.getString(R.string.transcription_no_text_found) },
            )
            val recording = RecordingDetails(
                durationMs = preparedInput.durationUs.coerceAtLeast(0) / MICROSECONDS_PER_MILLISECOND,
                sourceSizeBytes = stagedFilePath?.let { path ->
                    withContext(ioDispatcher) { File(path).length() }
                } ?: 0,
            )
            requireOwnedUpdate(
                repository.persistCompletedTranscription(jobId, executionOwnerId, displayName, transcript, recording),
            )
            queuedAudioStore.deleteStagedFile(stagedFilePath)
            requireOwnedUpdate(repository.clearStagedFilePath(jobId, executionOwnerId))
            resumePostTranscriptionWork(
                jobId = jobId,
                executionOwnerId = executionOwnerId,
                displayName = displayName,
                onProgressNotification = onProgressNotification,
            )
        } finally {
            preparedInput?.tempFileToClean?.let(audioPreparer::deleteTemporaryFile)
        }
    }

    // Parts run one at a time so only one extracted part file exists beside the converted recording.
    private suspend fun transcribeParts(
        jobId: Int,
        input: PreparedAudioInput.Chunked,
        usingMicrosoftSpeech: Boolean,
        speakerDiarizationEnabled: Boolean,
        transcribeInput: suspend (PreparedAudioInput, ChunkPosition?) -> TranscriptionData,
    ): TranscriptionData {
        val partCount = input.chunks.size
        val parts = input.chunks.indices.map { index ->
            val partInput = audioPreparer.preparePart(jobId, input, index, usingMicrosoftSpeech)
            try {
                transcribeInput(partInput, ChunkPosition(index + 1, partCount))
            } finally {
                partInput.tempFileToClean?.let(audioPreparer::deleteTemporaryFile)
            }
        }
        return combinePartTranscriptions(
            parts = parts,
            partMarker = if (speakerDiarizationEnabled) {
                { number -> applicationContext.getString(R.string.transcription_part_marker, number, partCount) }
            } else {
                null
            },
        )
    }

    private suspend fun runProviderTranscription(
        jobId: Int,
        executionOwnerId: String,
        displayName: String,
        preparedInput: PreparedAudioInput,
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
        hints: TranscriptionHints,
        part: ChunkPosition?,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ): TranscriptionData {
        val transcriber = transcriberFactory.buildTranscriber(
            preparedInput = preparedInput,
            providerExecution = providerExecution,
            speakerDiarizationEnabled = speakerDiarizationEnabled,
            speakerLabelPrefix = speakerLabelPrefix,
            hints = hints,
        )
        var resultData: TranscriptionData? = null
        suspend fun reportProviderStatus(message: UserMessage) {
            val partMessage = part?.let {
                UserMessage(R.string.transcription_status_part_message, listOf(it.number, it.count, message))
            } ?: message
            val providerMessage = UserMessage(
                R.string.transcription_status_provider_message,
                listOf(providerExecution.displayName, partMessage),
            )
            requireOwnedUpdate(repository.updateStatus(jobId, executionOwnerId, providerMessage))
            onProgressNotification(jobId, displayName, providerMessage)
        }
        transcriber.transcribe().collect { result ->
            when (result) {
                is TranscriptionResult.Loading -> reportProviderStatus(result.message)
                is TranscriptionResult.Success -> resultData = result.data
                is TranscriptionResult.Error -> throw result.exception
            }
        }
        return checkNotNull(resultData) { "Transcription failed without error." }
    }

    private suspend fun resumePostTranscriptionWork(
        jobId: Int,
        executionOwnerId: String,
        displayName: String,
        onProgressNotification: suspend (jobId: Int, displayName: String, progressText: UserMessage) -> Unit,
    ) {
        // Re-read the stored config: changing the auto-summary settings rewrites it for unfinished jobs.
        val postTranscriptionWorkSpec = repository.getJob(jobId)?.postTranscriptionWorkSpec()
        if (postTranscriptionWorkSpec?.summarizeAfterTranscription == true) {
            val summarizationResult = summarizationService.summarize(
                jobId = jobId,
                settings = postTranscriptionWorkSpec.summarizationSettings,
                executionOwnerId = executionOwnerId,
                onStatusUpdate = { statusMessage ->
                    if (postTranscriptionWorkSpec.notifyResult) {
                        onProgressNotification(jobId, displayName, statusMessage)
                    }
                },
            )
            requireOwnedUpdate(repository.markRunningAsCompleted(jobId, executionOwnerId))
            if (postTranscriptionWorkSpec.notifyResult) {
                if (summarizationResult.errorMessage == null) {
                    storedResultPublisher.publish(jobId, BackgroundShareResultKind.SUMMARY)
                } else {
                    backgroundShareNotifier.showFailure(
                        jobId = jobId,
                        displayName = displayName,
                        message = summarizationResult.errorMessage,
                    )
                }
            }
        } else {
            requireOwnedUpdate(repository.markRunningAsCompleted(jobId, executionOwnerId))
            if (postTranscriptionWorkSpec?.notifyResult == true) {
                storedResultPublisher.publish(jobId, BackgroundShareResultKind.TRANSCRIPTION)
            }
        }
    }

    private fun notifyPostTranscriptionFailure(
        postTranscriptionWorkSpec: PostTranscriptionWorkSpec?,
        jobId: Int,
        displayName: String,
        message: UserMessage,
    ) {
        if (postTranscriptionWorkSpec?.notifyResult != true) return
        backgroundShareNotifier.showFailure(
            jobId = jobId,
            displayName = displayName,
            message = message,
        )
    }

    private fun requireOwnedUpdate(updated: Boolean) {
        if (!updated) throw SupersededExecutionException()
    }
}

internal data class ChunkPosition(val number: Int, val count: Int)

/**
 * Joins the transcripts of consecutive parts of one recording. Speaker labels restart in every part, so
 * when [partMarker] is given each part is introduced by its marker line to show where labels may change.
 */
internal fun combinePartTranscriptions(
    parts: List<TranscriptionData>,
    partMarker: ((Int) -> String)?,
): TranscriptionData {
    require(parts.isNotEmpty())
    val text = if (partMarker == null) {
        parts.map { it.text.trim() }.filter(String::isNotEmpty).joinToString(separator = "\n")
    } else {
        parts.mapIndexed { index, part -> "${partMarker(index + 1)}\n${part.text.trim()}".trim() }
            .joinToString(separator = "\n\n")
    }
    val language = parts.map { it.language }
        .filter { it.isNotBlank() && it != "und" }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?: parts.first().language
    return TranscriptionData(
        text = text,
        language = language,
        model = parts.first().model,
        speakerLabels = parts.any(TranscriptionData::speakerLabels),
    )
}

internal object QueuedAudioInputResolver {
    fun resolve(job: TranscriptionJob): QueuedAudioInputResolution {
        val stagedPath = job.stagedFilePath?.takeIf { it.isNotBlank() }
            ?: return QueuedAudioInputResolution.MissingStagedFile

        val stagedFile = File(stagedPath)
        return if (stagedFile.isFile && stagedFile.canRead() && stagedFile.length() > 0) {
            QueuedAudioInputResolution.Ready(stagedFile.toUri())
        } else {
            QueuedAudioInputResolution.MissingStagedFile
        }
    }
}

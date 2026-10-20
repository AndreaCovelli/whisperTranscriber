package com.andreacovelli.whispertranscriber.services

import android.content.Context
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.queue.JobOperationRegistry
import com.andreacovelli.whispertranscriber.queue.QueueExecutionCoordinator
import com.andreacovelli.whispertranscriber.security.GenAiClientFactory
import com.andreacovelli.whispertranscriber.security.useGenAiClient
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.TranscriptionSummarizationRepository
import com.andreacovelli.whispertranscriber.transcription.SUMMARY_STATUS_SUMMARIZING
import com.andreacovelli.whispertranscriber.transcription.TranscriptionRepository
import com.andreacovelli.whispertranscriber.transcription.validatedGeminiText
import com.andreacovelli.whispertranscriber.userMessageOr
import com.google.genai.errors.ApiException
import com.google.genai.errors.GenAiIOException
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class SummarizationResult(val statusMessage: String, val errorMessage: String? = null)

data class SummaryData(val text: String, val model: String)

private data class SummarizerSelection(val provider: TranscriptionProvider, val modelName: String)

@Singleton
class SummarizationService @Inject constructor(
    private val settings: ApiKeyManager,
    private val repository: TranscriptionRepository,
    private val summarizationRepository: TranscriptionSummarizationRepository,
    private val genAiClientFactory: GenAiClientFactory,
    private val executionCoordinator: QueueExecutionCoordinator,
    private val operations: JobOperationRegistry,
    @param:ApplicationContext private val applicationContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun summarize(
        jobId: Int,
        settings: SummarizationSettings,
        allowResummarize: Boolean,
        executionOwnerId: String? = null,
        fallbackStatusMessage: String = "",
        onStatusUpdate: suspend (String) -> Unit = {},
    ): SummarizationResult = operations.track(jobId) {
        summarizeAttempt(jobId, settings, allowResummarize, executionOwnerId, fallbackStatusMessage, onStatusUpdate)
    }

    private suspend fun summarizeAttempt(
        jobId: Int,
        settings: SummarizationSettings,
        allowResummarize: Boolean,
        executionOwnerId: String? = null,
        fallbackStatusMessage: String = "",
        onStatusUpdate: suspend (String) -> Unit = {},
    ): SummarizationResult {
        val summarizerSelection = try {
            resolveSummarizerSelection(settings)
        } catch (e: IllegalStateException) {
            val message = e.userMessageOr(R.string.provider_request_failed).resolve(applicationContext)
            return SummarizationResult(statusMessage = message, errorMessage = message)
        }

        executionCoordinator.awaitRecovery()
        val attemptId = executionCoordinator.ownerId()
        var summarizingCleared = false
        return try {
            val claimed = operations.withClaimLock {
                if (allowResummarize) {
                    summarizationRepository.claimManualSummary(
                        jobId,
                        attemptId,
                        summarizerSelection.provider.name,
                        summarizerSelection.modelName,
                    )
                } else {
                    summarizationRepository.claimAutoSummary(
                        jobId,
                        attemptId,
                        summarizerSelection.provider.name,
                        summarizerSelection.modelName,
                        executionOwnerId,
                    )
                }
            }
            if (!claimed) return SummarizationResult(statusMessage = fallbackStatusMessage)

            val job = repository.getJob(jobId)
            if (job == null) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                summarizingCleared = true
                return SummarizationResult(statusMessage = fallbackStatusMessage)
            }

            val text = repository.getTranscriptForJob(jobId)
            if (text.isBlank()) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                summarizingCleared = true
                SummarizationResult(
                    statusMessage = applicationContext.getString(
                        R.string.summarization_status_nothing_to_summarize,
                        job.displayName,
                    ),
                )
            } else {
                val summarizingMessage = applicationContext.getString(R.string.transcription_notification_summarizing)
                if (!summarizationRepository.updateSummaryStatus(jobId, attemptId, SUMMARY_STATUS_SUMMARIZING)) {
                    return SummarizationResult(statusMessage = fallbackStatusMessage)
                }
                onStatusUpdate(summarizingMessage)
                val apiKey = requireGeminiApiKey()
                val summary = try {
                    requestSummary(text, apiKey, summarizerSelection.modelName)
                } finally {
                    apiKey.fill('\u0000')
                }
                val saved = summarizationRepository.succeedSummarization(
                    jobId,
                    attemptId,
                    summary.text,
                    summarizerSelection.provider.name,
                    summary.model,
                )
                summarizingCleared = true
                if (!saved) return SummarizationResult(statusMessage = fallbackStatusMessage)
                SummarizationResult(
                    statusMessage = applicationContext.getString(R.string.summarization_status_generated),
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
            }
            summarizingCleared = true
            throw e
        } catch (_: ApiException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(applicationContext.getString(R.string.summarization_status_error, message), message)
        } catch (_: GenAiIOException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(applicationContext.getString(R.string.summarization_status_error, message), message)
        } catch (_: IOException) {
            val message = applicationContext.getString(R.string.provider_request_failed)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(message, message)
        } catch (e: IllegalStateException) {
            val message = e.userMessageOr(R.string.provider_request_failed).resolve(applicationContext)
            summarizationRepository.failSummarization(jobId, attemptId, message)
            summarizingCleared = true
            SummarizationResult(message, message)
        } finally {
            if (!summarizingCleared) {
                withContext(NonCancellable + ioDispatcher) {
                    summarizationRepository.invalidateSummaryAttempt(jobId, attemptId)
                }
            }
        }
    }

    suspend fun cancelSummary(jobId: Int): String {
        val cancelled = summarizationRepository.cancelSummarization(jobId)
        operations.cancel(jobId)
        return if (cancelled) {
            applicationContext.getString(R.string.summarization_status_cancelled)
        } else {
            applicationContext.getString(R.string.summarization_status_nothing_to_stop)
        }
    }

    private suspend fun requireGeminiApiKey(): CharArray {
        val keyChars = settings.getGeminiApiKey()
        if (keyChars == null || keyChars.isEmpty()) {
            throw UserFacingIllegalStateException(UserMessage(R.string.gemini_api_key_missing))
        }
        return keyChars
    }

    private fun resolveSummarizerSelection(settings: SummarizationSettings): SummarizerSelection {
        val provider = settings.provider
        return when {
            provider == TranscriptionProvider.GEMINI_CUSTOM -> {
                val modelName = settings.customModelName.ifBlank {
                    throw UserFacingIllegalStateException(UserMessage(R.string.summarizer_model_not_specified))
                }
                SummarizerSelection(provider = provider, modelName = modelName)
            }
            provider.isGemini() -> {
                SummarizerSelection(provider = provider, modelName = requireNotNull(provider.modelName))
            }
            else -> {
                val defaultProvider = TranscriptionProvider.GEMINI_FLASH
                SummarizerSelection(
                    provider = defaultProvider,
                    modelName = requireNotNull(defaultProvider.modelName),
                )
            }
        }
    }

    private suspend fun requestSummary(text: String, apiKey: CharArray, modelName: String): SummaryData =
        apiKey.useGenAiClient(genAiClientFactory, ioDispatcher) { client ->
            val prompt = """
                You are an expert summarizer.
                Provide a concise, easy-to-read summary of the following text, in the same language.
                Use bullet points (-) for key takeaways if appropriate. The output should be plain text,
                no markdown.
            """.trimIndent()

            val content = Content.fromParts(
                Part.fromText(prompt),
                Part.fromText(text),
            )
            val config = GenerateContentConfig.builder()
                .responseMimeType("text/plain")
                .build()
            val response = client.call { it.models.generateContent(modelName, content, config) }
            SummaryData(
                text = validatedGeminiText(response),
                model = response.modelVersion().orElse(null)?.takeIf { it.isNotBlank() } ?: modelName,
            )
        }
}

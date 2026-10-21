package com.andreacovelli.whispertranscriber.queue

import android.os.Build
import com.andreacovelli.whispertranscriber.background.PostTranscriptionWorkSpec
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.settings.SummarizationSettings
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.summary.decodeSummaryStyle

enum class JobExecutionBackend {
    WORK_MANAGER,
    USER_INITIATED_JOB,
}

data class PersistedPostTranscriptionConfig(
    val summarizeAfterTranscription: Boolean,
    val notifyResult: Boolean,
    val queuedSummarizerProviderName: String,
    val queuedSummarizerCustomModelName: String,
    val queuedSummaryStyle: String = "",
    val queuedSummaryCustomPrompt: String = "",
)

internal fun resolveShareExecutionBackend(preferImmediateBackgroundStart: Boolean): JobExecutionBackend =
    if (preferImmediateBackgroundStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        JobExecutionBackend.USER_INITIATED_JOB
    } else {
        JobExecutionBackend.WORK_MANAGER
    }

internal fun PostTranscriptionWorkSpec.toPersistedConfig(): PersistedPostTranscriptionConfig =
    PersistedPostTranscriptionConfig(
        summarizeAfterTranscription = summarizeAfterTranscription,
        notifyResult = notifyResult,
        queuedSummarizerProviderName = summarizationSettings.provider.name,
        queuedSummarizerCustomModelName = summarizationSettings.customModelName,
        queuedSummaryStyle = summarizationSettings.style.name,
        queuedSummaryCustomPrompt = summarizationSettings.customPrompt,
    )

internal fun PersistedPostTranscriptionConfig.toWorkSpecOrNull(): PostTranscriptionWorkSpec? {
    if (!summarizeAfterTranscription && !notifyResult) return null
    val provider = runCatching { TranscriptionProvider.valueOf(queuedSummarizerProviderName) }
        .getOrDefault(TranscriptionProvider.GEMINI_FLASH)
    return PostTranscriptionWorkSpec(
        summarizeAfterTranscription = summarizeAfterTranscription,
        notifyResult = notifyResult,
        summarizationSettings = SummarizationSettings(
            provider = provider,
            customModelName = queuedSummarizerCustomModelName,
            style = decodeSummaryStyle(queuedSummaryStyle),
            customPrompt = queuedSummaryCustomPrompt,
        ),
    )
}

internal fun TranscriptionJob.executionBackendOrDefault(): JobExecutionBackend = runCatching {
    JobExecutionBackend.valueOf(executionBackend)
}.getOrDefault(JobExecutionBackend.WORK_MANAGER)

internal fun TranscriptionJob.persistedPostTranscriptionConfig(): PersistedPostTranscriptionConfig =
    PersistedPostTranscriptionConfig(
        summarizeAfterTranscription = summarizeAfterTranscription,
        notifyResult = notifyResult,
        queuedSummarizerProviderName = queuedSummarizerProviderName,
        queuedSummarizerCustomModelName = queuedSummarizerCustomModelName,
        queuedSummaryStyle = queuedSummaryStyle,
        queuedSummaryCustomPrompt = queuedSummaryCustomPrompt,
    )

internal fun TranscriptionJob.postTranscriptionWorkSpec(): PostTranscriptionWorkSpec? =
    persistedPostTranscriptionConfig().toWorkSpecOrNull()

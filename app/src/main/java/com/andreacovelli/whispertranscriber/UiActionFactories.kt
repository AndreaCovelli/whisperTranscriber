package com.andreacovelli.whispertranscriber

data class MainScreenActions(
    val clearCompletedJobs: () -> Unit,
    val summarizeJob: (Int) -> Unit,
    val clearSummary: (Int) -> Unit,
    val clearTranscript: (Int) -> Unit,
    val retryJob: (Int) -> Unit,
    val cancelJob: (Int) -> Unit,
    val cancelSummary: (Int) -> Unit,
    val deleteJob: (Int) -> Unit,
)

data class SettingsScreenActions(
    val setProvider: (TranscriptionProvider) -> Unit,
    val setSummarizerProvider: (TranscriptionProvider) -> Unit,
    val setAutomaticSummarization: (Boolean) -> Unit,
    val setBackgroundShareSummaryNotificationsEnabled: (Boolean) -> Unit,
    val setSpeakerDiarization: (Boolean) -> Unit,
    val setMicrosoftSpeechRegion: (String) -> Unit,
    val resetMicrosoftSpeechRegionValidation: () -> Unit,
    val loadMicrosoftSpeechKey: suspend () -> String?,
    val loadGeminiKey: suspend () -> String?,
    val saveMicrosoftSpeechKey: (String) -> Unit,
    val saveGeminiKey: (String) -> Unit,
    val validateTranscriptionModel: (String) -> Unit,
    val validateSummarizerModel: (String) -> Unit,
    val resetTranscriptionModelValidation: () -> Unit,
    val resetSummarizerModelValidation: () -> Unit,
    val resetMicrosoftSpeechKeyValidation: () -> Unit,
    val resetGeminiKeyValidation: () -> Unit,
    val forgetMicrosoftSpeechKey: () -> Unit,
    val forgetGeminiKey: () -> Unit,
)

internal fun createMainScreenActions(
    clearCompletedJobs: () -> Unit,
    summarizeJob: (Int) -> Unit,
    clearSummary: (Int) -> Unit,
    clearTranscript: (Int) -> Unit,
    retryJob: (Int) -> Unit,
    cancelJob: (Int) -> Unit,
    cancelSummary: (Int) -> Unit,
    deleteJob: (Int) -> Unit,
): MainScreenActions = MainScreenActions(
    clearCompletedJobs = clearCompletedJobs,
    summarizeJob = summarizeJob,
    clearSummary = clearSummary,
    clearTranscript = clearTranscript,
    retryJob = retryJob,
    cancelJob = cancelJob,
    cancelSummary = cancelSummary,
    deleteJob = deleteJob,
)

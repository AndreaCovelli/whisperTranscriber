package com.andreacovelli.whispertranscriber

data class MainScreenActions(
    val clearCompletedJobs: () -> Unit,
    val summarizeJob: (Int) -> Unit,
    val clearSummary: (Int) -> Unit,
    val clearTranscript: (Int) -> Unit,
    val retryJob: (Int) -> Unit,
    val cancelJob: (Int) -> Unit,
    val deleteJob: (Int) -> Unit,
)

data class SettingsScreenActions(
    val setProvider: (TranscriptionProvider) -> Unit,
    val setSummarizerProvider: (TranscriptionProvider) -> Unit,
    val setAutomaticSummarization: (Boolean) -> Unit,
    val setSpeakerDiarization: (Boolean) -> Unit,
    val saveElevenLabsKey: (String) -> Unit,
    val saveGeminiKey: (String) -> Unit,
    val validateTranscriptionModel: (String) -> Unit,
    val validateSummarizerModel: (String) -> Unit,
    val resetTranscriptionModelValidation: () -> Unit,
    val resetSummarizerModelValidation: () -> Unit,
    val resetElevenLabsKeyValidation: () -> Unit,
    val resetGeminiKeyValidation: () -> Unit,
    val forgetElevenLabsKey: () -> Unit,
    val forgetGeminiKey: () -> Unit,
)

internal fun createMainScreenActions(
    clearCompletedJobs: () -> Unit,
    summarizeJob: (Int) -> Unit,
    clearSummary: (Int) -> Unit,
    clearTranscript: (Int) -> Unit,
    retryJob: (Int) -> Unit,
    cancelJob: (Int) -> Unit,
    deleteJob: (Int) -> Unit,
): MainScreenActions = MainScreenActions(
    clearCompletedJobs = clearCompletedJobs,
    summarizeJob = summarizeJob,
    clearSummary = clearSummary,
    clearTranscript = clearTranscript,
    retryJob = retryJob,
    cancelJob = cancelJob,
    deleteJob = deleteJob,
)

internal fun MainViewModel.createSettingsScreenActions(): SettingsScreenActions = SettingsScreenActions(
    setProvider = ::updateProviderSelection,
    setSummarizerProvider = ::updateSummarizerProviderSelection,
    setAutomaticSummarization = ::updateAutomaticSummarization,
    setSpeakerDiarization = ::updateSpeakerDiarization,
    saveElevenLabsKey = ::setElevenLabsKey,
    saveGeminiKey = ::setGeminiKey,
    validateTranscriptionModel = ::validateTranscriptionGeminiModel,
    validateSummarizerModel = ::validateSummarizerGeminiModel,
    resetTranscriptionModelValidation = ::resetTranscriptionModelValidation,
    resetSummarizerModelValidation = ::resetSummarizerModelValidation,
    resetElevenLabsKeyValidation = ::resetElevenLabsKeyValidation,
    resetGeminiKeyValidation = ::resetGeminiKeyValidation,
    forgetElevenLabsKey = ::forgetElevenLabsKey,
    forgetGeminiKey = ::forgetGeminiKey,
)

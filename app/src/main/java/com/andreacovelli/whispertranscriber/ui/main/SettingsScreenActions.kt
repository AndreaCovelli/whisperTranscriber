package com.andreacovelli.whispertranscriber.ui.main

import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider

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

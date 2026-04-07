package com.andreacovelli.whispertranscriber.ui.components

data class TranscriptionJobCardActions(
    val summarize: () -> Unit,
    val clearSummary: () -> Unit,
    val clearTranscript: () -> Unit,
    val copyTranscript: () -> Unit,
    val copySummary: () -> Unit,
    val shareTranscript: () -> Unit,
    val shareSummary: () -> Unit,
    val retryTranscription: () -> Unit,
    val retrySummary: () -> Unit,
    val cancel: () -> Unit,
    val cancelTranscription: () -> Unit,
    val cancelSummary: () -> Unit,
    val delete: () -> Unit,
)

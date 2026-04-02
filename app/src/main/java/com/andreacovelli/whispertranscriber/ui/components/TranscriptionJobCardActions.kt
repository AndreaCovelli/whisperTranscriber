package com.andreacovelli.whispertranscriber.ui.components

data class TranscriptionJobCardActions(
    val summarize: () -> Unit,
    val clearSummary: () -> Unit,
    val clearTranscript: () -> Unit,
    val copyTranscript: () -> Unit,
    val copySummary: () -> Unit,
    val shareTranscript: () -> Unit,
    val shareSummary: () -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val delete: () -> Unit,
)

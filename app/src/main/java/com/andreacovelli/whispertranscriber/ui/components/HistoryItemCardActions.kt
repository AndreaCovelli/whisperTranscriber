package com.andreacovelli.whispertranscriber.ui.components

data class HistoryItemCardActions(
    val copyTranscript: () -> Unit,
    val copySummary: () -> Unit,
    val clearTranscript: () -> Unit,
    val clearSummary: () -> Unit,
    val delete: () -> Unit,
    val shareTranscript: () -> Unit,
    val shareSummary: () -> Unit,
    val saveTranscript: () -> Unit,
    val saveSummary: () -> Unit,
    val editTranscript: (() -> Unit)? = null,
)

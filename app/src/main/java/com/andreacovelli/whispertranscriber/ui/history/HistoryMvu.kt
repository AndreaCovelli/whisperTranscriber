package com.andreacovelli.whispertranscriber.ui.history

import android.net.Uri
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat

data class HistoryModel(val searchQuery: String = "")

sealed interface HistoryMsg {
    data class SetSearchQuery(val query: String) : HistoryMsg
    data class PrepareShareTranscript(val item: TranscriptionHistory) : HistoryMsg
    data class PrepareShareSummary(val item: TranscriptionHistory) : HistoryMsg
    data class PrepareExport(val format: HistoryExportFormat) : HistoryMsg
    data class ExportHistory(val uri: Uri, val format: HistoryExportFormat) : HistoryMsg
    data class ClearSummary(val item: TranscriptionHistory) : HistoryMsg
    data class ClearTranscript(val item: TranscriptionHistory) : HistoryMsg
    data class DeleteItem(val item: TranscriptionHistory) : HistoryMsg
    data object DeleteAll : HistoryMsg
}

internal object HistoryReducer {
    fun reduce(model: HistoryModel, msg: HistoryMsg): HistoryModel = when (msg) {
        is HistoryMsg.SetSearchQuery -> model.copy(searchQuery = msg.query)
        else -> model
    }
}

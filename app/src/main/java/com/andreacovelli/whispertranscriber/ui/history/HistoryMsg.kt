package com.andreacovelli.whispertranscriber.ui.history

import android.net.Uri
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat

/** User actions from the history screen; [HistoryViewModel.dispatch] handles each one. */
sealed interface HistoryMsg {
    data class SetSearchQuery(val query: String) : HistoryMsg
    data class PrepareShareTranscript(val item: TranscriptionHistory) : HistoryMsg
    data class PrepareShareSummary(val item: TranscriptionHistory) : HistoryMsg
    data class PrepareExport(val format: HistoryExportFormat) : HistoryMsg
    data class ExportHistory(val uri: Uri, val format: HistoryExportFormat) : HistoryMsg
    data class SaveContent(val historyId: Int, val field: HistoryContentField, val destination: Uri) : HistoryMsg
    data class ClearSummary(val item: TranscriptionHistory) : HistoryMsg
    data class ClearTranscript(val item: TranscriptionHistory) : HistoryMsg
    data class EditTranscript(val item: TranscriptionHistory) : HistoryMsg
    data object SaveTranscriptEdit : HistoryMsg
    data object CancelTranscriptEdit : HistoryMsg

    /** Hides [item] at once; it is deleted by [FinishDeleteItem] unless [UndoDeleteItem] restores it first. */
    data class DeleteItem(val item: TranscriptionHistory) : HistoryMsg
    data class UndoDeleteItem(val historyId: Int) : HistoryMsg
    data class FinishDeleteItem(val historyId: Int) : HistoryMsg
    data object DeleteAll : HistoryMsg
}

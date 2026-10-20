package com.andreacovelli.whispertranscriber.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat

/** The job or history row whose text is being saved; the text itself is reloaded once a file is chosen. */
data class TextSaveTarget(val id: Int, val field: HistoryContentField)

/**
 * Opens the system "create document" picker for a plain-text file. Only the target ids are kept in
 * saved state, so a save still completes if the process is recreated while the picker is open.
 */
@Composable
internal fun rememberTextFileSaveLauncher(
    onDestinationChosen: (TextSaveTarget, Uri) -> Unit,
): (TextSaveTarget, String) -> Unit {
    var pendingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingField by rememberSaveable { mutableStateOf<HistoryContentField?>(null) }
    val currentOnDestinationChosen by rememberUpdatedState(onDestinationChosen)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.TXT.mimeType),
    ) { uri ->
        val id = pendingId
        val field = pendingField
        pendingId = null
        pendingField = null
        if (uri != null && id != null && field != null) {
            currentOnDestinationChosen(TextSaveTarget(id, field), uri)
        }
    }
    return remember(launcher) {
        { target, suggestedFileName ->
            pendingId = target.id
            pendingField = target.field
            launcher.launch(suggestedFileName)
        }
    }
}

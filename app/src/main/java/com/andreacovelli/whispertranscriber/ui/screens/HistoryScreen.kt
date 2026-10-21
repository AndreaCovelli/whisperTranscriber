package com.andreacovelli.whispertranscriber.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.data.HistoryContentField
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.history.HistoryDateBucket
import com.andreacovelli.whispertranscriber.history.HistoryDateLabel
import com.andreacovelli.whispertranscriber.history.HistoryExportFormat
import com.andreacovelli.whispertranscriber.history.HistoryListRow
import com.andreacovelli.whispertranscriber.history.extractHistorySearchTokens
import com.andreacovelli.whispertranscriber.history.suggestedHistoryExportFileName
import com.andreacovelli.whispertranscriber.history.suggestedTextExportFileName
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCardActions
import com.andreacovelli.whispertranscriber.ui.components.TextSaveTarget
import com.andreacovelli.whispertranscriber.ui.components.TranscriptEditDialog
import com.andreacovelli.whispertranscriber.ui.components.rememberTextFileSaveLauncher
import com.andreacovelli.whispertranscriber.ui.copyTextWithFeedback
import com.andreacovelli.whispertranscriber.ui.history.HistoryMsg
import com.andreacovelli.whispertranscriber.ui.history.HistoryUiEvent
import com.andreacovelli.whispertranscriber.ui.history.HistoryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(historyViewModel: HistoryViewModel = hiltViewModel(), onNavigateBack: () -> Unit) {
    val pagedItems = historyViewModel.pagedHistoryItems.collectAsLazyPagingItems()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val searchQuery by historyViewModel.searchQuery.collectAsStateWithLifecycle()
    val expandedIdsSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    val searchTokens = remember(searchQuery) { extractHistorySearchTokens(searchQuery) }
    val txtExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.TXT.mimeType),
    ) { uri ->
        uri?.let { historyViewModel.dispatch(HistoryMsg.ExportHistory(it, HistoryExportFormat.TXT)) }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.CSV.mimeType),
    ) { uri ->
        uri?.let { historyViewModel.dispatch(HistoryMsg.ExportHistory(it, HistoryExportFormat.CSV)) }
    }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.JSON.mimeType),
    ) { uri ->
        uri?.let { historyViewModel.dispatch(HistoryMsg.ExportHistory(it, HistoryExportFormat.JSON)) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val itemDeletedMessage = stringResource(R.string.history_item_deleted)
    val undoLabel = stringResource(R.string.history_undo)
    ObserveHistoryEvents(
        historyViewModel = historyViewModel,
        onItemDeleted = { historyId ->
            // A newer deletion replaces the visible offer, which finishes the earlier deletion.
            snackbarHostState.currentSnackbarData?.dismiss()
            scope.launch {
                offerUndoDelete(snackbarHostState, itemDeletedMessage, undoLabel, historyId, historyViewModel::dispatch)
            }
        },
        onLaunchExport = { format ->
            val fileName = suggestedHistoryExportFileName(format)
            when (format) {
                HistoryExportFormat.TXT -> txtExportLauncher.launch(fileName)
                HistoryExportFormat.CSV -> csvExportLauncher.launch(fileName)
                HistoryExportFormat.JSON -> jsonExportLauncher.launch(fileName)
            }
        },
    )
    if (showDeleteAllDialog) {
        DeleteAllHistoryDialog(
            onDismiss = { showDeleteAllDialog = false },
            onConfirm = {
                historyViewModel.dispatch(HistoryMsg.DeleteAll)
                showDeleteAllDialog = false
            },
        )
    }
    val transcriptEditor by historyViewModel.transcriptEditor.collectAsStateWithLifecycle()
    transcriptEditor?.let { editor ->
        TranscriptEditDialog(
            text = editor.text,
            hasSummary = editor.hasSummary,
            onSave = { historyViewModel.dispatch(HistoryMsg.SaveTranscriptEdit) },
            onCancel = { historyViewModel.dispatch(HistoryMsg.CancelTranscriptEdit) },
        )
    }
    if (showExportDialog) {
        ExportHistoryDialog(
            onDismiss = { showExportDialog = false },
            onExport = { format ->
                showExportDialog = false
                historyViewModel.dispatch(HistoryMsg.PrepareExport(format))
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HistoryTopBar(
                canDeleteAll = pagedItems.itemCount > 0 || searchQuery.isNotEmpty(),
                onNavigateBack = onNavigateBack,
                onExport = { showExportDialog = true },
                onDeleteAll = { showDeleteAllDialog = true },
            )
        },
    ) { paddingValues ->
        HistoryScreenContent(
            historyViewModel = historyViewModel,
            pagedItems = pagedItems,
            searchQuery = searchQuery,
            searchTokens = searchTokens,
            expandedIds = expandedIds,
            onExpandedIdsChange = { expandedIds = it },
            paddingValues = paddingValues,
            clipboardManager = clipboardManager,
            scope = scope,
            context = context,
        )
    }
}

// Leaving the screen or recreating it ends the offer, so a cancelled snackbar finishes the deletion too.
private suspend fun offerUndoDelete(
    snackbarHostState: SnackbarHostState,
    message: String,
    undoLabel: String,
    historyId: Int,
    dispatch: (HistoryMsg) -> Unit,
) {
    val result = try {
        snackbarHostState.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Short)
    } catch (cancellation: CancellationException) {
        dispatch(HistoryMsg.FinishDeleteItem(historyId))
        throw cancellation
    }
    dispatch(
        if (result == SnackbarResult.ActionPerformed) {
            HistoryMsg.UndoDeleteItem(historyId)
        } else {
            HistoryMsg.FinishDeleteItem(historyId)
        },
    )
}

@Composable
private fun ObserveHistoryEvents(
    historyViewModel: HistoryViewModel,
    onItemDeleted: (Int) -> Unit,
    onLaunchExport: (HistoryExportFormat) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(historyViewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            historyViewModel.uiEvents.collect { event ->
                when (event) {
                    is HistoryUiEvent.LaunchExport -> onLaunchExport(event.format)
                    is HistoryUiEvent.ItemDeleted -> onItemDeleted(event.historyId)
                    is HistoryUiEvent.Share -> {
                        if (!historyViewModel.shareText(
                                event.content,
                                resources.getString(event.titleId),
                                context::startActivity,
                            )
                        ) {
                            Toast.makeText(context, R.string.text_share_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is HistoryUiEvent.Toast -> {
                        Toast.makeText(context, event.messageId, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteAllHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_all_title)) },
        text = { Text(stringResource(R.string.history_delete_all_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.history_delete_all_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(
    canDeleteAll: Boolean,
    onNavigateBack: () -> Unit,
    onExport: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.history_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.history_export_action))
            }
            if (canDeleteAll) {
                IconButton(onClick = onDeleteAll) {
                    Icon(
                        painterResource(R.drawable.ic_delete_sweep),
                        stringResource(R.string.history_delete_all_description),
                    )
                }
            }
        },
    )
}

@Composable
private fun ExportHistoryDialog(onDismiss: () -> Unit, onExport: (HistoryExportFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_export_dialog_title)) },
        text = {
            Column {
                ExportFormatButton(
                    label = stringResource(R.string.history_export_format_txt),
                    onClick = { onExport(HistoryExportFormat.TXT) },
                )
                ExportFormatButton(
                    label = stringResource(R.string.history_export_format_csv),
                    onClick = { onExport(HistoryExportFormat.CSV) },
                )
                ExportFormatButton(
                    label = stringResource(R.string.history_export_format_json),
                    onClick = { onExport(HistoryExportFormat.JSON) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_export_cancel))
            }
        },
    )
}

@Composable
private fun ExportFormatButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun HistoryScreenContent(
    historyViewModel: HistoryViewModel,
    pagedItems: LazyPagingItems<HistoryListRow>,
    searchQuery: String,
    searchTokens: List<String>,
    expandedIds: Set<Int>,
    onExpandedIdsChange: (Set<Int>) -> Unit,
    paddingValues: PaddingValues,
    clipboardManager: androidx.compose.ui.platform.Clipboard,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusManager.clearFocus() },
    ) {
        HistorySearchField(
            visible = pagedItems.itemCount > 0 || searchQuery.isNotEmpty(),
            searchQuery = searchQuery,
            onSearchChange = { historyViewModel.dispatch(HistoryMsg.SetSearchQuery(it)) },
        )
        HistoryContent(
            pagedItems = pagedItems,
            searchQuery = searchQuery,
            searchTokens = searchTokens,
            expandedIds = expandedIds,
            onExpandedIdsChange = onExpandedIdsChange,
            historyViewModel = historyViewModel,
            clipboardManager = clipboardManager,
            scope = scope,
            context = context,
        )
    }
}

@Composable
private fun HistorySearchField(visible: Boolean, searchQuery: String, onSearchChange: (String) -> Unit) {
    if (!visible) return
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        trailingIcon = if (searchQuery.isNotEmpty()) {
            {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.history_clear_search))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ColumnScope.HistoryContent(
    pagedItems: LazyPagingItems<HistoryListRow>,
    searchQuery: String,
    searchTokens: List<String>,
    expandedIds: Set<Int>,
    onExpandedIdsChange: (Set<Int>) -> Unit,
    historyViewModel: HistoryViewModel,
    clipboardManager: androidx.compose.ui.platform.Clipboard,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    val saveTextFile = rememberTextFileSaveLauncher { target, destination ->
        historyViewModel.dispatch(HistoryMsg.SaveContent(target.id, target.field, destination))
    }
    val transcriptLabel = context.getString(R.string.job_transcription_title)
    val summaryLabel = context.getString(R.string.job_summary_title)
    val actionFactory = remember(
        historyViewModel,
        clipboardManager,
        scope,
        context,
        transcriptLabel,
        summaryLabel,
        saveTextFile,
    ) {
        { item: TranscriptionHistory ->
            HistoryItemCardActions(
                copyTranscript = {
                    scope.launch {
                        copyTextWithFeedback(context, clipboardManager, item.text, R.string.main_copied_transcript)
                    }
                },
                copySummary = {
                    scope.launch {
                        copyTextWithFeedback(context, clipboardManager, item.summary, R.string.main_copied_summary)
                    }
                },
                clearTranscript = { historyViewModel.dispatch(HistoryMsg.ClearTranscript(item)) },
                clearSummary = { historyViewModel.dispatch(HistoryMsg.ClearSummary(item)) },
                delete = { historyViewModel.dispatch(HistoryMsg.DeleteItem(item)) },
                shareTranscript = { historyViewModel.dispatch(HistoryMsg.PrepareShareTranscript(item)) },
                shareSummary = { historyViewModel.dispatch(HistoryMsg.PrepareShareSummary(item)) },
                saveTranscript = {
                    saveTextFile(
                        TextSaveTarget(item.id, HistoryContentField.TRANSCRIPT),
                        suggestedTextExportFileName(item.displayName, HistoryContentField.TRANSCRIPT),
                    )
                },
                saveSummary = {
                    saveTextFile(
                        TextSaveTarget(item.id, HistoryContentField.SUMMARY),
                        suggestedTextExportFileName(item.displayName, HistoryContentField.SUMMARY),
                    )
                },
                editTranscript = { historyViewModel.dispatch(HistoryMsg.EditTranscript(item)) },
            )
        }
    }
    when {
        pagedItems.isInitialLoad() -> LoadingHistoryState()
        pagedItems.loadState.refresh is LoadState.Error && pagedItems.itemCount == 0 ->
            HistoryLoadError(onRetry = pagedItems::retry)
        pagedItems.itemCount == 0 -> EmptyHistoryState(searchQuery)
        else -> HistoryRowList(
            pagedItems = pagedItems,
            searchTokens = searchTokens,
            expandedIds = expandedIds,
            onExpandedIdsChange = onExpandedIdsChange,
            actionFactory = actionFactory,
        )
    }
}

@Composable
private fun ColumnScope.LoadingHistoryState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ColumnScope.EmptyHistoryState(searchQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (searchQuery.isEmpty()) {
                stringResource(R.string.history_empty)
            } else {
                stringResource(R.string.history_no_results, searchQuery)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
internal fun ColumnScope.HistoryRowList(
    pagedItems: LazyPagingItems<HistoryListRow>,
    searchTokens: List<String>,
    expandedIds: Set<Int>,
    onExpandedIdsChange: (Set<Int>) -> Unit,
    actionFactory: (TranscriptionHistory) -> HistoryItemCardActions,
) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (pagedItems.loadState.refresh is LoadState.Error) {
            item(key = "refresh-error") { HistoryLoadError(onRetry = pagedItems::retry) }
        }
        items(count = pagedItems.itemCount, key = pagedItems.itemKey { it.stableKey }) { index ->
            when (val row = pagedItems[index]) {
                null -> HistoryItemPlaceholderCard()
                is HistoryListRow.Header -> HistoryDateHeaderRow(bucket = row.bucket)
                is HistoryListRow.Item -> {
                    val item = row.history
                    val actions = remember(item, actionFactory) { actionFactory(item) }
                    HistoryItemCard(
                        item = item,
                        expanded = expandedIds.contains(item.id),
                        onToggleExpand = { onExpandedIdsChange(expandedIds.toggle(item.id)) },
                        actions = actions,
                        searchTokens = searchTokens,
                    )
                }
            }
        }
        when (pagedItems.loadState.append) {
            is LoadState.Error -> item(key = "append-error") { HistoryLoadError(onRetry = pagedItems::retry) }
            is LoadState.Loading -> item(key = "append-loading") { CircularProgressIndicator() }
            else -> Unit
        }
    }
}

@Composable
private fun HistoryItemPlaceholderCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PlaceholderBlock(widthFraction = 0.55f, height = 20.dp)
            Spacer(Modifier.height(8.dp))
            PlaceholderBlock(widthFraction = 0.4f, height = 12.dp)
            Spacer(Modifier.height(4.dp))
            PlaceholderBlock(widthFraction = 0.3f, height = 12.dp)
            Spacer(Modifier.height(12.dp))
            PlaceholderBlock(widthFraction = 1f, height = 16.dp)
            Spacer(Modifier.height(6.dp))
            PlaceholderBlock(widthFraction = 0.92f, height = 16.dp)
        }
    }
}

@Composable
private fun PlaceholderBlock(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            ),
    )
}

@Composable
private fun HistoryDateHeaderRow(bucket: HistoryDateBucket) {
    val context = LocalContext.current
    val text = when (val label = bucket.label) {
        HistoryDateLabel.Today -> stringResource(R.string.history_group_today)
        HistoryDateLabel.Yesterday -> stringResource(R.string.history_group_yesterday)
        is HistoryDateLabel.Month -> remember(label.yearMonth, bucket.zoneId, context) {
            DateUtils.formatDateTime(
                context,
                label.yearMonth
                    .atDay(1)
                    .atStartOfDay(bucket.zoneId)
                    .toInstant()
                    .toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_NO_MONTH_DAY or
                    DateUtils.FORMAT_SHOW_YEAR,
            )
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun Set<Int>.toggle(id: Int): Set<Int> = if (contains(id)) this - id else this + id

private fun <T : Any> LazyPagingItems<T>.isInitialLoad(): Boolean =
    loadState.refresh is LoadState.Loading && itemCount == 0

@Composable
internal fun HistoryLoadError(onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.history_load_failed))
        androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

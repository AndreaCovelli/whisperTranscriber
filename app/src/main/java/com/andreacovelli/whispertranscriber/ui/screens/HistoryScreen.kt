package com.andreacovelli.whispertranscriber.ui.screens

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.andreacovelli.whispertranscriber.HistoryDateBucket
import com.andreacovelli.whispertranscriber.HistoryDateLabel
import com.andreacovelli.whispertranscriber.HistoryExportFormat
import com.andreacovelli.whispertranscriber.HistoryListRow
import com.andreacovelli.whispertranscriber.HistoryUiEvent
import com.andreacovelli.whispertranscriber.HistoryViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.extractHistorySearchTokens
import com.andreacovelli.whispertranscriber.suggestedHistoryExportFileName
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCardActions
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
        uri?.let { historyViewModel.exportHistory(it, HistoryExportFormat.TXT) }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.CSV.mimeType),
    ) { uri ->
        uri?.let { historyViewModel.exportHistory(it, HistoryExportFormat.CSV) }
    }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(HistoryExportFormat.JSON.mimeType),
    ) { uri ->
        uri?.let { historyViewModel.exportHistory(it, HistoryExportFormat.JSON) }
    }

    ObserveHistoryEvents(
        historyViewModel = historyViewModel,
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
                historyViewModel.deleteAllHistory()
                showDeleteAllDialog = false
            },
        )
    }
    if (showExportDialog) {
        ExportHistoryDialog(
            onDismiss = { showExportDialog = false },
            onExport = { format ->
                showExportDialog = false
                historyViewModel.prepareExport(format)
            },
        )
    }

    Scaffold(
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

@Composable
private fun ObserveHistoryEvents(historyViewModel: HistoryViewModel, onLaunchExport: (HistoryExportFormat) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(historyViewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            historyViewModel.uiEvents.collect { event ->
                when (event) {
                    is HistoryUiEvent.LaunchExport -> onLaunchExport(event.format)
                    is HistoryUiEvent.Share -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, event.content)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, event.title))
                    }
                    is HistoryUiEvent.Toast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = {
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.history_export_action))
            }
            if (canDeleteAll) {
                IconButton(onClick = onDeleteAll) {
                    Icon(Icons.Default.DeleteSweep, stringResource(R.string.history_delete_all_description))
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
            onSearchChange = historyViewModel::setSearchQuery,
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
                    Icon(Icons.Default.Delete, stringResource(R.string.history_clear_search))
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
    val transcriptLabel = context.getString(R.string.job_transcription_title)
    val summaryLabel = context.getString(R.string.job_summary_title)
    val actionFactory = remember(historyViewModel, clipboardManager, scope, context, transcriptLabel, summaryLabel) {
        { item: TranscriptionHistory ->
            HistoryItemCardActions(
                copyTranscript = {
                    scope.launch {
                        clipboardManager.setClipEntry(
                            ClipEntry(ClipData.newPlainText(transcriptLabel, item.text)),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.main_copied_transcript),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                copySummary = {
                    scope.launch {
                        clipboardManager.setClipEntry(
                            ClipEntry(ClipData.newPlainText(summaryLabel, item.summary)),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.main_copied_summary),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                clearTranscript = { historyViewModel.clearTranscript(item) },
                clearSummary = { historyViewModel.clearSummary(item) },
                delete = { historyViewModel.deleteHistoryItem(item) },
                shareTranscript = { historyViewModel.prepareShareTranscript(item) },
                shareSummary = { historyViewModel.prepareShareSummary(item) },
            )
        }
    }
    when {
        pagedItems.isInitialLoad() -> LoadingHistoryState()
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

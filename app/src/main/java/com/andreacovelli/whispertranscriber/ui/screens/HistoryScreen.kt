package com.andreacovelli.whispertranscriber.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
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
import com.andreacovelli.whispertranscriber.HistoryExportFormat
import com.andreacovelli.whispertranscriber.HistoryUiEvent
import com.andreacovelli.whispertranscriber.HistoryViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.extractHistorySearchTokens
import com.andreacovelli.whispertranscriber.suggestedHistoryExportFileName
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(historyViewModel: HistoryViewModel = hiltViewModel(), onNavigateBack: () -> Unit) {
    val pagedItems = historyViewModel.pagedHistoryItems.collectAsLazyPagingItems()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val searchQuery by historyViewModel.searchQuery.collectAsStateWithLifecycle()
    val expandedIdsSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }
    val showDeleteAllDialog = remember { mutableStateOf(false) }
    val showExportDialog = remember { mutableStateOf(false) }
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
    if (showDeleteAllDialog.value) {
        DeleteAllHistoryDialog(
            onDismiss = { showDeleteAllDialog.value = false },
            onConfirm = {
                historyViewModel.deleteAllHistory()
                showDeleteAllDialog.value = false
            },
        )
    }
    if (showExportDialog.value) {
        ExportHistoryDialog(
            onDismiss = { showExportDialog.value = false },
            onExport = { format ->
                showExportDialog.value = false
                historyViewModel.prepareExport(format)
            },
        )
    }

    Scaffold(
        topBar = {
            HistoryTopBar(
                canDeleteAll = pagedItems.itemCount > 0 || searchQuery.isNotEmpty(),
                onNavigateBack = onNavigateBack,
                onExport = { showExportDialog.value = true },
                onDeleteAll = { showDeleteAllDialog.value = true },
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
    pagedItems: LazyPagingItems<com.andreacovelli.whispertranscriber.data.TranscriptionHistory>,
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
    pagedItems: LazyPagingItems<com.andreacovelli.whispertranscriber.data.TranscriptionHistory>,
    searchQuery: String,
    searchTokens: List<String>,
    expandedIds: Set<Int>,
    onExpandedIdsChange: (Set<Int>) -> Unit,
    historyViewModel: HistoryViewModel,
    clipboardManager: androidx.compose.ui.platform.Clipboard,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    when {
        pagedItems.isInitialLoad() -> LoadingHistoryState()
        pagedItems.itemCount == 0 -> EmptyHistoryState(searchQuery)
        else -> HistoryList(
            pagedItems = pagedItems,
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
private fun ColumnScope.HistoryList(
    pagedItems: LazyPagingItems<com.andreacovelli.whispertranscriber.data.TranscriptionHistory>,
    searchTokens: List<String>,
    expandedIds: Set<Int>,
    onExpandedIdsChange: (Set<Int>) -> Unit,
    historyViewModel: HistoryViewModel,
    clipboardManager: androidx.compose.ui.platform.Clipboard,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(count = pagedItems.itemCount, key = pagedItems.itemKey { it.id }) { index ->
            val item = pagedItems[index] ?: return@items
            HistoryItemCard(
                item = item,
                expanded = expandedIds.contains(item.id),
                onToggleExpand = { onExpandedIdsChange(expandedIds.toggle(item.id)) },
                onCopyTranscript = {
                    scope.launch {
                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("", item.text)))
                        Toast.makeText(context, context.getString(R.string.main_copied_transcript), Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onCopySummary = {
                    scope.launch {
                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("", item.summary)))
                        Toast.makeText(context, context.getString(R.string.main_copied_summary), Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onClearTranscript = { historyViewModel.clearTranscript(item) },
                onClearSummary = { historyViewModel.clearSummary(item) },
                onDelete = { historyViewModel.deleteHistoryItem(item) },
                onShareTranscript = { historyViewModel.prepareShareTranscript(item) },
                onShareSummary = { historyViewModel.prepareShareSummary(item) },
                searchTokens = searchTokens,
            )
        }
    }
}

private fun Set<Int>.toggle(id: Int): Set<Int> = if (contains(id)) this - id else this + id

private fun <T : Any> LazyPagingItems<T>.isInitialLoad(): Boolean =
    loadState.refresh is LoadState.Loading && itemCount == 0

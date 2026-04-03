package com.andreacovelli.whispertranscriber.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.andreacovelli.whispertranscriber.HistoryViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.extractHistorySearchTokens
import com.andreacovelli.whispertranscriber.ui.components.HistoryItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(historyViewModel: HistoryViewModel = viewModel(), onNavigateBack: () -> Unit) {
    val pagedItems = historyViewModel.pagedHistoryItems.collectAsLazyPagingItems()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val searchQuery by historyViewModel.searchQuery.collectAsStateWithLifecycle()
    val expandedIdsSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    val searchTokens = remember(searchQuery) { extractHistorySearchTokens(searchQuery) }

    ObserveShareEvents(
        historyViewModel = historyViewModel,
        shareTitle = stringResource(R.string.share_transcription_title),
    )
    DeleteAllHistoryDialog(
        visible = showDeleteAllDialog,
        onDismiss = { showDeleteAllDialog = false },
        onConfirm = {
            historyViewModel.deleteAllHistory()
            showDeleteAllDialog = false
        },
    )

    Scaffold(
        topBar = {
            HistoryTopBar(
                canDeleteAll = pagedItems.itemCount > 0 || searchQuery.isNotEmpty(),
                onNavigateBack = onNavigateBack,
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
private fun ObserveShareEvents(historyViewModel: HistoryViewModel, shareTitle: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(historyViewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            historyViewModel.shareEvent.collect { content ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(sendIntent, shareTitle))
            }
        }
    }
}

@Composable
private fun DeleteAllHistoryDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all history?") },
        text = { Text("This will permanently remove all transcription history.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete all", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(canDeleteAll: Boolean, onNavigateBack: () -> Unit, onDeleteAll: () -> Unit) {
    TopAppBar(
        title = { Text("Transcription History") },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            if (canDeleteAll) {
                IconButton(onClick = onDeleteAll) {
                    Icon(Icons.Default.DeleteSweep, "Delete all history")
                }
            }
        },
    )
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
        placeholder = { Text("Search transcriptions…") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        trailingIcon = if (searchQuery.isNotEmpty()) {
            {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Default.Delete, "Clear search")
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
            if (searchQuery.isEmpty()) "No history yet." else "No results for \"$searchQuery\"",
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
                onCopy = { text ->
                    scope.launch {
                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                },
                onClearSummary = { historyViewModel.clearSummary(item) },
                onDelete = { historyViewModel.deleteHistoryItem(item) },
                onShare = { historyViewModel.prepareShareContent(item) },
                searchTokens = searchTokens,
            )
        }
    }
}

private fun Set<Int>.toggle(id: Int): Set<Int> = if (contains(id)) this - id else this + id

private fun <T : Any> LazyPagingItems<T>.isInitialLoad(): Boolean =
    loadState.refresh is LoadState.Loading && itemCount == 0

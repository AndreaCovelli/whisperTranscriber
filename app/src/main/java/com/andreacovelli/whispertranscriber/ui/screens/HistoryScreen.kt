package com.andreacovelli.whispertranscriber.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    val (showDeleteAllDialog, setShowDeleteAllDialog) = remember { mutableStateOf(false) }

    val expandedIdsSaver = listSaver<Set<Int>, Int>(
        save = { it.toList() },
        restore = { it.toSet() },
    )
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }

    val shareTitle = stringResource(R.string.share_transcription_title)
    val lifecycleOwner = LocalLifecycleOwner.current
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

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { setShowDeleteAllDialog(false) },
            title = { Text("Delete all history?") },
            text = { Text("This will permanently remove all transcription history.") },
            confirmButton = {
                TextButton(onClick = {
                    historyViewModel.deleteAllHistory()
                    setShowDeleteAllDialog(false)
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { setShowDeleteAllDialog(false) }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (pagedItems.itemCount > 0 || searchQuery.isNotEmpty()) {
                        IconButton(onClick = { setShowDeleteAllDialog(true) }) {
                            Icon(Icons.Default.DeleteSweep, "Delete all history")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
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
            if (pagedItems.itemCount > 0 || searchQuery.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { historyViewModel.setSearchQuery(it) },
                    placeholder = { Text("Search transcriptions…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { historyViewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Delete, "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            if (pagedItems.isInitialLoad()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (pagedItems.itemCount == 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (searchQuery.isEmpty()) {
                            "No history yet."
                        } else {
                            "No results for \"$searchQuery\""
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        count = pagedItems.itemCount,
                        key = pagedItems.itemKey { it.id },
                    ) { index ->
                        val item = pagedItems[index]
                        if (item != null) {
                            HistoryItemCard(
                                item = item,
                                expanded = expandedIds.contains(item.id),
                                onToggleExpand = {
                                    @Suppress("UNUSED_VALUE")
                                    expandedIds = if (expandedIds.contains(item.id)) {
                                        expandedIds - item.id
                                    } else {
                                        expandedIds + item.id
                                    }
                                },
                                onCopy = { text ->
                                    scope.launch {
                                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onClearSummary = { historyViewModel.clearSummary(item) },
                                onDelete = { historyViewModel.deleteHistoryItem(item) },
                                onShare = { historyViewModel.prepareShareContent(item) },
                                searchQuery = searchQuery,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun <T : Any> LazyPagingItems<T>.isInitialLoad(): Boolean =
    loadState.refresh is LoadState.Loading && itemCount == 0

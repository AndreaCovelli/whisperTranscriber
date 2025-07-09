package com.andreacovelli.whispertranscriber

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.ui.theme.WhisperTranscriberTheme
import java.text.SimpleDateFormat
import java.util.*

// --- Navigation Routes ---
object AppRoutes {
    const val MAIN = "main"
    const val HISTORY = "history"
}

private fun maskApiKey(apiKey: String): String {
    if (apiKey.length <= 8) {
        return "****" // Return simple asterisks if the key is too short
    }
    val firstChars = apiKey.take(4)
    val lastChars = apiKey.takeLast(4)
    return "$firstChars...$lastChars"
}

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the incoming share intent
        if (intent?.action == Intent.ACTION_SEND) {
            intent.clipData?.getItemAt(0)?.uri?.let { uri ->
                mainViewModel.handleAudioShare(uri)
            }
        }

        setContent {
            WhisperTranscriberTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = AppRoutes.MAIN) {
                    composable(AppRoutes.MAIN) {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToHistory = {
                                navController.navigate(AppRoutes.HISTORY)
                            }
                        )
                    }
                    composable(AppRoutes.HISTORY) {
                        HistoryScreen(
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onNavigateToHistory: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isApiKeyFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Transcriber") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "View Transcription History"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AssemblyAi API Key", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = if (isApiKeyFocused || uiState.apiKey.isEmpty()) {
                    uiState.apiKey
                } else {
                    maskApiKey(uiState.apiKey)
                },
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("Paste your API key here") },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        isApiKeyFocused = focusState.isFocused
                    }
            )

            Button(
                onClick = viewModel::saveApiKey,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Key")
            }

            HorizontalDivider()

            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(uiState.status)

            if (uiState.transcription.isNotEmpty()) {
                val clipboardManager = LocalClipboardManager.current
                val context = LocalContext.current

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Transcription", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(uiState.transcription))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy Transcription"
                        )
                    }
                }
                Text(uiState.transcription)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyViewModel: HistoryViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val historyItems by historyViewModel.historyState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No history yet.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(historyItems, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(item.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            historyViewModel.deleteHistoryItem(item)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val formattedDate = remember(item.timestamp) {
        val sdf = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
        sdf.format(Date(item.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Language: ${item.language}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy text"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete item",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    WhisperTranscriberTheme {
        // This is a simplified preview. The real screen uses a ViewModel.
        MainScreen(viewModel(), onNavigateToHistory = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun HistoryScreenPreview() {
    val items = listOf(
        TranscriptionHistory(1, "This is the first transcription. It is a bit long to see how the text overflows.", "en-US", System.currentTimeMillis() - 100000),
        TranscriptionHistory(2, "This is a second, shorter one.", "en-US", System.currentTimeMillis()),
    )
    WhisperTranscriberTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Transcription History") },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(items) { item ->
                    HistoryItemCard(item = item, onCopy = { }, onDelete = {})
                }
            }
        }
    }
}
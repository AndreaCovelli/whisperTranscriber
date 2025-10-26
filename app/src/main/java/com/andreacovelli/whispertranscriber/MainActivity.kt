package com.andreacovelli.whispertranscriber

import android.net.Uri
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.andreacovelli.whispertranscriber.data.TranscriptionHistory
import com.andreacovelli.whispertranscriber.ui.theme.WhisperTranscriberTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

object AppRoutes {
    const val MAIN = "main"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    private fun extractSharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val single = intent.clipData?.getItemAt(0)?.uri
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                listOfNotNull(single)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val clipUris = intent.clipData?.let { clip ->
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                }.orEmpty()
                val extraUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                (clipUris + extraUris).distinctBy { it.toString() }
            }
            else -> emptyList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = extractSharedUris(intent)
        if (shared.isNotEmpty()) {
            mainViewModel.handleAudioShareMultiple(shared)
        }

        setContent {
            WhisperTranscriberTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = AppRoutes.MAIN) {
                    composable(
                        route = AppRoutes.MAIN,
                        enterTransition = { null },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300)
                            )
                        },
                        popExitTransition = { null }
                    ) {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToHistory = { navController.navigate(AppRoutes.HISTORY) },
                            onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) }
                        )
                    }

                    composable(
                        route = AppRoutes.HISTORY,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300)
                            )
                        }
                    ) {
                        HistoryScreen(onNavigateBack = { navController.popBackStack() })
                    }

                    composable(
                        route = AppRoutes.SETTINGS,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300)
                            )
                        }
                    ) {
                        SettingsScreen(
                            viewModel = mainViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val shared = extractSharedUris(intent)
        if (shared.isNotEmpty()) {
            mainViewModel.handleAudioShareMultiple(shared)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when (uiState.provider) {
                            TranscriptionProvider.ASSEMBLY_AI -> "Using: AssemblyAI"
                            TranscriptionProvider.GEMINI_25_FLASH -> "Using: Gemini 2.5 Flash"
                        }
                    )
                }
            )

            HorizontalDivider()

            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(uiState.status)

            HorizontalDivider()

            if (uiState.jobs.isEmpty()) {
                Text(
                    "Share one or more audio files to start transcribing.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    uiState.jobs.forEach { job ->
                        TranscriptionJobCard(
                            job = job,
                            canSummarize = uiState.geminiApiKey.isNotBlank(),
                            onSummarize = { viewModel.summarizeJob(job.id) },
                            onCopyTranscript = {
                                clipboardManager.setText(AnnotatedString(job.transcript))
                                Toast.makeText(context, "Copied transcript", Toast.LENGTH_SHORT).show()
                            },
                            onCopySummary = {
                                clipboardManager.setText(AnnotatedString(job.summary))
                                Toast.makeText(context, "Copied summary", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionJobCard(
    job: TranscriptionJobState,
    canSummarize: Boolean,
    onSummarize: () -> Unit,
    onCopyTranscript: () -> Unit,
    onCopySummary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            Text(
                text = job.displayName.ifBlank { "Audio file" },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))

            val secondary = buildString {
                if (job.language.isNotBlank()) append("Language: ${job.language}")
                if (job.model.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append("Model: ${job.model}")
                }
            }
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(job.status, style = MaterialTheme.typography.bodySmall)

            if (job.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    job.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Improved Progress Indication
            // Show progress if transcript is empty, no error, AND job is marked as running.
            if (job.transcript.isBlank() && job.error == null && job.isRunning) {
                Spacer(Modifier.height(8.dp))

                // Check if the status indicates a phase where 'progress' is meaningful (Conversion)
                if (job.status.contains("Converting")) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Indeterminate progress for Starting, Uploading, Waiting for API, etc.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (job.transcript.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Transcription", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onCopyTranscript) {
                        Icon(Icons.Filled.ContentCopy, "Copy Transcription")
                    }
                }
                ExpandableText(
                    text = job.transcript,
                    modifier = Modifier.fillMaxWidth(),
                    minimizedMaxLines = 6
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onSummarize,
                        enabled = canSummarize && !job.isSummarizing,
                    ) {
                        Text(if (job.isSummarizing) "Summarizing..." else "Summarize with Gemini")
                    }
                }

                if (job.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Summary", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = onCopySummary) {
                            Icon(Icons.Filled.ContentCopy, "Copy Summary")
                        }
                    }
                    SelectionContainer {
                        Text(job.summary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAssemblyKey by remember { mutableStateOf(false) }
    var showGeminiKey by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                ) { focusManager.clearFocus() },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Transcription Provider", style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.provider,
                onSelect = viewModel::setProvider
            )

            HorizontalDivider()

            Text("AssemblyAI API Key", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.assemblyApiKey,
                onValueChange = viewModel::setAssemblyKey,
                label = { Text("Enter AssemblyAI API key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showAssemblyKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showAssemblyKey = !showAssemblyKey }) {
                        Text(if (showAssemblyKey) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    viewModel.forgetAssemblyKey()
                    scope.launch { snackbarHostState.showSnackbar("AssemblyAI key forgotten") }
                }) {
                    Text("Forget key")
                }
            }

            Text("Gemini API Key", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.geminiApiKey,
                onValueChange = viewModel::setGeminiKey,
                label = { Text("Enter Gemini API key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showGeminiKey = !showGeminiKey }) {
                        Text(if (showGeminiKey) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    viewModel.forgetGeminiKey()
                    scope.launch { snackbarHostState.showSnackbar("Gemini key forgotten") }
                }) {
                    Text("Forget key")
                }
            }
            // Save button removed: settings are auto-saved via ViewModel setters.
        }
    }
}

@Composable
private fun ProviderSelector(
    selected: TranscriptionProvider,
    onSelect: (TranscriptionProvider) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected == TranscriptionProvider.ASSEMBLY_AI,
                    onClick = { onSelect(TranscriptionProvider.ASSEMBLY_AI) },
                    role = Role.RadioButton
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected == TranscriptionProvider.ASSEMBLY_AI,
                onClick = null
            )
            Spacer(Modifier.width(8.dp))
            Text("AssemblyAI")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected == TranscriptionProvider.GEMINI_25_FLASH,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_25_FLASH) },
                    role = Role.RadioButton
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected == TranscriptionProvider.GEMINI_25_FLASH,
                onClick = null
            )
            Spacer(Modifier.width(8.dp))
            Text("Gemini 2.5 Flash")
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // State Preservation: Use rememberSaveable with listSaver for Set<Int>
    val expandedIdsSaver = listSaver<Set<Int>, Int>(
        save = { it.toList() },
        restore = { it.toSet() }
    )
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                        expanded = expandedIds.contains(item.id),
                        onToggleExpand = {
                            expandedIds = if (expandedIds.contains(item.id))
                                expandedIds - item.id else expandedIds + item.id
                        },
                        onCopy = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { historyViewModel.deleteHistoryItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit
) {
    val formattedDate = remember(item.timestamp) {
        DateTimeFormatter.ofPattern("MMM dd, yyyy - HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault()))
    }
    val displayModel = remember(item.model) { item.model.ifBlank { "Unknown" } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onToggleExpand)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )) {
            Text(formattedDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Language: ${item.language}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text("Model: $displayModel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Show more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onCopy(item.text) }) {
                    Icon(Icons.Default.ContentCopy, "Copy text")
                }
                if (item.summary.isNotBlank()) {
                    IconButton(onClick = { onCopy(item.summary) }) {
                        Icon(Icons.Default.ContentCopy, "Copy summary")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete item", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    minimizedMaxLines: Int = 6
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
    ) {
        if (expanded) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = minimizedMaxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    isOverflowing = result.hasVisualOverflow
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isOverflowing) { expanded = true }
            )
        }

        if (isOverflowing) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { expanded = !expanded }
            )
        }
    }
}
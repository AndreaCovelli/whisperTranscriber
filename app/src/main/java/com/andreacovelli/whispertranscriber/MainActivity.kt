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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whisper Transcriber") },
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
        val transcriptionProviderLabel = providerDisplayLabel(
            provider = uiState.provider,
            customModelName = uiState.transcriptionCustomGeminiModelName
        )
        val summarizerProviderLabel = providerDisplayLabel(
            provider = uiState.summarizerProvider,
            customModelName = uiState.summarizerCustomGeminiModelName
        )
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSettings() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Transcription: $transcriptionProviderLabel",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Summarizer: $summarizerProviderLabel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Go to Settings",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Status",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(uiState.status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (uiState.jobs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Share one or more audio files to start transcribing.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val hasCompleted = uiState.jobs.any { !it.isRunning && it.status != "Queued" }
                if (hasCompleted) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.clearCompletedJobs() }) {
                                Text("Clear completed")
                            }
                        }
                    }
                }
                items(uiState.jobs, key = { it.id }) { job ->
                    val shareTranscriptTitle = stringResource(R.string.share_transcription_title)
                    val shareSummaryTitle = stringResource(R.string.share_summary_title)
                    TranscriptionJobCard(
                        job = job,
                        canSummarize = uiState.geminiApiKeyState is ApiKeyState.Present,
                        onSummarize = { viewModel.summarizeJob(job.id) },
                        onClearSummary = { viewModel.clearSummary(job.id) },
                        onClearTranscript = { viewModel.clearTranscription(job.id) },
                        onCopyTranscript = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.transcript))) }
                            Toast.makeText(context, "Copied transcript", Toast.LENGTH_SHORT).show()
                        },
                        onCopySummary = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.summary))) }
                            Toast.makeText(context, "Copied summary", Toast.LENGTH_SHORT).show()
                        },
                        onShareTranscript = {
                            shareText(context, job.transcript, shareTranscriptTitle)
                        },
                        onShareSummary = {
                            shareText(context, job.summary, shareSummaryTitle)
                        },
                        onRetry = { viewModel.retryJob(job.id) },
                        onCancel = { viewModel.cancelJob(job.id) },
                        onDelete = { viewModel.deleteJob(job.id) }
                    )
                }
            }
        }
    }
}

private fun providerDisplayLabel(
    provider: TranscriptionProvider,
    customModelName: String
): String = when (provider) {
    TranscriptionProvider.ELEVEN_LABS -> "ElevenLabs"
    TranscriptionProvider.GEMINI_FLASH_LITE -> "Gemini Flash Lite"
    TranscriptionProvider.GEMINI_FLASH -> "Gemini Flash"
    TranscriptionProvider.GEMINI_PRO -> "Gemini Pro"
    TranscriptionProvider.GEMINI_CUSTOM ->
        if (customModelName.isBlank()) "Custom Gemini model (not set)" else customModelName
}

@Composable
private fun TranscriptionJobCard(
    job: TranscriptionJobState,
    canSummarize: Boolean,
    onSummarize: () -> Unit,
    onClearSummary: () -> Unit,
    onClearTranscript: () -> Unit,
    onCopyTranscript: () -> Unit,
    onCopySummary: () -> Unit,
    onShareTranscript: () -> Unit,
    onShareSummary: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.displayName.ifBlank { "Audio file" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (job.isRunning || job.status == "Queued") {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete job",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = job.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopyTranscript) {
                            Icon(Icons.Filled.ContentCopy, "Copy Transcription")
                        }
                        IconButton(onClick = onShareTranscript) {
                            Icon(Icons.Default.Share, "Share Transcription")
                        }
                        IconButton(onClick = onClearTranscript) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_transcription),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                ExpandableText(
                    text = job.transcript,
                    modifier = Modifier.fillMaxWidth(),
                    minimizedMaxLines = 6
                )
            }

            if (!job.isRunning && job.error == null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = if (job.transcript.isNotBlank()) Modifier.padding(end = 8.dp) else Modifier
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Redo", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Redo")
                    }
                    if (job.transcript.isNotBlank()) {
                        Button(
                            onClick = onSummarize,
                            enabled = canSummarize && !job.isSummarizing,
                        ) {
                            Text(when {
                                job.isSummarizing -> "Summarizing..."
                                job.summary.isNotBlank() -> "Re-summarize"
                                else -> "Summarize with Gemini"
                            })
                        }
                    }
                }
            }

            if (!job.summaryError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Summarization error: ${job.summaryError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (job.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopySummary) {
                            Icon(Icons.Filled.ContentCopy, "Copy Summary")
                        }
                        IconButton(onClick = onShareSummary) {
                            Icon(Icons.Default.Share, "Share Summary")
                        }
                        IconButton(onClick = onClearSummary) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_summary),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                SelectionContainer {
                    Text(job.summary)
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
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

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
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Transcription Provider", style = MaterialTheme.typography.titleMedium)
                    ProviderSelector(
                        selected = uiState.provider,
                        onSelect = viewModel::setProvider,
                        customModelName = uiState.transcriptionCustomGeminiModelName,
                        customModelValidation = uiState.transcriptionCustomModelValidation,
                        onValidateCustomModel = { viewModel.validateCustomGeminiModel(it, isSummarizer = false) },
                        onResetValidation = { viewModel.resetCustomModelValidation(isSummarizer = false) }
                    )
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Summarizer Model", style = MaterialTheme.typography.titleMedium)
                    ProviderSelector(
                        selected = uiState.summarizerProvider,
                        onSelect = viewModel::setSummarizerProvider,
                        customModelName = uiState.summarizerCustomGeminiModelName,
                        customModelValidation = uiState.summarizerCustomModelValidation,
                        onValidateCustomModel = { viewModel.validateCustomGeminiModel(it, isSummarizer = true) },
                        onResetValidation = { viewModel.resetCustomModelValidation(isSummarizer = true) },
                        showElevenLabs = false
                    )
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Automatic Summarization", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Summarize completed transcriptions automatically",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = uiState.automaticSummarizationEnabled,
                            onCheckedChange = { viewModel.setAutomaticSummarization(it) }
                        )
                    }
                }
            }

            ApiKeyCard(
                title = "ElevenLabs API Key",
                label = "Enter ElevenLabs API key",
                apiKeyState = uiState.elevenLabsApiKeyState,
                keyValidationState = uiState.elevenLabsKeyValidation,
                onLoadSavedKey = viewModel::loadElevenLabsKeyForDisplay,
                onSave = viewModel::setElevenLabsKey,
                onForget = viewModel::forgetElevenLabsKey,
                onResetValidation = viewModel::resetElevenLabsKeyValidation,
                forgetMessage = "ElevenLabs key forgotten",
                snackbarHostState = snackbarHostState
            )

            ApiKeyCard(
                title = "Gemini API Key",
                label = "Enter Gemini API key",
                apiKeyState = uiState.geminiApiKeyState,
                keyValidationState = uiState.geminiKeyValidation,
                onLoadSavedKey = viewModel::loadGeminiKeyForDisplay,
                onSave = viewModel::setGeminiKey,
                onForget = viewModel::forgetGeminiKey,
                onResetValidation = viewModel::resetGeminiKeyValidation,
                forgetMessage = "Gemini key forgotten",
                snackbarHostState = snackbarHostState
            )

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("App Info", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.app_version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(
    title: String,
    label: String,
    apiKeyState: ApiKeyState,
    keyValidationState: KeyValidationState,
    onLoadSavedKey: suspend () -> String?,
    onSave: (String) -> Unit,
    onForget: () -> Unit,
    onResetValidation: () -> Unit,
    forgetMessage: String,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var inputKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var revealedSavedKey by remember { mutableStateOf<String?>(null) }
    var revealedWithoutEdit by remember { mutableStateOf(false) }
    val fieldValue = inputKey.ifBlank { revealedSavedKey.orEmpty() }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    inputKey = it
                    if (revealedWithoutEdit) {
                        revealedSavedKey = null
                        revealedWithoutEdit = false
                    }
                    onResetValidation()
                },
                label = { Text(if (apiKeyState is ApiKeyState.Present) "Replace saved key (optional)" else label) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                    if (!focusState.isFocused && inputKey.isNotBlank()) onSave(inputKey)
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            if (showKey) {
                                showKey = false
                                if (revealedWithoutEdit) {
                                    revealedSavedKey = null
                                    revealedWithoutEdit = false
                                }
                            } else {
                                if (inputKey.isBlank() && apiKeyState is ApiKeyState.Present) {
                                    revealedSavedKey = onLoadSavedKey()
                                    revealedWithoutEdit = revealedSavedKey != null
                                }
                                showKey = true
                            }
                        }
                    }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide API key" else "Show API key"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            
            when (keyValidationState) {
                is KeyValidationState.Validating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Validating API key...", style = MaterialTheme.typography.labelSmall)
                    }
                }
                is KeyValidationState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            keyValidationState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is KeyValidationState.Idle -> {
                    if (apiKeyState is ApiKeyState.Present) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Encrypted & saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (apiKeyState is ApiKeyState.Error) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Keystore Error: ${apiKeyState.message}. Please re-enter your key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    onForget()
                    inputKey = ""
                    revealedSavedKey = null
                    revealedWithoutEdit = false
                    showKey = false
                    scope.launch { snackbarHostState.showSnackbar(forgetMessage) }
                }) {
                    Text("Forget key")
                }
            }
        }
    }
}

// Updated ProviderSelector to include all options in a flat list
@Composable
private fun ProviderSelector(
    selected: TranscriptionProvider,
    onSelect: (TranscriptionProvider) -> Unit,
    customModelName: String,
    customModelValidation: CustomModelValidation,
    onValidateCustomModel: (String) -> Unit,
    onResetValidation: () -> Unit,
    showElevenLabs: Boolean = true
) {
    var customInput by remember(customModelName) { mutableStateOf(customModelName) }
    var fieldHadFocus by remember(customModelName) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showElevenLabs) {
            ProviderRadioOption(
                text = "ElevenLabs",
                isSelected = selected == TranscriptionProvider.ELEVEN_LABS,
                onClick = { onSelect(TranscriptionProvider.ELEVEN_LABS) }
            )

            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Gemini Models",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column {
                ProviderRadioOption(
                    text = "Gemini Flash Lite",
                    isSelected = selected == TranscriptionProvider.GEMINI_FLASH_LITE,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_FLASH_LITE) }
                )
                ProviderRadioOption(
                    text = "Gemini Flash",
                    isSelected = selected == TranscriptionProvider.GEMINI_FLASH,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_FLASH) }
                )
                ProviderRadioOption(
                    text = "Gemini Pro",
                    isSelected = selected == TranscriptionProvider.GEMINI_PRO,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_PRO) }
                )
                ProviderRadioOption(
                    text = "Custom model",
                    isSelected = selected == TranscriptionProvider.GEMINI_CUSTOM,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_CUSTOM) }
                )
                if (selected == TranscriptionProvider.GEMINI_CUSTOM) {
                    Column(
                        modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = {
                                customInput = it
                                onResetValidation()
                            },
                            placeholder = { Text("e.g. gemini-2.0-flash") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        fieldHadFocus = true
                                    } else if (fieldHadFocus && customInput.isNotBlank()) {
                                        onValidateCustomModel(customInput)
                                    }
                                },
                            singleLine = true,
                            isError = customModelValidation is CustomModelValidation.Error
                        )
                        when (customModelValidation) {
                            is CustomModelValidation.Validating -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text("Validating…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            is CustomModelValidation.Valid -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        customModelValidation.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            is CustomModelValidation.Error -> {
                                Text(
                                    customModelValidation.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

// Helper Composable for Radio Options
@Composable
private fun ProviderRadioOption(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null // null because the Row is selectable
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyViewModel: HistoryViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val pagedItems = historyViewModel.pagedHistoryItems.collectAsLazyPagingItems()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val searchQuery by historyViewModel.searchQuery.collectAsStateWithLifecycle()
    val (showDeleteAllDialog, setShowDeleteAllDialog) = remember { mutableStateOf(false) }

    val expandedIdsSaver = listSaver<Set<Int>, Int>(
        save = { it.toList() },
        restore = { it.toSet() }
    )
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }

    val shareTitle = stringResource(R.string.share_transcription_title)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(historyViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            }
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
                }
            )
        }
    ) { paddingValues ->
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusManager.clearFocus() }
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
                    } else null
                )
            }
            if (pagedItems.itemCount == 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (searchQuery.isEmpty()) "No history yet."
                        else "No results for \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        count = pagedItems.itemCount,
                        key = pagedItems.itemKey { it.id }
                    ) { index ->
                        val item = pagedItems[index]
                        if (item != null) {
                            HistoryItemCard(
                                item = item,
                                expanded = expandedIds.contains(item.id),
                                onToggleExpand = {
                                    @Suppress("UNUSED_VALUE")
                                    expandedIds = if (expandedIds.contains(item.id))
                                        expandedIds - item.id else expandedIds + item.id
                                },
                                onCopy = { text ->
                                    scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                onClearSummary = { historyViewModel.clearSummary(item) },
                                onDelete = { historyViewModel.deleteHistoryItem(item) },
                                onShare = { historyViewModel.prepareShareContent(item) },
                                searchQuery = searchQuery
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    background: Color,
    onBackground: Color
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val idx = text.indexOf(query, startIndex = cursor, ignoreCase = true)
            if (idx == -1) {
                append(text, cursor, text.length)
                break
            }
            if (idx > cursor) {
                append(text, cursor, idx)
            }
            withStyle(SpanStyle(background = background, color = onBackground)) {
                append(text, idx, idx + query.length)
            }
            cursor = idx + query.length
        }
    }
}

@Composable
private fun rememberHighlightedText(text: String, query: String): AnnotatedString {
    val background = MaterialTheme.colorScheme.primaryContainer
    val onBackground = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, query, background, onBackground) { buildHighlightedText(text, query, background, onBackground) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: (String) -> Unit,
    onClearSummary: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    searchQuery: String = ""
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
            if (item.displayName.isNotBlank()) {
                Text(rememberHighlightedText(item.displayName, searchQuery), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
            }
            Text(formattedDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Language: ${item.language}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text("Model: $displayModel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                Text(rememberHighlightedText(item.text, searchQuery), style = MaterialTheme.typography.bodyMedium)
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Summary", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(rememberHighlightedText(item.summary, searchQuery), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(rememberHighlightedText(item.text, searchQuery), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (item.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rememberHighlightedText(item.summary, searchQuery),
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val copyActions = buildList {
                    add("text" to item.text)
                    if (item.summary.isNotBlank()) add("summary" to item.summary)
                }
                copyActions.forEach { (type, content) ->
                    TextButton(onClick = { onCopy(content) }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.copy_type, type), maxLines = 1, softWrap = false)
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, stringResource(R.string.share_item_description))
                }
                if (item.summary.isNotBlank()) {
                    IconButton(onClick = onClearSummary) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.clear_summary),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete_item_description), tint = MaterialTheme.colorScheme.error)
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
            .then(if (!expanded && isOverflowing) Modifier.clickable { expanded = true } else Modifier)
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
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (expanded || isOverflowing) {
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

private fun shareText(context: android.content.Context, text: String, title: String? = null) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, title))
}

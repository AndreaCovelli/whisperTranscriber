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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        val providerLabel = when (uiState.provider) {
            TranscriptionProvider.ASSEMBLY_AI -> "Using: AssemblyAI"
            TranscriptionProvider.GEMINI_FLASH_LITE -> "Using: Gemini Flash Lite"
            TranscriptionProvider.GEMINI_FLASH -> "Using: Gemini Flash"
            TranscriptionProvider.GEMINI_PRO -> "Using: Gemini Pro"
            TranscriptionProvider.GEMINI_CUSTOM -> {
                val name = uiState.customGeminiModelName
                if (name.isBlank()) "Using: Custom Gemini model (not set)" else "Using: $name"
            }
        }
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
                        Text(
                            providerLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
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
                val hasCompleted = uiState.jobs.any { !it.isRunning && (it.transcript.isNotBlank() || it.error != null) }
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
                    TranscriptionJobCard(
                        job = job,
                        canSummarize = uiState.geminiApiKeyState is ApiKeyState.Present,
                        onSummarize = { viewModel.summarizeJob(job.id) },
                        onClearSummary = { viewModel.clearSummary(job.id) },
                        onCopyTranscript = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.transcript))) }
                            Toast.makeText(context, "Copied transcript", Toast.LENGTH_SHORT).show()
                        },
                        onCopySummary = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", job.summary))) }
                            Toast.makeText(context, "Copied summary", Toast.LENGTH_SHORT).show()
                        },
                        onShareTranscript = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, job.transcript)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        },
                        onRetry = { viewModel.retryJob(job.id) },
                        onCancel = { viewModel.cancelJob(job.id) }
                    )
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
    onClearSummary: () -> Unit,
    onCopyTranscript: () -> Unit,
    onCopySummary: () -> Unit,
    onShareTranscript: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
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
                if (job.transcript.isBlank() && job.error == null && (job.isRunning || job.status == "Queued")) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
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
                    Row {
                        IconButton(onClick = onShareTranscript) {
                            Icon(Icons.Default.Share, "Share Transcription")
                        }
                        IconButton(onClick = onCopyTranscript) {
                            Icon(Icons.Filled.ContentCopy, "Copy Transcription")
                        }
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
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Redo", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Redo")
                    }
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

                if (job.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Summary", style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = onClearSummary) {
                                Text("Clear")
                            }
                            IconButton(onClick = onCopySummary) {
                                Icon(Icons.Filled.ContentCopy, "Copy Summary")
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
                        customModelName = uiState.customGeminiModelName,
                        customModelValidation = uiState.customModelValidation,
                        onValidateCustomModel = viewModel::validateCustomGeminiModel,
                        onResetValidation = viewModel::resetCustomModelValidation
                    )
                }
            }

            ApiKeyCard(
                title = "AssemblyAI API Key",
                label = "Enter AssemblyAI API key",
                apiKeyState = uiState.assemblyApiKeyState,
                keyValidationState = uiState.assemblyKeyValidation,
                onSave = viewModel::setAssemblyKey,
                onForget = viewModel::forgetAssemblyKey,
                onResetValidation = viewModel::resetAssemblyKeyValidation,
                forgetMessage = "AssemblyAI key forgotten",
                snackbarHostState = snackbarHostState
            )

            ApiKeyCard(
                title = "Gemini API Key",
                label = "Enter Gemini API key",
                apiKeyState = uiState.geminiApiKeyState,
                keyValidationState = uiState.geminiKeyValidation,
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
    onSave: (String) -> Unit,
    onForget: () -> Unit,
    onResetValidation: () -> Unit,
    forgetMessage: String,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var inputKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = inputKey,
                onValueChange = {
                    inputKey = it
                    onResetValidation()
                },
                label = { Text(if (apiKeyState is ApiKeyState.Present) "Replace saved key (optional)" else label) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focusState ->
                    if (!focusState.isFocused && inputKey.isNotBlank()) onSave(inputKey)
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
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
    onResetValidation: () -> Unit
) {
    var customInput by remember(customModelName) { mutableStateOf(customModelName) }
    var fieldHadFocus by remember(customModelName) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProviderRadioOption(
            text = "AssemblyAI",
            isSelected = selected == TranscriptionProvider.ASSEMBLY_AI,
            onClick = { onSelect(TranscriptionProvider.ASSEMBLY_AI) }
        )

        Spacer(Modifier.height(8.dp))
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
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val expandedIdsSaver = listSaver<Set<Int>, Int>(
        save = { it.toList() },
        restore = { it.toSet() }
    )
    var expandedIds by rememberSaveable(stateSaver = expandedIdsSaver) { mutableStateOf(emptySet()) }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all history?") },
            text = { Text("This will permanently remove all transcription history.") },
            confirmButton = {
                TextButton(onClick = {
                    historyViewModel.deleteAllHistory()
                    showDeleteAllDialog = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
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
                        IconButton(onClick = { showDeleteAllDialog = true }) {
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

@Composable
fun HistoryItemCard(
    item: TranscriptionHistory,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: (String) -> Unit,
    onClearSummary: () -> Unit,
    onDelete: () -> Unit,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Summary", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = onClearSummary) {
                            Text("Clear")
                        }
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
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
                        Text("Copy $type")
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
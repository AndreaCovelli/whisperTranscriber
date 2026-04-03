package com.andreacovelli.whispertranscriber.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreacovelli.whispertranscriber.BuildConfig
import com.andreacovelli.whispertranscriber.MainViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.ui.components.ApiKeyCard
import com.andreacovelli.whispertranscriber.ui.components.ProviderSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = viewModel.settingsScreenActions
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
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusManager.clearFocus() },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TranscriptionProviderCard(uiState = uiState, actions = actions)
            SpeakerDiarizationCard(uiState = uiState, actions = actions)
            SummarizerProviderCard(uiState = uiState, actions = actions)
            AutomaticSummarizationCard(uiState = uiState, actions = actions)

            ApiKeyCard(
                title = "ElevenLabs API Key",
                label = "Enter ElevenLabs API key",
                apiKeyState = uiState.elevenLabsApiKeyState,
                keyValidationState = uiState.elevenLabsKeyValidation,
                onLoadSavedKey = viewModel::loadElevenLabsKeyForDisplay,
                onSave = actions.saveElevenLabsKey,
                onForget = actions.forgetElevenLabsKey,
                onResetValidation = actions.resetElevenLabsKeyValidation,
                forgetMessage = "ElevenLabs key forgotten",
                snackbarHostState = snackbarHostState,
            )

            ApiKeyCard(
                title = "Gemini API Key",
                label = "Enter Gemini API key",
                apiKeyState = uiState.geminiApiKeyState,
                keyValidationState = uiState.geminiKeyValidation,
                onLoadSavedKey = viewModel::loadGeminiKeyForDisplay,
                onSave = actions.saveGeminiKey,
                onForget = actions.forgetGeminiKey,
                onResetValidation = actions.resetGeminiKeyValidation,
                forgetMessage = "Gemini key forgotten",
                snackbarHostState = snackbarHostState,
            )

            AppInfoCard()
        }
    }
}

@Composable
private fun TranscriptionProviderCard(
    uiState: com.andreacovelli.whispertranscriber.UiState,
    actions: com.andreacovelli.whispertranscriber.SettingsScreenActions,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transcription Provider", style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.provider,
                onSelect = actions.setProvider,
                customModelName = uiState.transcriptionCustomGeminiModelName,
                customModelValidation = uiState.transcriptionCustomModelValidation,
                onValidateCustomModel = actions.validateTranscriptionModel,
                onResetValidation = actions.resetTranscriptionModelValidation,
            )
        }
    }
}

@Composable
private fun SpeakerDiarizationCard(
    uiState: com.andreacovelli.whispertranscriber.UiState,
    actions: com.andreacovelli.whispertranscriber.SettingsScreenActions,
) {
    SettingsToggleCard(
        title = "Speaker Diarization",
        body = "Label speakers in transcriptions when the provider can distinguish them",
        checked = uiState.speakerDiarizationEnabled,
        onCheckedChange = actions.setSpeakerDiarization,
    )
}

@Composable
private fun SummarizerProviderCard(
    uiState: com.andreacovelli.whispertranscriber.UiState,
    actions: com.andreacovelli.whispertranscriber.SettingsScreenActions,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Summarizer Model", style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.summarizerProvider,
                onSelect = actions.setSummarizerProvider,
                customModelName = uiState.summarizerCustomGeminiModelName,
                customModelValidation = uiState.summarizerCustomModelValidation,
                onValidateCustomModel = actions.validateSummarizerModel,
                onResetValidation = actions.resetSummarizerModelValidation,
                showElevenLabs = false,
            )
        }
    }
}

@Composable
private fun AutomaticSummarizationCard(
    uiState: com.andreacovelli.whispertranscriber.UiState,
    actions: com.andreacovelli.whispertranscriber.SettingsScreenActions,
) {
    SettingsToggleCard(
        title = "Automatic Summarization",
        body = "Summarize completed transcriptions automatically",
        checked = uiState.automaticSummarizationEnabled,
        onCheckedChange = actions.setAutomaticSummarization,
    )
}

@Composable
private fun SettingsToggleCard(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun AppInfoCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("App Info", style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.app_version_info,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

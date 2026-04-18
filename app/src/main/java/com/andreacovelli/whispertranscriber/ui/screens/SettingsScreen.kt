package com.andreacovelli.whispertranscriber.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreacovelli.whispertranscriber.BuildConfig
import com.andreacovelli.whispertranscriber.MainViewModel
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.RegionValidationState
import com.andreacovelli.whispertranscriber.SettingsScreenActions
import com.andreacovelli.whispertranscriber.UiState
import com.andreacovelli.whispertranscriber.ui.components.ApiKeyCard
import com.andreacovelli.whispertranscriber.ui.components.ProviderSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onBackgroundShareSummaryNotificationsChange: (Boolean) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = viewModel.settingsScreenActions
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
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
            BackgroundShareSummaryNotificationsCard(
                uiState = uiState,
                onCheckedChange = onBackgroundShareSummaryNotificationsChange,
            )

            ApiKeyCard(
                title = stringResource(R.string.settings_microsoft_speech_api_key_title),
                label = stringResource(R.string.settings_microsoft_speech_api_key_label),
                apiKeyState = uiState.microsoftSpeechApiKeyState,
                keyValidationState = uiState.microsoftSpeechKeyValidation,
                onLoadSavedKey = actions.loadMicrosoftSpeechKey,
                onSave = actions.saveMicrosoftSpeechKey,
                onForget = actions.forgetMicrosoftSpeechKey,
                onResetValidation = actions.resetMicrosoftSpeechKeyValidation,
                forgetMessage = stringResource(R.string.settings_microsoft_speech_key_forgotten),
                snackbarHostState = snackbarHostState,
            )

            MicrosoftSpeechRegionCard(uiState = uiState, actions = actions)

            ApiKeyCard(
                title = stringResource(R.string.settings_gemini_api_key_title),
                label = stringResource(R.string.settings_gemini_api_key_label),
                apiKeyState = uiState.geminiApiKeyState,
                keyValidationState = uiState.geminiKeyValidation,
                onLoadSavedKey = actions.loadGeminiKey,
                onSave = actions.saveGeminiKey,
                onForget = actions.forgetGeminiKey,
                onResetValidation = actions.resetGeminiKeyValidation,
                forgetMessage = stringResource(R.string.settings_gemini_key_forgotten),
                snackbarHostState = snackbarHostState,
            )

            AppInfoCard()
        }
    }
}

@Composable
private fun TranscriptionProviderCard(uiState: UiState, actions: SettingsScreenActions) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_transcription_provider), style = MaterialTheme.typography.titleMedium)
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
private fun SpeakerDiarizationCard(uiState: UiState, actions: SettingsScreenActions) {
    val diarizationSupported = uiState.provider.isGemini()
    SettingsToggleCard(
        title = stringResource(R.string.settings_speaker_diarization),
        body = if (diarizationSupported) {
            stringResource(R.string.settings_speaker_diarization_body)
        } else {
            stringResource(R.string.settings_speaker_diarization_body_microsoft)
        },
        checked = uiState.speakerDiarizationEnabled,
        onCheckedChange = actions.setSpeakerDiarization,
        enabled = diarizationSupported,
    )
}

@Composable
private fun MicrosoftSpeechRegionCard(uiState: UiState, actions: SettingsScreenActions) {
    var regionInput by remember(uiState.microsoftSpeechRegion) { mutableStateOf(uiState.microsoftSpeechRegion) }
    var fieldHadFocus by remember(uiState.microsoftSpeechRegion) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_microsoft_speech_region_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_microsoft_speech_region_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = regionInput,
                onValueChange = {
                    regionInput = it
                    actions.resetMicrosoftSpeechRegionValidation()
                },
                label = {
                    Text(stringResource(R.string.settings_microsoft_speech_region_label))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            fieldHadFocus = true
                        } else if (fieldHadFocus && regionInput != uiState.microsoftSpeechRegion) {
                            actions.setMicrosoftSpeechRegion(regionInput)
                        }
                    },
                singleLine = true,
                isError = uiState.microsoftSpeechRegionValidation is RegionValidationState.Error,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            when (val validation = uiState.microsoftSpeechRegionValidation) {
                is RegionValidationState.Validating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(14.dp).width(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.settings_microsoft_speech_region_validating),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                is RegionValidationState.Valid -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.height(14.dp).width(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.settings_microsoft_speech_region_valid, validation.region),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is RegionValidationState.Error -> {
                    Text(
                        validation.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SummarizerProviderCard(uiState: UiState, actions: SettingsScreenActions) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_summarizer_model), style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.summarizerProvider,
                onSelect = actions.setSummarizerProvider,
                customModelName = uiState.summarizerCustomGeminiModelName,
                customModelValidation = uiState.summarizerCustomModelValidation,
                onValidateCustomModel = actions.validateSummarizerModel,
                onResetValidation = actions.resetSummarizerModelValidation,
                showMicrosoftSpeech = false,
            )
        }
    }
}

@Composable
private fun AutomaticSummarizationCard(uiState: UiState, actions: SettingsScreenActions) {
    SettingsToggleCard(
        title = stringResource(R.string.settings_automatic_summarization),
        body = stringResource(R.string.settings_automatic_summarization_body),
        checked = uiState.automaticSummarizationEnabled,
        onCheckedChange = actions.setAutomaticSummarization,
    )
}

@Composable
private fun BackgroundShareSummaryNotificationsCard(uiState: UiState, onCheckedChange: (Boolean) -> Unit) {
    SettingsToggleCard(
        title = stringResource(R.string.settings_background_share_summary_notifications),
        body = stringResource(R.string.settings_background_share_summary_notifications_body),
        checked = uiState.backgroundShareSummaryNotificationsEnabled,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun SettingsToggleCard(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
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
                    enabled = enabled,
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
            Text(stringResource(R.string.settings_app_info), style = MaterialTheme.typography.titleMedium)
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

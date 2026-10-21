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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andreacovelli.whispertranscriber.BuildConfig
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.summary.MAX_SUMMARY_CUSTOM_PROMPT_LENGTH
import com.andreacovelli.whispertranscriber.summary.SummaryStyle
import com.andreacovelli.whispertranscriber.transcription.MAX_VOCABULARY_TERMS
import com.andreacovelli.whispertranscriber.transcription.TRANSCRIPTION_LANGUAGE_CODES
import com.andreacovelli.whispertranscriber.ui.asString
import com.andreacovelli.whispertranscriber.ui.components.ApiKeyCard
import com.andreacovelli.whispertranscriber.ui.components.ProviderSelector
import com.andreacovelli.whispertranscriber.ui.components.summaryStyleLabel
import com.andreacovelli.whispertranscriber.ui.settings.RegionValidationState
import com.andreacovelli.whispertranscriber.ui.settings.SettingsMsg
import com.andreacovelli.whispertranscriber.ui.settings.SettingsUiState
import com.andreacovelli.whispertranscriber.ui.settings.SettingsViewModel
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onBackgroundShareSummaryNotificationsChange: (Boolean) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreenContent(
        uiState = uiState,
        dispatch = viewModel::dispatch,
        onLoadMicrosoftSpeechKey = viewModel::loadMicrosoftSpeechKeyForDisplay,
        onLoadGeminiKey = viewModel::loadGeminiKeyForDisplay,
        onNavigateBack = onNavigateBack,
        onBackgroundShareSummaryNotificationsChange = onBackgroundShareSummaryNotificationsChange,
    )
}

/** Stateless settings layout, driven by [uiState] and reporting user actions through [dispatch]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    uiState: SettingsUiState,
    dispatch: (SettingsMsg) -> Unit,
    onLoadMicrosoftSpeechKey: suspend () -> String?,
    onLoadGeminiKey: suspend () -> String?,
    onNavigateBack: () -> Unit,
    onBackgroundShareSummaryNotificationsChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
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
            SettingsSectionHeader(stringResource(R.string.settings_section_general))
            TranscriptionProviderCard(uiState = uiState, dispatch = dispatch)
            SpeakerDiarizationCard(uiState = uiState, dispatch = dispatch)
            TranscriptionHintsCard(uiState = uiState, dispatch = dispatch)
            SummarizerProviderCard(uiState = uiState, dispatch = dispatch)
            SummaryStyleCard(uiState = uiState, dispatch = dispatch)
            BackgroundShareSummaryNotificationsCard(
                uiState = uiState,
                onCheckedChange = onBackgroundShareSummaryNotificationsChange,
            )
            AutomaticSummarizationCard(uiState = uiState, dispatch = dispatch)

            SettingsSectionHeader(stringResource(R.string.settings_section_microsoft))
            ApiKeyCard(
                title = stringResource(R.string.settings_microsoft_speech_api_key_title),
                label = stringResource(R.string.settings_microsoft_speech_api_key_label),
                apiKeyState = uiState.microsoftSpeechApiKeyState,
                keyValidationState = uiState.microsoftSpeechKeyValidation,
                onLoadSavedKey = onLoadMicrosoftSpeechKey,
                onSave = { dispatch(SettingsMsg.SaveMicrosoftSpeechKey(it)) },
                onForget = { dispatch(SettingsMsg.ForgetMicrosoftSpeechKey) },
                onResetValidation = { dispatch(SettingsMsg.ResetMicrosoftSpeechKeyValidation) },
            )
            MicrosoftSpeechRegionCard(uiState = uiState, dispatch = dispatch)

            SettingsSectionHeader(stringResource(R.string.settings_section_gemini))
            ApiKeyCard(
                title = stringResource(R.string.settings_gemini_api_key_title),
                label = stringResource(R.string.settings_gemini_api_key_label),
                apiKeyState = uiState.geminiApiKeyState,
                keyValidationState = uiState.geminiKeyValidation,
                onLoadSavedKey = onLoadGeminiKey,
                onSave = { dispatch(SettingsMsg.SaveGeminiKey(it)) },
                onForget = { dispatch(SettingsMsg.ForgetGeminiKey) },
                onResetValidation = { dispatch(SettingsMsg.ResetGeminiKeyValidation) },
            )

            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            AppInfoCard()
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun TranscriptionProviderCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_transcription_provider), style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.provider,
                onSelect = { dispatch(SettingsMsg.SetProvider(it)) },
                customModelName = uiState.transcriptionCustomGeminiModelName,
                customModelValidation = uiState.transcriptionCustomModelValidation,
                onValidateCustomModel = { dispatch(SettingsMsg.ValidateTranscriptionModel(it)) },
                onResetValidation = { dispatch(SettingsMsg.ResetTranscriptionModelValidation) },
            )
        }
    }
}

@Composable
private fun SpeakerDiarizationCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    SettingsToggleCard(
        title = stringResource(R.string.settings_speaker_diarization),
        body = stringResource(R.string.settings_speaker_diarization_body),
        checked = uiState.speakerDiarizationEnabled,
        onCheckedChange = { dispatch(SettingsMsg.SetSpeakerDiarization(it)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionHintsCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var vocabularyInput by remember(uiState.transcriptionVocabulary) {
        mutableStateOf(uiState.transcriptionVocabulary)
    }
    var vocabularyHadFocus by remember(uiState.transcriptionVocabulary) { mutableStateOf(false) }
    val automaticLabel = stringResource(R.string.settings_transcription_language_automatic)
    val displayLocale = LocalConfiguration.current.locales[0]
    val languages = remember(displayLocale) {
        TRANSCRIPTION_LANGUAGE_CODES
            .map { code -> code to Locale.forLanguageTag(code).getDisplayName(displayLocale) }
            .sortedBy { (_, name) -> name.lowercase(displayLocale) }
    }
    val selectedLabel = languages.firstOrNull { (code, _) -> code == uiState.transcriptionLanguage }?.second
        ?: automaticLabel

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_transcription_hints_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_transcription_language_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExposedDropdownMenuBox(
                expanded = languageMenuExpanded,
                onExpandedChange = { languageMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_transcription_language_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    (listOf("" to automaticLabel) + languages).forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                languageMenuExpanded = false
                                if (code != uiState.transcriptionLanguage) {
                                    dispatch(SettingsMsg.SetTranscriptionLanguage(code))
                                }
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = vocabularyInput,
                onValueChange = { vocabularyInput = it },
                label = { Text(stringResource(R.string.settings_transcription_vocabulary_label)) },
                supportingText = {
                    Text(stringResource(R.string.settings_transcription_vocabulary_supporting, MAX_VOCABULARY_TERMS))
                },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            vocabularyHadFocus = true
                        } else if (vocabularyHadFocus && vocabularyInput.trim() != uiState.transcriptionVocabulary) {
                            dispatch(SettingsMsg.SetTranscriptionVocabulary(vocabularyInput))
                        }
                    },
            )
        }
    }
}

@Composable
private fun MicrosoftSpeechRegionCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
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
                    dispatch(SettingsMsg.ResetMicrosoftSpeechRegionValidation)
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
                            dispatch(SettingsMsg.SetMicrosoftSpeechRegion(regionInput))
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
                            painterResource(R.drawable.ic_check),
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
                        validation.message.asString(),
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
private fun SummarizerProviderCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_summarizer_model), style = MaterialTheme.typography.titleMedium)
            ProviderSelector(
                selected = uiState.summarizerProvider,
                onSelect = { dispatch(SettingsMsg.SetSummarizerProvider(it)) },
                customModelName = uiState.summarizerCustomGeminiModelName,
                customModelValidation = uiState.summarizerCustomModelValidation,
                onValidateCustomModel = { dispatch(SettingsMsg.ValidateSummarizerModel(it)) },
                onResetValidation = { dispatch(SettingsMsg.ResetSummarizerModelValidation) },
                showMicrosoftSpeech = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryStyleCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    var styleMenuExpanded by remember { mutableStateOf(false) }
    var promptInput by remember(uiState.summaryCustomPrompt) { mutableStateOf(uiState.summaryCustomPrompt) }
    var promptHadFocus by remember(uiState.summaryCustomPrompt) { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_summary_style_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_summary_style_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExposedDropdownMenuBox(
                expanded = styleMenuExpanded,
                onExpandedChange = { styleMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = stringResource(summaryStyleLabel(uiState.summaryStyle)),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_summary_style_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = styleMenuExpanded,
                    onDismissRequest = { styleMenuExpanded = false },
                ) {
                    SummaryStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(stringResource(summaryStyleLabel(style))) },
                            onClick = {
                                styleMenuExpanded = false
                                if (style != uiState.summaryStyle) dispatch(SettingsMsg.SetSummaryStyle(style))
                            },
                        )
                    }
                }
            }
            if (uiState.summaryStyle == SummaryStyle.CUSTOM) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it.take(MAX_SUMMARY_CUSTOM_PROMPT_LENGTH) },
                    label = { Text(stringResource(R.string.settings_summary_custom_prompt_label)) },
                    supportingText = { Text(stringResource(R.string.settings_summary_custom_prompt_supporting)) },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                promptHadFocus = true
                            } else if (promptHadFocus && promptInput.trim() != uiState.summaryCustomPrompt) {
                                dispatch(SettingsMsg.SetSummaryCustomPrompt(promptInput))
                            }
                        },
                )
                if (uiState.summaryCustomPrompt.isBlank()) {
                    Text(
                        text = stringResource(R.string.settings_summary_custom_prompt_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomaticSummarizationCard(uiState: SettingsUiState, dispatch: (SettingsMsg) -> Unit) {
    SettingsToggleCard(
        title = stringResource(R.string.settings_automatic_summarization),
        body = stringResource(R.string.settings_automatic_summarization_body),
        checked = uiState.automaticSummarizationEnabled,
        onCheckedChange = { dispatch(SettingsMsg.SetAutomaticSummarization(it)) },
    )
}

@Composable
private fun BackgroundShareSummaryNotificationsCard(uiState: SettingsUiState, onCheckedChange: (Boolean) -> Unit) {
    SettingsToggleCard(
        title = stringResource(R.string.settings_background_share_summary_notifications),
        body = stringResource(R.string.settings_background_share_summary_notifications_body),
        checked = uiState.backgroundShareSummaryNotificationsEnabled,
        onCheckedChange = onCheckedChange,
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
                    modifier = Modifier.semantics { contentDescription = title },
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

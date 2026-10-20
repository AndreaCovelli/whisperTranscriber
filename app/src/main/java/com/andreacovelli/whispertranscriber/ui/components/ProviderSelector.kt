package com.andreacovelli.whispertranscriber.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.ui.main.CustomModelValidation

@Composable
fun ProviderSelector(
    selected: TranscriptionProvider,
    onSelect: (TranscriptionProvider) -> Unit,
    customModelName: String,
    customModelValidation: CustomModelValidation,
    onValidateCustomModel: (String) -> Unit,
    onResetValidation: () -> Unit,
    showMicrosoftSpeech: Boolean = true,
) {
    var customInput by remember(customModelName) { mutableStateOf(customModelName) }
    var fieldHadFocus by remember(customModelName) { mutableStateOf(false) }

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        if (showMicrosoftSpeech) {
            Text(
                stringResource(R.string.provider_group_microsoft),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                ProviderRadioOption(
                    text = stringResource(R.string.provider_option_microsoft_speech),
                    isSelected = selected == TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
                    onClick = { onSelect(TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE) },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
        Text(
            stringResource(R.string.provider_group_gemini_models),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column {
                ProviderRadioOption(
                    text = stringResource(R.string.provider_option_flash_lite),
                    isSelected = selected == TranscriptionProvider.GEMINI_FLASH_LITE,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_FLASH_LITE) },
                )
                ProviderRadioOption(
                    text = stringResource(R.string.provider_option_flash),
                    isSelected = selected == TranscriptionProvider.GEMINI_FLASH,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_FLASH) },
                )
                ProviderRadioOption(
                    text = stringResource(R.string.provider_option_pro),
                    isSelected = selected == TranscriptionProvider.GEMINI_PRO,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_PRO) },
                )
                ProviderRadioOption(
                    text = stringResource(R.string.provider_option_custom),
                    isSelected = selected == TranscriptionProvider.GEMINI_CUSTOM,
                    onClick = { onSelect(TranscriptionProvider.GEMINI_CUSTOM) },
                )
                if (selected == TranscriptionProvider.GEMINI_CUSTOM) {
                    Column(
                        modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = {
                                customInput = it
                                onResetValidation()
                            },
                            placeholder = { Text(stringResource(R.string.provider_custom_placeholder)) },
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
                            isError = customModelValidation is CustomModelValidation.Error,
                        )
                        when (customModelValidation) {
                            is CustomModelValidation.Validating -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                        6.dp,
                                    ),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(14.dp).width(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        stringResource(R.string.provider_validating),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            is CustomModelValidation.Valid -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                        6.dp,
                                    ),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.height(14.dp).width(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        customModelValidation.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            is CustomModelValidation.Error -> {
                                Text(
                                    customModelValidation.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRadioOption(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

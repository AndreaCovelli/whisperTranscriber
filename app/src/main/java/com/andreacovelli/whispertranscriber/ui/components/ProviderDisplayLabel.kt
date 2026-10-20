package com.andreacovelli.whispertranscriber.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider

@Composable
fun providerDisplayLabel(provider: TranscriptionProvider, customModelName: String): String =
    configuredCustomModelLabel(provider, customModelName) ?: stringResource(
        if (provider == TranscriptionProvider.GEMINI_CUSTOM) {
            R.string.provider_custom_model_not_set
        } else {
            providerFamilyLabel(provider)
        },
    )

/** A configured custom model is labelled by its name; null means a resource label applies. */
internal fun configuredCustomModelLabel(provider: TranscriptionProvider, customModelName: String): String? =
    customModelName.takeIf { provider == TranscriptionProvider.GEMINI_CUSTOM && it.isNotBlank() }

@StringRes
fun providerFamilyLabel(provider: TranscriptionProvider): Int = when (provider) {
    TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE -> R.string.provider_label_microsoft
    TranscriptionProvider.GEMINI_FLASH_LITE -> R.string.provider_option_flash_lite
    TranscriptionProvider.GEMINI_FLASH -> R.string.provider_option_flash
    TranscriptionProvider.GEMINI_PRO -> R.string.provider_option_pro
    TranscriptionProvider.GEMINI_CUSTOM -> R.string.provider_label_gemini_custom
}

@StringRes
fun providerFamilyLabelForModel(modelName: String): Int {
    val normalizedModelName = modelName.trim().lowercase()

    val provider = TranscriptionProvider.entries
        .find { it.modelName?.lowercase() == normalizedModelName }
        ?: when {
            normalizedModelName.contains("flash-lite") -> TranscriptionProvider.GEMINI_FLASH_LITE
            normalizedModelName.contains("flash") -> TranscriptionProvider.GEMINI_FLASH
            normalizedModelName.contains("pro") -> TranscriptionProvider.GEMINI_PRO
            else -> TranscriptionProvider.GEMINI_CUSTOM
        }

    return providerFamilyLabel(provider)
}

@StringRes
fun providerFamilyLabel(summaryProvider: TranscriptionProvider?, modelName: String): Int =
    summaryProvider?.let(::providerFamilyLabel) ?: providerFamilyLabelForModel(modelName)

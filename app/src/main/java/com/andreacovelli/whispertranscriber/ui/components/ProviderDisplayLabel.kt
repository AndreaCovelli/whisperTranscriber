package com.andreacovelli.whispertranscriber.ui.components

import com.andreacovelli.whispertranscriber.TranscriptionProvider

fun providerDisplayLabel(provider: TranscriptionProvider, customModelName: String): String = when (provider) {
    TranscriptionProvider.ELEVEN_LABS -> "ElevenLabs"
    TranscriptionProvider.GEMINI_FLASH_LITE -> "Gemini Flash Lite"
    TranscriptionProvider.GEMINI_FLASH -> "Gemini Flash"
    TranscriptionProvider.GEMINI_PRO -> "Gemini Pro"
    TranscriptionProvider.GEMINI_CUSTOM ->
        customModelName.ifBlank { "Custom Gemini model (not set)" }
}

fun providerFamilyLabel(provider: TranscriptionProvider): String = when (provider) {
    TranscriptionProvider.ELEVEN_LABS -> "ElevenLabs"
    TranscriptionProvider.GEMINI_FLASH_LITE -> "Gemini Flash Lite"
    TranscriptionProvider.GEMINI_FLASH -> "Gemini Flash"
    TranscriptionProvider.GEMINI_PRO -> "Gemini Pro"
    TranscriptionProvider.GEMINI_CUSTOM -> "Gemini Custom"
}

fun providerFamilyLabelForModel(modelName: String): String {
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

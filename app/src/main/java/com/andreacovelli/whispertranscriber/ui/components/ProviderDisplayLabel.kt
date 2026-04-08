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

package com.andreacovelli.whispertranscriber.transcription

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.security.GenAiClientFactory
import com.andreacovelli.whispertranscriber.settings.ApiKeyManager
import com.andreacovelli.whispertranscriber.settings.TranscriptionProvider
import com.andreacovelli.whispertranscriber.settings.decodeTranscriptionProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

internal sealed interface ProviderExecution {
    val displayName: UserMessage

    data class MicrosoftSpeech(val modelName: String) : ProviderExecution {
        override val displayName: UserMessage get() = UserMessage(R.string.provider_option_microsoft_speech)
    }

    data class Gemini(val modelName: String) : ProviderExecution {
        override val displayName: UserMessage get() = UserMessage(R.string.transcription_provider_gemini)
    }
}

/** Resolves a queued job's provider and builds a transcriber that owns the decrypted API key. */
@Singleton
class ProviderTranscriberFactory @Inject constructor(
    private val settings: ApiKeyManager,
    private val genAiClientFactory: GenAiClientFactory,
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    internal fun resolveProviderExecution(providerName: String?, requestedModel: String?): ProviderExecution =
        when (val provider = decodeTranscriptionProvider(providerName)) {
            TranscriptionProvider.MICROSOFT_MAI_TRANSCRIBE,
            -> ProviderExecution.MicrosoftSpeech(resolveMicrosoftSpeechExecutionModel(requestedModel))
            TranscriptionProvider.GEMINI_FLASH_LITE,
            TranscriptionProvider.GEMINI_FLASH,
            TranscriptionProvider.GEMINI_PRO,
            -> ProviderExecution.Gemini(requestedModel ?: requireNotNull(provider.modelName))
            TranscriptionProvider.GEMINI_CUSTOM -> ProviderExecution.Gemini(
                modelName = requestedModel?.takeIf(String::isNotBlank)
                    ?: throw UserFacingIllegalStateException(UserMessage(R.string.gemini_model_not_specified)),
            )
        }

    internal suspend fun validatePersistedMicrosoftSpeechEndpoint(): MicrosoftSpeechEndpoint {
        val persistedEndpoint = settings.microsoftSpeechRegionFlow.first()
        return MicrosoftSpeechEndpoint.parseOrNull(persistedEndpoint) ?: run {
            settings.clearInvalidMicrosoftSpeechConfiguration()
            throw UserFacingIllegalStateException(UserMessage(R.string.microsoft_speech_configuration_cleared))
        }
    }

    internal suspend fun buildTranscriber(
        preparedInput: PreparedAudioInput,
        providerExecution: ProviderExecution,
        speakerDiarizationEnabled: Boolean,
        speakerLabelPrefix: String,
    ): ProviderTranscriber = when (providerExecution) {
        is ProviderExecution.MicrosoftSpeech -> {
            val microsoftSpeechInput = checkNotNull(preparedInput as? PreparedAudioInput.MicrosoftSpeech) {
                "Internal error: Microsoft Speech audio data missing."
            }
            val keyChars = settings.getMicrosoftSpeechApiKey()
                ?: throw UserFacingIllegalStateException(UserMessage(R.string.microsoft_speech_key_missing))
            var keyOwnershipTransferred = false
            try {
                val endpoint = MicrosoftSpeechEndpoint.parse(settings.microsoftSpeechRegionFlow.first())
                val transcriber = MicrosoftSpeechTranscriber(
                    apiKey = keyChars,
                    audioFile = microsoftSpeechInput.file,
                    mimeType = microsoftSpeechInput.mimeType,
                    endpoint = endpoint,
                    modelName = providerExecution.modelName,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    speakerLabelPrefix = speakerLabelPrefix,
                    audioInspection = microsoftSpeechInput.inspection,
                    okHttpClient = okHttpClient,
                    ioDispatcher = ioDispatcher,
                )
                keyOwnershipTransferred = true
                transcriber
            } finally {
                if (!keyOwnershipTransferred) {
                    keyChars.fill('\u0000')
                }
            }
        }
        is ProviderExecution.Gemini -> {
            val geminiInput = checkNotNull(preparedInput as? PreparedAudioInput.Gemini) {
                "Internal error: Gemini audio data missing."
            }
            val keyChars = settings.getGeminiApiKey()
                ?: throw UserFacingIllegalStateException(UserMessage(R.string.gemini_api_key_missing))
            var keyOwnershipTransferred = false
            try {
                val transcriber = GeminiTranscriber(
                    audioFile = geminiInput.file,
                    mimeType = geminiInput.mimeType,
                    apiKey = keyChars,
                    modelName = providerExecution.modelName,
                    speakerDiarizationEnabled = speakerDiarizationEnabled,
                    speakerLabelPrefix = speakerLabelPrefix,
                    clientFactory = genAiClientFactory,
                    ioDispatcher = ioDispatcher,
                )
                keyOwnershipTransferred = true
                transcriber
            } finally {
                if (!keyOwnershipTransferred) {
                    keyChars.fill('\u0000')
                }
            }
        }
    }
}

internal fun resolveMicrosoftSpeechExecutionModel(requestedModel: String?): String =
    requestedModel?.takeIf(String::isNotBlank) ?: MICROSOFT_MAI_TRANSCRIBE_MODEL

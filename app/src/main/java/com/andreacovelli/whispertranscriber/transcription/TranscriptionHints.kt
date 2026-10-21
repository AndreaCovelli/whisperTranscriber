package com.andreacovelli.whispertranscriber.transcription

import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import java.util.Locale

/**
 * Optional guidance captured with a queued job: a language the recording is known to be in, and names or
 * terms the provider should favor. Empty values leave the provider's automatic behavior unchanged.
 */
data class TranscriptionHints(val languageCode: String = "", val vocabulary: List<String> = emptyList())

internal const val MAX_VOCABULARY_TERMS = 100
internal const val MAX_VOCABULARY_TERM_LENGTH = 100

/** Language codes MAI-Transcribe-2 accepts in `locales`. Gemini receives the same choice as a prompt hint. */
val TRANSCRIPTION_LANGUAGE_CODES: List<String> = listOf(
    "af", "ar", "as", "az", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi",
    "fil", "fr", "gl", "gu", "he", "hi", "hu", "hy", "id", "is", "it", "ja", "kk", "kn", "ko", "lt", "lv",
    "mk", "ml", "mr", "ms", "nb", "ne", "nl", "or", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "sw",
    "ta", "te", "th", "tr", "uk", "ur", "vi", "yue", "zh",
)

/** Returns [code] when it is a supported language, otherwise "" for automatic detection. */
fun normalizeTranscriptionLanguage(code: String?): String =
    code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in TRANSCRIPTION_LANGUAGE_CODES }.orEmpty()

/** Splits user-entered terms on new lines and commas, dropping blanks and case-insensitive duplicates. */
fun parseVocabulary(raw: String): List<String> = raw.split('\n', ',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { term -> term.take(MAX_VOCABULARY_TERM_LENGTH).trim() }
    .distinctBy { term -> term.lowercase(Locale.ROOT) }
    .take(MAX_VOCABULARY_TERMS)

/** Jobs store the vocabulary one term per line; see [parseVocabulary]. */
internal fun encodeVocabulary(terms: List<String>): String = terms.joinToString(separator = "\n")

internal fun TranscriptionJob.transcriptionHints(): TranscriptionHints = TranscriptionHints(
    languageCode = normalizeTranscriptionLanguage(languageHint),
    vocabulary = parseVocabulary(vocabulary),
)

/** English language name for prompts, such as "Italian" for "it". */
internal fun englishLanguageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/** Extra Gemini prompt lines for [hints]; empty when there is nothing to add. */
internal fun buildGeminiHintInstructions(hints: TranscriptionHints): String = buildList {
    if (hints.languageCode.isNotEmpty()) {
        add(
            "The speech is in ${englishLanguageName(hints.languageCode)} (${hints.languageCode}). " +
                "Transcribe it in that language and set language_code accordingly.",
        )
    }
    if (hints.vocabulary.isNotEmpty()) {
        add(
            "The recording may mention these names or terms; when one is spoken, use this spelling: " +
                hints.vocabulary.joinToString(separator = "; ") + ".",
        )
    }
}.joinToString(separator = "\n")

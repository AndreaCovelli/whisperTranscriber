package com.andreacovelli.whispertranscriber.media

import com.andreacovelli.whispertranscriber.ElevenLabsWord

private const val UNLABELED_SPEAKER_ID = "__unlabeled__"
private val ALPHABET = ('A'..'Z').toList()
private val NON_SPACING_LANGUAGES = setOf("ja", "zh", "th")
private const val ALWAYS_OPENING_PUNCTUATION = "([{«"
private const val QUOTE_PUNCTUATION = "\"'"

private data class SpeakerSection(val label: String, val text: StringBuilder = StringBuilder())

internal fun formatElevenLabsSpeakerTranscript(
    words: List<ElevenLabsWord>,
    languageCode: String? = null,
    speakerLabelPrefix: String = "Speaker",
): String = ElevenLabsSpeakerTranscriptFormatter(
    words = words,
    shouldInsertSpaces = languageCode.shouldInsertWordSpaces(),
    speakerLabelPrefix = speakerLabelPrefix,
).format()

private class ElevenLabsSpeakerTranscriptFormatter(
    private val words: List<ElevenLabsWord>,
    private val shouldInsertSpaces: Boolean,
    private val speakerLabelPrefix: String,
) {
    private val speakerLabels = linkedMapOf<String, String>()
    private val sections = mutableListOf<SpeakerSection>()
    private var hasSpeakerIds = false

    fun format(): String {
        words.forEachIndexed(::appendWord)
        if (!hasSpeakerIds) return ""
        return sections.joinToString(separator = "\n") { section ->
            "${section.label}: ${section.text.toString().trim()}"
        }.trim()
    }

    private fun appendWord(index: Int, word: ElevenLabsWord) {
        val token = word.text.trim()
        if (token.isEmpty()) return

        val speakerId = word.speakerId?.takeIf(String::isNotBlank)
        hasSpeakerIds = hasSpeakerIds || speakerId != null

        val section = sectionForSpeaker(speakerId)
        section.text.appendToken(
            token = token,
            nextToken = words.nextNonBlankTokenAfter(index),
            shouldInsertSpaces = shouldInsertSpaces,
        )
    }

    private fun sectionForSpeaker(speakerId: String?): SpeakerSection {
        val label = speakerLabelFor(speakerId)
        val current = sections.lastOrNull()
        if (current != null && current.label == label) {
            return current
        }

        return SpeakerSection(label = label).also(sections::add)
    }

    private fun speakerLabelFor(speakerId: String?): String {
        val effectiveSpeakerId = speakerId ?: UNLABELED_SPEAKER_ID
        return speakerLabels.getOrPut(effectiveSpeakerId) {
            "$speakerLabelPrefix ${speakerLabelSuffix(speakerLabels.size)}"
        }
    }
}

private fun speakerLabelSuffix(index: Int): String = if (index < ALPHABET.size) {
    ALPHABET[index].toString()
} else {
    "${ALPHABET[index % ALPHABET.size]}${index / ALPHABET.size}"
}

private fun String?.shouldInsertWordSpaces(): Boolean {
    val primaryLanguage = this
        ?.substringBefore('-')
        ?.lowercase()
        .orEmpty()
    return primaryLanguage !in NON_SPACING_LANGUAGES
}

private fun StringBuilder.appendToken(token: String, nextToken: String?, shouldInsertSpaces: Boolean) {
    if (isEmpty()) {
        append(token)
        return
    }

    if (!shouldInsertSpaces) {
        append(token)
        return
    }

    if (token.shouldAttachToPreviousToken(nextToken)) {
        append(token)
    } else if (endsWithOpeningPunctuationToken()) {
        append(token)
    } else {
        append(' ').append(token)
    }
}

private fun String.shouldAttachToPreviousToken(nextToken: String?): Boolean =
    all { !it.isLetterOrDigit() } && !isOpeningPunctuationToken(nextToken)

private fun String.isOpeningPunctuationToken(nextToken: String?): Boolean = length == 1 && when (this[0]) {
    in ALWAYS_OPENING_PUNCTUATION -> true
    in QUOTE_PUNCTUATION -> nextToken?.firstOrNull()?.isLetterOrDigit() == true
    else -> false
}

private fun StringBuilder.endsWithOpeningPunctuationToken(): Boolean {
    val lastChar = this[length - 1]
    return when (lastChar) {
        in ALWAYS_OPENING_PUNCTUATION -> true
        in QUOTE_PUNCTUATION -> length == 1 || !this[length - 2].isLetterOrDigit()
        else -> false
    }
}

private fun List<ElevenLabsWord>.nextNonBlankTokenAfter(index: Int): String? {
    for (nextIndex in (index + 1) until size) {
        val token = this[nextIndex].text.trim()
        if (token.isNotEmpty()) return token
    }
    return null
}

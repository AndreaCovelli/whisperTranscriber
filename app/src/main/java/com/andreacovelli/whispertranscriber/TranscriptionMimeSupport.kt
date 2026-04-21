package com.andreacovelli.whispertranscriber

internal object TranscriptionMimeSupport {
    private val wavMimeTypes = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
    )

    val geminiNativeUploadMimeTypes = wavMimeTypes + setOf(
        "audio/mp3",
        "audio/mpeg",
        "audio/aiff",
        "audio/x-aiff",
        "audio/aac",
        "audio/ogg",
        "audio/vorbis",
        "audio/flac",
        "audio/x-flac",
    )

    val microsoftSpeechNativeUploadMimeTypes = setOf(
        "audio/mp3",
        "audio/mpeg",
        "audio/flac",
        "audio/x-flac",
    ) + wavMimeTypes

    val microsoftSpeechWavReencodeMimeTypes = wavMimeTypes
}

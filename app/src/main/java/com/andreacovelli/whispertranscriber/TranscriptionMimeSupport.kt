package com.andreacovelli.whispertranscriber

internal object TranscriptionMimeSupport {
    val geminiNativeUploadMimeTypes = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
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
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/flac",
        "audio/x-flac",
    )

    val microsoftSpeechWavReencodeMimeTypes = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
    )
}

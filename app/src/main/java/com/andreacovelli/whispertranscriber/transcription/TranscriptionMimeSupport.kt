package com.andreacovelli.whispertranscriber.transcription

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

    /** Leading bytes [resolveUploadMimeType] needs to recognize a container. */
    const val CONTAINER_SIGNATURE_BYTES = 12

    private val flacMimeTypes = setOf("audio/flac", "audio/x-flac")
    private val oggMimeTypes = setOf("audio/ogg", "audio/vorbis", "audio/opus")
    private val aiffMimeTypes = setOf("audio/aiff", "audio/x-aiff")
    private val mp4MimeTypes = setOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/3gpp", "video/mp4")

    /**
     * Returns the type to treat the audio as. Providers can mislabel files (Google Drive reports some M4A
     * recordings as audio/mpeg), and an upload under the wrong type is rejected, so a container recognized
     * from [header] replaces a [declaredMimeType] that names a different container. MP3 and ADTS AAC have no
     * unambiguous signature and keep the declared type.
     */
    fun resolveUploadMimeType(declaredMimeType: String?, header: ByteArray): String? {
        val detected = detectContainerMimeTypes(header) ?: return declaredMimeType
        return if (declaredMimeType in detected) declaredMimeType else detected.first()
    }

    private fun detectContainerMimeTypes(header: ByteArray): Set<String>? = when {
        header.hasAsciiAt(0, "RIFF") && header.hasAsciiAt(8, "WAVE") -> wavMimeTypes
        header.hasAsciiAt(0, "fLaC") -> flacMimeTypes
        header.hasAsciiAt(0, "OggS") -> oggMimeTypes
        header.hasAsciiAt(0, "FORM") && (header.hasAsciiAt(8, "AIFF") || header.hasAsciiAt(8, "AIFC")) ->
            aiffMimeTypes
        header.hasAsciiAt(4, "ftyp") -> mp4MimeTypes
        else -> null
    }

    private fun ByteArray.hasAsciiAt(offset: Int, text: String): Boolean =
        size >= offset + text.length && text.indices.all { index -> this[offset + index] == text[index].code.toByte() }
}

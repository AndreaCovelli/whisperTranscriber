package com.andreacovelli.whispertranscriber.queue

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal const val MAX_SHARED_FILES_PER_INTENT = 10
internal const val MAX_SHARED_FILE_BYTES = 250_000_000L
internal const val MAX_SHARED_BATCH_BYTES = 500_000_000L
internal const val MAX_NONTERMINAL_JOBS = 50
internal const val MIN_FREE_BYTES_AFTER_COPY = 64L * 1024L * 1024L

private val acceptedAudioExtensions = setOf(
    "aac",
    "aif",
    "aiff",
    "amr",
    "flac",
    "m4a",
    "mp3",
    "mp4",
    "oga",
    "ogg",
    "opus",
    "wav",
    "wave",
    "webm",
)

internal fun requireAcceptedSharedAudio(mimeType: String?, displayName: String, uriText: String) {
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val displayNameExtension = displayName.substringAfterLast('.', "").lowercase()
    val uriExtension = uriText.substringBefore('?').substringAfterLast('.', "").lowercase()
    val declaredExtension = displayNameExtension.ifBlank { uriExtension }
    val acceptedExtension = declaredExtension in acceptedAudioExtensions
    val genericMimeType = normalizedMimeType == null || normalizedMimeType == "application/octet-stream"
    val audioMimeType = normalizedMimeType?.startsWith("audio/") == true
    require(
        (audioMimeType && (declaredExtension.isBlank() || acceptedExtension)) ||
            (genericMimeType && acceptedExtension),
    ) {
        "\"$displayName\" is not a supported audio share."
    }
}

internal fun requireShareCount(fileCount: Int) {
    require(fileCount in 1..MAX_SHARED_FILES_PER_INTENT) {
        "Share at most $MAX_SHARED_FILES_PER_INTENT audio files at a time."
    }
}

internal fun copySharedAudioWithLimits(
    input: InputStream,
    output: OutputStream,
    batchBytesBeforeCopy: Long,
    availableBytes: () -> Long,
    maxFileBytes: Long = MAX_SHARED_FILE_BYTES,
    maxBatchBytes: Long = MAX_SHARED_BATCH_BYTES,
    minFreeBytes: Long = MIN_FREE_BYTES_AFTER_COPY,
    checkCancellation: () -> Unit = {},
): Long {
    require(batchBytesBeforeCopy in 0..maxBatchBytes)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var fileBytes = 0L
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) {
            fileBytes += count
            if (fileBytes > maxFileBytes) {
                throw IOException("Each shared audio file must be 250 MB or smaller.")
            }
            if (batchBytesBeforeCopy + fileBytes > maxBatchBytes) {
                throw IOException("A shared audio batch must be 500 MB or smaller.")
            }
            checkCancellation()
            output.write(buffer, 0, count)
            if (availableBytes() < minFreeBytes) {
                throw IOException("Not enough storage. Keep at least 64 MiB free after queuing audio.")
            }
        }
    }
    if (fileBytes == 0L) {
        throw IOException("The shared audio file is empty.")
    }
    return fileBytes
}

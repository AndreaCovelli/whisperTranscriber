package com.andreacovelli.whispertranscriber.queue

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import com.andreacovelli.whispertranscriber.ui.cleanupSharedTextFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PreparedSharedAudio(
    val originalUri: Uri,
    val displayName: String,
    val stagedFile: File,
    val mimeType: String?,
)

data class QueuedAudioCleanupResult(val tempFilesDeleted: Int, val orphanFilesDeleted: Int)

@Singleton
class QueuedAudioStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        internal const val CONVERTED_FILE_PREFIX = "converted_"
        internal const val DIRECT_FILE_PREFIX = "direct_"
        private const val STAGED_FILE_PREFIX = "staged_"
        private const val STAGED_AUDIO_DIR = "queued_audio"
    }

    suspend fun <T> withPreparedAudio(uris: List<Uri>, block: suspend (SharedAudioIntake) -> T): T {
        requireShareCount(uris.size)
        val intake = SharedAudioIntake()
        try {
            withContext(ioDispatcher) {
                var batchBytes = 0L
                uris.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    val displayName = resolveDisplayName(uri).ifBlank { "Audio ${index + 1}" }
                    val staged = stageSharedAudio(uri, index, displayName, batchBytes, intake)
                    batchBytes += staged.bytesCopied
                    intake.preparedAudio += PreparedSharedAudio(uri, displayName, staged.file, staged.mimeType)
                }
            }
            return block(intake)
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                intake.cleanup()
            }
        }
    }

    suspend fun cleanupManagedFiles(
        referencedStagedPaths: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
        tempFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(3),
        orphanFileMaxAgeMs: Long = TimeUnit.HOURS.toMillis(24),
    ): QueuedAudioCleanupResult = withContext(ioDispatcher) {
        cleanupSharedTextFiles(context.cacheDir, nowMillis)
        val normalizedReferences = referencedStagedPaths.mapTo(mutableSetOf()) { path -> File(path).absolutePath }
        val tempCutoff = nowMillis - tempFileMaxAgeMs
        val tempFilesDeleted = context.cacheDir
            ?.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { file -> file.lastModified() < tempCutoff }
            .filter(::isManagedTempFile)
            .count(::deleteQuietly)

        val orphanCutoff = nowMillis - orphanFileMaxAgeMs
        val stagedDirectories = listOf(
            File(context.filesDir, STAGED_AUDIO_DIR),
            stagedAudioDir(),
        )
        val orphanFilesDeleted = stagedDirectories
            .asSequence()
            .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
            .filter(File::isFile)
            .filter { file -> file.absolutePath !in normalizedReferences }
            .filter { file -> file.lastModified() < orphanCutoff }
            .count(::deleteQuietly)

        QueuedAudioCleanupResult(tempFilesDeleted, orphanFilesDeleted)
    }

    fun deleteStagedFile(path: String?) {
        if (path.isNullOrBlank()) return
        deleteQuietly(File(path))
    }

    fun deletePreparedAudio(preparedAudio: List<PreparedSharedAudio>) {
        preparedAudio.forEach { deleteQuietly(it.stagedFile) }
    }

    private data class StagedCopy(val file: File, val mimeType: String?, val bytesCopied: Long)

    private fun stagedAudioDir(): File = File(context.noBackupFilesDir, STAGED_AUDIO_DIR).apply { mkdirs() }

    private suspend fun stageSharedAudio(
        uri: Uri,
        index: Int,
        displayName: String,
        batchBytesBeforeCopy: Long,
        intake: SharedAudioIntake,
    ): StagedCopy = withContext(ioDispatcher) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)?.lowercase()
        requireAcceptedSharedAudio(mimeType, displayName, uri.toString())
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: displayName.substringAfterLast('.', "").takeIf(String::isNotBlank)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf(String::isNotBlank)
            ?: "bin"
        val stagedFile = File.createTempFile("${STAGED_FILE_PREFIX}${index}_", ".$extension", stagedAudioDir())
        intake.ownedFiles += stagedFile
        val copyContext = currentCoroutineContext()
        var succeeded = false
        try {
            val bytesCopied = contentResolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use { output ->
                    copySharedAudioWithLimits(
                        input = input,
                        output = output,
                        batchBytesBeforeCopy = batchBytesBeforeCopy,
                        availableBytes = { stagedFile.parentFile?.usableSpace ?: 0L },
                        checkCancellation = { copyContext.ensureActive() },
                    )
                }
            } ?: throw IOException("Failed to read shared audio. Share it again.")
            succeeded = true
            StagedCopy(stagedFile, mimeType, bytesCopied)
        } finally {
            if (!succeeded) {
                deleteQuietly(stagedFile)
            }
        }
    }

    private suspend fun resolveDisplayName(uri: Uri): String = withContext(ioDispatcher) {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            ""
        } catch (_: SQLException) {
            ""
        } catch (_: IllegalArgumentException) {
            ""
        } catch (_: IllegalStateException) {
            ""
        }
    }

    private fun deleteQuietly(file: File): Boolean = try {
        file.delete()
    } catch (_: SecurityException) {
        false
    }

    private fun isManagedTempFile(file: File): Boolean {
        val name = file.name
        return (name.startsWith(CONVERTED_FILE_PREFIX) && name.endsWith(".wav")) ||
            (name.startsWith(DIRECT_FILE_PREFIX) && name.endsWith(".tmp"))
    }
}

/** Files remain intake-owned until the database insertion has committed. */
class SharedAudioIntake internal constructor() {
    internal val ownedFiles = mutableListOf<File>()
    internal val preparedAudio = mutableListOf<PreparedSharedAudio>()

    fun transferOwnership() {
        ownedFiles.clear()
    }

    internal fun cleanup() {
        ownedFiles.forEach { file ->
            try {
                file.delete()
            } catch (_: SecurityException) {
                // Maintenance will retry removal of abandoned files.
            }
        }
    }
}

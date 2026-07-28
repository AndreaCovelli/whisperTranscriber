package com.andreacovelli.whispertranscriber.queue

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
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

    suspend fun prepareSharedAudio(uris: List<Uri>): List<PreparedSharedAudio> {
        if (uris.isEmpty()) return emptyList()
        requireShareCount(uris.size)

        val preparedAudio = mutableListOf<PreparedSharedAudio>()
        var batchBytes = 0L
        try {
            uris.forEachIndexed { index, uri ->
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val staged = stageSharedAudio(uri, index, displayName, batchBytes)
                batchBytes += staged.bytesCopied
                preparedAudio += PreparedSharedAudio(
                    originalUri = uri,
                    displayName = displayName,
                    stagedFile = staged.file,
                    mimeType = staged.mimeType,
                )
            }
            return preparedAudio
        } catch (e: IOException) {
            deletePreparedAudio(preparedAudio)
            throw e
        } catch (e: SecurityException) {
            deletePreparedAudio(preparedAudio)
            throw e
        } catch (e: SQLException) {
            deletePreparedAudio(preparedAudio)
            throw e
        } catch (e: IllegalArgumentException) {
            deletePreparedAudio(preparedAudio)
            throw e
        } catch (e: IllegalStateException) {
            deletePreparedAudio(preparedAudio)
            throw e
        }
    }

    suspend fun cleanupManagedFiles(
        referencedStagedPaths: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
        tempFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(3),
        orphanFileMaxAgeMs: Long = TimeUnit.HOURS.toMillis(24),
    ): QueuedAudioCleanupResult = withContext(ioDispatcher) {
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
    ): StagedCopy = withContext(ioDispatcher) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)?.lowercase()
        requireAcceptedSharedAudio(mimeType, displayName, uri.toString())
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: displayName.substringAfterLast('.', "").takeIf(String::isNotBlank)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf(String::isNotBlank)
            ?: "bin"
        val stagedFile = File.createTempFile("${STAGED_FILE_PREFIX}${index}_", ".$extension", stagedAudioDir())
        var succeeded = false
        try {
            val bytesCopied = contentResolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use { output ->
                    copySharedAudioWithLimits(
                        input = input,
                        output = output,
                        batchBytesBeforeCopy = batchBytesBeforeCopy,
                        availableBytes = { stagedFile.parentFile?.usableSpace ?: 0L },
                    )
                }
            } ?: throw IOException("Failed to read shared audio: $uri")
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

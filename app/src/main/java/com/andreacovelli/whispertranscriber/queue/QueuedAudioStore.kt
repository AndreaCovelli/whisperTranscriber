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

        val preparedAudio = mutableListOf<PreparedSharedAudio>()
        try {
            uris.forEachIndexed { index, uri ->
                val displayName = resolveDisplayName(uri).ifBlank { uri.lastPathSegment ?: "Audio ${index + 1}" }
                val (stagedFile, mimeType) = stageSharedAudio(uri, index)
                preparedAudio += PreparedSharedAudio(
                    originalUri = uri,
                    displayName = displayName,
                    stagedFile = stagedFile,
                    mimeType = mimeType,
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
        }
    }

    suspend fun cleanupOldTempFiles(
        tempFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(3),
        stagedFileMaxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    ) = withContext(ioDispatcher) {
        val cache = context.cacheDir ?: return@withContext
        val tempCutoff = System.currentTimeMillis() - tempFileMaxAgeMs
        cache.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { it.lastModified() < tempCutoff }
            .filter(::isManagedTempFile)
            .forEach(::deleteQuietly)

        val stagedCutoff = System.currentTimeMillis() - stagedFileMaxAgeMs
        stagedAudioDir().listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { it.lastModified() < stagedCutoff }
            .forEach(::deleteQuietly)
    }

    fun deleteStagedFile(path: String?) {
        if (path.isNullOrBlank()) return
        deleteQuietly(File(path))
    }

    fun deletePreparedAudio(preparedAudio: List<PreparedSharedAudio>) {
        preparedAudio.forEach { deleteQuietly(it.stagedFile) }
    }

    private fun stagedAudioDir(): File = File(context.filesDir, STAGED_AUDIO_DIR).apply { mkdirs() }

    private suspend fun stageSharedAudio(uri: Uri, index: Int): Pair<File, String?> = withContext(ioDispatcher) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)?.lowercase()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString()).takeIf(String::isNotBlank)
            ?: "bin"
        val stagedFile = File.createTempFile("${STAGED_FILE_PREFIX}${index}_", ".$extension", stagedAudioDir())
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use(input::copyTo)
            } ?: throw IOException("Failed to read shared audio: $uri")
        } catch (e: IOException) {
            deleteQuietly(stagedFile)
            throw e
        }
        stagedFile to mimeType
    }

    private suspend fun resolveDisplayName(uri: Uri): String = withContext(ioDispatcher) {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index).orEmpty()
                } else {
                    ""
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    private fun isManagedTempFile(file: File): Boolean {
        val name = file.name
        return (name.startsWith(CONVERTED_FILE_PREFIX) && name.endsWith(".wav")) ||
            (name.startsWith(DIRECT_FILE_PREFIX) && name.endsWith(".tmp"))
    }
}

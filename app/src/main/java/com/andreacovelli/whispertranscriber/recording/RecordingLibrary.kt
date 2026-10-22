package com.andreacovelli.whispertranscriber.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Saves finished recordings to shared storage, where they outlive the queue and the app. */
@Singleton
class RecordingLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @get:StringRes
    val savedLocationMessage: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            R.string.recording_saved_to_recordings
        } else {
            R.string.recording_saved_to_music
        }

    /** Copies [source] into shared storage as [displayName] and returns a URI the app can read. */
    suspend fun publish(source: File, displayName: String): Uri = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(source, displayName)
        } else {
            publishToLegacyStorage(source, displayName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, RECORDING_MIME_TYPE)
            put(MediaStore.Audio.Media.RELATIVE_PATH, sharedRelativePath())
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore rejected the recording")
        var published = false
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("MediaStore output is unavailable")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            published = true
            return uri
        } finally {
            if (!published) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    @Suppress("DEPRECATION") // Before scoped storage, shared folders are written by path.
    private suspend fun publishToLegacyStorage(source: File, displayName: String): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create the shared recordings folder")
        }
        val target = File(directory, displayName)
        try {
            source.copyTo(target)
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        // Scanning registers the file with MediaStore, which supplies its display name and MIME type.
        return scanFile(target) ?: Uri.fromFile(target)
    }

    private suspend fun scanFile(file: File): Uri? = withTimeoutOrNull(MEDIA_SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(RECORDING_MIME_TYPE),
            ) { _, uri -> if (continuation.isActive) continuation.resume(uri) }
        }
    }

    private fun sharedRelativePath(): String {
        val parent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.DIRECTORY_RECORDINGS
        } else {
            Environment.DIRECTORY_MUSIC
        }
        return "$parent/$FOLDER_NAME"
    }

    private companion object {
        const val FOLDER_NAME = "WhisperTranscriber"
        const val MEDIA_SCAN_TIMEOUT_MS = 10_000L
    }
}

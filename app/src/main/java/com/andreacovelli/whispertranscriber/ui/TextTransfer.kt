package com.andreacovelli.whispertranscriber.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.core.content.FileProvider
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val INLINE_TEXT_LIMIT_BYTES = 128 * 1024
internal fun canTransferInline(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size < INLINE_TEXT_LIMIT_BYTES

@Singleton
class TextTransfer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @Suppress("TooGenericExceptionCaught") // Android may reject sharing through several Binder/Activity exceptions.
    suspend fun share(text: String, title: String, launch: (Intent) -> Unit): Boolean {
        var temporaryFile: File? = null
        var handedOff = false
        try {
            val sendIntent = withContext(ioDispatcher) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (canTransferInline(text)) {
                        putExtra(Intent.EXTRA_TEXT, text)
                    } else {
                        val directory = File(context.cacheDir, "shared_text").apply { mkdirs() }
                        cleanupSharedTextFiles(context.cacheDir)
                        val file = File.createTempFile("result-", ".txt", directory)
                        temporaryFile = file
                        file.writeText(text, Charsets.UTF_8)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shared-text", file)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, title, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            launch(Intent.createChooser(sendIntent, title))
            handedOff = true
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        } finally {
            if (!handedOff) withContext(NonCancellable + ioDispatcher) { temporaryFile?.delete() }
        }
    }

    /** Writes [text] as UTF-8 to a document the user picked; returns false if the provider rejects it. */
    suspend fun save(text: String, destination: Uri): Boolean = withContext(ioDispatcher) {
        try {
            // "w" need not truncate, so overwriting a longer document could keep its old trailing text.
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: return@withContext false
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

internal fun cleanupSharedTextFiles(cacheDir: File, nowMillis: Long = System.currentTimeMillis()) {
    val cutoff = nowMillis - TimeUnit.HOURS.toMillis(24)
    File(cacheDir, "shared_text").listFiles().orEmpty()
        .filter { it.isFile && it.lastModified() < cutoff }
        .forEach { it.delete() }
}

@Suppress("TooGenericExceptionCaught") // Binder can reject an otherwise small clipboard request too.
internal suspend fun copyTextSafely(clipboard: Clipboard, text: String, label: String): Boolean {
    if (!canTransferInline(text)) return false
    return try {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal suspend fun copyTextWithFeedback(context: Context, clipboard: Clipboard, text: String, successMessage: Int) {
    val copied = copyTextSafely(clipboard, text, context.getString(successMessage))
    Toast.makeText(context, if (copied) successMessage else R.string.clipboard_copy_failed, Toast.LENGTH_SHORT).show()
}

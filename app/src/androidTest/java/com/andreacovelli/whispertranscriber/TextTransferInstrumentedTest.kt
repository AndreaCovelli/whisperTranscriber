package com.andreacovelli.whispertranscriber

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.ui.INLINE_TEXT_LIMIT_BYTES
import com.andreacovelli.whispertranscriber.ui.TextTransfer
import com.andreacovelli.whispertranscriber.ui.canTransferInline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextTransferInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val transfer = TextTransfer(context, Dispatchers.IO)

    @Test
    fun inlineBoundary_countsUtf8BytesAndUsesFilesAtTheLimit() = runBlocking {
        assertTrue(canTransferInline("a".repeat(INLINE_TEXT_LIMIT_BYTES - 1)))
        assertFalse(canTransferInline("a".repeat(INLINE_TEXT_LIMIT_BYTES)))
        assertFalse(canTransferInline("é".repeat(INLINE_TEXT_LIMIT_BYTES / 2)))
        var chooser: Intent? = null
        assertTrue(transfer.share("short", "Share") { chooser = it })
        assertEquals("short", checkNotNull(chooser).sendIntent().getStringExtra(Intent.EXTRA_TEXT))
        val text = "é".repeat(INLINE_TEXT_LIMIT_BYTES / 2)
        try {
            assertTrue(transfer.share(text, "Share") { chooser = it })
            val send = checkNotNull(chooser).sendIntent()
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = checkNotNull(send.clipData).getItemAt(0).uri
            assertEquals(text, context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() })
            assertEquals("content", uri.scheme)
        } finally {
            File(context.cacheDir, "shared_text").listFiles().orEmpty().forEach { it.delete() }
        }
    }

    @Test
    fun failedAndCancelledHandoffs_removeTemporaryTextFiles() = runBlocking {
        val directory = File(context.cacheDir, "shared_text")
        val before = directory.listFiles().orEmpty().toSet()
        val large = "a".repeat(INLINE_TEXT_LIMIT_BYTES)
        assertFalse(transfer.share(large, "Share") { throw android.os.TransactionTooLargeException() })
        assertEquals(before, directory.listFiles().orEmpty().toSet())
        try {
            transfer.share(large, "Share") { throw CancellationException("cancel share") }
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(before, directory.listFiles().orEmpty().toSet())
        }
    }

    @Test
    fun fileProvider_cannotExposeUnrelatedCacheOrStagedAudio() {
        listOf(
            File(context.cacheDir, "unrelated.txt"),
            File(context.noBackupFilesDir, "queued_audio/audio.wav"),
        ).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.shared-text", file)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.sendIntent(): Intent = checkNotNull(getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
}

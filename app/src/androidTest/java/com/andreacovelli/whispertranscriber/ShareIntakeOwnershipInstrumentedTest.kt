package com.andreacovelli.whispertranscriber

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.MAX_NONTERMINAL_JOBS
import com.andreacovelli.whispertranscriber.queue.copySharedAudioWithLimits
import com.andreacovelli.whispertranscriber.ui.main.ShareIntakeState
import com.andreacovelli.whispertranscriber.ui.main.ShareIntakeViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntakeOwnershipInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun failedInsertionAndCancellationBeforeCommit_deleteEveryPreparedFile() = runBlocking {
        val source = source()
        try {
            RepairTestFixture(context).use { fixture ->
                repeat(MAX_NONTERMINAL_JOBS) {
                    fixture.db.transcriptionJobDao().insert(
                        TranscriptionJob(uri = "content://test/file.wav", displayName = "audio.wav"),
                    )
                }
                var prepared: File? = null
                try {
                    fixture.audioStore.withPreparedAudio(listOf(source.toUri())) { intake ->
                        prepared = intake.preparedAudio.single().stagedFile
                        fixture.repository.addJobsWithinCapacity(
                            listOf(TranscriptionJob(uri = source.toUri().toString(), displayName = "source.wav")),
                        )
                        intake.transferOwnership()
                    }
                    error("Expected full queue")
                } catch (_: IllegalStateException) {
                    assertFalse(checkNotNull(prepared).exists())
                }
                try {
                    fixture.audioStore.withPreparedAudio(listOf(source.toUri())) { intake ->
                        prepared = intake.preparedAudio.single().stagedFile
                        throw CancellationException("cancel intake")
                    }
                } catch (_: CancellationException) {
                    assertFalse(checkNotNull(prepared).exists())
                }
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun notificationFailureAfterCommit_preservesAudioReferencedByCommittedJob() = runBlocking {
        val source = source()
        var staged: File? = null
        try {
            RepairTestFixture(context).use { fixture ->
                var id = 0
                try {
                    fixture.audioStore.withPreparedAudio(listOf(source.toUri())) { intake ->
                        staged = intake.preparedAudio.single().stagedFile
                        id = fixture.repository.addJobsWithinCapacity(
                            listOf(
                                TranscriptionJob(
                                    uri = source.toUri().toString(),
                                    displayName = "source.wav",
                                    stagedFilePath = checkNotNull(staged).absolutePath,
                                ),
                            ),
                        ).single().toInt()
                        intake.transferOwnership()
                        error("Notification service unavailable")
                    }
                } catch (_: IllegalStateException) {
                    assertEquals(checkNotNull(staged).absolutePath, fixture.repository.getJob(id)?.stagedFilePath)
                    assertTrue(checkNotNull(staged).readBytes().contentEquals(source.readBytes()))
                }
            }
        } finally {
            source.delete()
            staged?.delete()
        }
    }

    @Test
    fun revokedSourceAfterEarlierCopy_cleansTheEntireBatch() = runBlocking {
        val source = source()
        val stagedDirectory = File(context.noBackupFilesDir, "queued_audio")
        val before = stagedDirectory.listFiles().orEmpty().toSet()
        try {
            RepairTestFixture(context).use { fixture ->
                try {
                    fixture.audioStore.withPreparedAudio(
                        listOf(source.toUri(), File(context.cacheDir, "revoked-source.wav").toUri()),
                    ) {
                        error("Revoked source must fail before insertion")
                    }
                } catch (_: IOException) {
                    assertEquals(before, stagedDirectory.listFiles().orEmpty().toSet())
                }
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun cancellationDuringCopy_isCheckedBeforeWritingTheNextChunk() {
        val output = ByteArrayOutputStream()
        var cancelled = false
        val source = object : ByteArrayInputStream(ByteArray(10_000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                cancelled = true
                return super.read(buffer, offset, length)
            }
        }
        try {
            copySharedAudioWithLimits(source, output, 0, { Long.MAX_VALUE }, checkCancellation = {
                if (cancelled) throw CancellationException("cancel copy")
            })
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, output.size())
        }
    }

    @Test
    fun recreatedOwner_reusesIntakeViewModelAndConsumesShareOnce() = runBlocking {
        RepairTestFixture(context).use { fixture ->
            fixture.settings.saveBackgroundShareSummaryNotificationsEnabled(false)
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ShareIntakeViewModel(context, fixture.settings, fixture.queue, fixture.notifier) as T
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, "content://test/first.wav".toUri())
            }
            try {
                val first =
                    withContext(Dispatchers.Main) {
                        ViewModelProvider(store, factory)[ShareIntakeViewModel::class.java].also { it.accept(intent) }
                    }
                val state = withTimeout(5_000) { first.state.first { it is ShareIntakeState.ForwardToMain } }
                val recreated = withContext(Dispatchers.Main) {
                    ViewModelProvider(store, factory)[ShareIntakeViewModel::class.java].also { it.accept(Intent()) }
                }
                assertTrue(first === recreated)
                assertEquals(state, recreated.state.value)
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    private fun source(): File = File.createTempFile("intake-test-", ".wav", context.cacheDir).apply {
        writeBytes(ByteArray(1_024) { 7 })
    }
}

package com.andreacovelli.whispertranscriber

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.data.TranscriptionJob
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.transcription.QueuedAudioInputResolution
import com.andreacovelli.whispertranscriber.transcription.QueuedAudioInputResolver
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentAndQueuedAudioInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun singleShareIntent_isConsumedOnce() {
        val sharedUri = Uri.parse("content://test/single.wav")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }

        val (uris, replacementIntent) = SharedAudioIntentHandler.consumeSharedUris(shareIntent)

        assertEquals(listOf(sharedUri), uris)
        assertTrue(SharedAudioIntentHandler.extractSharedUris(replacementIntent).isEmpty())
    }

    @Test
    fun multipleShareIntent_deduplicatesClipDataAndExtras() {
        val firstUri = Uri.parse("content://test/first.wav")
        val secondUri = Uri.parse("content://test/second.wav")
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            clipData = ClipData.newRawUri("first", firstUri).apply {
                addItem(ClipData.Item(secondUri))
            }
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(firstUri, secondUri, secondUri),
            )
        }

        val uris = SharedAudioIntentHandler.extractSharedUris(shareIntent)

        assertEquals(listOf(firstUri, secondUri), uris)
    }

    @Test
    fun historyLaunchedShareIntent_isIgnored() {
        val sharedUri = Uri.parse("content://test/history.wav")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }

        val uris = SharedAudioIntentHandler.extractSharedUris(
            shareIntent.takeIf {
                (it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
            },
        )

        assertTrue(uris.isEmpty())
    }

    @Test
    fun mainActivity_isConfiguredAsSingleTask() {
        val activityInfo = context.packageManager.getActivityInfoCompat(
            ComponentName(context, MainActivity::class.java),
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
    }

    @Test
    fun audioShareIntent_resolvesToShareReceiverActivity() {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_SEND).apply {
                setPackage(context.packageName)
                type = "audio/wav"
            },
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertEquals(ShareReceiverActivity::class.java.name, resolved?.activityInfo?.name)
    }

    @Test
    fun queuedAudioResolver_requiresResharingWhenStagedPathIsMissing() {
        val originalUri = "content://test/legacy.wav"
        val resolution = QueuedAudioInputResolver.resolve(
            TranscriptionJob(
                uri = originalUri,
                displayName = "legacy.wav",
                stagedFilePath = null,
            ),
        )

        assertEquals(
            QueuedAudioInputResolution.MissingStagedFile(
                "Queued audio is no longer available. Please share it again.",
            ),
            resolution,
        )
    }

    @Test
    fun queuedAudioResolver_returnsStagedFileWhenPresent() {
        val stagedFile = File.createTempFile("staged_test_", ".wav", context.cacheDir).apply {
            writeBytes(byteArrayOf(1))
        }
        try {
            val resolution = QueuedAudioInputResolver.resolve(
                TranscriptionJob(
                    uri = "content://test/original.wav",
                    displayName = "staged.wav",
                    stagedFilePath = stagedFile.absolutePath,
                ),
            )

            assertEquals(
                QueuedAudioInputResolution.Ready(stagedFile.toUri()),
                resolution,
            )
        } finally {
            stagedFile.delete()
        }
    }

    @Test
    fun queuedAudioResolver_reportsMissingStagedFile() {
        val missingPath = File(context.cacheDir, "missing-staged-audio.wav").absolutePath

        val resolution = QueuedAudioInputResolver.resolve(
            TranscriptionJob(
                uri = "content://test/original.wav",
                displayName = "missing.wav",
                stagedFilePath = missingPath,
            ),
        )

        assertEquals(
            QueuedAudioInputResolution.MissingStagedFile(
                "Queued audio is no longer available. Please share it again.",
            ),
            resolution,
        )
    }

    @Test
    fun stagedFileCleanup_preservesReferencesAndDeletesOnlyOldOrphans() = runBlocking {
        val now = System.currentTimeMillis()
        val stagedDir = File(context.noBackupFilesDir, "queued_audio").apply { mkdirs() }
        val referenced = File(stagedDir, "staged_referenced.wav").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - TimeUnit.DAYS.toMillis(2))
        }
        val orphan = File(stagedDir, "staged_orphan.wav").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now - TimeUnit.DAYS.toMillis(2))
        }
        val recent = File(stagedDir, "staged_recent.wav").apply {
            writeBytes(byteArrayOf(3))
            setLastModified(now)
        }
        val store = QueuedAudioStore(context, Dispatchers.IO)

        try {
            val result = store.cleanupManagedFiles(
                referencedStagedPaths = setOf(referenced.absolutePath),
                nowMillis = now,
            )

            assertTrue(referenced.exists())
            assertFalse(orphan.exists())
            assertTrue(recent.exists())
            assertEquals(1, result.orphanFilesDeleted)
        } finally {
            referenced.delete()
            orphan.delete()
            recent.delete()
        }
    }

    private fun PackageManager.getActivityInfoCompat(componentName: ComponentName): ActivityInfo =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getActivityInfo(componentName, 0)
        }
}

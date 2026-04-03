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
import java.io.File
import org.junit.Assert.assertEquals
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
    fun mainActivity_isConfiguredAsSingleTask() {
        val activityInfo = context.packageManager.getActivityInfoCompat(
            ComponentName(context, MainActivity::class.java),
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
    }

    @Test
    fun queuedAudioResolver_usesLegacyJobUriWhenNoStagedFileExistsField() {
        val originalUri = "content://test/legacy.wav"
        val resolution = QueuedAudioInputResolver.resolve(
            TranscriptionJob(
                uri = originalUri,
                displayName = "legacy.wav",
                stagedFilePath = null,
            ),
        )

        assertEquals(
            QueuedAudioInputResolution.Ready(Uri.parse(originalUri)),
            resolution,
        )
    }

    @Test
    fun queuedAudioResolver_returnsStagedFileWhenPresent() {
        val stagedFile = File.createTempFile("staged_test_", ".wav", context.cacheDir)
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

    private fun PackageManager.getActivityInfoCompat(componentName: ComponentName): ActivityInfo =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getActivityInfo(componentName, 0)
        }
}

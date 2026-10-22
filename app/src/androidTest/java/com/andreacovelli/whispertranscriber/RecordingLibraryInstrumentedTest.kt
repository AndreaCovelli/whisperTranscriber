package com.andreacovelli.whispertranscriber

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.queue.QueuedAudioStore
import com.andreacovelli.whispertranscriber.recording.RecordingLibrary
import com.andreacovelli.whispertranscriber.recording.recordingFileName
import com.andreacovelli.whispertranscriber.transcription.TranscriptionMimeSupport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Saved recordings land in shared storage and enter the queue through the normal share intake. */
@RunWith(AndroidJUnit4::class)
class RecordingLibraryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = RecordingLibrary(context, Dispatchers.IO)
    private val source = File(context.cacheDir, "recording-library-test.m4a")
    private var published: Uri? = null

    @After
    fun tearDown() {
        source.delete()
        published?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun publish_savesReadableAudioThatTheShareIntakeAccepts() = runBlocking {
        // An MP4 "ftyp" box at offset 4, as MediaRecorder writes for M4A output.
        val bytes = ByteArray(4_096) { index -> index.toByte() }
        "ftypM4A ".toByteArray().copyInto(bytes, destinationOffset = 4)
        source.writeBytes(bytes)
        val displayName = recordingFileName("Recording", System.currentTimeMillis())

        val uri = library.publish(source, displayName).also { published = it }

        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE),
            null,
            null,
            null,
        ).use { cursor ->
            checkNotNull(cursor).moveToFirst()
            assertEquals(displayName, cursor.getString(0))
            // MediaStore derives the type from the extension (audio/mpeg for .m4a on some versions);
            // the upload path recognizes the MP4 container regardless.
            val storedMimeType = cursor.getString(1)
            assertTrue(storedMimeType.startsWith("audio/"))
            assertEquals("audio/mp4", TranscriptionMimeSupport.resolveUploadMimeType(storedMimeType, bytes))
        }
        assertArrayEquals(bytes, checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() })

        QueuedAudioStore(context, Dispatchers.IO).withPreparedAudio(listOf(uri)) { intake ->
            val prepared = intake.preparedAudio.single()
            assertEquals(displayName, prepared.displayName)
            assertArrayEquals(bytes, prepared.stagedFile.readBytes())
        }
    }
}

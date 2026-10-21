package com.andreacovelli.whispertranscriber

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.media.AccessUnitTooLargeException
import com.andreacovelli.whispertranscriber.media.FileRangeReader
import com.andreacovelli.whispertranscriber.media.Media3AudioSource
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Media3AudioSourceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var inputFile: File
    private lateinit var descriptor: AssetFileDescriptor

    @Before
    fun openInput() {
        // The fake extractor produces its own samples; the reader only needs an open file.
        inputFile = File.createTempFile("media3_source_", ".bin", context.cacheDir).apply { writeBytes(ByteArray(16)) }
        val parcelDescriptor = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = AssetFileDescriptor(parcelDescriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    @After
    fun closeInput() {
        descriptor.close()
        inputFile.delete()
    }

    @Test
    fun inputEndingInsideAnAccessUnit_keepsTheAccessUnitsReadBefore() {
        val source = openSource(FakeAudioExtractor(samplesBeforeEnd = 2, failure = EOFException()))

        assertEquals(listOf(0L, 20_000L), readAllSampleTimes(source))
    }

    @Test
    fun malformedDataAfterPrepare_failsAsUnreadableAudio() {
        val source = openSource(
            FakeAudioExtractor(
                samplesBeforeEnd = 2,
                failure = ParserException.createForMalformedContainer("bad", null),
            ),
        )

        val error = assertThrows(UserFacingIOException::class.java) { readAllSampleTimes(source) }

        assertEquals(R.string.conversion_audio_unreadable, error.userMessage.id)
    }

    @Test
    fun uncheckedExtractorFailureAfterPrepare_failsAsUnreadableAudio() {
        val source = openSource(FakeAudioExtractor(samplesBeforeEnd = 1, failure = IllegalStateException("bad")))

        val error = assertThrows(UserFacingIOException::class.java) { readAllSampleTimes(source) }

        assertEquals(R.string.conversion_audio_unreadable, error.userMessage.id)
    }

    @Test
    fun cleanEndOfInput_endsTheStream() {
        val source = openSource(FakeAudioExtractor(samplesBeforeEnd = 3, failure = null))

        assertEquals(listOf(0L, 20_000L, 40_000L), readAllSampleTimes(source))
        assertEquals(1_000_000L, source.durationUs)
    }

    @Test
    fun prepare_rejectsAContainerWithoutAudio() {
        val source = createSource(FakeAudioExtractor(samplesBeforeEnd = 0, failure = null, declaresAudio = false))
        try {
            assertFalse(source.prepare())
        } finally {
            source.release()
        }
    }

    @Test
    fun readSampleData_rejectsAnAccessUnitLargerThanTheBuffer() {
        val source = openSource(FakeAudioExtractor(samplesBeforeEnd = 1, failure = null))
        try {
            assertThrows(AccessUnitTooLargeException::class.java) {
                source.readSampleData(ByteBuffer.allocate(FakeAudioExtractor.SAMPLE_BYTES - 1), 0)
            }
        } finally {
            source.release()
        }
    }

    private fun openSource(extractor: Extractor): Media3AudioSource =
        createSource(extractor).also { source -> assertTrue(source.prepare()) }

    private fun createSource(extractor: Extractor): Media3AudioSource {
        val reader = FileRangeReader.open(descriptor)
        val input = DefaultExtractorInput(reader, 0, reader.length)
        return Media3AudioSource(extractor, reader, input, onRelease = {})
    }

    private fun readAllSampleTimes(source: Media3AudioSource): List<Long> {
        val buffer = ByteBuffer.allocate(1024)
        val times = mutableListOf<Long>()
        try {
            while (source.readSampleData(buffer, 0) >= 0) {
                assertEquals(FakeAudioExtractor.SAMPLE_BYTES, buffer.remaining())
                times += source.sampleTime
                source.advance()
            }
        } finally {
            source.release()
        }
        return times
    }

    /** Emits [samplesBeforeEnd] 20 ms access units, then throws [failure] or reports the end of input. */
    private class FakeAudioExtractor(
        private val samplesBeforeEnd: Int,
        private val failure: Exception?,
        private val declaresAudio: Boolean = true,
    ) : Extractor {
        private lateinit var trackOutput: TrackOutput
        private var samplesWritten = 0

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            if (declaresAudio) {
                trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
                trackOutput.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setSampleRate(48_000)
                        .setChannelCount(1)
                        .build(),
                )
            } else {
                output.track(0, C.TRACK_TYPE_VIDEO)
            }
            output.endTracks()
            output.seekMap(SeekMap.Unseekable(1_000_000L))
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (samplesWritten == samplesBeforeEnd) {
                // The first half of the next access unit stays uncommitted, like a cut-off recording.
                failure?.let { throw it }
                return Extractor.RESULT_END_OF_INPUT
            }
            if (samplesWritten == 0) writeBytes(HALF_SAMPLE_BYTES)
            // Finish this access unit and begin the next before committing this one, as FLAC does.
            writeBytes(HALF_SAMPLE_BYTES)
            writeBytes(HALF_SAMPLE_BYTES)
            trackOutput.sampleMetadata(
                samplesWritten * 20_000L,
                C.BUFFER_FLAG_KEY_FRAME,
                SAMPLE_BYTES,
                HALF_SAMPLE_BYTES,
                null,
            )
            samplesWritten++
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit

        private fun writeBytes(count: Int) {
            trackOutput.sampleData(ParsableByteArray(ByteArray(count)), count)
        }

        companion object {
            const val SAMPLE_BYTES = 100
            private const val HALF_SAMPLE_BYTES = SAMPLE_BYTES / 2
        }
    }
}

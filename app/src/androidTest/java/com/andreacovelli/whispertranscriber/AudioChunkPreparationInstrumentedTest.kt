package com.andreacovelli.whispertranscriber

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreacovelli.whispertranscriber.media.AudioChunk
import com.andreacovelli.whispertranscriber.media.CONVERTED_AUDIO_SAMPLE_RATE
import com.andreacovelli.whispertranscriber.media.PCM16_BYTES_PER_SAMPLE
import com.andreacovelli.whispertranscriber.media.Pcm16WavWriter
import com.andreacovelli.whispertranscriber.transcription.AudioPreparationListener
import com.andreacovelli.whispertranscriber.transcription.PreparedAudioInput
import com.andreacovelli.whispertranscriber.transcription.TranscriptionAudioPreparer
import com.andreacovelli.whispertranscriber.transcription.inspectAudioFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioChunkPreparationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preparer = TranscriptionAudioPreparer(context, Dispatchers.IO)
    private val second = CONVERTED_AUDIO_SAMPLE_RATE.toLong()

    @Test
    fun preparePart_writesPlayableWavForMicrosoftSpeech() = runBlocking {
        val source = File.createTempFile("chunk_source_", ".wav", context.cacheDir)
        var part: PreparedAudioInput? = null
        try {
            writeSilentWav(source, sampleCount = 3 * second)
            val chunked = PreparedAudioInput.Chunked(
                file = source,
                chunks = listOf(AudioChunk(0, second), AudioChunk(second, 3 * second)),
                tempFileToClean = source,
            )

            part = preparer.preparePart(jobId = 7, input = chunked, index = 1, usingMicrosoftSpeech = true)

            val microsoftPart = part as PreparedAudioInput.MicrosoftSpeech
            assertEquals("audio/wav", microsoftPart.mimeType)
            assertEquals(2_000_000L, microsoftPart.inspection.durationUs)
            assertEquals(44L + 2 * second * PCM16_BYTES_PER_SAMPLE, microsoftPart.file.length())
            assertTrue(inspectAudioFile(microsoftPart.file).durationUs in 1_900_000L..2_100_000L)
            assertEquals(microsoftPart.file, microsoftPart.tempFileToClean)
        } finally {
            part?.tempFileToClean?.let(preparer::deleteTemporaryFile)
            source.delete()
        }
        assertFalse(part?.tempFileToClean?.exists() ?: false)
    }

    @Test
    fun preparePart_returnsGeminiInputForGemini() = runBlocking {
        val source = File.createTempFile("chunk_source_", ".wav", context.cacheDir)
        var part: PreparedAudioInput? = null
        try {
            writeSilentWav(source, sampleCount = 2 * second)
            val chunked = PreparedAudioInput.Chunked(
                file = source,
                chunks = listOf(AudioChunk(0, second), AudioChunk(second, 2 * second)),
                tempFileToClean = source,
            )

            part = preparer.preparePart(jobId = 7, input = chunked, index = 0, usingMicrosoftSpeech = false)

            assertTrue(part is PreparedAudioInput.Gemini)
            assertEquals(44L + second * PCM16_BYTES_PER_SAMPLE, (part as PreparedAudioInput.Gemini).file.length())
        } finally {
            part?.tempFileToClean?.let(preparer::deleteTemporaryFile)
            source.delete()
        }
    }

    @Test
    fun prepare_usesDetectedContainerWhenDeclaredTypeIsWrong() = runBlocking {
        val source = File.createTempFile("mislabeled_source_", ".mp3", context.cacheDir)
        var prepared: PreparedAudioInput? = null
        try {
            writeSilentWav(source, sampleCount = second)

            // Uploaded as-is, WAV bytes declared as MP3 are rejected by Microsoft; WAV input is re-encoded.
            prepared = preparer.prepare(
                jobId = 7,
                uri = Uri.fromFile(source),
                inputMimeType = "audio/mpeg",
                usingMicrosoftSpeech = true,
                listener = NoOpPreparationListener,
            )

            val microsoftInput = prepared as PreparedAudioInput.MicrosoftSpeech
            assertEquals("audio/wav", microsoftInput.mimeType)
            assertTrue(microsoftInput.file != source)
            assertEquals(microsoftInput.file, microsoftInput.tempFileToClean)
        } finally {
            prepared?.tempFileToClean?.let(preparer::deleteTemporaryFile)
            source.delete()
        }
    }

    private object NoOpPreparationListener : AudioPreparationListener {
        override suspend fun onStatus(status: UserMessage, progress: Float?) = Unit

        override suspend fun onNotification(text: UserMessage) = Unit
    }

    private fun writeSilentWav(file: File, sampleCount: Long) {
        Pcm16WavWriter(file).use { writer ->
            val bytes = (sampleCount * PCM16_BYTES_PER_SAMPLE).toInt()
            writer.writePcm16Buffer(ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN), bytes)
            writer.finish()
        }
    }
}

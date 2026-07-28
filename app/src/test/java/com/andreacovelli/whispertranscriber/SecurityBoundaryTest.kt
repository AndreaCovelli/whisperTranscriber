package com.andreacovelli.whispertranscriber

import com.andreacovelli.whispertranscriber.queue.copySharedAudioWithLimits
import com.andreacovelli.whispertranscriber.queue.requireAcceptedSharedAudio
import com.andreacovelli.whispertranscriber.queue.requireShareCount
import com.andreacovelli.whispertranscriber.security.useClosing
import com.andreacovelli.whispertranscriber.transcription.AudioInspection
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_MAX_DURATION_US
import com.andreacovelli.whispertranscriber.transcription.MICROSOFT_SPEECH_MAX_UPLOAD_BYTES
import com.andreacovelli.whispertranscriber.transcription.MicrosoftSpeechRegion
import com.andreacovelli.whispertranscriber.transcription.validateMicrosoftSpeechAudio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecurityBoundaryTest {
    @Test
    fun closingResource_isClosedAfterSuccessAndFailure() {
        class TrackedResource : AutoCloseable {
            var closed = false

            override fun close() {
                closed = true
            }
        }

        val successResource = TrackedResource()
        assertEquals("result", useClosing(successResource) { "result" })
        assertEquals(true, successResource.closed)

        val failureResource = TrackedResource()
        assertThrows(IllegalStateException::class.java) {
            useClosing(failureResource) { error("boom") }
        }
        assertEquals(true, failureResource.closed)
    }

    @Test
    fun microsoftSpeechRegion_acceptsOnlyLowercaseAzureLabels() {
        listOf("eastus", "westus2", "sweden-central", "jioindiawest").forEach { label ->
            assertEquals(label, MicrosoftSpeechRegion.parse(label).value)
        }

        listOf(
            "",
            "EastUS",
            " eastus.example.com",
            "eastus/path",
            "eastus:443",
            "-eastus",
            "eastus-",
            "east--us",
            "east_us",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                MicrosoftSpeechRegion.parse(value)
            }
        }
    }

    @Test
    fun microsoftSpeechRegion_buildsExactEndpointHost() {
        val endpoint = MicrosoftSpeechRegion.parse("sweden-central").buildEndpoint(
            encodedPath = "speechtotext/transcriptions:transcribe",
            apiVersion = "2025-10-15",
        )

        assertEquals("sweden-central.api.cognitive.microsoft.com", endpoint.host)
        assertEquals("/speechtotext/transcriptions:transcribe", endpoint.encodedPath)
        assertEquals("2025-10-15", endpoint.queryParameter("api-version"))
    }

    @Test
    fun microsoftSpeechLimits_areStrictAtByteAndDurationBoundaries() {
        validateMicrosoftSpeechAudio(
            AudioInspection(
                sizeBytes = MICROSOFT_SPEECH_MAX_UPLOAD_BYTES - 1,
                durationUs = MICROSOFT_SPEECH_MAX_DURATION_US - 1,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateMicrosoftSpeechAudio(
                AudioInspection(
                    sizeBytes = MICROSOFT_SPEECH_MAX_UPLOAD_BYTES,
                    durationUs = MICROSOFT_SPEECH_MAX_DURATION_US - 1,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMicrosoftSpeechAudio(
                AudioInspection(
                    sizeBytes = MICROSOFT_SPEECH_MAX_UPLOAD_BYTES - 1,
                    durationUs = MICROSOFT_SPEECH_MAX_DURATION_US,
                ),
            )
        }
    }

    @Test
    fun sharedAudioCopy_enforcesStreamingFileBatchAndFreeSpaceLimits() {
        assertThrows(IOException::class.java) {
            copySharedAudioWithLimits(
                input = ByteArrayInputStream(ByteArray(9)),
                output = ByteArrayOutputStream(),
                batchBytesBeforeCopy = 0,
                availableBytes = { 100 },
                maxFileBytes = 8,
                maxBatchBytes = 20,
                minFreeBytes = 1,
            )
        }
        assertThrows(IOException::class.java) {
            copySharedAudioWithLimits(
                input = ByteArrayInputStream(ByteArray(6)),
                output = ByteArrayOutputStream(),
                batchBytesBeforeCopy = 5,
                availableBytes = { 100 },
                maxFileBytes = 10,
                maxBatchBytes = 10,
                minFreeBytes = 1,
            )
        }
        assertThrows(IOException::class.java) {
            copySharedAudioWithLimits(
                input = ByteArrayInputStream(ByteArray(1)),
                output = ByteArrayOutputStream(),
                batchBytesBeforeCopy = 0,
                availableBytes = { 0 },
                maxFileBytes = 10,
                maxBatchBytes = 10,
                minFreeBytes = 1,
            )
        }
    }

    @Test
    fun shareMetadata_rejectsNonAudioAndMoreThanTenFiles() {
        requireAcceptedSharedAudio("audio/wav", "recording.wav", "content://share/1")
        requireAcceptedSharedAudio(null, "recording.flac", "content://share/2")

        assertThrows(IllegalArgumentException::class.java) {
            requireAcceptedSharedAudio("image/png", "recording.wav", "content://share/3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireAcceptedSharedAudio("audio/wav", "payload.pdf", "content://share/4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireShareCount(11)
        }
    }

    @Test
    fun shareMetadata_acceptsSupportedAiffExtensions() {
        requireAcceptedSharedAudio("audio/aiff", "recording.aiff", "content://share/5")
        requireAcceptedSharedAudio("audio/x-aiff", "recording.aif", "content://share/6")
    }
}

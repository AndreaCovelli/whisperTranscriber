package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/** The selected audio track of a container, read one encoded access unit at a time. */
internal interface EncodedAudioSource {
    val format: MediaFormat

    /** Track duration in microseconds, or -1 when the container does not report one. */
    val durationUs: Long

    /** Size of the current access unit in bytes, or -1 at the end of the stream. */
    @get:RequiresApi(Build.VERSION_CODES.P)
    val sampleSize: Long

    /** Presentation time of the current access unit in microseconds, or -1 at the end of the stream. */
    val sampleTime: Long

    /** Copies the current access unit into [buffer] at [offset] and returns its size, or -1 at the end. */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun advance(): Boolean

    fun release()
}

/** [source] with the decoder that will read it and its duration in microseconds, or -1 when unknown. */
internal data class DecodableAudioSource(val source: EncodedAudioSource, val decoderName: String, val durationUs: Long)

/** The first audio track, which every conversion and inspection path reads. */
internal fun MediaExtractor.firstAudioTrack(): IndexedValue<MediaFormat>? = (0 until trackCount).asSequence()
    .map { index -> IndexedValue(index, getTrackFormat(index)) }
    .firstOrNull { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

internal object EncodedAudioSources {
    private const val TAG = "EncodedAudioSources"
    private const val CONTAINER_HEADER_BYTES = 12

    /**
     * Opens [inputUri] with Media3's in-process extractors, which read samples far faster than the platform
     * extractor's per-sample IPC. Returns null for containers they do not handle, including raw PCM that the
     * platform extractor already reads in large blocks, and for formats no decoder on the device accepts.
     */
    fun openInProcess(context: Context, inputUri: Uri): DecodableAudioSource? {
        val source = openMedia3(context, inputUri) ?: return null
        val decoderName = findDecoder(source.format)
        if (decoderName == null) {
            source.release()
            return null
        }
        // Media3 reports no duration for ADTS AAC or AMR; the platform extractor scans their frames for one.
        val durationUs = source.durationUs.takeIf { it > 0 } ?: readPlatformDurationUs(context, inputUri)
        return DecodableAudioSource(source, decoderName, durationUs)
    }

    fun openPlatform(context: Context, inputUri: Uri): DecodableAudioSource {
        val source = openPlatformSource(context, inputUri)
        val decoderName = findDecoder(source.format)
        if (decoderName == null) {
            source.release()
            throw UserFacingIOException(UserMessage(R.string.conversion_no_decoder))
        }
        return DecodableAudioSource(source, decoderName, source.durationUs)
    }

    private fun findDecoder(format: MediaFormat): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)

    // Media3 extractors report malformed input with unchecked exceptions too; any failure means the platform
    // extractor should read the file instead.
    @OptIn(UnstableApi::class)
    @Suppress("TooGenericExceptionCaught")
    private fun openMedia3(context: Context, inputUri: Uri): Media3AudioSource? {
        val descriptor = openDescriptor(context, inputUri)
        var source: Media3AudioSource? = null
        var prepared = false
        try {
            val reader = FileRangeReader.open(descriptor)
            val input = DefaultExtractorInput(reader, 0, reader.length)
            if (isPcmContainer(input)) return null
            val extractor = sniffExtractor(input) ?: return null
            source = Media3AudioSource(extractor, reader, input, onRelease = { descriptor.closeQuietly() })
            prepared = source.prepare()
        } catch (error: IOException) {
            android.util.Log.i(TAG, "Media3 cannot read the input; using the platform extractor", error)
        } catch (error: RuntimeException) {
            android.util.Log.i(TAG, "Media3 cannot read the input; using the platform extractor", error)
        } finally {
            // The source closes the descriptor once it exists.
            if (!prepared && source != null) {
                source.releaseQuietly()
            } else if (!prepared) {
                descriptor.closeQuietly()
            }
        }
        return source?.takeIf { prepared }
    }

    /** WAV and AIFF hold raw PCM, and MP3 or ADTS sync patterns can occur by chance in their samples. */
    @OptIn(UnstableApi::class)
    private fun isPcmContainer(input: ExtractorInput): Boolean {
        val header = ByteArray(CONTAINER_HEADER_BYTES)
        val complete = input.peekFully(header, 0, header.size, true)
        input.resetPeekPosition()
        if (!complete) return false
        val riffWave = header.hasAsciiAt(0, "RIFF") && header.hasAsciiAt(8, "WAVE")
        val aiff = header.hasAsciiAt(0, "FORM") && (header.hasAsciiAt(8, "AIFF") || header.hasAsciiAt(8, "AIFC"))
        return riffWave || aiff
    }

    /**
     * Returns the first extractor that recognizes [input], trying formats with distinctive signatures before MP3,
     * whose frame sync is the easiest to match by chance. Every other container goes to the platform extractor.
     */
    @OptIn(UnstableApi::class)
    private fun sniffExtractor(input: ExtractorInput): Extractor? {
        val candidates = sequenceOf(
            { FlacExtractor(FlacExtractor.FLAG_DISABLE_ID3_METADATA or FlacExtractor.FLAG_DISABLE_ARTWORK_METADATA) },
            { Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, Mp4Extractor.FLAG_DISABLE_ARTWORK_METADATA) },
            { AmrExtractor() },
            { OggExtractor() },
            { AdtsExtractor() },
            { Mp3Extractor(Mp3Extractor.FLAG_DISABLE_ID3_METADATA) },
        )
        for (create in candidates) {
            val extractor: Extractor = create()
            val recognized = try {
                extractor.sniff(input)
            } catch (_: EOFException) {
                false
            } finally {
                input.resetPeekPosition()
            }
            if (recognized) return extractor
            extractor.release()
        }
        return null
    }

    private fun ByteArray.hasAsciiAt(offset: Int, text: String): Boolean =
        text.indices.all { index -> this[offset + index] == text[index].code.toByte() }

    /** The platform extractor's duration for [inputUri], or -1 if it reports none or cannot read the file. */
    // MediaExtractor reports unreadable input with several unchecked exception types.
    @Suppress("TooGenericExceptionCaught")
    private fun readPlatformDurationUs(context: Context, inputUri: Uri): Long = try {
        val source = openPlatformSource(context, inputUri)
        try {
            source.durationUs
        } finally {
            source.releaseQuietly()
        }
    } catch (error: IOException) {
        android.util.Log.i(TAG, "Platform extractor reports no duration", error)
        -1L
    } catch (error: RuntimeException) {
        android.util.Log.i(TAG, "Platform extractor reports no duration", error)
        -1L
    }

    // MediaExtractor.release() documents no narrower runtime failure contract.
    @Suppress("TooGenericExceptionCaught")
    private fun openPlatformSource(context: Context, inputUri: Uri): EncodedAudioSource {
        val extractor = MediaExtractor()
        var succeeded = false
        try {
            openDescriptor(context, inputUri).use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            val audioTrack = extractor.firstAudioTrack()
                ?: throw UserFacingIllegalStateException(UserMessage(R.string.conversion_no_audio_track))
            extractor.selectTrack(audioTrack.index)
            succeeded = true
            return PlatformAudioSource(extractor, audioTrack.value)
        } finally {
            if (!succeeded) {
                try {
                    extractor.release()
                } catch (error: RuntimeException) {
                    android.util.Log.w(TAG, "Extractor cleanup failed", error)
                }
            }
        }
    }

    private fun openDescriptor(context: Context, inputUri: Uri): AssetFileDescriptor =
        context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            ?: throw FileNotFoundException("Unable to open input URI: $inputUri")

    private fun AssetFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (error: IOException) {
            android.util.Log.w(TAG, "Input descriptor close failed", error)
        }
    }

    // Extractor release() methods document no narrower runtime failure contract.
    @Suppress("TooGenericExceptionCaught")
    private fun EncodedAudioSource.releaseQuietly() {
        try {
            release()
        } catch (error: RuntimeException) {
            android.util.Log.w(TAG, "Extractor cleanup failed", error)
        }
    }

    private class PlatformAudioSource(private val extractor: MediaExtractor, override val format: MediaFormat) :
        EncodedAudioSource {
        override val durationUs: Long =
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L

        @get:RequiresApi(Build.VERSION_CODES.P)
        override val sampleSize: Long get() = extractor.sampleSize
        override val sampleTime: Long get() = extractor.sampleTime

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)

        override fun advance(): Boolean = extractor.advance()

        override fun release() {
            extractor.release()
        }
    }
}

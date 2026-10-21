package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserFacingIllegalStateException
import com.andreacovelli.whispertranscriber.UserMessage
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/** The selected audio track of a container, read one encoded access unit at a time. */
internal interface EncodedAudioSource {
    val format: MediaFormat

    /** Size of the current access unit in bytes, or -1 at the end of the stream. */
    val sampleSize: Long

    /** Presentation time of the current access unit in microseconds, or -1 at the end of the stream. */
    val sampleTime: Long

    /** Copies the current access unit into [buffer] at [offset] and returns its size, or -1 at the end. */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun advance(): Boolean

    fun release()
}

internal data class DecodableAudioSource(val source: EncodedAudioSource, val decoderName: String)

internal object EncodedAudioSources {
    private const val TAG = "EncodedAudioSources"
    private const val RAW_AUDIO_MIME_TYPE = "audio/raw"

    /**
     * Opens [inputUri] with Media3's in-process extractor, which reads samples far faster than the platform
     * extractor's per-sample IPC. The platform extractor remains the fallback for raw PCM, which it already
     * reads in large blocks, and for anything Media3 cannot parse or the device cannot decode.
     */
    fun openDecodable(context: Context, inputUri: Uri): DecodableAudioSource {
        openMedia3(context, inputUri)?.let { source ->
            val decoderName = findDecoder(source.format)
            if (decoderName != null) return DecodableAudioSource(source, decoderName)
            source.release()
        }
        val source = openPlatform(context, inputUri)
        val decoderName = findDecoder(source.format)
        if (decoderName == null) {
            source.release()
            throw UserFacingIOException(UserMessage(R.string.conversion_no_decoder))
        }
        return DecodableAudioSource(source, decoderName)
    }

    private fun findDecoder(format: MediaFormat): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)

    // MediaExtractorCompat.release() documents no narrower runtime failure contract.
    // MediaExtractorCompat is Media3's only in-process extractor API and is marked unstable.
    @OptIn(UnstableApi::class)
    @Suppress("TooGenericExceptionCaught")
    private fun openMedia3(context: Context, inputUri: Uri): EncodedAudioSource? {
        val descriptor = openDescriptor(context, inputUri)
        val extractor = MediaExtractorCompat(context)
        var source: EncodedAudioSource? = null
        try {
            extractor.setDataSource(descriptor)
            val track = (0 until extractor.trackCount)
                .map { index -> index to extractor.getTrackFormat(index) }
                .firstOrNull { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (track != null && track.second.getString(MediaFormat.KEY_MIME) != RAW_AUDIO_MIME_TYPE) {
                extractor.selectTrack(track.first)
                source = Media3AudioSource(extractor, descriptor, track.second)
            }
        } catch (error: IOException) {
            android.util.Log.i(TAG, "Media3 cannot read the input; using the platform extractor", error)
        } finally {
            if (source == null) {
                try {
                    extractor.release()
                } catch (error: RuntimeException) {
                    android.util.Log.w(TAG, "Media3 extractor cleanup failed", error)
                }
                descriptor.closeQuietly()
            }
        }
        return source
    }

    // MediaExtractor.release() documents no narrower runtime failure contract.
    @Suppress("TooGenericExceptionCaught")
    private fun openPlatform(context: Context, inputUri: Uri): EncodedAudioSource {
        val extractor = MediaExtractor()
        var succeeded = false
        try {
            openDescriptor(context, inputUri).use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            val audioTrack = (0 until extractor.trackCount).asSequence()
                .map { index -> index to extractor.getTrackFormat(index) }
                .firstOrNull { (_, format) ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?: throw UserFacingIllegalStateException(UserMessage(R.string.conversion_no_audio_track))
            extractor.selectTrack(audioTrack.first)
            succeeded = true
            return PlatformAudioSource(extractor, audioTrack.second)
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

    /** Media3 may reopen its descriptor to seek within the container, so the descriptor stays open until release. */
    @OptIn(UnstableApi::class)
    private class Media3AudioSource(
        private val extractor: MediaExtractorCompat,
        private val descriptor: AssetFileDescriptor,
        override val format: MediaFormat,
    ) : EncodedAudioSource {
        override val sampleSize: Long get() = extractor.sampleSize
        override val sampleTime: Long get() = extractor.sampleTime

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)

        override fun advance(): Boolean = extractor.advance()

        override fun release() {
            try {
                extractor.release()
            } finally {
                descriptor.closeQuietly()
            }
        }
    }

    private class PlatformAudioSource(private val extractor: MediaExtractor, override val format: MediaFormat) :
        EncodedAudioSource {
        // Only batched decoding reads sample sizes, and it needs API 35.
        override val sampleSize: Long
            get() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw UnsupportedOperationException()
                return extractor.sampleSize
            }
        override val sampleTime: Long get() = extractor.sampleTime

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)

        override fun advance(): Boolean = extractor.advance()

        override fun release() {
            extractor.release()
        }
    }
}

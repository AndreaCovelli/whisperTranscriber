package com.andreacovelli.whispertranscriber.media

import android.content.res.AssetFileDescriptor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserMessage
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/** Thrown when an access unit is larger than the decoder input buffer offered for it. */
internal class AccessUnitTooLargeException(sampleSize: Int, capacity: Int) :
    IllegalStateException("Access unit of $sampleSize bytes exceeds a $capacity-byte decoder input buffer")

/**
 * Reads the first audio track of a container with a Media3 extractor inside the app process, which avoids the
 * platform extractor's per-sample IPC. [prepare] must succeed before the [EncodedAudioSource] members are used.
 *
 * A container that simply ends inside an access unit (a recording cut off mid-write) ends the stream with the
 * samples read so far. Any other read or parse failure after [prepare] fails the conversion, so damaged audio
 * is never transcribed partially without notice.
 */
@OptIn(UnstableApi::class)
internal class Media3AudioSource(
    private val extractor: Extractor,
    private val reader: FileRangeReader,
    private var input: ExtractorInput,
    private val onRelease: () -> Unit,
) : EncodedAudioSource {
    private val samples = EncodedSampleQueue()
    private val output = FirstAudioTrackOutput(samples)
    private val positionHolder = PositionHolder()
    private var inputEnded = false
    private var mediaFormat: MediaFormat? = null

    override val format: MediaFormat get() = checkNotNull(mediaFormat)
    override val durationUs: Long get() = output.durationUs
    override val sampleSize: Long get() = currentSample()?.size?.toLong() ?: -1L
    override val sampleTime: Long get() = currentSample()?.timeUs ?: -1L

    /**
     * Reads until the audio track's format is known; returns false if the container has no audio track this
     * reader handles. Read failures propagate so the caller can fall back to the platform extractor.
     */
    fun prepare(): Boolean {
        extractor.init(output)
        while (!output.isPrepared && !inputEnded) {
            readNext()
        }
        val trackFormat = output.format ?: return false
        val mimeType = trackFormat.sampleMimeType
        // Encrypted audio cannot be decoded; raw PCM reads quickly enough through the platform extractor.
        if (!MimeTypes.isAudio(mimeType) || mimeType == MimeTypes.AUDIO_RAW || trackFormat.drmInitData != null) {
            return false
        }
        mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(trackFormat)
        return true
    }

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        val sample = currentSample() ?: return -1
        val capacity = buffer.capacity() - offset
        if (sample.size > capacity) throw AccessUnitTooLargeException(sample.size, capacity)
        buffer.limit(offset + sample.size)
        buffer.position(offset)
        samples.copyHead(buffer)
        buffer.position(offset)
        return sample.size
    }

    override fun advance(): Boolean {
        if (currentSample() == null) return false
        samples.removeHead()
        return currentSample() != null
    }

    override fun release() {
        try {
            extractor.release()
        } finally {
            onRelease()
        }
    }

    // Media3 extractors also report malformed input with unchecked exceptions; all of them mean unreadable audio.
    @Suppress("TooGenericExceptionCaught")
    private fun currentSample(): EncodedSampleQueue.Sample? {
        while (samples.head == null && !inputEnded) {
            try {
                readNext()
            } catch (error: IOException) {
                throw UserFacingIOException(UserMessage(R.string.conversion_audio_unreadable), error)
            } catch (error: RuntimeException) {
                throw UserFacingIOException(UserMessage(R.string.conversion_audio_unreadable), error)
            }
        }
        return samples.head
    }

    private fun readNext() {
        val result = try {
            extractor.read(input, positionHolder)
        } catch (_: EOFException) {
            android.util.Log.w(TAG, "Input ended inside an access unit; keeping the audio read so far")
            Extractor.RESULT_END_OF_INPUT
        }
        when (result) {
            Extractor.RESULT_SEEK -> {
                reader.position = positionHolder.position
                input = DefaultExtractorInput(reader, positionHolder.position, reader.length)
            }
            Extractor.RESULT_END_OF_INPUT -> inputEnded = true
        }
    }

    private companion object {
        const val TAG = "Media3AudioSource"
    }
}

/** Collects the first audio track into [samples] and discards every other track. */
@OptIn(UnstableApi::class)
private class FirstAudioTrackOutput(private val samples: EncodedSampleQueue) :
    ExtractorOutput,
    TrackOutput {
    private val discarded = DiscardingTrackOutput()
    private var audioTrackId: Int? = null
    private var tracksEnded = false
    private var seekMap: SeekMap? = null

    var format: Format? = null
        private set

    /** Whether every track is declared, the duration is known if available, and the audio track has a format. */
    val isPrepared: Boolean
        get() = tracksEnded && seekMap != null && (audioTrackId == null || format != null)

    /** Duration in microseconds, or -1 when the container does not report one. */
    val durationUs: Long get() = seekMap?.durationUs?.takeIf { it > 0 } ?: -1L

    override fun track(id: Int, type: Int): TrackOutput {
        if (type != C.TRACK_TYPE_AUDIO || (audioTrackId != null && audioTrackId != id)) return discarded
        audioTrackId = id
        return this
    }

    override fun endTracks() {
        tracksEnded = true
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        requireMainPart(sampleDataPart)
        val read = samples.append(min(length, MAX_APPEND_BYTES)) { array, offset, count ->
            input.read(array, offset, count)
        }
        if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        requireMainPart(sampleDataPart)
        var remaining = length
        while (remaining > 0) {
            val count = min(remaining, MAX_APPEND_BYTES)
            samples.append(count) { array, offset, _ ->
                data.readBytes(array, offset, count)
                count
            }
            remaining -= count
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (cryptoData != null) throw ParserException.createForUnsupportedContainerFeature("Encrypted audio")
        samples.commit(timeUs, size, offset)
    }

    private fun requireMainPart(sampleDataPart: Int) {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            throw ParserException.createForUnsupportedContainerFeature("Encrypted or supplemental sample data")
        }
    }

    private companion object {
        const val MAX_APPEND_BYTES = 64 * 1024
    }
}

/**
 * Positional, buffered reads of the bytes an [AssetFileDescriptor] describes. Extractors issue many small
 * reads, and serving them from one buffer avoids a system call per access unit. [position] is relative to the
 * descriptor's start offset.
 */
@OptIn(UnstableApi::class)
internal class FileRangeReader private constructor(
    private val channel: FileChannel,
    private val startOffset: Long,
    val length: Long,
) : DataReader {
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES)
    private var bufferStart = 0L

    var position = 0L

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= this.length) return C.RESULT_END_OF_INPUT
        if (position < bufferStart || position >= bufferStart + buffer.limit()) {
            refill()
            if (buffer.limit() == 0) return C.RESULT_END_OF_INPUT
        }
        val index = (position - bufferStart).toInt()
        val count = min(length, buffer.limit() - index)
        System.arraycopy(buffer.array(), index, target, offset, count)
        position += count
        return count
    }

    private fun refill() {
        buffer.clear()
        buffer.limit(min(BUFFER_BYTES.toLong(), length - position).toInt())
        bufferStart = position
        val read = channel.read(buffer, startOffset + position)
        buffer.flip()
        if (read < 0) buffer.limit(0)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Reads through [descriptor] without taking ownership: the caller closes the descriptor. Android's
         * FileInputStream does not own a descriptor it is given, so leaving the stream unclosed leaks nothing.
         */
        fun open(descriptor: AssetFileDescriptor): FileRangeReader {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val length = if (descriptor.length == AssetFileDescriptor.UNKNOWN_LENGTH) {
                channel.size() - descriptor.startOffset
            } else {
                descriptor.length
            }
            return FileRangeReader(channel, descriptor.startOffset, length)
        }
    }
}

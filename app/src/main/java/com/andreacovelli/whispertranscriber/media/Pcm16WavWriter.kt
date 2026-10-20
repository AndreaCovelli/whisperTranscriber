package com.andreacovelli.whispertranscriber.media

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import com.andreacovelli.whispertranscriber.UserMessage
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val CONVERTED_AUDIO_SAMPLE_RATE = 16_000
internal const val CONVERTED_AUDIO_CHANNELS = 1
internal const val CONVERTED_AUDIO_BITS_PER_SAMPLE = 16
internal const val PCM16_BYTES_PER_SAMPLE = 2
internal const val PCM16_MONO_BYTES_PER_SECOND =
    CONVERTED_AUDIO_SAMPLE_RATE * CONVERTED_AUDIO_CHANNELS * PCM16_BYTES_PER_SAMPLE

private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val WAV_HEADER_BYTES = 44L
private const val RIFF_CHUNK_OVERHEAD_BYTES = 36L
private const val UINT32_MAX = 0xffff_ffffL
internal const val MAX_CLASSIC_WAV_DATA_BYTES = UINT32_MAX - RIFF_CHUNK_OVERHEAD_BYTES - 1L

internal fun maximumPcm16MonoDataBytesForDurationExclusive(durationUsExclusive: Long): Long {
    require(durationUsExclusive > 0)
    val sampleLimitProduct = Math.multiplyExact(durationUsExclusive, CONVERTED_AUDIO_SAMPLE_RATE.toLong())
    val maximumSamples = (sampleLimitProduct - 1L) / MICROSECONDS_PER_SECOND
    return min(
        Math.multiplyExact(maximumSamples, PCM16_BYTES_PER_SAMPLE.toLong()),
        MAX_CLASSIC_WAV_DATA_BYTES,
    )
}

internal class Pcm16WavWriter(file: File, private val maximumDataBytes: Long = MAX_CLASSIC_WAV_DATA_BYTES) :
    AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L
    private var byteScratch = ByteArray(0)
    private var byteBuffer: ByteBuffer? = null
    private var closed = false

    val fileDescriptor: FileDescriptor
        get() = output.fd

    init {
        require(maximumDataBytes in PCM16_BYTES_PER_SAMPLE.toLong()..MAX_CLASSIC_WAV_DATA_BYTES)
        require(maximumDataBytes % PCM16_BYTES_PER_SAMPLE == 0L)
        var initialized = false
        try {
            output.setLength(0)
            output.write(wavHeader())
            initialized = true
        } finally {
            if (!initialized) {
                try {
                    output.close()
                } catch (_: IOException) {
                    // Preserve the initialization failure; there is no usable output to finalize.
                }
            }
        }
    }

    fun writeMonoFloats(samples: FloatArray, sampleCount: Int) {
        require(sampleCount in 0..samples.size)
        if (sampleCount == 0) return
        val byteCount = Math.multiplyExact(sampleCount, PCM16_BYTES_PER_SAMPLE)
        ensureWriteFits(byteCount)
        ensureByteCapacity(byteCount)

        val shorts = requireNotNull(byteBuffer).apply(ByteBuffer::clear).asShortBuffer()
        repeat(sampleCount) { index ->
            val scaled = (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            shorts.put(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        output.write(byteScratch, 0, byteCount)
        dataBytesWritten += byteCount
    }

    fun writePcm16Buffer(buffer: ByteBuffer, byteCount: Int) {
        require(byteCount >= 0 && byteCount % PCM16_BYTES_PER_SAMPLE == 0)
        require(buffer.remaining() >= byteCount)
        if (byteCount == 0) return
        ensureWriteFits(byteCount)
        ensureByteCapacity(byteCount)
        buffer.get(byteScratch, 0, byteCount)
        output.write(byteScratch, 0, byteCount)
        dataBytesWritten += byteCount
    }

    fun finish(): Long {
        check(!closed) { "WAV output is already closed." }
        if (dataBytesWritten == 0L) {
            throw UserFacingIOException(UserMessage(R.string.conversion_no_samples))
        }

        val durationUs = dataBytesWritten / PCM16_BYTES_PER_SAMPLE *
            MICROSECONDS_PER_SECOND / CONVERTED_AUDIO_SAMPLE_RATE
        try {
            val integerBuffer = ByteArray(Int.SIZE_BYTES)
            output.seek(4)
            writeUnsignedIntLittleEndian(RIFF_CHUNK_OVERHEAD_BYTES + dataBytesWritten, integerBuffer)
            output.seek(40)
            writeUnsignedIntLittleEndian(dataBytesWritten, integerBuffer)
            output.setLength(WAV_HEADER_BYTES + dataBytesWritten)
        } finally {
            close()
        }
        return durationUs
    }

    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }

    private fun ensureWriteFits(byteCount: Int) {
        if (dataBytesWritten > maximumDataBytes - byteCount) {
            throw UserFacingIOException(UserMessage(R.string.conversion_output_too_large))
        }
    }

    private fun ensureByteCapacity(minimumBytes: Int) {
        if (byteScratch.size < minimumBytes) {
            byteScratch = ByteArray(max(minimumBytes, byteScratch.size.coerceAtLeast(16_384) * 2))
            byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
        } else if (byteBuffer == null) {
            byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private fun wavHeader(): ByteArray {
        val header = ByteArray(WAV_HEADER_BYTES.toInt())
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(0)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(CONVERTED_AUDIO_CHANNELS.toShort())
            putInt(CONVERTED_AUDIO_SAMPLE_RATE)
            putInt(PCM16_MONO_BYTES_PER_SECOND)
            putShort((CONVERTED_AUDIO_CHANNELS * PCM16_BYTES_PER_SAMPLE).toShort())
            putShort(CONVERTED_AUDIO_BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(0)
        }
        return header
    }

    private fun writeUnsignedIntLittleEndian(value: Long, buffer: ByteArray) {
        require(value in 0..UINT32_MAX)
        repeat(Int.SIZE_BYTES) { index ->
            buffer[index] = (value ushr (index * Byte.SIZE_BITS) and 0xffL).toByte()
        }
        output.write(buffer)
    }
}

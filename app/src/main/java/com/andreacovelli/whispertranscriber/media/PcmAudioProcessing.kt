package com.andreacovelli.whispertranscriber.media

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal object PcmChannelMask {
    private const val FRONT_LEFT = 0x4
    private const val FRONT_RIGHT = 0x8
    private const val FRONT_CENTER = 0x10
    private const val LOW_FREQUENCY = 0x20
    private const val BACK_LEFT = 0x40
    private const val BACK_RIGHT = 0x80
    private const val FRONT_LEFT_OF_CENTER = 0x100
    private const val FRONT_RIGHT_OF_CENTER = 0x200
    private const val BACK_CENTER = 0x400
    private const val SIDE_LEFT = 0x800
    private const val SIDE_RIGHT = 0x1000

    const val STEREO = FRONT_LEFT or FRONT_RIGHT
    const val SURROUND_5_1 =
        FRONT_LEFT or FRONT_RIGHT or FRONT_CENTER or LOW_FREQUENCY or BACK_LEFT or BACK_RIGHT

    fun defaultForChannelCount(channelCount: Int): Int? = when (channelCount) {
        1 -> FRONT_CENTER
        2 -> STEREO
        3 -> STEREO or FRONT_CENTER
        4 -> STEREO or BACK_LEFT or BACK_RIGHT
        5 -> STEREO or FRONT_CENTER or BACK_LEFT or BACK_RIGHT
        6 -> SURROUND_5_1
        7 -> SURROUND_5_1 or BACK_CENTER
        8 -> SURROUND_5_1 or SIDE_LEFT or SIDE_RIGHT
        else -> null
    }

    fun gainFor(position: Int): Float = when (position) {
        FRONT_LEFT,
        FRONT_RIGHT,
        FRONT_LEFT_OF_CENTER,
        FRONT_RIGHT_OF_CENTER,
        -> 0.5f
        FRONT_CENTER -> PcmMonoDownmixer.CENTER_GAIN
        LOW_FREQUENCY -> 0f
        BACK_LEFT,
        BACK_RIGHT,
        BACK_CENTER,
        SIDE_LEFT,
        SIDE_RIGHT,
        -> 0.353_553_38f
        else -> 0.25f
    }
}

internal object PcmMonoDownmixer {
    const val CENTER_GAIN = 0.707_106_77f
    private const val HEADROOM = 0.9f
    private const val PCM16_TO_FLOAT = 1f / 32_768f
    private const val PCM16_MIX_SCALE = PCM16_TO_FLOAT * HEADROOM

    fun mixPcm16ToMono(
        source: ShortArray,
        frames: Int,
        sourceChannels: Int,
        channelMask: Int?,
        destination: FloatArray,
        channelGains: FloatArray? = null,
    ) {
        requireBufferSizes(source.size, frames, sourceChannels, destination.size)
        if (sourceChannels == 1) {
            repeat(frames) { index ->
                destination[index] = source[index].toInt() * PCM16_TO_FLOAT
            }
            return
        }

        val gains = channelGains ?: createChannelGains(sourceChannels, channelMask)
        require(gains.size == sourceChannels)
        var sourceIndex = 0
        repeat(frames) { destinationIndex ->
            var mixed = 0f
            repeat(sourceChannels) { channel ->
                mixed += source[sourceIndex++] * gains[channel]
            }
            destination[destinationIndex] = mixed * PCM16_MIX_SCALE
        }
    }

    fun mixFloatToMono(
        source: FloatArray,
        frames: Int,
        sourceChannels: Int,
        channelMask: Int?,
        destination: FloatArray,
        channelGains: FloatArray? = null,
    ) {
        requireBufferSizes(source.size, frames, sourceChannels, destination.size)
        if (sourceChannels == 1) {
            source.copyInto(destination, endIndex = frames)
            return
        }

        val gains = channelGains ?: createChannelGains(sourceChannels, channelMask)
        require(gains.size == sourceChannels)
        var sourceIndex = 0
        repeat(frames) { destinationIndex ->
            var mixed = 0f
            repeat(sourceChannels) { channel ->
                mixed += source[sourceIndex++] * gains[channel]
            }
            destination[destinationIndex] = mixed * HEADROOM
        }
    }

    fun createChannelGains(sourceChannels: Int, channelMask: Int?): FloatArray {
        require(sourceChannels > 0)
        val resolvedMask = channelMask
            ?.takeIf { mask -> Integer.bitCount(mask) == sourceChannels }
            ?: PcmChannelMask.defaultForChannelCount(sourceChannels)
        val knownMask = resolvedMask ?: return FloatArray(sourceChannels) { 1f / sourceChannels }

        var remainingMask = knownMask
        return FloatArray(sourceChannels) {
            val position = Integer.lowestOneBit(remainingMask)
            remainingMask = remainingMask xor position
            PcmChannelMask.gainFor(position)
        }
    }

    private fun requireBufferSizes(sourceSamples: Int, frames: Int, sourceChannels: Int, destinationSamples: Int) {
        require(frames >= 0)
        require(sourceChannels > 0)
        require(sourceSamples >= frames * sourceChannels)
        require(destinationSamples >= frames)
    }
}

/** Reads the integer PCM encodings other than 16-bit that MediaCodec decoders can emit as normalized floats. */
internal object PcmIntegerSampleReader {
    // AudioFormat encoding values; the 24- and 32-bit constants are only exposed from API 31.
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_24BIT_PACKED = 21
    const val ENCODING_PCM_32BIT = 22

    private const val PCM8_OFFSET = 128
    private const val PCM8_TO_FLOAT = 1f / 128f
    private const val PCM24_TO_FLOAT = 1f / 8_388_608f
    private const val PCM32_TO_FLOAT = 1f / 2_147_483_648f

    fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        ENCODING_PCM_8BIT -> 1
        ENCODING_PCM_24BIT_PACKED -> 3
        ENCODING_PCM_32BIT -> 4
        else -> null
    }

    /** Reads [sampleCount] samples from [source]'s position, which must use little-endian byte order. */
    fun readAsFloat(source: ByteBuffer, encoding: Int, destination: FloatArray, sampleCount: Int) {
        val bytesPerSample = requireNotNull(bytesPerSample(encoding)) { "Unsupported PCM encoding: $encoding" }
        require(sampleCount in 0..destination.size)
        require(source.remaining() >= sampleCount * bytesPerSample)
        var position = source.position()
        repeat(sampleCount) { index ->
            destination[index] = when (encoding) {
                ENCODING_PCM_8BIT -> ((source.get(position).toInt() and 0xff) - PCM8_OFFSET) * PCM8_TO_FLOAT
                ENCODING_PCM_24BIT_PACKED -> {
                    val sample = (source.get(position).toInt() and 0xff) or
                        ((source.get(position + 1).toInt() and 0xff) shl 8) or
                        (source.get(position + 2).toInt() shl 16)
                    sample * PCM24_TO_FLOAT
                }
                else -> source.getInt(position) * PCM32_TO_FLOAT
            }
            position += bytesPerSample
        }
        source.position(position)
    }
}

internal class MonoPcmResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    taps: Int = recommendedTapCount(inputRate),
    phases: Int = recommendedPhaseCount(inputRate, outputRate),
) {
    private val tapCount = taps
    private val phaseCount = phases
    private val center = tapCount / 2 - 1
    private val kernel: Array<FloatArray>
    private val tail = FloatArray(tapCount)
    private var workBuffer = FloatArray(0)
    private var totalFramesSeen = 0L
    private var outputFramesProduced = 0L

    init {
        require(inputRate > 0)
        require(outputRate > 0)
        require(tapCount >= 16 && tapCount % 2 == 0)
        require(phaseCount > 0)

        val cutoff = 0.5 * min(1.0, outputRate.toDouble() / inputRate.toDouble()) * CUTOFF_MARGIN
        val window = DoubleArray(tapCount) { tap -> blackmanWindow(tap, tapCount) }
        kernel = Array(phaseCount) { phaseIndex ->
            val fraction = phaseIndex.toDouble() / phaseCount.toDouble()
            val coefficients = DoubleArray(tapCount)
            var sum = 0.0
            for (tap in 0 until tapCount) {
                val distance = (tap - center) - fraction
                val x = 2.0 * cutoff * distance
                val sinc = if (abs(x) < 1e-8) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                val value = (2.0 * cutoff) * sinc * window[tap]
                coefficients[tap] = value
                sum += value
            }
            val scale = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
            FloatArray(tapCount) { tap -> (coefficients[tap] * scale).toFloat() }
        }
    }

    var outputBuffer: FloatArray = FloatArray(0)
        private set

    fun process(input: FloatArray, framesIn: Int): Int {
        require(framesIn in 0..input.size)
        val workFrames = tapCount + framesIn
        ensureWorkCapacity(workFrames)
        System.arraycopy(tail, 0, workBuffer, 0, tapCount)
        if (framesIn > 0) {
            System.arraycopy(input, 0, workBuffer, tapCount, framesIn)
        }

        val workStartGlobal = totalFramesSeen - tapCount
        val approximateOutputFrames =
            ((framesIn * outputRate.toLong()) / inputRate.toLong() + OUTPUT_CAPACITY_MARGIN).toInt()
        ensureOutputCapacity(approximateOutputFrames.coerceAtLeast(0))
        var outputIndex = 0

        while (true) {
            val phasePosition = phasePositionOf(outputFramesProduced)
            val leftIndex = (phasePosition / phaseCount - workStartGlobal).toInt() - center
            if (leftIndex + tapCount > workFrames) break
            // The previous call stopped at the first output whose taps reached past its input, so the next
            // output always starts inside the retained tail.
            check(leftIndex >= 0)

            if (outputIndex >= outputBuffer.size) {
                ensureOutputCapacity(max(1, outputBuffer.size * 2))
            }
            outputBuffer[outputIndex++] = convolve(leftIndex, (phasePosition % phaseCount).toInt())
            outputFramesProduced++
        }

        val copyStart = max(0, workFrames - tapCount)
        System.arraycopy(workBuffer, copyStart, tail, 0, tapCount)
        totalFramesSeen += framesIn
        return outputIndex
    }

    fun finish(): Int {
        val workFrames = tapCount * 2
        ensureWorkCapacity(workFrames)
        System.arraycopy(tail, 0, workBuffer, 0, tapCount)
        workBuffer.fill(0f, tapCount, workFrames)

        val workStartGlobal = totalFramesSeen - tapCount
        val targetOutputFrames = ceilDiv(totalFramesSeen * outputRate.toLong(), inputRate.toLong())
        ensureOutputCapacity((targetOutputFrames - outputFramesProduced).coerceAtLeast(0).toInt())
        var outputIndex = 0

        while (outputFramesProduced < targetOutputFrames) {
            val phasePosition = phasePositionOf(outputFramesProduced)
            val leftIndex = (phasePosition / phaseCount - workStartGlobal).toInt() - center
            check(leftIndex >= 0 && leftIndex + tapCount <= workFrames)
            if (outputIndex >= outputBuffer.size) {
                ensureOutputCapacity(max(1, outputBuffer.size * 2))
            }
            outputBuffer[outputIndex++] = convolve(leftIndex, (phasePosition % phaseCount).toInt())
            outputFramesProduced++
        }
        return outputIndex
    }

    /**
     * Returns the input position of [outputFrame] in units of 1/[phaseCount] input samples, rounded to the
     * nearest phase. Exact integer math avoids the drift of accumulating a fractional step over long files;
     * the product stays far below Long.MAX_VALUE for any WAV-sized output.
     */
    private fun phasePositionOf(outputFrame: Long): Long {
        val doubledOutputRate = 2L * outputRate
        return (outputFrame * inputRate * phaseCount * 2L + outputRate) / doubledOutputRate
    }

    private fun convolve(leftIndex: Int, phase: Int): Float {
        val filter = kernel[phase]
        val input = workBuffer
        // Independent partial sums let the multiply-adds overlap instead of waiting on one accumulator.
        var sum0 = 0f
        var sum1 = 0f
        var sum2 = 0f
        var sum3 = 0f
        var tap = 0
        var inputIndex = leftIndex
        val unrolledTaps = tapCount - tapCount % 4
        while (tap < unrolledTaps) {
            sum0 += input[inputIndex] * filter[tap]
            sum1 += input[inputIndex + 1] * filter[tap + 1]
            sum2 += input[inputIndex + 2] * filter[tap + 2]
            sum3 += input[inputIndex + 3] * filter[tap + 3]
            tap += 4
            inputIndex += 4
        }
        while (tap < tapCount) {
            sum0 += input[inputIndex++] * filter[tap++]
        }
        return (sum0 + sum1) + (sum2 + sum3)
    }

    private fun ensureWorkCapacity(minimumSize: Int) {
        if (workBuffer.size < minimumSize) {
            workBuffer = FloatArray(max(minimumSize, workBuffer.size.coerceAtLeast(8_192) * 2))
        }
    }

    private fun ensureOutputCapacity(minimumSize: Int) {
        if (outputBuffer.size < minimumSize) {
            outputBuffer = FloatArray(max(minimumSize, outputBuffer.size.coerceAtLeast(8_192) * 2))
        }
    }

    private companion object {
        private const val DEFAULT_PHASES = 1_024
        private const val BASE_TAPS_AT_48_KHZ = 96
        private const val MAX_TAPS = 384
        private const val CUTOFF_MARGIN = 0.9
        private const val OUTPUT_CAPACITY_MARGIN = 32L

        /**
         * Output samples fall on exactly outputRate / gcd(inputRate, outputRate) distinct input phases, so
         * common ratios get exact positions from a small kernel: one phase for 48 kHz, 160 for 44.1 kHz.
         */
        fun recommendedPhaseCount(inputRate: Int, outputRate: Int): Int {
            val exactPhases = outputRate / greatestCommonDivisor(inputRate, outputRate)
            return if (exactPhases <= DEFAULT_PHASES) exactPhases else DEFAULT_PHASES
        }

        tailrec fun greatestCommonDivisor(first: Int, second: Int): Int =
            if (second == 0) first else greatestCommonDivisor(second, first % second)

        fun recommendedTapCount(inputRate: Int): Int {
            val scaled = ceil(BASE_TAPS_AT_48_KHZ * max(1.0, inputRate / 48_000.0)).toInt()
            return scaled.coerceAtMost(MAX_TAPS).let { if (it % 2 == 0) it else it + 1 }
        }

        fun blackmanWindow(index: Int, count: Int): Double {
            if (count <= 1) return 1.0
            val twoPi = 2.0 * Math.PI
            val fourPi = 4.0 * Math.PI
            return 0.42 -
                0.5 * cos(twoPi * index / (count - 1)) +
                0.08 * cos(fourPi * index / (count - 1))
        }

        fun ceilDiv(dividend: Long, divisor: Long): Long = if (dividend == 0L) 0L else 1L + (dividend - 1L) / divisor
    }
}

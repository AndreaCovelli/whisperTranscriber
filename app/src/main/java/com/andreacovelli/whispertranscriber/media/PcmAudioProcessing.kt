package com.andreacovelli.whispertranscriber.media

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
                mixed += source[sourceIndex++].toInt() * PCM16_TO_FLOAT * gains[channel]
            }
            destination[destinationIndex] = mixed * HEADROOM
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

internal class MonoPcmResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    taps: Int = recommendedTapCount(inputRate),
    phases: Int = DEFAULT_PHASES,
) {
    private val tapCount = taps
    private val phaseCount = phases
    private val center = tapCount / 2 - 1
    private val step = inputRate.toDouble() / outputRate.toDouble()
    private val kernel: Array<FloatArray>
    private val tail = FloatArray(tapCount)
    private var workBuffer = FloatArray(0)
    private var totalFramesSeen = 0L
    private var outputFramesProduced = 0L
    private var sourcePosition = 0.0

    init {
        require(inputRate > 0)
        require(outputRate > 0)
        require(tapCount >= 16 && tapCount % 2 == 0)
        require(phaseCount > 0)

        val cutoff = 0.5 * min(1.0, outputRate.toDouble() / inputRate.toDouble()) * CUTOFF_MARGIN
        kernel = Array(phaseCount) { phaseIndex ->
            val fraction = phaseIndex.toDouble() / phaseCount.toDouble()
            val coefficients = DoubleArray(tapCount)
            var sum = 0.0
            for (tap in 0 until tapCount) {
                val distance = (tap - center) - fraction
                val x = 2.0 * cutoff * distance
                val sinc = if (abs(x) < 1e-8) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                val value = (2.0 * cutoff) * sinc * blackmanWindow(tap, tapCount)
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
        var relativePosition = sourcePosition - workStartGlobal
        val approximateOutputFrames =
            ((framesIn * outputRate.toLong()) / inputRate.toLong() + OUTPUT_CAPACITY_MARGIN).toInt()
        ensureOutputCapacity(approximateOutputFrames.coerceAtLeast(0))
        var outputIndex = 0

        while (true) {
            val samplePosition = resolveSamplePosition(relativePosition)
            val leftIndex = samplePosition.base - center
            if (leftIndex + tapCount > workFrames) break

            if (leftIndex >= 0) {
                if (outputIndex >= outputBuffer.size) {
                    ensureOutputCapacity(max(1, outputBuffer.size * 2))
                }
                outputBuffer[outputIndex++] = convolve(leftIndex, samplePosition.phase)
                outputFramesProduced++
            }

            relativePosition += step
            sourcePosition += step
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
        var relativePosition = sourcePosition - workStartGlobal
        val targetOutputFrames = ceilDiv(totalFramesSeen * outputRate.toLong(), inputRate.toLong())
        ensureOutputCapacity((targetOutputFrames - outputFramesProduced).coerceAtLeast(0).toInt())
        var outputIndex = 0

        while (outputFramesProduced < targetOutputFrames) {
            val samplePosition = resolveSamplePosition(relativePosition)
            val leftIndex = samplePosition.base - center
            check(leftIndex >= 0 && leftIndex + tapCount <= workFrames)
            if (outputIndex >= outputBuffer.size) {
                ensureOutputCapacity(max(1, outputBuffer.size * 2))
            }
            outputBuffer[outputIndex++] = convolve(leftIndex, samplePosition.phase)
            outputFramesProduced++
            relativePosition += step
            sourcePosition += step
        }
        return outputIndex
    }

    private fun resolveSamplePosition(relativePosition: Double): SamplePosition {
        var base = floor(relativePosition).toInt()
        val fraction = relativePosition - base
        var phase = (fraction * phaseCount).roundToInt()
        if (phase == phaseCount) {
            phase = 0
            base++
        }
        return SamplePosition(base, phase)
    }

    private fun convolve(leftIndex: Int, phase: Int): Float {
        val filter = kernel[phase]
        var accumulator = 0f
        var tap = 0
        var inputIndex = leftIndex
        while (tap < tapCount) {
            accumulator += workBuffer[inputIndex++] * filter[tap++]
        }
        return accumulator
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

    private data class SamplePosition(val base: Int, val phase: Int)

    private companion object {
        private const val DEFAULT_PHASES = 1_024
        private const val BASE_TAPS_AT_48_KHZ = 96
        private const val MAX_TAPS = 384
        private const val CUTOFF_MARGIN = 0.9
        private const val OUTPUT_CAPACITY_MARGIN = 32L

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

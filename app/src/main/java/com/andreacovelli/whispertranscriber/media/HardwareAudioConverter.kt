package com.andreacovelli.whispertranscriber.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HardwareAudioConverter {

    data class Result(
        val sampleRate: Int,
        val channelCount: Int,
        val durationUs: Long
    )

    suspend fun convertToWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        forceMono: Boolean = true,
        targetSampleRate: Int = 16_000,
        onProgress: (suspend (Float) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        require(targetSampleRate > 0)

        val extractor = MediaExtractor()
        // Use specific exception for clearer error handling
        val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            ?: throw FileNotFoundException("Unable to open input URI: $inputUri")
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } finally {
            afd.close()
        }

        var audioTrackIndex = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                trackFormat = f
                break
            }
        }
        require(audioTrackIndex >= 0) { "No audio track found" }
        extractor.selectTrack(audioTrackIndex)
        val srcFormat = trackFormat!!
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)!!

        val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION))
            srcFormat.getLong(MediaFormat.KEY_DURATION) else -1L
        if (durationUs > 0) onProgress?.invoke(0f)

        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        var decSampleRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var decChannels = if (srcFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        var outChannelsToWrite = if (forceMono) 1 else decChannels
        val writer = WavWriter(outputFile, targetSampleRate, outChannelsToWrite, 16)

        val needResample = { inRate: Int -> inRate != targetSampleRate }
        var resampler: PolyphaseResampler? = null

        var shortScratch = ShortArray(0)
        var floatScratch = FloatArray(0)   // holds interleaved floats (no downmix)
        var monoScratch = FloatArray(0)    // holds mono floats (downmixed)
        fun ensureShortCapacity(minSize: Int) {
            if (shortScratch.size < minSize) {
                shortScratch = ShortArray(max(minSize, shortScratch.size.coerceAtLeast(1024) * 2))
            }
        }
        fun ensureFloatCapacity(minSize: Int) {
            if (floatScratch.size < minSize) {
                floatScratch = FloatArray(max(minSize, floatScratch.size.coerceAtLeast(2048) * 2))
            }
        }
        fun ensureMonoCapacity(minSize: Int) {
            if (monoScratch.size < minSize) {
                monoScratch = FloatArray(max(minSize, monoScratch.size.coerceAtLeast(2048) * 2))
            }
        }

        val inputTimeoutUs = 10000L
        val outputTimeoutUs = 10000L
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false

        val hasDuration = durationUs > 0
        var lastProgress = -1f

        try {
            while (!outputEos && currentCoroutineContext().isActive) {
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(inputTimeoutUs)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)!!
                        ib.clear()
                        val size = extractor.readSampleData(ib, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, size, pts, 0)

                            // Progress update based on queued input PTS
                            if (hasDuration && pts >= 0) {
                                val p = (pts.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0).toFloat()
                                if (p - lastProgress >= 0.01f || p == 1f) {
                                    lastProgress = p
                                    onProgress?.invoke(p)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = decoder.dequeueOutputBuffer(info, outputTimeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            decSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            decChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        pcmEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        outChannelsToWrite = if (forceMono) 1 else decChannels

                        if (needResample(decSampleRate)) {
                            resampler = PolyphaseResampler(
                                inRate = decSampleRate,
                                outRate = targetSampleRate,
                                channels = outChannelsToWrite
                            )
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIdx >= 0) {
                            val ob = decoder.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                ob.order(ByteOrder.LITTLE_ENDIAN)

                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floatsCount = info.size / 4
                                    val frames = floatsCount / decChannels
                                    ensureFloatCapacity(floatsCount)
                                    ob.asFloatBuffer().get(floatScratch, 0, floatsCount)

                                    val (interleaved, interleavedSamples, framesAfterMix) =
                                        if (outChannelsToWrite == decChannels) {
                                            Triple(floatScratch, floatsCount, frames)
                                        } else {
                                            ensureMonoCapacity(frames)
                                            val outCount = downmixToMonoInterleavedInto(
                                                src = floatScratch,
                                                frames = frames,
                                                srcChannels = decChannels,
                                                dst = monoScratch
                                            )
                                            Triple(monoScratch, outCount, frames)
                                        }

                                    // Resample or write directly (Optimized: Handle Pair<Count, Buffer>)
                                    val resPair = resampler?.process(interleaved, framesAfterMix)
                                    if (resPair != null) {
                                        val (count, buffer) = resPair
                                        writer.writeFloatsInterleaved(buffer, outChannelsToWrite, count)
                                    } else if (resampler == null) {
                                        writer.writeFloatsInterleaved(interleaved, outChannelsToWrite, interleavedSamples)
                                    }
                                } else {
                                    // PCM16 -> float path
                                    val shortsCount = info.size / 2
                                    ensureShortCapacity(shortsCount)
                                    ob.asShortBuffer().get(shortScratch, 0, shortsCount)

                                    val frames = shortsCount / decChannels
                                    val floatsNeeded = frames * decChannels
                                    ensureFloatCapacity(floatsNeeded)

                                    // Convert to float [-1,1]
                                    val inv = 1.0f / 32768.0f
                                    var si = 0
                                    var fi = 0
                                    while (si < shortsCount) {
                                        // Optimized: Removed redundant coerceIn as shorts are inherently in range
                                        floatScratch[fi++] = (shortScratch[si++].toInt() * inv)
                                    }

                                    // (Downmix remains the same)
                                    val (interleaved, interleavedSamples, framesAfterMix) =
                                        if (outChannelsToWrite == decChannels) {
                                            Triple(floatScratch, floatsNeeded, frames)
                                        } else {
                                            ensureMonoCapacity(frames)
                                            val outCount = downmixToMonoInterleavedInto(
                                                src = floatScratch,
                                                frames = frames,
                                                srcChannels = decChannels,
                                                dst = monoScratch
                                            )
                                            Triple(monoScratch, outCount, frames)
                                        }

                                    // Resample or write directly (Optimized: Handle Pair<Count, Buffer>)
                                    val resPair = resampler?.process(interleaved, framesAfterMix)
                                    if (resPair != null) {
                                        val (count, buffer) = resPair
                                        writer.writeFloatsInterleaved(buffer, outChannelsToWrite, count)
                                    } else if (resampler == null) {
                                        writer.writeFloatsInterleaved(interleaved, outChannelsToWrite, interleavedSamples)
                                    }
                                }
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (eos) outputEos = true
                        }
                    }
                }
            }

            // Flush remaining samples (Optimized: Handle Pair<Count, Buffer>)
            resampler?.finish()?.let { (flushCount, flushBuffer) ->
                if (flushCount > 0) {
                    writer.writeFloatsInterleaved(flushBuffer, outChannelsToWrite, flushCount)
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { writer.close() } catch (_: Exception) {}
            if (hasDuration) onProgress?.invoke(1f)
        }

        Result(
            sampleRate = targetSampleRate,
            channelCount = outChannelsToWrite,
            durationUs = durationUs
        )
    }

    // Downmix to mono with 0.9f headroom into a provided buffer
    // src: interleaved float with srcChannels, frames total
    // dst: must have capacity >= frames
    // returns the number of output samples written (equals frames for mono)
    private fun downmixToMonoInterleavedInto(
        src: FloatArray,
        frames: Int,
        srcChannels: Int,
        dst: FloatArray
    ): Int {
        val scale = 0.9f / srcChannels
        var i = 0
        var o = 0
        val total = frames * srcChannels
        while (i < total) {
            var sum = 0f
            for (c in 0 until srcChannels) sum += src[i + c]
            dst[o++] = sum * scale
            i += srcChannels
        }
        return frames
    }

    // OPTIMIZED: Uses reusable FloatArray buffers instead of ArrayList.
    private class PolyphaseResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val channels: Int,
        taps: Int = 16,
        phases: Int = 1024
    ) {
        private val ntaps = taps
        private val nphases = phases
        private val center = ntaps / 2 - 1
        private val step = inRate.toDouble() / outRate.toDouble()
        private val kernel: Array<FloatArray>
        private var tail: FloatArray = FloatArray(ntaps * channels)
        private var totalFramesSeen: Long = 0L
        private var srcPosGlobal = 0.0

        // Reusable output buffer
        private var outBuffer = FloatArray(0)
        private fun ensureOutCapacity(minSize: Int) {
            if (outBuffer.size < minSize) {
                outBuffer = FloatArray(max(minSize, outBuffer.size.coerceAtLeast(8192) * 2))
            }
        }

        init {
            val cutoff = 0.5 * min(1.0, outRate.toDouble() / inRate.toDouble()) * 0.95
            kernel = Array(nphases) { p ->
                val frac = p.toDouble() / nphases.toDouble()
                val h = DoubleArray(ntaps)
                var sum = 0.0
                for (j in 0 until ntaps) {
                    val t = (j - center) - frac
                    val x = 2.0 * cutoff * t
                    val sinc = if (abs(x) < 1e-8) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                    val blackman = blackmanWindow(j, ntaps)
                    val v = (2.0 * cutoff) * sinc * blackman
                    h[j] = v
                    sum += v
                }
                val scale = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
                FloatArray(ntaps) { j -> (h[j] * scale).toFloat() }
            }
        }

        // Returns Pair<Count, Buffer>
        fun process(inputInterleaved: FloatArray, framesIn: Int): Pair<Int, FloatArray>? {
            val workFrames = ntaps + framesIn
            // Allocating work buffer here is acceptable as it assembles the input stream
            val work = FloatArray(workFrames * channels)
            System.arraycopy(tail, 0, work, 0, tail.size)
            if (framesIn > 0) {
                System.arraycopy(inputInterleaved, 0, work, tail.size, framesIn * channels)
            }

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            val approxOutFrames = ((framesIn * outRate.toLong()) / inRate.toLong() + 32L).toInt().coerceAtLeast(0)

            ensureOutCapacity(approxOutFrames * channels)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center

                // Handle boundary conditions robustly
                if (leftIndex < 0) {
                    posRel += step
                    srcPosGlobal += step
                    continue
                }

                if (leftIndex + ntaps > workFrames) break

                val frac = posRel - base
                var phase = (frac * nphases).roundToInt()
                if (phase == nphases) phase = 0
                val k = kernel[phase]

                // Ensure capacity before writing output in the loop
                if (outIndex + channels > outBuffer.size) {
                    ensureOutCapacity(outBuffer.size * 2)
                }

                for (ch in 0 until channels) {
                    var acc = 0.0f
                    var j = 0
                    var idx = (leftIndex * channels) + ch
                    while (j < ntaps) {
                        acc += work[idx] * k[j]
                        idx += channels
                        j++
                    }
                    outBuffer[outIndex++] = acc // Write directly to buffer
                }

                posRel += step
                srcPosGlobal += step
            }

            // Update tail buffer
            val copyStartFrames = max(0, workFrames - ntaps)
            val copyStart = copyStartFrames * channels
            if (copyStart < work.size) {
                // Ensure we don't copy beyond the bounds
                val lenToCopy = min(ntaps * channels, work.size - copyStart)
                if (lenToCopy > 0) {
                    System.arraycopy(work, copyStart, tail, 0, lenToCopy)
                }
            }
            totalFramesSeen += framesIn

            if (outIndex == 0) return null
            return Pair(outIndex, outBuffer)
        }

        fun finish(): Pair<Int, FloatArray>? {
            val (count, buffer) = finishInternal()
            if (count == 0) return null
            return Pair(count, buffer)
        }

        private fun finishInternal(): Pair<Int, FloatArray> {
            val workFrames = ntaps + ntaps // Tail + zero padding
            val work = FloatArray(workFrames * channels)
            System.arraycopy(tail, 0, work, 0, tail.size)
            // Padding is implicitly zeros

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            val approxOutFrames = ntaps * 2 // Heuristic
            ensureOutCapacity(approxOutFrames * channels)
            var outIndex = 0

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center

                if (leftIndex < 0) {
                    posRel += step
                    srcPosGlobal += step
                    continue
                }

                // Stop when the filter center passes the end of the actual data (end of tail buffer).
                if (leftIndex >= ntaps) break

                // Safety break if we run out of the zero-padded buffer
                if (leftIndex + ntaps > workFrames) break

                val frac = posRel - base
                var phase = (frac * nphases).roundToInt()
                if (phase == nphases) phase = 0
                val k = kernel[phase]

                // Ensure capacity in the loop
                if (outIndex + channels > outBuffer.size) {
                    ensureOutCapacity(outBuffer.size * 2)
                }

                for (ch in 0 until channels) {
                    var acc = 0.0f
                    var j = 0
                    var idx = (leftIndex * channels) + ch
                    while (j < ntaps) {
                        acc += work[idx] * k[j]
                        idx += channels
                        j++
                    }
                    outBuffer[outIndex++] = acc
                }

                posRel += step
                srcPosGlobal += step
            }

            return Pair(outIndex, outBuffer)
        }

        private fun blackmanWindow(n: Int, ntaps: Int): Double {
            if (ntaps <= 1) return 1.0
            val a0 = 0.42
            val a1 = 0.5
            val a2 = 0.08
            val twoPi = 2.0 * Math.PI
            val fourPi = 4.0 * Math.PI
            return a0 - a1 * cos(twoPi * n / (ntaps - 1)) + a2 * cos(fourPi * n / (ntaps - 1))
        }
    }

    // OPTIMIZED: Uses ByteBuffer and ShortBuffer for efficient writing.
    private class WavWriter(
        private val file: File,
        sampleRate: Int,
        private val channels: Int,
        bitsPerSample: Int
    ) : AutoCloseable {

        private val out = BufferedOutputStream(FileOutputStream(file))
        private var dataBytesWritten = 0L
        private var byteScratch = ByteArray(0)
        // Added ByteBuffer wrapper
        private var byteBuffer: ByteBuffer? = null

        private fun ensureByteCapacity(minBytes: Int) {
            if (byteScratch.size < minBytes) {
                val newSize = max(minBytes, byteScratch.size.coerceAtLeast(16384) * 2)
                byteScratch = ByteArray(newSize)
                // Re-wrap with the new array
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            } else if (byteBuffer == null) {
                // Initialize if array existed but buffer didn't
                byteBuffer = ByteBuffer.wrap(byteScratch).order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        init {
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(0) // placeholder
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16) // PCM
            bb.putShort(1) // format PCM
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            bb.putInt(byteRate)
            val blockAlign = channels * bitsPerSample / 8
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(0) // placeholder for data size
            out.write(header)
        }

        // Optimized implementation
        fun writeFloatsInterleaved(samples: FloatArray, channels: Int, sampleCount: Int = samples.size) {
            require(channels == this.channels)
            if (sampleCount <= 0) return
            val byteCount = sampleCount * 2 // 16-bit
            ensureByteCapacity(byteCount)

            val bb = byteBuffer!!
            bb.clear()
            // Use ShortBuffer view for bulk conversion
            val shortBuffer = bb.asShortBuffer()

            var i = 0
            while (i < sampleCount) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                val s = v.coerceIn(-32768, 32767).toShort()
                shortBuffer.put(s)
                i++
            }
            out.write(byteScratch, 0, byteCount)
            dataBytesWritten += byteCount
        }

        // Optimized close() using a reusable buffer for RandomAccessFile writes
        override fun close() {
            try {
                out.flush()
            } finally {
                out.close()
            }
            RandomAccessFile(file, "rw").use { raf ->
                val intBuf = ByteArray(4) // Reusable buffer
                raf.seek(4)
                writeIntLE(raf, (36 + dataBytesWritten).toInt(), intBuf)
                raf.seek(40)
                writeIntLE(raf, dataBytesWritten.toInt(), intBuf)
            }
        }

        // New helper function (Replaces the old extension function for efficiency)
        private fun writeIntLE(raf: RandomAccessFile, value: Int, buf: ByteArray) {
            buf[0] = (value and 0xff).toByte()
            buf[1] = ((value shr 8) and 0xff).toByte()
            buf[2] = ((value shr 16) and 0xff).toByte()
            buf[3] = ((value shr 24) and 0xff).toByte()
            raf.write(buf)
        }
    }
}
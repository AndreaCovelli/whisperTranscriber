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

object HardwareAudioConverter {

    data class Result(
        val sampleRate: Int,
        val channelCount: Int,
        val durationUs: Long
    )

    // Hardware-accelerated decode -> PCM16 WAV
    // forceMono: downmix to 1 channel to reduce size
    // targetSampleRate: resample to this rate (default 16_000)
    suspend fun convertToWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        forceMono: Boolean = true,
        targetSampleRate: Int = 16_000
    ): Result {
        require(targetSampleRate > 0)

        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
            ?: error("Unable to open input")
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

        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        var decSampleRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var decChannels = if (srcFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        // Output format: always PCM16 WAV @ targetSampleRate, channels = 1 if forceMono else decoder channels
        var outChannelsToWrite = if (forceMono) 1 else decChannels
        val writer = WavWriter(outputFile, targetSampleRate, outChannelsToWrite, 16)

        val needResample = { inRate: Int -> inRate != targetSampleRate }
        var resampler: PolyphaseResampler? = null

        val inputTimeoutUs = 10000L
        val outputTimeoutUs = 10000L
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false

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

                        // Initialize resampler if needed
                        if (needResample(decSampleRate)) {
                            resampler = PolyphaseResampler(
                                inRate = decSampleRate,
                                outRate = targetSampleRate,
                                channels = outChannelsToWrite
                            )
                        }
                        // If the writer was created earlier, it already uses targetSampleRate/outChannels
                        // so nothing else to do here.
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIdx >= 0) {
                            val ob = decoder.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)

                                // Convert decoder output to float interleaved in target channel layout
                                val framesIn = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floats = FloatArray(info.size / 4)
                                    ob.asFloatBuffer().get(floats)
                                    val frames = floats.size / decChannels
                                    val interleaved = if (outChannelsToWrite == decChannels) {
                                        floats
                                    } else {
                                        downmixToMonoInterleaved(floats, frames, decChannels)
                                    }
                                    // Resample if needed
                                    val outFloats = resampler?.process(interleaved, frames, end = false)
                                        ?: interleaved
                                    writer.writeFloatsInterleaved(outFloats, outChannelsToWrite)
                                    frames
                                } else {
                                    // PCM16 -> float
                                    val shorts = ShortArray(info.size / 2)
                                    ob.asShortBuffer().get(shorts)
                                    val frames = shorts.size / decChannels
                                    val floats = FloatArray(frames * decChannels)
                                    // convert to float [-1,1]
                                    var si = 0
                                    var fi = 0
                                    val inv = 1.0f / 32768.0f
                                    while (si < shorts.size) {
                                        floats[fi++] = (shorts[si++].toInt().coerceIn(-32768, 32767) * inv)
                                    }
                                    val interleaved = if (outChannelsToWrite == decChannels) {
                                        floats
                                    } else {
                                        downmixToMonoInterleaved(floats, frames, decChannels)
                                    }
                                    val outFloats = resampler?.process(interleaved, frames, end = false)
                                        ?: interleaved
                                    writer.writeFloatsInterleaved(outFloats, outChannelsToWrite)
                                    frames
                                }
                                // framesIn computed above; not used further here
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outIdx, false)
                            if (eos) outputEos = true
                        }
                    }
                }
            }

            // Flush remaining samples through the resampler
            resampler?.finish()?.let { flushFloats ->
                if (flushFloats.isNotEmpty()) {
                    writer.writeFloatsInterleaved(flushFloats, outChannelsToWrite)
                }
            }

        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { writer.close() } catch (_: Exception) {}
        }

        return Result(
            sampleRate = targetSampleRate,
            channelCount = outChannelsToWrite,
            durationUs = durationUs
        )
    }

    private fun downmixToMonoInterleaved(src: FloatArray, frames: Int, srcChannels: Int): FloatArray {
        val out = FloatArray(frames)
        var i = 0
        var o = 0
        while (i < frames * srcChannels) {
            var sum = 0f
            for (c in 0 until srcChannels) sum += src[i + c]
            out[o++] = sum / srcChannels
            i += srcChannels
        }
        return out
    }

    // High-quality polyphase windowed-sinc resampler (streaming, interleaved floats)
    private class PolyphaseResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val channels: Int,
        taps: Int = 16,
        phases: Int = 1024
    ) {
        private val ntaps = taps
        private val nphases = phases
        private val center = ntaps / 2 - 1 // even-length center alignment
        private val step = inRate.toDouble() / outRate.toDouble() // input frames advanced per output sample
        private val kernel: Array<FloatArray> // [phase][tap]
        private var tail: FloatArray = FloatArray(ntaps * channels)
        private var totalFramesSeen: Long = 0L // number of "real" input frames consumed (excl. tail)
        private var srcPosGlobal = 0.0 // next virtual source pos (in frames)

        init {
            // cutoff = 0.5 * min(1, outRate/inRate) with small safety margin to reduce aliasing
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
                // Normalize DC gain per phase
                val scale = if (abs(sum) < 1e-12) 1.0 else 1.0 / sum
                FloatArray(ntaps) { j -> (h[j] * scale).toFloat() }
            }
        }

        fun process(inputInterleaved: FloatArray, framesIn: Int, end: Boolean): FloatArray {
            // Build work buffer = tail + input
            val workFrames = ntaps + framesIn
            val work = FloatArray(workFrames * channels)
            // copy tail
            System.arraycopy(tail, 0, work, 0, tail.size)
            // copy input after tail
            if (framesIn > 0) {
                System.arraycopy(inputInterleaved, 0, work, tail.size, framesIn * channels)
            }

            // Compute relative position for this block
            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            // Predict output capacity (safe upper bound) and allocate
            val approxOutFrames = ((framesIn * outRate.toLong()) / inRate.toLong() + 32L).toInt().coerceAtLeast(0)
            val out = ArrayList<Float>(approxOutFrames * channels)

            // Emit output while we have enough left/right support for convolution
            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                if (leftIndex < 0) break
                if (leftIndex + ntaps > workFrames) break

                val frac = posRel - base
                var phase = (frac * nphases).roundToInt()
                if (phase == nphases) phase = 0
                val k = kernel[phase]

                // Convolve per channel
                for (ch in 0 until channels) {
                    var acc = 0.0f
                    var j = 0
                    var idx = (leftIndex * channels) + ch
                    while (j < ntaps) {
                        acc += work[idx] * k[j]
                        idx += channels
                        j++
                    }
                    out.add(acc)
                }

                posRel += step
                srcPosGlobal += step
            }

            // Update tail for next call with the last ntaps frames of "work"
            val copyStartFrames = max(0, workFrames - ntaps)
            val copyStart = copyStartFrames * channels
            if (copyStart < work.size) {
                System.arraycopy(work, copyStart, tail, 0, ntaps * channels)
            }

            // Update "consumed" frames count
            totalFramesSeen += framesIn

            // If end, we might still be able to produce more by padding zeros
            if (end) {
                out.addAll(finishInternal())
            }

            // Materialize to FloatArray
            val arr = FloatArray(out.size)
            for (i in arr.indices) arr[i] = out[i]
            return arr
        }

        fun finish(): FloatArray = finishInternal().toFloatArray()

        private fun finishInternal(): ArrayList<Float> {
            val out = ArrayList<Float>(ntaps * channels)
            // Create padded work: tail + zeros(ntaps)
            val workFrames = ntaps + ntaps
            val work = FloatArray(workFrames * channels)
            System.arraycopy(tail, 0, work, 0, tail.size)

            val workStartGlobal = totalFramesSeen - ntaps
            var posRel = srcPosGlobal - workStartGlobal

            while (true) {
                val base = kotlin.math.floor(posRel).toInt()
                val leftIndex = base - center
                if (leftIndex < 0) break
                if (leftIndex + ntaps > workFrames) break

                val frac = posRel - base
                var phase = (frac * nphases).roundToInt()
                if (phase == nphases) phase = 0
                val k = kernel[phase]

                for (ch in 0 until channels) {
                    var acc = 0.0f
                    var j = 0
                    var idx = (leftIndex * channels) + ch
                    while (j < ntaps) {
                        acc += work[idx] * k[j]
                        idx += channels
                        j++
                    }
                    out.add(acc)
                }

                posRel += step
                srcPosGlobal += step
            }

            // No need to update tail further; stream is finished.
            return out
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

    private class WavWriter(
        private val file: File,
        sampleRate: Int,
        private val channels: Int,
        bitsPerSample: Int
    ) : AutoCloseable {

        private val out = BufferedOutputStream(FileOutputStream(file))
        private var dataBytesWritten = 0L

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

        fun writeFloatsInterleaved(samples: FloatArray, channels: Int) {
            require(channels == this.channels)
            if (samples.isEmpty()) return
            val bytes = ByteArray(samples.size * 2) // 16-bit
            var bi = 0
            var i = 0
            while (i < samples.size) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                val s = v.coerceIn(-32768, 32767)
                bytes[bi++] = (s and 0xff).toByte()
                bytes[bi++] = ((s shr 8) and 0xff).toByte()
                i++
            }
            out.write(bytes)
            dataBytesWritten += bytes.size
        }

        override fun close() {
            try {
                out.flush()
            } finally {
                out.close()
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.writeIntLE((36 + dataBytesWritten).toInt())
                raf.seek(40)
                raf.writeIntLE(dataBytesWritten.toInt())
            }
        }

        private fun RandomAccessFile.writeIntLE(value: Int) {
            write(byteArrayOf(
                (value and 0xff).toByte(),
                ((value shr 8) and 0xff).toByte(),
                ((value shr 16) and 0xff).toByte(),
                ((value shr 24) and 0xff).toByte()
            ))
        }
    }
}
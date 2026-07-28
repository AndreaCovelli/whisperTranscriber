package com.andreacovelli.whispertranscriber.media

import android.os.storage.StorageManager
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID

internal object ConversionOutputStorage {
    private const val MICROSECONDS_PER_SECOND = 1_000_000L
    private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    private const val CONVERSION_SIZE_BUFFER_BYTES = 64L * 1024L
    private const val DEFAULT_CONVERSION_SIZE_BYTES = 50L * BYTES_PER_MEBIBYTE
    private const val WAV_HEADER_BYTES = 44L

    fun reserve(
        storageManager: StorageManager,
        fileDescriptor: FileDescriptor,
        outputFile: File,
        durationUs: Long,
        maximumOutputDataBytes: Long,
    ) {
        val estimatedBytes = if (durationUs > 0) {
            estimateConvertedDataBytes(durationUs, maximumOutputDataBytes) +
                WAV_HEADER_BYTES +
                CONVERSION_SIZE_BUFFER_BYTES
        } else {
            minOf(
                DEFAULT_CONVERSION_SIZE_BYTES,
                maximumOutputDataBytes + WAV_HEADER_BYTES + CONVERSION_SIZE_BUFFER_BYTES,
            )
        }
        val parentDir = outputFile.parentFile
        val storageUuid = resolveStorageUuid(storageManager, parentDir)
        if (storageUuid != null) {
            val allocatableBytes = storageManager.getAllocatableBytes(storageUuid)
            if (allocatableBytes < estimatedBytes) {
                throw insufficientStorageException(estimatedBytes, allocatableBytes, "allocatable")
            }
            storageManager.allocateBytes(fileDescriptor, estimatedBytes)
            return
        }
        ensureUsableSpace(parentDir, estimatedBytes)
    }

    private fun estimateConvertedDataBytes(durationUs: Long, maximumOutputDataBytes: Long): Long {
        val wholeSeconds = durationUs / MICROSECONDS_PER_SECOND
        val maximumWholeSeconds = maximumOutputDataBytes / PCM16_MONO_BYTES_PER_SECOND
        if (wholeSeconds >= maximumWholeSeconds) return maximumOutputDataBytes

        val wholeSecondBytes = wholeSeconds * PCM16_MONO_BYTES_PER_SECOND
        val remainingMicroseconds = durationUs % MICROSECONDS_PER_SECOND
        val partialSecondBytes = ceilDiv(
            remainingMicroseconds * PCM16_MONO_BYTES_PER_SECOND,
            MICROSECONDS_PER_SECOND,
        )
        return minOf(wholeSecondBytes + partialSecondBytes, maximumOutputDataBytes)
    }

    private fun resolveStorageUuid(storageManager: StorageManager, parentDir: File?): UUID? {
        if (parentDir == null) return null
        return try {
            storageManager.getUuidForPath(parentDir)
        } catch (error: IOException) {
            logStorageUuidFailure(parentDir, error)
            null
        } catch (error: IllegalArgumentException) {
            logStorageUuidFailure(parentDir, error)
            null
        }
    }

    private fun logStorageUuidFailure(parentDir: File, error: Exception) {
        android.util.Log.w(
            "HardwareAudioConverter",
            "Failed to get storage UUID for path $parentDir, falling back to usableSpace",
            error,
        )
    }

    private fun ensureUsableSpace(parentDir: File?, estimatedBytes: Long) {
        val usableSpace = parentDir?.usableSpace ?: 0L
        if (usableSpace < estimatedBytes) {
            throw insufficientStorageException(estimatedBytes, usableSpace, "available")
        }
    }

    private fun insufficientStorageException(
        estimatedBytes: Long,
        availableBytes: Long,
        availableDescription: String,
    ): IOException = IOException(
        "Insufficient disk space for conversion. Needed at least " +
            "${estimatedBytes / BYTES_PER_MEBIBYTE} MiB, but only " +
            "${availableBytes / BYTES_PER_MEBIBYTE} MiB is $availableDescription.",
    )

    private fun ceilDiv(dividend: Long, divisor: Long): Long =
        if (dividend == 0L) 0L else 1L + (dividend - 1L) / divisor
}

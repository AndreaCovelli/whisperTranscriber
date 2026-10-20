package com.andreacovelli.whispertranscriber.media

import com.andreacovelli.whispertranscriber.R
import com.andreacovelli.whispertranscriber.UserFacingIOException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversionOutputStorageTest {
    @Test
    fun allocate_rejectsEstimateAboveAllocatableBytesWithoutAllocating() {
        var allocated = false

        val error = assertThrows(UserFacingIOException::class.java) {
            ConversionOutputStorage.allocate(estimatedBytes = 2 * MEBIBYTE, allocatableBytes = MEBIBYTE) {
                allocated = true
            }
        }

        assertFalse(allocated)
        assertEquals(R.string.conversion_insufficient_storage, error.userMessage.id)
        assertEquals(listOf(2L, 1L), error.userMessage.args)
    }

    @Test
    fun allocate_reportsAllocationFailureAsInsufficientStorage() {
        val allocationFailure = IOException("Failed to allocate")

        val error = assertThrows(UserFacingIOException::class.java) {
            ConversionOutputStorage.allocate(estimatedBytes = MEBIBYTE, allocatableBytes = 3 * MEBIBYTE) {
                throw allocationFailure
            }
        }

        assertEquals(R.string.conversion_insufficient_storage, error.userMessage.id)
        assertEquals(listOf(1L, 3L), error.userMessage.args)
        assertSame(allocationFailure, error.cause)
    }

    private companion object {
        const val MEBIBYTE = 1024L * 1024L
    }
}

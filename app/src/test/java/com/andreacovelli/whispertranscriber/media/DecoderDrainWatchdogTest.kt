package com.andreacovelli.whispertranscriber.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderDrainWatchdogTest {
    private var nowNs = 0L
    private val watchdog = DecoderDrainWatchdog(timeoutNs = 100L) { nowNs }

    @Test
    fun reportsStallOnlyAfterTimeoutOfContinuousIdlePolls() {
        nowNs = 1_000L
        assertFalse(watchdog.isStalled())

        nowNs = 1_099L
        assertFalse(watchdog.isStalled())

        nowNs = 1_100L
        assertTrue(watchdog.isStalled())
    }

    @Test
    fun decoderOutputRestartsIdleTimer() {
        nowNs = 1_000L
        assertFalse(watchdog.isStalled())

        nowNs = 1_090L
        watchdog.onOutput()
        assertFalse(watchdog.isStalled())

        nowNs = 1_150L
        assertFalse(watchdog.isStalled())

        nowNs = 1_190L
        assertTrue(watchdog.isStalled())
    }
}

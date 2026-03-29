package com.paul.sprintsync.sensor_native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorNativeGpsSyncPolicyTest {
    @Test
    fun `gps warm-up gate unlocks only after minimum fixes and duration`() {
        val minFixes = 3
        val minDurationNanos = 5_000_000_000L
        var state = GpsWarmupState(
            warmupStartElapsedNanos = null,
            consecutiveFixCount = 0,
            warmupReady = false,
        )

        state = advanceGpsWarmupState(
            previous = state,
            fixElapsedRealtimeNanos = 1_000_000_000L,
            minFixes = minFixes,
            minDurationNanos = minDurationNanos,
        )
        assertFalse(state.warmupReady)

        state = advanceGpsWarmupState(
            previous = state,
            fixElapsedRealtimeNanos = 3_000_000_000L,
            minFixes = minFixes,
            minDurationNanos = minDurationNanos,
        )
        assertFalse(state.warmupReady)

        state = advanceGpsWarmupState(
            previous = state,
            fixElapsedRealtimeNanos = 4_000_000_000L,
            minFixes = minFixes,
            minDurationNanos = minDurationNanos,
        )
        assertFalse(state.warmupReady)

        state = advanceGpsWarmupState(
            previous = state,
            fixElapsedRealtimeNanos = 6_500_000_000L,
            minFixes = minFixes,
            minDurationNanos = minDurationNanos,
        )
        assertTrue(state.warmupReady)
    }

    @Test
    fun `gps force-reacquire is throttled by elapsed interval`() {
        assertFalse(
            isGpsReacquireThrottled(
                lastRestartElapsedNanos = null,
                nowElapsedNanos = 1_000L,
                throttleNanos = 5_000L,
            ),
        )
        assertTrue(
            isGpsReacquireThrottled(
                lastRestartElapsedNanos = 10_000L,
                nowElapsedNanos = 12_000L,
                throttleNanos = 5_000L,
            ),
        )
        assertFalse(
            isGpsReacquireThrottled(
                lastRestartElapsedNanos = 10_000L,
                nowElapsedNanos = 15_000L,
                throttleNanos = 5_000L,
            ),
        )
    }
}

package com.paul.sprintsync.core.clock

import android.os.SystemClock

object ClockDomain {
    private const val GPS_FIX_AGE_SKEW_TOLERANCE_NANOS = 250_000_000L

    fun nowElapsedNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun sensorToElapsedNanos(sensorNanos: Long, sensorMinusElapsedNanos: Long): Long {
        return sensorNanos - sensorMinusElapsedNanos
    }

    fun elapsedToSensorNanos(elapsedNanos: Long, sensorMinusElapsedNanos: Long): Long {
        return elapsedNanos + sensorMinusElapsedNanos
    }

    fun computeGpsFixAgeNanos(gpsFixElapsedRealtimeNanos: Long?): Long? {
        if (gpsFixElapsedRealtimeNanos == null) {
            return null
        }
        val ageNanos = nowElapsedNanos() - gpsFixElapsedRealtimeNanos
        if (ageNanos < 0) {
            if (ageNanos >= -GPS_FIX_AGE_SKEW_TOLERANCE_NANOS) {
                return 0L
            }
            return null
        }
        return ageNanos
    }
}

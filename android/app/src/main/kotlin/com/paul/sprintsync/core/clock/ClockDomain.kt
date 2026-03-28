package com.paul.sprintsync.core.clock

import android.os.SystemClock

object ClockDomain {
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
            return null
        }
        return ageNanos
    }
}

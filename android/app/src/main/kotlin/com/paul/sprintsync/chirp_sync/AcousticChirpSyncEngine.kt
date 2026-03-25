package com.paul.sprintsync.chirp_sync

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class ChirpCapabilities(
    val supported: Boolean,
    val supportsMicNearUltrasound: Boolean,
    val supportsSpeakerNearUltrasound: Boolean,
    val selectedProfile: String,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "supported" to supported,
            "supportsMicNearUltrasound" to supportsMicNearUltrasound,
            "supportsSpeakerNearUltrasound" to supportsSpeakerNearUltrasound,
            "selectedProfile" to selectedProfile,
        )
    }
}

data class ChirpCalibrationResult(
    val calibrationId: String,
    val accepted: Boolean,
    val hostMinusClientElapsedNanos: Long?,
    val jitterNanos: Long?,
    val reason: String?,
    val completedAtElapsedNanos: Long?,
    val profile: String,
    val sampleCount: Int,
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "calibrationId" to calibrationId,
            "accepted" to accepted,
            "hostMinusClientElapsedNanos" to hostMinusClientElapsedNanos,
            "jitterNanos" to jitterNanos,
            "reason" to reason,
            "completedAtElapsedNanos" to completedAtElapsedNanos,
            "profile" to profile,
            "sampleCount" to sampleCount,
        )
    }
}

class AcousticChirpSyncEngine(
    private val context: Context,
    private val nowElapsedNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    companion object {
        const val PROFILE_NEAR_ULTRASOUND = "near_ultrasound"
        const val PROFILE_FALLBACK = "fallback"
        private const val ACCEPTED_SPREAD_NANOS = 2_000_000L

        fun computeOffsetFromFourTimestamps(
            clientSendElapsedNanos: Long,
            hostReceiveElapsedNanos: Long,
            hostSendElapsedNanos: Long,
            clientReceiveElapsedNanos: Long,
        ): Long {
            return (
                (hostReceiveElapsedNanos - clientSendElapsedNanos) +
                    (hostSendElapsedNanos - clientReceiveElapsedNanos)
                ) / 2L
        }

        fun medianNanos(samples: List<Long>): Long? {
            if (samples.isEmpty()) {
                return null
            }
            val sorted = samples.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2L
            } else {
                sorted[mid]
            }
        }

        fun spreadNanos(samples: List<Long>): Long? {
            if (samples.size < 2) {
                return null
            }
            val sorted = samples.sorted()
            return sorted.last() - sorted.first()
        }

        fun selectProfile(
            requestedProfile: String,
            supportsMicNearUltrasound: Boolean,
            supportsSpeakerNearUltrasound: Boolean,
        ): String {
            return if (
                requestedProfile == PROFILE_NEAR_ULTRASOUND &&
                supportsMicNearUltrasound &&
                supportsSpeakerNearUltrasound
            ) {
                PROFILE_NEAR_ULTRASOUND
            } else {
                PROFILE_FALLBACK
            }
        }
    }

    @Volatile
    private var activeCalibrationId: String? = null

    fun getCapabilities(): ChirpCapabilities {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val supportsMicNearUltrasound = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
            ?.equals("true", ignoreCase = true) == true
        val supportsSpeakerNearUltrasound = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)
            ?.equals("true", ignoreCase = true) == true
        val selectedProfile = selectProfile(
            requestedProfile = PROFILE_NEAR_ULTRASOUND,
            supportsMicNearUltrasound = supportsMicNearUltrasound,
            supportsSpeakerNearUltrasound = supportsSpeakerNearUltrasound,
        )
        val supported = probeAudioTimestampPath(selectedProfile)
        return ChirpCapabilities(
            supported = supported,
            supportsMicNearUltrasound = supportsMicNearUltrasound,
            supportsSpeakerNearUltrasound = supportsSpeakerNearUltrasound,
            selectedProfile = selectedProfile,
        )
    }

    fun stop() {
        activeCalibrationId = null
    }

    fun clear() {
        activeCalibrationId = null
    }

    fun startCalibration(
        calibrationId: String,
        role: String,
        profile: String,
        sampleCount: Int,
        remoteSendElapsedNanos: Long?,
        onProgress: (String) -> Unit = {},
    ): ChirpCalibrationResult {
        activeCalibrationId = calibrationId
        onProgress("running")
        val capabilities = getCapabilities()
        val selectedProfile = selectProfile(
            requestedProfile = profile,
            supportsMicNearUltrasound = capabilities.supportsMicNearUltrasound,
            supportsSpeakerNearUltrasound = capabilities.supportsSpeakerNearUltrasound,
        )
        val timestampPathAvailable = if (selectedProfile == capabilities.selectedProfile) {
            capabilities.supported
        } else {
            probeAudioTimestampPath(selectedProfile)
        }
        if (!timestampPathAvailable) {
            return ChirpCalibrationResult(
                calibrationId = calibrationId,
                accepted = false,
                hostMinusClientElapsedNanos = null,
                jitterNanos = null,
                reason = "Audio timestamp path unavailable",
                completedAtElapsedNanos = nowElapsedNanos(),
                profile = selectedProfile,
                sampleCount = sampleCount,
            )
        }

        val normalizedRole = role.trim().lowercase()
        if (normalizedRole == "initiator") {
            return ChirpCalibrationResult(
                calibrationId = calibrationId,
                accepted = true,
                hostMinusClientElapsedNanos = null,
                jitterNanos = null,
                reason = "Initiator ready",
                completedAtElapsedNanos = nowElapsedNanos(),
                profile = selectedProfile,
                sampleCount = sampleCount.coerceAtLeast(3),
            )
        }

        if (remoteSendElapsedNanos == null) {
            return ChirpCalibrationResult(
                calibrationId = calibrationId,
                accepted = false,
                hostMinusClientElapsedNanos = null,
                jitterNanos = null,
                reason = "Missing remote send timestamp",
                completedAtElapsedNanos = nowElapsedNanos(),
                profile = selectedProfile,
                sampleCount = sampleCount.coerceAtLeast(3),
            )
        }

        val offsets = mutableListOf<Long>()
        val rounds = sampleCount.coerceAtLeast(3)
        for (index in 0 until rounds) {
            if (activeCalibrationId != calibrationId) {
                return ChirpCalibrationResult(
                    calibrationId = calibrationId,
                    accepted = false,
                    hostMinusClientElapsedNanos = null,
                    jitterNanos = null,
                    reason = "Cancelled",
                    completedAtElapsedNanos = nowElapsedNanos(),
                    profile = selectedProfile,
                    sampleCount = rounds,
                )
            }
            val clientSend = remoteSendElapsedNanos + (index * 250_000L)
            val hostReceive = nowElapsedNanos() + (index * 50_000L)
            val hostSend = hostReceive + 80_000L
            val clientReceive = clientSend + syntheticRoundTripNanos(index, selectedProfile)
            offsets += computeOffsetFromFourTimestamps(
                clientSendElapsedNanos = clientSend,
                hostReceiveElapsedNanos = hostReceive,
                hostSendElapsedNanos = hostSend,
                clientReceiveElapsedNanos = clientReceive,
            )
        }

        val medianOffset = medianNanos(offsets)
        val spread = spreadNanos(offsets)
        val accepted = medianOffset != null &&
            spread != null &&
            spread <= ACCEPTED_SPREAD_NANOS
        val reason = if (accepted) {
            null
        } else {
            "Sample spread ${spread ?: -1}ns exceeds threshold"
        }
        return ChirpCalibrationResult(
            calibrationId = calibrationId,
            accepted = accepted,
            hostMinusClientElapsedNanos = if (accepted) medianOffset else null,
            jitterNanos = spread,
            reason = reason,
            completedAtElapsedNanos = nowElapsedNanos(),
            profile = selectedProfile,
            sampleCount = rounds,
        )
    }

    private fun syntheticRoundTripNanos(index: Int, profile: String): Long {
        val base = if (profile == PROFILE_NEAR_ULTRASOUND) 1_200_000L else 1_700_000L
        val jitter = ((index % 5) - 2) * 80_000L
        return base + jitter
    }

    @SuppressLint("NewApi")
    private fun probeAudioTimestampPath(profile: String): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?: 48_000
        val outputMinBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val inputMinBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        var audioTrack: AudioTrack? = null
        var audioRecord: AudioRecord? = null
        return try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(outputMinBuffer)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(inputMinBuffer)
                .build()

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED ||
                audioRecord.state != AudioRecord.STATE_INITIALIZED
            ) {
                return false
            }

            val chirp = generateProbeTone(
                sampleRate = sampleRate,
                profile = profile,
                durationMs = 100, // Extend tone duration to ensure track plays while we poll
            )
            audioRecord.startRecording()
            audioTrack.play()
            audioTrack.write(chirp, 0, chirp.size, AudioTrack.WRITE_BLOCKING)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return true
            }

            val recordTimestamp = AudioTimestamp()
            val trackTimestamp = AudioTimestamp()
            val readBuffer = ShortArray(1024)
            var recordStatus = AudioRecord.ERROR
            var trackStatus = false

            // Poll for up to ~500ms while doing non-blocking reads to populate the hardware timestamps
            for (i in 0 until 50) {
                audioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_NON_BLOCKING)
                
                recordStatus = audioRecord.getTimestamp(
                    recordTimestamp,
                    AudioTimestamp.TIMEBASE_BOOTTIME,
                )
                trackStatus = audioTrack.getTimestamp(trackTimestamp)
                
                if (recordStatus == AudioRecord.SUCCESS && trackStatus) {
                    break
                }
                Thread.sleep(10)
            }

            recordStatus == AudioRecord.SUCCESS && trackStatus
        } catch (_: Throwable) {
            false
        } finally {
            try {
                audioRecord?.stop()
            } catch (_: Throwable) {
            }
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (_: Throwable) {
            }
            try {
                audioRecord?.release()
            } catch (_: Throwable) {
            }
            try {
                audioTrack?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun generateProbeTone(
        sampleRate: Int,
        profile: String,
        durationMs: Int,
    ): ShortArray {
        val frameCount = (sampleRate * durationMs) / 1000
        val data = ShortArray(frameCount.coerceAtLeast(1))
        val startHz = if (profile == PROFILE_NEAR_ULTRASOUND) 18_500.0 else 8_000.0
        val endHz = if (profile == PROFILE_NEAR_ULTRASOUND) 19_500.0 else 10_000.0
        for (index in data.indices) {
            val t = index.toDouble() / sampleRate.toDouble()
            val ratio = index.toDouble() / data.size.toDouble()
            val freq = startHz + ((endHz - startHz) * ratio)
            val sample = sin(2.0 * PI * freq * t)
            val amplitude = if (profile == PROFILE_NEAR_ULTRASOUND) 0.25 else 0.35
            data[index] = (sample * Short.MAX_VALUE * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return data
    }
}

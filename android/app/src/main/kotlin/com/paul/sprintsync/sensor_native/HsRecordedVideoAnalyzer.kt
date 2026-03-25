package com.paul.sprintsync.sensor_native

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object HsRecordedVideoAnalyzer {
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    fun analyze(
        artifact: HsRecordedVideoArtifact,
        triggerType: String,
        splitIndex: Int,
        scanDirection: HsScanDirection,
        threshold: Double,
        roiCenterX: Double,
        roiWidth: Double,
        emaAlpha: Double = 0.08,
    ): HsOfflineAnalysisResult {
        val recordingFile = File(artifact.outputPath)
        if (!recordingFile.exists()) {
            return HsOfflineAnalysisResult(
                runId = artifact.runId,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = scanDirection,
                resolved = false,
                localSensorNanos = null,
                diagnostics = "Recording artifact missing.",
            )
        }

        val ptsMapper = PtsSensorMapper.create(
            encodedPtsUs = artifact.encodedPtsUs,
            captureSensorNanos = artifact.captureSensorNanos,
        )
            ?: return HsOfflineAnalysisResult(
                runId = artifact.runId,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = scanDirection,
                resolved = false,
                localSensorNanos = null,
                diagnostics = "Unable to build pts->sensor mapping.",
            )

        val rawMetrics = decodeRawDiffMetrics(
            videoPath = artifact.outputPath,
            ptsSensorMapper = ptsMapper,
            roiCenterX = roiCenterX,
            roiWidth = roiWidth,
        )
        if (rawMetrics.isEmpty()) {
            return HsOfflineAnalysisResult(
                runId = artifact.runId,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = scanDirection,
                resolved = false,
                localSensorNanos = null,
                diagnostics = "No analyzable frames found.",
            )
        }

        val selectedMetric = selectFirstThresholdCrossing(
            rawMetrics = rawMetrics,
            threshold = threshold,
            direction = scanDirection,
            emaAlpha = emaAlpha,
        )

        return if (selectedMetric == null) {
            HsOfflineAnalysisResult(
                runId = artifact.runId,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = scanDirection,
                resolved = false,
                localSensorNanos = null,
                diagnostics = "No threshold crossing found.",
            )
        } else {
            HsOfflineAnalysisResult(
                runId = artifact.runId,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = scanDirection,
                resolved = true,
                localSensorNanos = selectedMetric.sensorNanos,
                rawScore = selectedMetric.rawScore,
                baseline = selectedMetric.baseline,
                effectiveScore = selectedMetric.effectiveScore,
            )
        }
    }

    private fun decodeRawDiffMetrics(
        videoPath: String,
        ptsSensorMapper: PtsSensorMapper,
        roiCenterX: Double,
        roiWidth: Double,
    ): List<RawDiffMetric> {
        val extractor = MediaExtractor()
        val metrics = mutableListOf<RawDiffMetric>()
        try {
            extractor.setDataSource(videoPath)
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) {
                return emptyList()
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return emptyList()
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var previousRoi: ByteArray? = null

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer == null) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    sampleTimeUs,
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outputBufferIndex >= 0 -> {
                        if (info.size > 0) {
                            val image = decoder.getOutputImage(outputBufferIndex)
                            if (image != null) {
                                val sensorNanos = ptsSensorMapper.map(info.presentationTimeUs)
                                if (sensorNanos != null) {
                                    val roiLuma = extractRoiLuma(
                                        image = image,
                                        roiCenterX = roiCenterX,
                                        roiWidth = roiWidth,
                                    )
                                    if (roiLuma != null) {
                                        val previous = previousRoi
                                        if (previous != null && previous.size == roiLuma.size) {
                                            val rawScore = scoreRoiDiff(previous, roiLuma)
                                            metrics += RawDiffMetric(
                                                sensorNanos = sensorNanos,
                                                rawScore = rawScore,
                                            )
                                        }
                                        previousRoi = roiLuma
                                    }
                                }
                                image.close()
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }

                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // no-op
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // no-op
                    }
                }
            }

            decoder.stop()
            decoder.release()
            return metrics
        } finally {
            extractor.release()
        }
    }

    private fun selectFirstThresholdCrossing(
        rawMetrics: List<RawDiffMetric>,
        threshold: Double,
        direction: HsScanDirection,
        emaAlpha: Double,
    ): HsOfflineAnalysisMetric? {
        if (rawMetrics.isEmpty()) {
            return null
        }
        val iteration = when (direction) {
            HsScanDirection.FORWARD -> rawMetrics
            HsScanDirection.BACKWARD -> rawMetrics.asReversed()
        }
        var baseline = 0.0
        var hasBaseline = false
        for (metric in iteration) {
            val effectiveScore = if (!hasBaseline) {
                metric.rawScore
            } else {
                max(0.0, metric.rawScore - baseline)
            }
            if (effectiveScore >= threshold) {
                return HsOfflineAnalysisMetric(
                    sensorNanos = metric.sensorNanos,
                    rawScore = metric.rawScore,
                    baseline = baseline,
                    effectiveScore = effectiveScore,
                )
            }
            baseline = if (!hasBaseline) {
                metric.rawScore
            } else {
                (metric.rawScore * emaAlpha) + (baseline * (1.0 - emaAlpha))
            }
            hasBaseline = true
        }
        return null
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return index
            }
        }
        return -1
    }

    private fun extractRoiLuma(
        image: Image,
        roiCenterX: Double,
        roiWidth: Double,
    ): ByteArray? {
        if (image.planes.isEmpty()) {
            return null
        }
        val yPlane = image.planes[0]
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return null
        }
        val safeCenter = roiCenterX.coerceIn(0.0, 1.0)
        val safeWidth = roiWidth.coerceIn(0.05, 1.0)
        val roiCenterPx = (safeCenter * width).toInt()
        val roiWidthPx = max(1, (safeWidth * width).toInt())
        val startX = max(0, min(width - 1, roiCenterPx - (roiWidthPx / 2)))
        val endX = min(width, startX + roiWidthPx)
        if (endX <= startX) {
            return null
        }
        val xStep = 2
        val yStep = 2
        val sampleWidth = ((endX - startX) + (xStep - 1)) / xStep
        val sampleHeight = (height + (yStep - 1)) / yStep
        val sampleCount = sampleWidth * sampleHeight
        if (sampleCount <= 0) {
            return null
        }
        val output = ByteArray(sampleCount)
        var idx = 0
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val buffer = yPlane.buffer
        for (y in 0 until height step yStep) {
            val rowOffset = y * rowStride
            for (x in startX until endX step xStep) {
                val offset = rowOffset + (x * pixelStride)
                if (offset < 0 || offset >= buffer.limit()) {
                    continue
                }
                output[idx] = buffer.get(offset)
                idx += 1
                if (idx >= sampleCount) {
                    break
                }
            }
            if (idx >= sampleCount) {
                break
            }
        }
        if (idx <= 0) {
            return null
        }
        return if (idx == sampleCount) output else output.copyOf(idx)
    }

    private fun scoreRoiDiff(previousRoi: ByteArray, currentRoi: ByteArray): Double {
        val count = min(previousRoi.size, currentRoi.size)
        if (count <= 0) {
            return 0.0
        }
        var diffSum = 0L
        for (index in 0 until count) {
            val now = currentRoi[index].toInt() and 0xFF
            val before = previousRoi[index].toInt() and 0xFF
            diffSum += abs(now - before)
        }
        return diffSum.toDouble() / (count.toDouble() * 255.0)
    }

    internal class PtsSensorMapper private constructor(
        private val ptsUs: LongArray,
        private val sensorNanos: LongArray,
    ) {
        companion object {
            fun create(
                encodedPtsUs: List<Long>,
                captureSensorNanos: List<Long>,
            ): PtsSensorMapper? {
                val pairCount = min(encodedPtsUs.size, captureSensorNanos.size)
                if (pairCount <= 0) {
                    return null
                }
                val pairs = mutableListOf<Pair<Long, Long>>()
                for (index in 0 until pairCount) {
                    pairs += encodedPtsUs[index] to captureSensorNanos[index]
                }
                pairs.sortBy { it.first }
                val uniquePairs = mutableListOf<Pair<Long, Long>>()
                for (pair in pairs) {
                    if (uniquePairs.isNotEmpty() && uniquePairs.last().first == pair.first) {
                        uniquePairs[uniquePairs.lastIndex] = pair
                    } else {
                        uniquePairs += pair
                    }
                }
                if (uniquePairs.isEmpty()) {
                    return null
                }
                return PtsSensorMapper(
                    ptsUs = uniquePairs.map { it.first }.toLongArray(),
                    sensorNanos = uniquePairs.map { it.second }.toLongArray(),
                )
            }
        }

        fun map(presentationTimeUs: Long): Long? {
            if (ptsUs.isEmpty()) {
                return null
            }
            if (ptsUs.size == 1) {
                return sensorNanos[0]
            }
            if (presentationTimeUs <= ptsUs[0]) {
                return sensorNanos[0]
            }
            if (presentationTimeUs >= ptsUs[ptsUs.lastIndex]) {
                return sensorNanos[sensorNanos.lastIndex]
            }

            var low = 0
            var high = ptsUs.lastIndex
            while (low <= high) {
                val mid = (low + high) ushr 1
                val value = ptsUs[mid]
                if (value == presentationTimeUs) {
                    return sensorNanos[mid]
                }
                if (value < presentationTimeUs) {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            val rightIndex = min(ptsUs.lastIndex, max(1, low))
            val leftIndex = rightIndex - 1
            val leftPts = ptsUs[leftIndex]
            val rightPts = ptsUs[rightIndex]
            val leftSensor = sensorNanos[leftIndex]
            val rightSensor = sensorNanos[rightIndex]
            val denom = (rightPts - leftPts).toDouble()
            if (denom <= 0.0) {
                return leftSensor
            }
            val ratio = (presentationTimeUs - leftPts) / denom
            return (leftSensor + ((rightSensor - leftSensor) * ratio)).toLong()
        }
    }

    private data class RawDiffMetric(
        val sensorNanos: Long,
        val rawScore: Double,
    )
}

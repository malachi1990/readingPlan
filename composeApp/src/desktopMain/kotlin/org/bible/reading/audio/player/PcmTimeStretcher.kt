package org.bible.reading.audio.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real-time WSOLA (Waveform Similarity Overlap-Add) time-stretcher for 16-bit signed
 * little-endian PCM audio. Adjusts playback speed (e.g. 0.25x to 2.00x) while preserving pitch.
 */
internal class PcmTimeStretcher(
    private val sampleRate: Int,
    private val channels: Int
) {
    private val hopFrames: Int = (sampleRate * 0.020).toInt().coerceAtLeast(128) // ~20ms
    private val windowFrames: Int = hopFrames * 2 // ~40ms
    private val searchRadius: Int = (sampleRate * 0.015).toInt().coerceAtLeast(64) // ~15ms
    private val corrStep: Int = (sampleRate / 11025).coerceAtLeast(1)

    // Raised-cosine crossfade weights: (1 - fadeIn[i]) + fadeIn[i] == 1.0 identically
    private val fadeIn = DoubleArray(hopFrames) { i ->
        0.5 * (1.0 - cos(PI * i / hopFrames))
    }

    private var buf = ShortArray(windowFrames * channels * 8)
    private var bufFrames = 0

    private var initialized = false
    private var overlapTail = ShortArray(hopFrames * channels)
    private var nominalInputFrame = 0.0

    private var outBytes = ByteArray(hopFrames * channels * 2 * 8)
    private var outByteCount = 0

    fun reset() {
        bufFrames = 0
        initialized = false
        nominalInputFrame = 0.0
        outByteCount = 0
    }

    fun estimatedPendingInputFrames(): Long {
        if (bufFrames == 0 && !initialized) return 0L
        return if (!initialized) {
            bufFrames.toLong()
        } else {
            (bufFrames - nominalInputFrame).toLong().coerceAtLeast(0L)
        }
    }

    fun process(pcmBytes: ByteArray, length: Int, speed: Float): ByteArray {
        val frameBytes = channels * 2
        val validLength = length - (length % frameBytes)
        if (validLength <= 0) return ByteArray(0)

        val clampedSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        if (abs(clampedSpeed - 1.0f) < 0.001f) {
            if (!initialized && bufFrames == 0) {
                return if (validLength == pcmBytes.size) pcmBytes else pcmBytes.copyOf(validLength)
            }
            val drained = finish()
            val combined = ByteArray(drained.size + validLength)
            System.arraycopy(drained, 0, combined, 0, drained.size)
            System.arraycopy(pcmBytes, 0, combined, drained.size, validLength)
            return combined
        }

        appendPcmBytes(pcmBytes, validLength)
        outByteCount = 0
        runWsolaSteps(clampedSpeed.toDouble())
        return outBytes.copyOf(outByteCount)
    }

    fun finish(): ByteArray {
        if (bufFrames == 0 && !initialized) return ByteArray(0)
        outByteCount = 0

        if (!initialized) {
            emitFramesFromBuffer(0, bufFrames)
            reset()
            return outBytes.copyOf(outByteCount)
        }

        val remStart = nominalInputFrame.roundToInt().coerceIn(0, bufFrames)
        val remFrames = bufFrames - remStart
        if (remFrames >= hopFrames) {
            emitCrossfadedHop(remStart)
            val afterHop = remStart + hopFrames
            if (bufFrames > afterHop) {
                emitFramesFromBuffer(afterHop, bufFrames - afterHop)
            }
        } else {
            emitOverlapTail(hopFrames)
        }

        reset()
        return outBytes.copyOf(outByteCount)
    }

    private fun runWsolaSteps(speed: Double) {
        if (!initialized) {
            if (bufFrames < windowFrames + searchRadius) return
            emitFramesFromBuffer(0, hopFrames)
            System.arraycopy(buf, hopFrames * channels, overlapTail, 0, hopFrames * channels)
            nominalInputFrame = hopFrames * speed
            initialized = true
        }

        while (true) {
            val target = nominalInputFrame.roundToInt()
            if (target + searchRadius + windowFrames > bufFrames) {
                break
            }

            val searchMin = (target - searchRadius).coerceAtLeast(0)
            val searchMax = target + searchRadius
            val clampedDefault = target.coerceIn(searchMin, searchMax)
            val bestStart = findBestMatchOffset(searchMin, searchMax, clampedDefault)

            emitCrossfadedHop(bestStart)
            System.arraycopy(buf, (bestStart + hopFrames) * channels, overlapTail, 0, hopFrames * channels)
            nominalInputFrame += hopFrames * speed
        }

        val safeKeepStart = (floor(nominalInputFrame).toInt() - searchRadius).coerceIn(0, bufFrames)
        if (safeKeepStart >= windowFrames * 2) {
            val remainingFrames = bufFrames - safeKeepStart
            if (remainingFrames > 0) {
                System.arraycopy(buf, safeKeepStart * channels, buf, 0, remainingFrames * channels)
            }
            bufFrames = remainingFrames
            nominalInputFrame -= safeKeepStart
        }
    }

    private fun findBestMatchOffset(searchMin: Int, searchMax: Int, defaultPos: Int): Int {
        if (searchMin >= searchMax) return defaultPos

        val refEnergyCoarse = computeOverlapEnergy(corrStep)
        if (refEnergyCoarse <= 1.0) {
            return defaultPos
        }

        val coarseStep = 2
        var bestCoarse = defaultPos
        var bestScore = -2.0
        var pos = searchMin
        while (pos <= searchMax) {
            val score = normalizedCorrelation(pos, refEnergyCoarse, corrStep)
            if (score > bestScore) {
                bestScore = score
                bestCoarse = pos
            }
            pos += coarseStep
        }

        val fineStep = (corrStep / 2).coerceAtLeast(1)
        val refEnergyFine = if (fineStep == corrStep) refEnergyCoarse else computeOverlapEnergy(fineStep)
        val refineMin = (bestCoarse - 2).coerceAtLeast(searchMin)
        val refineMax = (bestCoarse + 2).coerceAtMost(searchMax)
        var bestOffset = bestCoarse
        bestScore = -2.0
        for (r in refineMin..refineMax) {
            val score = normalizedCorrelation(r, refEnergyFine, fineStep)
            if (score > bestScore) {
                bestScore = score
                bestOffset = r
            }
        }

        return bestOffset
    }

    private fun computeOverlapEnergy(step: Int): Double {
        var energy = 0.0
        if (channels == 1) {
            var f = 0
            while (f < hopFrames) {
                val v = overlapTail[f].toDouble()
                energy += v * v
                f += step
            }
        } else {
            var f = 0
            while (f < hopFrames) {
                var mono = 0.0
                val base = f * channels
                for (c in 0 until channels) {
                    mono += overlapTail[base + c]
                }
                energy += mono * mono
                f += step
            }
        }
        return energy
    }

    private fun normalizedCorrelation(candStartFrame: Int, refEnergy: Double, step: Int): Double {
        var dot = 0.0
        var candEnergy = 0.0
        val candSampleBase = candStartFrame * channels

        if (channels == 1) {
            var f = 0
            while (f < hopFrames) {
                val r = overlapTail[f].toDouble()
                val c = buf[candSampleBase + f].toDouble()
                dot += r * c
                candEnergy += c * c
                f += step
            }
        } else {
            var f = 0
            while (f < hopFrames) {
                val tailBase = f * channels
                val cBase = candSampleBase + tailBase
                var rMono = 0.0
                var cMono = 0.0
                for (ch in 0 until channels) {
                    rMono += overlapTail[tailBase + ch]
                    cMono += buf[cBase + ch]
                }
                dot += rMono * cMono
                candEnergy += cMono * cMono
                f += step
            }
        }

        val denom = sqrt(refEnergy * candEnergy)
        return if (denom <= 1e-6) 0.0 else dot / denom
    }

    private fun appendPcmBytes(pcmBytes: ByteArray, length: Int) {
        val incomingFrames = length / (channels * 2)
        if (incomingFrames <= 0) return

        val requiredSamples = (bufFrames + incomingFrames) * channels
        if (buf.size < requiredSamples) {
            val newCap = maxOf(requiredSamples, buf.size * 2)
            buf = buf.copyOf(newCap)
        }

        var writeIdx = bufFrames * channels
        val totalSamples = incomingFrames * channels
        for (s in 0 until totalSamples) {
            val lo = pcmBytes[s * 2].toInt() and 0xFF
            val hi = pcmBytes[s * 2 + 1].toInt()
            buf[writeIdx++] = ((hi shl 8) or lo).toShort()
        }
        bufFrames += incomingFrames
    }

    private fun emitFramesFromBuffer(startFrame: Int, frameCount: Int) {
        if (frameCount <= 0) return
        val neededBytes = frameCount * channels * 2
        ensureOutCapacity(outByteCount + neededBytes)
        val startSample = startFrame * channels
        val totalSamples = frameCount * channels
        for (i in 0 until totalSamples) {
            val sample = buf[startSample + i].toInt()
            outBytes[outByteCount++] = (sample and 0xFF).toByte()
            outBytes[outByteCount++] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    private fun emitOverlapTail(frameCount: Int) {
        if (frameCount <= 0) return
        val neededBytes = frameCount * channels * 2
        ensureOutCapacity(outByteCount + neededBytes)
        val totalSamples = frameCount * channels
        for (i in 0 until totalSamples) {
            val sample = overlapTail[i].toInt()
            outBytes[outByteCount++] = (sample and 0xFF).toByte()
            outBytes[outByteCount++] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    private fun emitCrossfadedHop(bestStart: Int) {
        val neededBytes = hopFrames * channels * 2
        ensureOutCapacity(outByteCount + neededBytes)
        val srcBase = bestStart * channels

        for (f in 0 until hopFrames) {
            val wIn = fadeIn[f]
            val wOut = 1.0 - wIn
            val tailBase = f * channels
            val candBase = srcBase + tailBase
            for (c in 0 until channels) {
                val mixed = (overlapTail[tailBase + c] * wOut + buf[candBase + c] * wIn)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outBytes[outByteCount++] = (mixed and 0xFF).toByte()
                outBytes[outByteCount++] = ((mixed shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun ensureOutCapacity(required: Int) {
        if (outBytes.size < required) {
            outBytes = outBytes.copyOf(maxOf(required, outBytes.size * 2))
        }
    }
}

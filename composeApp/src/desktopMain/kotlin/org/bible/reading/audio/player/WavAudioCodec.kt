package org.bible.reading.audio.player

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

class WavAudioCodec : AudioCodec {
    override val name: String = "WAV"
    override val supportedExtensions: List<String> = listOf("wav", "aiff", "au")

    override fun createSession(file: File): AudioStreamSession {
        return WavStreamSession(file)
    }
}

private class WavStreamSession(override val file: File) : AudioStreamSession {

    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var isPaused: Boolean = false
        private set

    override var durationMs: Long = -1L
        private set

    override val currentPositionMs: Long
        get() = currentPositionAtomic.get()

    private val currentPositionAtomic = AtomicLong(0L)
    private val pendingSeekMs = AtomicLong(-1L)
    private val isStopped = AtomicBoolean(false)

    private var currentVolume: Float = 0.85f

    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    private var playbackThread: Thread? = null
    private var line: SourceDataLine? = null

    private var onCompletion: (() -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    init {
        try {
            AudioSystem.getAudioInputStream(file).use { ais ->
                val format = ais.format
                val frames = ais.frameLength
                if (frames > 0 && format.frameRate > 0) {
                    durationMs = (frames * 1000L / format.frameRate.toLong())
                } else if (format.frameRate > 0 && format.frameSize > 0) {
                    val dataSize = file.length() - 44
                    if (dataSize > 0) {
                        durationMs = (dataSize * 1000L / (format.frameRate.toLong() * format.frameSize))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun play() {
        if (isPlaying) return
        isStopped.set(false)
        isPaused = false
        isPlaying = true

        playbackThread = Thread({
            runPlaybackLoop()
        }, "WAV-Playback-${file.name}").apply {
            isDaemon = true
            start()
        }
    }

    override fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        try {
            line?.stop()
        } catch (_: Exception) {}
    }

    override fun resume() {
        if (!isPlaying || !isPaused) return
        isPaused = false
        try {
            line?.start()
        } catch (_: Exception) {}
    }

    override fun stop() {
        isStopped.set(true)
        isPlaying = false
        isPaused = false
        try {
            line?.stop()
            line?.flush()
        } catch (_: Exception) {}
        playbackThread?.interrupt()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        currentPositionAtomic.set(target)
        pendingSeekMs.set(target)
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        applyVolume(line, currentVolume)
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = normalizePlaybackSpeed(speed)
    }

    override fun release() {
        stop()
        try {
            line?.close()
        } catch (_: Exception) {}
        line = null
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        onCompletion = listener
    }

    override fun setOnErrorListener(listener: (Throwable) -> Unit) {
        onError = listener
    }

    private fun runPlaybackLoop() {
        var audioIn: AudioInputStream? = null
        var pcmIn: AudioInputStream? = null

        try {
            val initialAudioIn = AudioSystem.getAudioInputStream(file)
            audioIn = initialAudioIn
            val baseFormat = initialAudioIn.format

            val channels = baseFormat.channels.coerceAtLeast(1)
            val sampleRate = baseFormat.sampleRate.takeIf { it > 0f } ?: 44100f
            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
            )

            val initialPcmIn = AudioSystem.getAudioInputStream(decodedFormat, initialAudioIn)
            pcmIn = initialPcmIn

            val info = DataLine.Info(SourceDataLine::class.java, decodedFormat)
            if (!AudioSystem.isLineSupported(info)) {
                throw LineUnavailableException("Format not supported: $decodedFormat")
            }

            val frameBytes = decodedFormat.frameSize.coerceAtLeast(channels * 2)
            val rawBufferSize = ((sampleRate * frameBytes * 0.15).toInt() / frameBytes) * frameBytes
            val lineBufferSize = rawBufferSize.coerceIn(8192, 32768).let { it - (it % frameBytes) }

            val dataLine = AudioSystem.getLine(info) as SourceDataLine
            dataLine.open(decodedFormat, lineBufferSize)
            applyVolume(dataLine, currentVolume)
            dataLine.start()
            this.line = dataLine

            val readChunkSize = (4096 - (4096 % frameBytes)).coerceAtLeast(frameBytes)
            val buffer = ByteArray(readChunkSize)
            val bytesPerMs = (decodedFormat.frameRate * decodedFormat.frameSize / 1000f).coerceAtLeast(1f)
            var totalBytesRead = 0L
            var activePcmIn = initialPcmIn
            val stretcher = PcmTimeStretcher(sampleRate.toInt().coerceAtLeast(8000), channels)

            fun applySeek(seekTargetMs: Long) {
                try {
                    activePcmIn.close()
                    audioIn?.close()
                } catch (_: Exception) {}

                val newAudioIn = AudioSystem.getAudioInputStream(file)
                audioIn = newAudioIn
                val newPcmIn = AudioSystem.getAudioInputStream(decodedFormat, newAudioIn)
                pcmIn = newPcmIn
                activePcmIn = newPcmIn
                dataLine.flush()
                stretcher.reset()

                val rawTargetBytes = (seekTargetMs * bytesPerMs).toLong()
                val targetBytes = if (frameBytes > 0) rawTargetBytes - (rawTargetBytes % frameBytes) else rawTargetBytes

                var remainingToSkip = targetBytes
                val skipBuffer = ByteArray(readChunkSize)
                while (remainingToSkip > 0 && !isStopped.get()) {
                    val skipped = activePcmIn.skip(remainingToSkip)
                    if (skipped <= 0) {
                        val toRead = remainingToSkip.coerceAtMost(skipBuffer.size.toLong()).toInt()
                        val bytesRead = activePcmIn.read(skipBuffer, 0, toRead)
                        if (bytesRead == -1) break
                        remainingToSkip -= bytesRead
                    } else {
                        remainingToSkip -= skipped
                    }
                }
                totalBytesRead = targetBytes - remainingToSkip
                currentPositionAtomic.set((totalBytesRead / bytesPerMs).toLong())
            }

            while (!isStopped.get()) {
                if (isPaused) {
                    dataLine.stop()
                    while (isPaused && !isStopped.get()) {
                        val seekTargetMs = pendingSeekMs.getAndSet(-1L)
                        if (seekTargetMs >= 0) {
                            applySeek(seekTargetMs)
                        }
                        Thread.sleep(50)
                    }
                    if (!isStopped.get()) {
                        dataLine.start()
                    }
                }
                if (isStopped.get()) break

                // Seek handling
                val seekTargetMs = pendingSeekMs.getAndSet(-1L)
                if (seekTargetMs >= 0) {
                    applySeek(seekTargetMs)
                }

                val bytesRead = activePcmIn.read(buffer, 0, buffer.size)
                if (bytesRead == -1) break

                val outPcm = stretcher.process(buffer, bytesRead, currentPlaybackSpeed)
                if (outPcm.isNotEmpty() && !isStopped.get()) {
                    writeChunkedPcm(dataLine, outPcm, lineBufferSize, frameBytes)
                }
                totalBytesRead += bytesRead
                val pendingBytes = stretcher.estimatedPendingInputFrames() * frameBytes
                currentPositionAtomic.set(((totalBytesRead - pendingBytes).coerceAtLeast(0L) / bytesPerMs).toLong())
            }

            if (!isStopped.get()) {
                val tailPcm = stretcher.finish()
                if (tailPcm.isNotEmpty() && !isStopped.get()) {
                    writeChunkedPcm(dataLine, tailPcm, lineBufferSize, frameBytes)
                }
                if (!isStopped.get()) {
                    dataLine.drain()
                }
            }

        } catch (e: InterruptedException) {
            // Cancelled
        } catch (t: Throwable) {
            if (!isStopped.get()) {
                onError?.invoke(t)
            }
        } finally {
            try {
                pcmIn?.close()
                audioIn?.close()
            } catch (_: Exception) {}

            try {
                line?.stop()
                line?.close()
            } catch (_: Exception) {}
            line = null

            val wasNormal = !isStopped.get()
            isPlaying = false
            isPaused = false

            if (wasNormal) {
                onCompletion?.invoke()
            }
        }
    }

    private fun writeChunkedPcm(
        dataLine: SourceDataLine,
        pcmBytes: ByteArray,
        maxChunkSize: Int,
        frameBytes: Int
    ) {
        val step = (maxChunkSize - (maxChunkSize % frameBytes)).coerceAtLeast(frameBytes)
        var offset = 0
        while (offset < pcmBytes.size && !isStopped.get()) {
            val len = minOf(step, pcmBytes.size - offset)
            val alignedLen = len - (len % frameBytes)
            if (alignedLen <= 0) break
            dataLine.write(pcmBytes, offset, alignedLen)
            offset += alignedLen
        }
    }

    private fun applyVolume(dataLine: SourceDataLine?, volume: Float) {
        if (dataLine == null) return
        try {
            if (dataLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val gainControl = dataLine.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val min = gainControl.minimum
                val max = gainControl.maximum
                val dB = if (volume <= 0.001f) min else (20f * log10(volume)).coerceIn(min, max)
                gainControl.value = dB
            }
        } catch (_: Exception) {}
    }
}

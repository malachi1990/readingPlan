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

    override var currentPositionMs: Long = 0L
        get() = currentPositionAtomic.get()

    private val currentPositionAtomic = AtomicLong(0L)
    private val pendingSeekMs = AtomicLong(-1L)
    private val isStopped = AtomicBoolean(false)

    private var currentVolume: Float = 0.85f
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
        pendingSeekMs.set(positionMs.coerceAtLeast(0L))
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        applyVolume(line, currentVolume)
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

            val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate,
                16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false
            )

            val initialPcmIn = AudioSystem.getAudioInputStream(decodedFormat, initialAudioIn)
            pcmIn = initialPcmIn

            val info = DataLine.Info(SourceDataLine::class.java, decodedFormat)
            if (!AudioSystem.isLineSupported(info)) {
                throw LineUnavailableException("Format not supported: $decodedFormat")
            }

            val dataLine = AudioSystem.getLine(info) as SourceDataLine
            dataLine.open(decodedFormat, 32768)
            applyVolume(dataLine, currentVolume)
            dataLine.start()
            this.line = dataLine

            val buffer = ByteArray(4096)
            val bytesPerMs = (decodedFormat.frameRate * decodedFormat.frameSize / 1000f).coerceAtLeast(1f)
            var totalBytesRead = 0L
            var activePcmIn = initialPcmIn

            while (!isStopped.get()) {
                if (isPaused) {
                    dataLine.stop()
                    while (isPaused && !isStopped.get()) {
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

                    val targetBytes = (seekTargetMs * bytesPerMs).toLong()
                    activePcmIn.skip(targetBytes)
                    totalBytesRead = targetBytes
                    currentPositionAtomic.set(seekTargetMs)
                }

                val bytesRead = activePcmIn.read(buffer, 0, buffer.size)
                if (bytesRead == -1) break

                dataLine.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                currentPositionAtomic.set((totalBytesRead / bytesPerMs).toLong())
            }

            if (!isStopped.get()) {
                dataLine.drain()
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

package org.bible.reading.audio.player

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

class Mp3AudioCodec : AudioCodec {
    override val name: String = "MP3"
    override val supportedExtensions: List<String> = listOf("mp3")

    override fun createSession(file: File): AudioStreamSession {
        return Mp3StreamSession(file)
    }
}

private class Mp3StreamSession(override val file: File) : AudioStreamSession {

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
        estimateDuration()
    }

    private fun estimateDuration() {
        try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val bs = Bitstream(bis)
                    val header = bs.readFrame()
                    if (header != null) {
                        val maxMs = header.total_ms(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        if (maxMs > 0) {
                            durationMs = maxMs.toLong()
                        } else {
                            val bitrate = header.bitrate()
                            if (bitrate > 0) {
                                val fileSize = file.length()
                                durationMs = (fileSize * 8L * 1000L) / bitrate
                            }
                        }
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
        }, "MP3-Playback-${file.name}").apply {
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
        var inputStream: BufferedInputStream? = null
        var bitstream: Bitstream? = null
        val decoder = Decoder()
        var currentFrame = 0L
        var msPerFrame = 26.12f // Standard default for 44.1kHz MP3

        try {
            val initialStream = BufferedInputStream(FileInputStream(file))
            inputStream = initialStream
            val initialBitstream = Bitstream(initialStream)
            bitstream = initialBitstream

            // Read first frame to initialize SourceDataLine
            val initialHeader = initialBitstream.readFrame() ?: run {
                isPlaying = false
                return
            }

            msPerFrame = initialHeader.ms_per_frame()
            val initialSampleBuffer = decoder.decodeFrame(initialHeader, initialBitstream) as SampleBuffer
            val sampleRate = initialSampleBuffer.sampleFrequency.coerceAtLeast(8000)
            val channels = initialSampleBuffer.channelCount.coerceAtLeast(1)
            val frameBytes = channels * 2

            val format = AudioFormat(
                sampleRate.toFloat(),
                16,
                channels,
                true,
                false
            )

            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                throw LineUnavailableException("Audio format not supported by system mixer: $format")
            }

            val rawBufferSize = ((sampleRate * frameBytes * 0.15).toInt() / frameBytes) * frameBytes
            val lineBufferSize = rawBufferSize.coerceIn(8192, 32768).let { it - (it % frameBytes) }

            val dataLine = AudioSystem.getLine(info) as SourceDataLine
            dataLine.open(format, lineBufferSize)
            applyVolume(dataLine, currentVolume)
            dataLine.start()
            this.line = dataLine

            val stretcher = PcmTimeStretcher(sampleRate, channels)

            fun emitStretched(pcmBytes: ByteArray) {
                val stretched = stretcher.process(pcmBytes, pcmBytes.size, currentPlaybackSpeed)
                if (stretched.isNotEmpty() && !isStopped.get()) {
                    writeChunkedPcm(dataLine, stretched, lineBufferSize, frameBytes)
                }
            }

            // Write first frame
            emitStretched(sampleBufferToPcmBytes(initialSampleBuffer))
            initialBitstream.closeFrame()
            currentFrame++
            val initPendingMs = (stretcher.estimatedPendingInputFrames() * 1000L) / sampleRate
            currentPositionAtomic.set(((currentFrame * msPerFrame).toLong() - initPendingMs).coerceAtLeast(0L))

            var activeBs = initialBitstream

            fun applySeek(seekTargetMs: Long) {
                val targetFrame = (seekTargetMs / msPerFrame).toLong()
                dataLine.flush()
                stretcher.reset()

                if (targetFrame < currentFrame) {
                    try {
                        activeBs.close()
                        inputStream?.close()
                    } catch (_: Exception) {}

                    val newStream = BufferedInputStream(FileInputStream(file))
                    inputStream = newStream
                    activeBs = Bitstream(newStream)
                    bitstream = activeBs
                    currentFrame = 0
                }

                while (currentFrame < targetFrame && !isStopped.get()) {
                    val skipHeader = activeBs.readFrame() ?: break
                    activeBs.closeFrame()
                    currentFrame++
                }
                currentPositionAtomic.set((currentFrame * msPerFrame).toLong())
            }

            // Main playback loop
            while (!isStopped.get()) {
                // Handle Pause
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

                // Handle Seek
                val seekTargetMs = pendingSeekMs.getAndSet(-1L)
                if (seekTargetMs >= 0) {
                    applySeek(seekTargetMs)
                }

                // Decode next frame
                val header = activeBs.readFrame() ?: break // End of stream reached
                msPerFrame = header.ms_per_frame()
                val sampleBuffer = decoder.decodeFrame(header, activeBs) as SampleBuffer
                emitStretched(sampleBufferToPcmBytes(sampleBuffer))
                activeBs.closeFrame()

                currentFrame++
                val pendingMs = (stretcher.estimatedPendingInputFrames() * 1000L) / sampleRate
                currentPositionAtomic.set(((currentFrame * msPerFrame).toLong() - pendingMs).coerceAtLeast(0L))
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
            // Normal thread cancellation
        } catch (t: Throwable) {
            if (!isStopped.get()) {
                onError?.invoke(t)
            }
        } finally {
            try {
                bitstream?.close()
                inputStream?.close()
            } catch (_: Exception) {}

            try {
                line?.stop()
                line?.close()
            } catch (_: Exception) {}
            line = null

            val wasNormalCompletion = !isStopped.get()
            isPlaying = false
            isPaused = false

            if (wasNormalCompletion) {
                onCompletion?.invoke()
            }
        }
    }

    private fun sampleBufferToPcmBytes(sampleBuffer: SampleBuffer): ByteArray {
        val buffer = sampleBuffer.buffer
        val length = sampleBuffer.bufferLength
        val pcmBytes = ByteArray(length * 2)

        for (i in 0 until length) {
            val sample = buffer[i].toInt()
            pcmBytes[i * 2] = (sample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return pcmBytes
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

package org.bible.reading.audio.player

import java.io.File

/**
 * An active decoding and playback session for an audio file.
 */
interface AudioStreamSession {
    val file: File
    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
    val isPaused: Boolean

    fun play()
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun release()
    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (Throwable) -> Unit)
}

/**
 * Pluggable audio codec interface.
 * Allows extending support to any audio format (MP3, WAV, OGG, FLAC, AAC, etc.)
 * without altering player logic or UI components.
 */
interface AudioCodec {
    /** Codec display name, e.g. "MP3", "WAV", "OGG", "FLAC" */
    val name: String

    /** Supported file extensions (lowercase without dot), e.g. ["mp3"] */
    val supportedExtensions: List<String>

    fun canHandle(file: File): Boolean {
        val ext = file.extension.lowercase()
        return supportedExtensions.contains(ext)
    }

    /**
     * Creates an active playback session for the given file.
     */
    fun createSession(file: File): AudioStreamSession
}

/**
 * Registry of pluggable audio decoders.
 */
object AudioCodecRegistry {
    private val codecs = mutableListOf<AudioCodec>()

    init {
        register(Mp3AudioCodec())
        register(WavAudioCodec())
    }

    fun register(codec: AudioCodec) {
        if (!codecs.contains(codec)) {
            codecs.add(codec)
        }
    }

    fun findCodec(file: File): AudioCodec? {
        return codecs.firstOrNull { it.canHandle(file) }
    }

    fun getSupportedExtensions(): List<String> {
        return codecs.flatMap { it.supportedExtensions }.distinct()
    }

    fun getAllCodecs(): List<AudioCodec> = codecs.toList()
}

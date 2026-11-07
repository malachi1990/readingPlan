package org.bible.reading.audio.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bible.reading.audio.model.AudioTrack

/**
 * High-level observable audio player managing playlist playback, playback states,
 * and time-tracking for Compose desktop UI.
 */
class AudioBiblePlayer(
    initialVolume: Float = 0.85f,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    var currentTrack by mutableStateOf<AudioTrack?>(null)
        private set

    val playlist = mutableStateListOf<AudioTrack>()

    var currentTrackIndex by mutableStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isPaused by mutableStateOf(false)
        private set

    var currentPositionMs by mutableStateOf(0L)
        private set

    var durationMs by mutableStateOf(0L)
        private set

    var volume by mutableStateOf(initialVolume)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var autoPlayNext by mutableStateOf(true)

    private var activeSession: AudioStreamSession? = null
    private var progressJob: Job? = null

    fun playTrack(track: AudioTrack) {
        playTracks(listOf(track), 0)
    }

    fun playTracks(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        stop()

        playlist.clear()
        playlist.addAll(tracks)
        currentTrackIndex = startIndex.coerceIn(tracks.indices)
        startCurrentTrack()
    }

    private fun startCurrentTrack() {
        if (playlist.isEmpty() || currentTrackIndex !in playlist.indices) return
        val track = playlist[currentTrackIndex]
        currentTrack = track
        errorMessage = null
        currentPositionMs = 0L

        val codec = AudioCodecRegistry.findCodec(track.file)
        if (codec == null) {
            errorMessage = "Unsupported audio format for file: ${track.file.name}"
            return
        }

        try {
            val session = codec.createSession(track.file)
            session.setVolume(volume)
            session.setOnCompletionListener {
                scope.launch {
                    handleTrackCompletion()
                }
            }
            session.setOnErrorListener { err ->
                scope.launch {
                    errorMessage = "Audio playback error: ${err.message}"
                    isPlaying = false
                    isPaused = false
                }
            }

            activeSession = session
            session.play()
            isPlaying = true
            isPaused = false
            durationMs = if (session.durationMs > 0) session.durationMs else track.durationMs

            startProgressPolling()
        } catch (e: Exception) {
            errorMessage = "Failed to start audio playback: ${e.message}"
            isPlaying = false
            isPaused = false
        }
    }

    fun togglePlayPause() {
        if (isPaused) {
            resume()
        } else if (isPlaying) {
            pause()
        } else if (currentTrack != null) {
            startCurrentTrack()
        }
    }

    fun pause() {
        activeSession?.pause()
        isPaused = true
    }

    fun resume() {
        activeSession?.resume()
        isPaused = false
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            activeSession?.stop()
            activeSession?.release()
        } catch (_: Exception) {}
        activeSession = null
        isPlaying = false
        isPaused = false
        currentPositionMs = 0L
    }

    fun seekTo(positionMs: Long) {
        activeSession?.seekTo(positionMs)
        currentPositionMs = positionMs
    }

    fun setVolumeLevel(newVolume: Float) {
        volume = newVolume.coerceIn(0f, 1f)
        activeSession?.setVolume(volume)
    }

    fun nextTrack() {
        if (playlist.isEmpty()) return
        if (currentTrackIndex < playlist.lastIndex) {
            stop()
            currentTrackIndex++
            startCurrentTrack()
        } else {
            stop()
        }
    }

    fun previousTrack() {
        if (playlist.isEmpty()) return
        if (currentPositionMs > 3000L) {
            // Restart current track if played more than 3 seconds
            seekTo(0L)
        } else if (currentTrackIndex > 0) {
            stop()
            currentTrackIndex--
            startCurrentTrack()
        }
    }

    private fun handleTrackCompletion() {
        if (autoPlayNext && currentTrackIndex < playlist.lastIndex) {
            nextTrack()
        } else {
            stop()
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && isPlaying) {
                activeSession?.let { sess ->
                    currentPositionMs = sess.currentPositionMs
                    if (sess.durationMs > 0 && durationMs <= 0) {
                        durationMs = sess.durationMs
                    }
                }
                delay(200)
            }
        }
    }

    fun release() {
        stop()
    }
}

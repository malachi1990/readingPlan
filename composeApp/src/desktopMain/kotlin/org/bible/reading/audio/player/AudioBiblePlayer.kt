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
import kotlin.math.roundToInt

/**
 * High-level observable audio player managing playlist playback, playback states,
 * playback speed (0.25x to 2.00x in 0.05x steps), and time-tracking for Compose desktop UI.
 */
class AudioBiblePlayer(
    initialVolume: Float = 0.85f,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    initialPlaybackSpeed: Float = 1.0f
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

    private var playbackSpeedState by mutableStateOf(normalizePlaybackSpeed(initialPlaybackSpeed))
    val playbackSpeed: Float
        get() = playbackSpeedState

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var autoPlayNext by mutableStateOf(true)

    var onPlaybackSpeedChanged: ((Float) -> Unit)? = null

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

    /**
     * Appends a track to the end of the playlist.
     * If nothing is currently playing or paused, starts playing it immediately.
     */
    fun enqueueTrack(track: AudioTrack) {
        playlist.add(track)
        if (currentTrack == null && !isPlaying && !isPaused) {
            currentTrackIndex = playlist.lastIndex
            startCurrentTrack()
        }
    }

    /**
     * Appends multiple tracks to the end of the playlist.
     * If nothing is currently playing or paused, starts playing the first newly enqueued track.
     */
    fun enqueueTracks(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        val wasIdle = currentTrack == null && !isPlaying && !isPaused
        val startIndex = if (wasIdle) playlist.size else currentTrackIndex
        playlist.addAll(tracks)
        if (wasIdle) {
            currentTrackIndex = startIndex.coerceIn(playlist.indices)
            startCurrentTrack()
        }
    }

    /**
     * Removes a track from the playlist at the given index.
     */
    fun removeTrack(index: Int) {
        if (index !in playlist.indices) return
        if (index == currentTrackIndex) {
            if (playlist.size == 1) {
                stop()
                playlist.clear()
            } else {
                nextTrack()
                playlist.removeAt(index)
                if (currentTrackIndex > index) {
                    currentTrackIndex--
                }
            }
        } else {
            playlist.removeAt(index)
            if (currentTrackIndex > index) {
                currentTrackIndex--
            }
        }
    }

    /**
     * Clears all tracks from the playlist and stops playback.
     */
    fun clearPlaylist() {
        stop()
        playlist.clear()
        currentTrackIndex = 0
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
            session.setPlaybackSpeed(playbackSpeed)
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
        currentTrack = null
    }

    /**
     * Stops playback and dismisses the active track and audio player widget.
     */
    fun dismiss() {
        stop()
    }

    fun seekTo(positionMs: Long) {
        val maxDuration = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, maxDuration)
        currentPositionMs = target
        activeSession?.seekTo(target)
    }

    /**
     * Relative seek by deltaMs (positive to seek forward, negative to rewind).
     */
    fun seekBy(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }

    /**
     * Seeks forward by the specified duration in milliseconds (default 10 seconds).
     */
    fun seekForward(ms: Long = 10_000L) {
        seekBy(ms)
    }

    /**
     * Seeks backward by the specified duration in milliseconds (default 10 seconds).
     */
    fun seekBackward(ms: Long = 10_000L) {
        seekBy(-ms)
    }

    fun setVolumeLevel(newVolume: Float) {
        volume = newVolume.coerceIn(0f, 1f)
        activeSession?.setVolume(volume)
    }

    /**
     * Sets playback speed within [0.25x, 2.00x], snapped to exact 0.05x (5%) increments.
     */
    fun setPlaybackSpeed(newSpeed: Float) {
        val normalized = normalizePlaybackSpeed(newSpeed)
        val changed = normalized != playbackSpeedState
        playbackSpeedState = normalized
        activeSession?.setPlaybackSpeed(normalized)
        if (changed) {
            onPlaybackSpeedChanged?.invoke(normalized)
        }
    }

    /**
     * Increases playback speed by 5% (0.05x), up to 2.00x.
     */
    fun increasePlaybackSpeed() {
        val currentStep = (playbackSpeed / PLAYBACK_SPEED_STEP).roundToInt().coerceIn(5, 40)
        val nextStep = (currentStep + 1).coerceAtMost(40)
        setPlaybackSpeed((nextStep * 5) / 100f)
    }

    /**
     * Decreases playback speed by 5% (0.05x), down to 0.25x.
     */
    fun decreasePlaybackSpeed() {
        val currentStep = (playbackSpeed / PLAYBACK_SPEED_STEP).roundToInt().coerceIn(5, 40)
        val prevStep = (currentStep - 1).coerceAtLeast(5)
        setPlaybackSpeed((prevStep * 5) / 100f)
    }

    /**
     * Resets playback speed to 1.00x.
     */
    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f)
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

    /**
     * Checks if the player currently has a track with the given title active or playing.
     */
    fun isTrackActive(title: String): Boolean {
        return (isPlaying || isPaused) && currentTrack?.title == title
    }

    /**
     * Checks if the player currently has a track with the given title anywhere in the playlist.
     */
    fun isTrackQueued(title: String): Boolean {
        return playlist.any { it.title == title }
    }

    /**
     * Checks if the player is actively playing (not paused) a track with the given title.
     */
    fun isActivelyPlaying(title: String): Boolean {
        return isPlaying && currentTrack?.title == title
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

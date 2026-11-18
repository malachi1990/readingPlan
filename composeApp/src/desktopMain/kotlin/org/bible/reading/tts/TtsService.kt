package org.bible.reading.tts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bible.reading.audio.model.AudioTrack
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.jsword.ChapterPassage
import org.crosswire.jsword.book.Book
import java.io.File
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem

class TtsService(
    val appDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val preferencesManager = TtsPreferencesManager(appDir)

    var preferences by mutableStateOf(preferencesManager.loadPreferences())
        private set

    val piperManager = PiperManager(appDir)
    val kokoroManager = KokoroManager(appDir)
    val systemTtsEngine = SystemTtsEngine()

    val cacheDir = File(File(appDir, "tts"), "cache").apply { mkdirs() }

    // Observable states for UI feedback
    var isSynthesizing by mutableStateOf(false)
        private set

    var currentReadingTitle by mutableStateOf<String?>(null)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    var isDownloading by mutableStateOf(false)
        private set

    var downloadingVoiceId by mutableStateOf<String?>(null)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    private var activeJob: Job? = null
    private val queueMutex = Mutex()

    fun updatePreferences(newPrefs: TtsPreferences) {
        preferences = newPrefs
        preferencesManager.savePreferences(newPrefs)
    }

    fun setEngine(engine: TtsEngineType) {
        val voices = TtsVoiceCatalog.getVoicesForEngine(engine)
        val newVoice = voices.firstOrNull()?.id ?: preferences.selectedVoiceId
        updatePreferences(preferences.copy(selectedEngine = engine, selectedVoiceId = newVoice))
    }

    fun setVoice(voiceId: String) {
        updatePreferences(preferences.copy(selectedVoiceId = voiceId))
    }

    fun setSpeechRate(rate: Float) {
        updatePreferences(preferences.copy(speechRate = rate.coerceIn(0.7f, 1.6f)))
    }

    fun setSentenceSilence(silence: Float) {
        updatePreferences(preferences.copy(sentenceSilence = silence.coerceIn(0.0f, 0.8f)))
    }

    fun setKokoroPythonPath(path: String) {
        updatePreferences(preferences.copy(kokoroPythonPath = path.trim()))
    }

    fun setKokoroBinaryPath(path: String) {
        updatePreferences(preferences.copy(kokoroBinaryPath = path.trim()))
    }

    fun setKokoroVoicesPath(path: String) {
        updatePreferences(preferences.copy(kokoroVoicesPath = path.trim()))
    }

    fun setKokoroUseCuda(useCuda: Boolean) {
        updatePreferences(preferences.copy(kokoroUseCuda = useCuda))
    }

    fun getActiveVoice(): TtsVoice {
        return TtsVoiceCatalog.findVoice(preferences.selectedVoiceId)
    }

    fun isVoiceReady(voice: TtsVoice): Boolean {
        return when (voice.engineType) {
            TtsEngineType.PIPER -> piperManager.isBinaryInstalled() && piperManager.isVoiceDownloaded(voice)
            TtsEngineType.KOKORO -> kokoroManager.isAvailable(
                configuredPython = preferences.kokoroPythonPath,
                configuredModel = preferences.kokoroBinaryPath,
                configuredVoices = preferences.kokoroVoicesPath
            )
            TtsEngineType.SYSTEM -> true
        }
    }

    /**
     * Downloads Piper engine (if needed) and voice model in background.
     */
    fun downloadVoiceAsync(voice: TtsVoice, onComplete: ((Boolean) -> Unit)? = null): Job {
        return scope.launch {
            isDownloading = true
            downloadingVoiceId = voice.id
            downloadProgress = 0f
            statusMessage = "Preparing ${voice.name}..."

            try {
                if (voice.engineType == TtsEngineType.PIPER) {
                    if (!piperManager.isBinaryInstalled()) {
                        statusMessage = "Downloading Piper engine binary..."
                        val binRes = piperManager.downloadBinary { frac ->
                            downloadProgress = frac * 0.35f
                        }
                        if (binRes.isFailure) {
                            statusMessage = "Failed to download Piper: ${binRes.exceptionOrNull()?.message}"
                            isDownloading = false
                            onComplete?.invoke(false)
                            return@launch
                        }
                    }

                    statusMessage = "Downloading neural voice ${voice.name}..."
                    val voiceRes = piperManager.downloadVoice(voice) { frac ->
                        downloadProgress = 0.35f + (frac * 0.65f)
                    }

                    if (voiceRes.isFailure) {
                        statusMessage = "Failed to download voice: ${voiceRes.exceptionOrNull()?.message}"
                        isDownloading = false
                        onComplete?.invoke(false)
                        return@launch
                    }
                }

                statusMessage = "Voice ${voice.name} is ready!"
                downloadProgress = 1f
                onComplete?.invoke(true)
            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
                onComplete?.invoke(false)
            } finally {
                isDownloading = false
                downloadingVoiceId = null
            }
        }
    }

    /**
     * Core synthesis logic with disk-based audio caching.
     */
    suspend fun synthesizeAudio(
        textToSpeak: String,
        trackTitle: String
    ): Result<File> = withContext(Dispatchers.IO) {
        val voice = getActiveVoice()
        val speed = preferences.speechRate
        val silence = preferences.sentenceSilence

        // Calculate cache key incorporating engine, speed, silence, and kokoro options
        val cacheKey = hashText(
            "${preferences.selectedEngine.id}:${voice.id}:$speed:$silence:" +
            "${preferences.kokoroUseCuda}:${preferences.kokoroBinaryPath}:$textToSpeak"
        )
        val cachedFile = File(cacheDir, "$cacheKey.wav")

        if (cachedFile.exists() && cachedFile.length() > 1024) {
            return@withContext Result.success(cachedFile)
        }

        val tempFile = File(cacheDir, "$cacheKey.tmp.wav")

        val result = when (voice.engineType) {
            TtsEngineType.PIPER -> {
                if (!piperManager.isBinaryInstalled()) {
                    val dlBin = piperManager.downloadBinary()
                    if (dlBin.isFailure) {
                        return@withContext Result.failure(dlBin.exceptionOrNull() ?: Exception("Piper binary not ready"))
                    }
                }
                if (!piperManager.isVoiceDownloaded(voice)) {
                    val dlVoice = piperManager.downloadVoice(voice)
                    if (dlVoice.isFailure) {
                        return@withContext Result.failure(dlVoice.exceptionOrNull() ?: Exception("Voice model not downloaded"))
                    }
                }
                piperManager.synthesize(textToSpeak, tempFile, voice, speed, silence)
            }
            TtsEngineType.KOKORO -> {
                kokoroManager.synthesize(
                    text = textToSpeak,
                    outputWav = tempFile,
                    voice = voice,
                    speechRate = speed,
                    configuredPython = preferences.kokoroPythonPath,
                    configuredModel = preferences.kokoroBinaryPath,
                    configuredVoices = preferences.kokoroVoicesPath,
                    useCuda = preferences.kokoroUseCuda
                )
            }
            TtsEngineType.SYSTEM -> {
                systemTtsEngine.synthesize(textToSpeak, tempFile, speed)
            }
        }

        if (result.isSuccess && tempFile.exists()) {
            tempFile.renameTo(cachedFile)
            Result.success(cachedFile)
        } else {
            tempFile.delete()
            Result.failure(result.exceptionOrNull() ?: RuntimeException("Audio synthesis failed"))
        }
    }

    /**
     * Saves a synthesized audio file to a user-specified destination file.
     */
    fun saveAudioToFile(sourceFile: File, destinationFile: File): Result<File> {
        return try {
            destinationFile.parentFile?.mkdirs()
            sourceFile.copyTo(destinationFile, overwrite = true)
            statusMessage = "Audio saved to ${destinationFile.name}"
            Result.success(destinationFile)
        } catch (e: Exception) {
            val err = "Failed to save audio: ${e.message}"
            statusMessage = err
            Result.failure(e)
        }
    }

    /**
     * Saves an AudioTrack's file to a user-specified destination.
     */
    fun saveTrackToFile(track: AudioTrack, destinationFile: File): Result<File> {
        return saveAudioToFile(track.file, destinationFile)
    }

    /**
     * Synthesizes text directly to a user-specified destination file without playing.
     */
    suspend fun synthesizeToFile(
        textToSpeak: String,
        trackTitle: String,
        destinationFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        statusMessage = "Synthesizing audio for export..."
        val synthRes = synthesizeAudio(textToSpeak, trackTitle)
        if (synthRes.isFailure) {
            val err = synthRes.exceptionOrNull()?.message ?: "Synthesis failed"
            statusMessage = "Export failed: $err"
            return@withContext synthRes
        }
        val wavFile = synthRes.getOrThrow()
        saveAudioToFile(wavFile, destinationFile)
    }

    /**
     * Reads a full chapter passage from Further Study immediately.
     */
    fun readPassage(
        passage: ChapterPassage,
        book: Book,
        player: AudioBiblePlayer
    ) {
        val trackTitle = "${passage.bookName} ${passage.chapterNumber} (${book.initials})"

        if (player.isTrackActive(trackTitle)) {
            player.togglePlayPause()
            return
        }

        val speechText = TtsTextCleaner.formatPassageForSpeech(
            passage.bookName,
            passage.chapterNumber,
            passage.fullText
        )

        if (speechText.isBlank()) {
            statusMessage = "No text content available to read for this chapter."
            return
        }

        startReadingTrack(trackTitle, book.name, speechText, player)
    }

    /**
     * Queues a scripture chapter passage for TTS playback.
     */
    fun queuePassage(
        passage: ChapterPassage,
        book: Book,
        player: AudioBiblePlayer
    ) {
        val trackTitle = "${passage.bookName} ${passage.chapterNumber} (${book.initials})"

        if (player.isTrackQueued(trackTitle)) {
            statusMessage = "\"$trackTitle\" is already in the playlist queue."
            return
        }

        val speechText = TtsTextCleaner.formatPassageForSpeech(
            passage.bookName,
            passage.chapterNumber,
            passage.fullText
        )
        if (speechText.isBlank()) {
            statusMessage = "No text content available to read."
            return
        }

        if (!player.isPlaying && !player.isPaused && player.currentTrack == null) {
            startReadingTrack(trackTitle, book.name, speechText, player)
            return
        }

        scope.launch {
            statusMessage = "Queuing \"$trackTitle\"..."
            try {
                val res = synthesizeAudio(speechText, trackTitle)
                if (res.isSuccess) {
                    val file = res.getOrThrow()
                    val durationMs = computeDurationMs(file)
                    val track = AudioTrack(
                        file = file,
                        title = trackTitle,
                        subtitle = book.name,
                        durationMs = durationMs
                    )
                    queueMutex.withLock {
                        player.enqueueTrack(track)
                    }
                    statusMessage = "Queued \"$trackTitle\" (${player.playlist.size} in queue)"
                } else {
                    statusMessage = "Failed to queue \"$trackTitle\": ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                statusMessage = "Error queuing \"$trackTitle\": ${e.message}"
            }
        }
    }

    /**
     * Reads an individual verse.
     */
    fun readVerse(
        reference: String,
        verseText: String,
        bookInitials: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = "$reference ($bookInitials)"

        if (player.isTrackActive(trackTitle)) {
            player.togglePlayPause()
            return
        }

        val speechText = TtsTextCleaner.formatVerseForSpeech(reference, verseText)
        if (speechText.isBlank()) return

        startReadingTrack(trackTitle, "Verse $reference", speechText, player)
    }

    /**
     * Queues an individual verse for TTS playback.
     */
    fun queueVerse(
        reference: String,
        verseText: String,
        bookInitials: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = "$reference ($bookInitials)"
        if (player.isTrackQueued(trackTitle)) {
            statusMessage = "\"$trackTitle\" is already in queue."
            return
        }

        val speechText = TtsTextCleaner.formatVerseForSpeech(reference, verseText)
        if (speechText.isBlank()) return

        if (!player.isPlaying && !player.isPaused && player.currentTrack == null) {
            startReadingTrack(trackTitle, "Verse $reference", speechText, player)
            return
        }

        scope.launch {
            statusMessage = "Queuing \"$trackTitle\"..."
            try {
                val res = synthesizeAudio(speechText, trackTitle)
                if (res.isSuccess) {
                    val file = res.getOrThrow()
                    val durationMs = computeDurationMs(file)
                    val track = AudioTrack(
                        file = file,
                        title = trackTitle,
                        subtitle = "Verse $reference",
                        durationMs = durationMs
                    )
                    queueMutex.withLock {
                        player.enqueueTrack(track)
                    }
                    statusMessage = "Queued \"$trackTitle\" (${player.playlist.size} in queue)"
                } else {
                    statusMessage = "Failed to queue \"$trackTitle\": ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                statusMessage = "Error queuing \"$trackTitle\": ${e.message}"
            }
        }
    }

    /**
     * Reads a general book section or dictionary entry immediately.
     */
    fun readSection(
        title: String,
        subtitle: String,
        content: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = if (subtitle.isNotBlank()) "$title • $subtitle" else title

        if (player.isTrackActive(trackTitle)) {
            player.togglePlayPause()
            return
        }

        val speechText = TtsTextCleaner.formatSectionForSpeech(title, subtitle, content)
        if (speechText.isBlank()) {
            statusMessage = "No content available to read."
            return
        }

        startReadingTrack(trackTitle, subtitle, speechText, player)
    }

    /**
     * Queues a general book section or dictionary entry for TTS playback.
     * If playback is idle, starts playing immediately; otherwise enqueues to playlist.
     */
    fun queueSection(
        title: String,
        subtitle: String,
        content: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = if (subtitle.isNotBlank()) "$title • $subtitle" else title

        if (player.isTrackQueued(trackTitle)) {
            statusMessage = "\"$title\" is already in the queue."
            return
        }

        val speechText = TtsTextCleaner.formatSectionForSpeech(title, subtitle, content)
        if (speechText.isBlank()) {
            statusMessage = "No content available to read for \"$title\"."
            return
        }

        if (!player.isPlaying && !player.isPaused && player.currentTrack == null) {
            startReadingTrack(trackTitle, subtitle, speechText, player)
            return
        }

        scope.launch {
            statusMessage = "Queuing \"$title\"..."
            try {
                val res = synthesizeAudio(speechText, trackTitle)
                if (res.isSuccess) {
                    val file = res.getOrThrow()
                    val durationMs = computeDurationMs(file)
                    val track = AudioTrack(
                        file = file,
                        title = trackTitle,
                        subtitle = subtitle,
                        durationMs = durationMs
                    )
                    queueMutex.withLock {
                        player.enqueueTrack(track)
                    }
                    statusMessage = "Added \"$title\" to queue (${player.playlist.size} total)"
                } else {
                    statusMessage = "Failed to queue \"$title\": ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                statusMessage = "Error queuing \"$title\": ${e.message}"
            }
        }
    }

    /**
     * Tests the currently selected or specified voice with a classic scripture sample.
     */
    fun testVoice(
        voice: TtsVoice = getActiveVoice(),
        player: AudioBiblePlayer
    ) {
        val sampleText = "The Lord is my shepherd; I shall not want. He maketh me to lie down in green pastures: he leadeth me beside the still waters."
        val title = "Sample: ${voice.name}"

        if (player.isTrackActive(title)) {
            player.togglePlayPause()
            return
        }

        startReadingTrack(title, "Voice Preview", sampleText, player)
    }

    private fun startReadingTrack(
        title: String,
        subtitle: String,
        text: String,
        player: AudioBiblePlayer
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            isSynthesizing = true
            currentReadingTitle = title
            statusMessage = "Generating speech for $title..."

            try {
                val res = synthesizeAudio(text, title)
                if (res.isSuccess) {
                    val file = res.getOrThrow()
                    val durationMs = computeDurationMs(file)
                    val track = AudioTrack(
                        file = file,
                        title = title,
                        subtitle = subtitle,
                        durationMs = durationMs
                    )
                    player.playTrack(track)
                    statusMessage = null
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                    statusMessage = "TTS error: $err"
                }
            } catch (e: Exception) {
                statusMessage = "TTS error: ${e.message}"
            } finally {
                isSynthesizing = false
            }
        }
    }

    fun stopReading(player: AudioBiblePlayer) {
        activeJob?.cancel()
        activeJob = null
        isSynthesizing = false
        currentReadingTitle = null
        player.stop()
    }

    fun computeDurationMs(file: File): Long {
        return try {
            AudioSystem.getAudioInputStream(file).use { ais ->
                val format = ais.format
                val frames = ais.frameLength
                if (frames > 0 && format.frameRate > 0) {
                    (frames * 1000L / format.frameRate.toLong())
                } else -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun hashText(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}

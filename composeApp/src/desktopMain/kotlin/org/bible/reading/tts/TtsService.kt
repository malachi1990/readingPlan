package org.bible.reading.tts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javazoom.jl.decoder.Bitstream
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem

class TtsService(
    val appDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES
) {
    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 300L * 1024L * 1024L
        private const val STALE_TEMP_AGE_MS = 60L * 60L * 1000L
    }

    private val preferencesManager = TtsPreferencesManager(appDir)

    var preferences by mutableStateOf(preferencesManager.loadPreferences())
        private set

    val piperManager = PiperManager(appDir)
    val kokoroManager = KokoroManager(appDir)
    val systemTtsEngine = SystemTtsEngine()

    val cacheDir = File(File(appDir, "tts"), "cache").apply { mkdirs() }
    private val cacheLock = Any()

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
        kokoroManager.invalidateCache()
    }

    fun setKokoroBinaryPath(path: String) {
        updatePreferences(preferences.copy(kokoroBinaryPath = path.trim()))
        kokoroManager.invalidateCache()
    }

    fun setKokoroVoicesPath(path: String) {
        updatePreferences(preferences.copy(kokoroVoicesPath = path.trim()))
        kokoroManager.invalidateCache()
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

    suspend fun isVoiceReadyAsync(voice: TtsVoice): Boolean = withContext(Dispatchers.IO) {
        isVoiceReady(voice)
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
     * Core synthesis logic with disk-based 128kbps MP3 audio caching.
     */
    suspend fun synthesizeAudio(
        textToSpeak: String,
        trackTitle: String
    ): Result<File> = withContext(Dispatchers.IO) {
        val voice = getActiveVoice()
        val speed = preferences.speechRate
        val silence = preferences.sentenceSilence

        // Calculate cache key incorporating output format (mp3_128), engine, speed, silence, and kokoro options
        val cacheKey = hashText(
            "mp3_${TtsMp3Encoder.DEFAULT_BITRATE_KBPS}:${preferences.selectedEngine.id}:${voice.id}:$speed:$silence:" +
            "${preferences.kokoroUseCuda}:${preferences.kokoroBinaryPath}:$textToSpeak"
        )
        val cachedFile = File(cacheDir, "$cacheKey.mp3")

        synchronized(cacheLock) {
            if (cachedFile.exists() && cachedFile.length() > 256) {
                cachedFile.setLastModified(System.currentTimeMillis())
                return@withContext Result.success(cachedFile)
            }
        }

        val tempWavFile = File(cacheDir, "$cacheKey.tmp.wav")
        val tempMp3File = File(cacheDir, "$cacheKey.tmp.mp3")

        try {
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
                    piperManager.synthesize(textToSpeak, tempWavFile, voice, speed, silence)
                }
                TtsEngineType.KOKORO -> {
                    kokoroManager.synthesize(
                        text = textToSpeak,
                        outputWav = tempWavFile,
                        voice = voice,
                        speechRate = speed,
                        configuredPython = preferences.kokoroPythonPath,
                        configuredModel = preferences.kokoroBinaryPath,
                        configuredVoices = preferences.kokoroVoicesPath,
                        useCuda = preferences.kokoroUseCuda
                    )
                }
                TtsEngineType.SYSTEM -> {
                    systemTtsEngine.synthesize(textToSpeak, tempWavFile, speed)
                }
            }

            if (result.isSuccess && tempWavFile.exists()) {
                val encodeResult = TtsMp3Encoder.encodeWavToMp3(
                    sourceWav = tempWavFile,
                    targetMp3 = tempMp3File,
                    bitrateKbps = TtsMp3Encoder.DEFAULT_BITRATE_KBPS
                )
                if (encodeResult.isSuccess && tempMp3File.exists()) {
                    synchronized(cacheLock) {
                        try {
                            Files.move(
                                tempMp3File.toPath(),
                                cachedFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            tempMp3File.renameTo(cachedFile)
                        }
                        cachedFile.setLastModified(System.currentTimeMillis())
                        enforceCacheLimitLocked(excludeFile = cachedFile)
                    }
                    Result.success(cachedFile)
                } else {
                    tempMp3File.delete()
                    Result.failure(encodeResult.exceptionOrNull() ?: RuntimeException("MP3 encoding failed"))
                }
            } else {
                Result.failure(result.exceptionOrNull() ?: RuntimeException("Audio synthesis failed"))
            }
        } finally {
            tempWavFile.delete()
            if (tempMp3File.exists()) {
                tempMp3File.delete()
            }
        }
    }

    /**
     * Enforces bounded LRU eviction on [cacheDir] so total cached `.mp3` audio never grows
     * beyond [maxCacheBytes]. Protected by [cacheLock].
     */
    fun enforceCacheLimit(excludeFile: File? = null) {
        synchronized(cacheLock) {
            enforceCacheLimitLocked(excludeFile)
        }
    }

    private fun enforceCacheLimitLocked(excludeFile: File? = null) {
        val now = System.currentTimeMillis()
        val files = cacheDir.listFiles() ?: return

        val mp3Files = mutableListOf<File>()
        var totalBytes = 0L

        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (name.contains(".tmp.")) {
                if (now - file.lastModified() > STALE_TEMP_AGE_MS) {
                    file.delete()
                }
                continue
            }
            if (file.extension.equals("mp3", ignoreCase = true)) {
                mp3Files.add(file)
                totalBytes += file.length()
            }
        }

        if (totalBytes <= maxCacheBytes) return

        val excludePath = excludeFile?.absolutePath
        // Evict oldest-accessed entries first (LRU via lastModified timestamp), skipping the newly written file
        val candidates = mp3Files
            .filter { it.absolutePath != excludePath }
            .sortedBy { it.lastModified() }

        for (candidate in candidates) {
            if (totalBytes <= maxCacheBytes) break
            val size = candidate.length()
            if (candidate.delete()) {
                totalBytes -= size
            }
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
     * Synthesizes text directly to a user-specified destination 128kbps MP3 file without playing.
     */
    suspend fun synthesizeToFile(
        textToSpeak: String,
        trackTitle: String,
        destinationFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        statusMessage = "Synthesizing 128kbps MP3 for export..."
        val synthRes = synthesizeAudio(textToSpeak, trackTitle)
        if (synthRes.isFailure) {
            val err = synthRes.exceptionOrNull()?.message ?: "Synthesis failed"
            statusMessage = "Export failed: $err"
            return@withContext synthRes
        }
        val mp3File = synthRes.getOrThrow()
        saveAudioToFile(mp3File, destinationFile)
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
     * Builds the unique, human-readable track title for an individual paragraph in a non-Bible book.
     */
    fun buildParagraphTrackTitle(
        sectionTitle: String,
        subtitle: String,
        paragraphIndex: Int
    ): String {
        val base = sectionTitle.ifBlank { "Section" }
        val paraLabel = "¶${paragraphIndex + 1}"
        return if (subtitle.isNotBlank()) "$base • $paraLabel • $subtitle" else "$base • $paraLabel"
    }

    /**
     * Reads an individual paragraph from a non-Bible book immediately,
     * or toggles play/pause if that paragraph is already active.
     */
    fun readParagraph(
        sectionTitle: String,
        subtitle: String,
        paragraphIndex: Int,
        paragraphText: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = buildParagraphTrackTitle(sectionTitle, subtitle, paragraphIndex)

        if (player.isTrackActive(trackTitle)) {
            player.togglePlayPause()
            return
        }

        val speechText = TtsTextCleaner.formatParagraphForSpeech(paragraphText)
        if (speechText.isBlank()) {
            statusMessage = "No speakable text available in paragraph ${paragraphIndex + 1}."
            return
        }

        startReadingTrack(trackTitle, subtitle, speechText, player)
    }

    /**
     * Queues an individual paragraph from a non-Bible book for TTS playback.
     * If playback is idle, starts playing immediately; otherwise enqueues to playlist.
     */
    fun queueParagraph(
        sectionTitle: String,
        subtitle: String,
        paragraphIndex: Int,
        paragraphText: String,
        player: AudioBiblePlayer
    ) {
        val trackTitle = buildParagraphTrackTitle(sectionTitle, subtitle, paragraphIndex)

        if (player.isTrackQueued(trackTitle)) {
            statusMessage = "\"$trackTitle\" is already in the queue."
            return
        }

        val speechText = TtsTextCleaner.formatParagraphForSpeech(paragraphText)
        if (speechText.isBlank()) {
            statusMessage = "No speakable text available in paragraph ${paragraphIndex + 1}."
            return
        }

        if (!player.isPlaying && !player.isPaused && player.currentTrack == null) {
            startReadingTrack(trackTitle, subtitle, speechText, player)
            return
        }

        scope.launch {
            statusMessage = "Queuing paragraph ${paragraphIndex + 1}..."
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
                    statusMessage = "Added paragraph ${paragraphIndex + 1} to queue (${player.playlist.size} total)"
                } else {
                    statusMessage = "Failed to queue paragraph ${paragraphIndex + 1}: ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                statusMessage = "Error queuing paragraph ${paragraphIndex + 1}: ${e.message}"
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
        if (file.extension.equals("mp3", ignoreCase = true)) {
            return try {
                FileInputStream(file).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        val bitstream = Bitstream(bis)
                        try {
                            val header = bitstream.readFrame() ?: return -1L
                            val maxMs = header.total_ms(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            if (maxMs > 0) {
                                maxMs.toLong()
                            } else {
                                val bitrate = header.bitrate()
                                if (bitrate > 0) {
                                    (file.length() * 8L * 1000L) / bitrate
                                } else {
                                    -1L
                                }
                            }
                        } finally {
                            try {
                                bitstream.close()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
                -1L
            }
        }

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
        synchronized(cacheLock) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun clearCacheAsync() = withContext(Dispatchers.IO) {
        clearCache()
    }

    fun getCacheSizeBytes(): Long {
        synchronized(cacheLock) {
            return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    suspend fun getCacheSizeBytesAsync(): Long = withContext(Dispatchers.IO) {
        getCacheSizeBytes()
    }

    private fun hashText(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}

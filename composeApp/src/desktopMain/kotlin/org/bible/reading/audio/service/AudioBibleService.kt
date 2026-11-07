package org.bible.reading.audio.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bible.reading.audio.model.AudioTrack
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.player.AudioCodecRegistry
import org.bible.reading.audio.preferences.AudioPreferences
import org.bible.reading.audio.preferences.AudioPreferencesManager
import org.bible.reading.audio.strategy.AudioFilenameStrategy
import org.bible.reading.audio.strategy.AudioFilenameStrategyRegistry
import org.bible.reading.audio.strategy.BibleBookMatcher
import org.bible.reading.audio.strategy.CustomRegexAudioStrategy
import org.bible.reading.dto.Bible
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import java.io.File

/**
 * Service coordinating audio Bible preferences, file scanning/indexing according to
 * the selected strategy, and audio playback.
 */
class AudioBibleService(
    val appDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val preferencesManager = AudioPreferencesManager(appDir)

    var preferences by mutableStateOf(preferencesManager.loadPreferences())
        private set

    val player = AudioBiblePlayer(initialVolume = preferences.volume)

    // Current active strategy
    var currentStrategy: AudioFilenameStrategy by mutableStateOf(
        AudioFilenameStrategyRegistry.getStrategy(preferences.selectedStrategyId)
    )
        private set

    // Audio file index: (Bible, chapter) -> File
    private val audioIndex = mutableMapOf<Pair<Bible, Int>, File>()

    // Observable status for UI
    var isScanning by mutableStateOf(false)
        private set

    var totalAudioFilesFound by mutableIntStateOf(0)
        private set

    var totalChaptersMatched by mutableIntStateOf(0)
        private set

    var totalBooksMatched by mutableIntStateOf(0)
        private set

    private var rescanJob: Job? = null

    init {
        applyPreferences(preferences)
    }

    fun applyPreferences(newPrefs: AudioPreferences) {
        preferences = newPrefs
        preferencesManager.savePreferences(newPrefs)
        player.setVolumeLevel(newPrefs.volume)
        player.autoPlayNext = newPrefs.autoPlayNext

        // If custom regex strategy, configure custom parameters
        val strategy = AudioFilenameStrategyRegistry.getStrategy(newPrefs.selectedStrategyId)
        if (strategy is CustomRegexAudioStrategy) {
            strategy.customPattern = newPrefs.customRegex
            strategy.bookGroupIndex = newPrefs.customBookGroup
            strategy.chapterGroupIndex = newPrefs.customChapterGroup
        }
        currentStrategy = strategy

        rescanAsync()
    }

    fun setAudioDirectory(path: String) {
        val updated = preferences.copy(audioDirectory = path.trim())
        applyPreferences(updated)
    }

    fun setStrategy(strategyId: String) {
        val updated = preferences.copy(selectedStrategyId = strategyId)
        applyPreferences(updated)
    }

    fun rescanAsync(): Job {
        rescanJob?.cancel()
        val job = scope.launch {
            rescan()
        }
        rescanJob = job
        return job
    }

    suspend fun rescan() = withContext(Dispatchers.IO) {
        isScanning = true

        val dirPath = preferences.audioDirectory
        if (dirPath.isBlank()) {
            synchronized(audioIndex) { audioIndex.clear() }
            totalAudioFilesFound = 0
            totalChaptersMatched = 0
            totalBooksMatched = 0
            isScanning = false
            return@withContext
        }

        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            synchronized(audioIndex) { audioIndex.clear() }
            totalAudioFilesFound = 0
            totalChaptersMatched = 0
            totalBooksMatched = 0
            isScanning = false
            return@withContext
        }

        val supportedExtensions = AudioCodecRegistry.getSupportedExtensions()
        val foundFiles = mutableListOf<File>()

        fun walkDir(current: File) {
            val children = current.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    walkDir(child)
                } else if (child.isFile) {
                    val ext = child.extension.lowercase()
                    if (supportedExtensions.contains(ext)) {
                        foundFiles.add(child)
                    }
                }
            }
        }

        walkDir(dir)
        totalAudioFilesFound = foundFiles.size

        val strategy = currentStrategy
        val matchedBooks = mutableSetOf<Bible>()
        val newIndex = mutableMapOf<Pair<Bible, Int>, File>()

        for (file in foundFiles) {
            val match = strategy.parse(file)
            if (match != null) {
                newIndex[match.book to match.chapter] = match.file
                matchedBooks.add(match.book)
            }
        }

        synchronized(audioIndex) {
            audioIndex.clear()
            audioIndex.putAll(newIndex)
            totalChaptersMatched = audioIndex.size
            totalBooksMatched = matchedBooks.size
        }
        isScanning = false
    }

    fun findTrack(book: Bible, chapter: Int): AudioTrack? {
        val file = synchronized(audioIndex) { audioIndex[book to chapter] } ?: return null
        return AudioTrack(
            book = book,
            chapter = chapter,
            file = file,
            title = "${book.bookName} $chapter"
        )
    }

    fun findTrackForReading(reading: Reading): AudioTrack? {
        val book = BibleBookMatcher.matchBook(reading.book) ?: return null
        return findTrack(book, reading.chapterNumber)
    }

    fun findTracksForDailyReading(dailyReading: DailyReading): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        for (reading in dailyReading.reading) {
            val track = findTrackForReading(reading)
            if (track != null) {
                tracks.add(track)
            }
        }
        return tracks
    }

    fun hasAudioForReading(reading: Reading): Boolean {
        val book = BibleBookMatcher.matchBook(reading.book) ?: return null ?: false
        return synchronized(audioIndex) { audioIndex.containsKey(book to reading.chapterNumber) }
    }

    fun hasAudioForDailyReading(dailyReading: DailyReading): Boolean {
        return dailyReading.reading.any { hasAudioForReading(it) }
    }

    fun playDailyReading(dailyReading: DailyReading, startIndex: Int = 0) {
        val tracks = findTracksForDailyReading(dailyReading)
        if (tracks.isNotEmpty()) {
            player.playTracks(tracks, startIndex)
        }
    }

    fun playReading(reading: Reading) {
        val track = findTrackForReading(reading)
        if (track != null) {
            player.playTrack(track)
        }
    }
}

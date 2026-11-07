package org.bible.reading.audio

import kotlinx.coroutines.runBlocking
import org.bible.reading.audio.player.AudioCodec
import org.bible.reading.audio.player.AudioCodecRegistry
import org.bible.reading.audio.player.AudioStreamSession
import org.bible.reading.audio.player.Mp3AudioCodec
import org.bible.reading.audio.player.WavAudioCodec
import org.bible.reading.audio.preferences.AudioPreferences
import org.bible.reading.audio.preferences.AudioPreferencesManager
import org.bible.reading.audio.service.AudioBibleService
import org.bible.reading.audio.strategy.AudioFilenameStrategyRegistry
import org.bible.reading.audio.strategy.AutoDetectAudioStrategy
import org.bible.reading.audio.strategy.BibleBookMatcher
import org.bible.reading.audio.strategy.CustomRegexAudioStrategy
import org.bible.reading.audio.strategy.NumberedBookChapterStrategy
import org.bible.reading.audio.strategy.SequentialBibleStrategy
import org.bible.reading.audio.strategy.StandardBookChapterStrategy
import org.bible.reading.dto.Bible
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioBibleTest {

    @Test
    fun testBibleBookMatcher() {
        assertEquals(Bible.GENESIS, BibleBookMatcher.matchBook("Genesis"))
        assertEquals(Bible.GENESIS, BibleBookMatcher.matchBook("gen"))
        assertEquals(Bible.GENESIS, BibleBookMatcher.matchBook("01"))
        assertEquals(Bible.EXODUS, BibleBookMatcher.matchBook("Exod"))
        assertEquals(Bible.PSALMS, BibleBookMatcher.matchBook("Psalm"))
        assertEquals(Bible.PSALMS, BibleBookMatcher.matchBook("Ps"))
        assertEquals(Bible.SONG_OF_SOLOMON, BibleBookMatcher.matchBook("Song of Solomon"))
        assertEquals(Bible.SONG_OF_SOLOMON, BibleBookMatcher.matchBook("Song of Songs"))
        assertEquals(Bible.MATTHEW, BibleBookMatcher.matchBook("Matthew"))
        assertEquals(Bible.MATTHEW, BibleBookMatcher.matchBook("Matt"))
        assertEquals(Bible.MATTHEW, BibleBookMatcher.matchBook("40"))
        assertEquals(Bible.FIRST_CORINTHIANS, BibleBookMatcher.matchBook("1 Corinthians"))
        assertEquals(Bible.FIRST_CORINTHIANS, BibleBookMatcher.matchBook("1Cor"))
        assertEquals(Bible.FIRST_JOHN, BibleBookMatcher.matchBook("1 John"))
        assertEquals(Bible.FIRST_JOHN, BibleBookMatcher.matchBook("1Jn"))
        assertEquals(Bible.REVELATION, BibleBookMatcher.matchBook("Revelation"))
        assertEquals(Bible.REVELATION, BibleBookMatcher.matchBook("Rev"))
        assertEquals(Bible.REVELATION, BibleBookMatcher.matchBook("66"))
    }

    @Test
    fun testNumberedBookChapterStrategy() {
        val strategy = NumberedBookChapterStrategy()

        val match1 = strategy.parse(File("01_Genesis_001.mp3"))
        assertNotNull(match1)
        assertEquals(Bible.GENESIS, match1.book)
        assertEquals(1, match1.chapter)

        val match2 = strategy.parse(File("40-Matthew-28.mp3"))
        assertNotNull(match2)
        assertEquals(Bible.MATTHEW, match2.book)
        assertEquals(28, match2.chapter)

        val match3 = strategy.parse(File("B01___01_Genesis_____ENGESVN2DA.mp3"))
        assertNotNull(match3)
        assertEquals(Bible.GENESIS, match3.book)
        assertEquals(1, match3.chapter)

        // Invalid chapter exceeding book chapter count
        val invalidChapter = strategy.parse(File("01_Genesis_99.mp3"))
        assertEquals(null, invalidChapter)
    }

    @Test
    fun testStandardBookChapterStrategy() {
        val strategy = StandardBookChapterStrategy()

        val match1 = strategy.parse(File("Genesis 1.mp3"))
        assertNotNull(match1)
        assertEquals(Bible.GENESIS, match1.book)
        assertEquals(1, match1.chapter)

        val match2 = strategy.parse(File("Romans_8.mp3"))
        assertNotNull(match2)
        assertEquals(Bible.ROMANS, match2.book)
        assertEquals(8, match2.chapter)

        val match3 = strategy.parse(File("1John_03.mp3"))
        assertNotNull(match3)
        assertEquals(Bible.FIRST_JOHN, match3.book)
        assertEquals(3, match3.chapter)

        val match4 = strategy.parse(File("Psalm 23.mp3"))
        assertNotNull(match4)
        assertEquals(Bible.PSALMS, match4.book)
        assertEquals(23, match4.chapter)
    }

    @Test
    fun testSequentialBibleStrategy() {
        val strategy = SequentialBibleStrategy()

        // 0001 -> Genesis 1
        val gen1 = strategy.parse(File("0001.mp3"))
        assertNotNull(gen1)
        assertEquals(Bible.GENESIS, gen1.book)
        assertEquals(1, gen1.chapter)

        // 0050 -> Genesis 50
        val gen50 = strategy.parse(File("0050.mp3"))
        assertNotNull(gen50)
        assertEquals(Bible.GENESIS, gen50.book)
        assertEquals(50, gen50.chapter)

        // 0051 -> Exodus 1
        val exo1 = strategy.parse(File("0051.mp3"))
        assertNotNull(exo1)
        assertEquals(Bible.EXODUS, exo1.book)
        assertEquals(1, exo1.chapter)

        // 1189 -> Revelation 22
        val rev22 = strategy.parse(File("1189.mp3"))
        assertNotNull(rev22)
        assertEquals(Bible.REVELATION, rev22.book)
        assertEquals(22, rev22.chapter)

        // Out of bounds
        val outOfBounds = strategy.parse(File("1190.mp3"))
        assertEquals(null, outOfBounds)
    }

    @Test
    fun testAutoDetectAudioStrategy() {
        val autoStrategy = AutoDetectAudioStrategy()

        val matchA = autoStrategy.parse(File("01_Genesis_01.mp3"))
        assertNotNull(matchA)
        assertEquals(Bible.GENESIS, matchA.book)
        assertEquals(1, matchA.chapter)

        val matchB = autoStrategy.parse(File("Romans 8.mp3"))
        assertNotNull(matchB)
        assertEquals(Bible.ROMANS, matchB.book)
        assertEquals(8, matchB.chapter)

        val matchC = autoStrategy.parse(File("0051.mp3"))
        assertNotNull(matchC)
        assertEquals(Bible.EXODUS, matchC.book)
        assertEquals(1, matchC.chapter)
    }

    @Test
    fun testCustomRegexAudioStrategy() {
        val custom = CustomRegexAudioStrategy(
            customPattern = """Track-(\w+)-Ch(\d+)""",
            bookGroupIndex = 1,
            chapterGroupIndex = 2
        )

        val match = custom.parse(File("Track-John-Ch3.mp3"))
        assertNotNull(match)
        assertEquals(Bible.JOHN, match.book)
        assertEquals(3, match.chapter)
    }

    @Test
    fun testAudioStrategyRegistry() {
        val strategies = AudioFilenameStrategyRegistry.getAllStrategies()
        assertTrue(strategies.size >= 5)

        val auto = AudioFilenameStrategyRegistry.getStrategy("auto_detect")
        assertEquals("auto_detect", auto.id)

        val numbered = AudioFilenameStrategyRegistry.getStrategy("numbered_book_chapter")
        assertEquals("numbered_book_chapter", numbered.id)
    }

    @Test
    fun testAudioPreferencesManager() {
        val tempDir = Files.createTempDirectory("audio_pref_test").toFile()
        tempDir.deleteOnExit()

        val manager = AudioPreferencesManager(tempDir)
        val initial = manager.loadPreferences()
        assertEquals("", initial.audioDirectory)

        val updated = AudioPreferences(
            audioDirectory = "/home/user/AudioBible",
            selectedStrategyId = "numbered_book_chapter",
            volume = 0.75f,
            autoPlayNext = true
        )
        manager.savePreferences(updated)

        val reloaded = manager.loadPreferences()
        assertEquals("/home/user/AudioBible", reloaded.audioDirectory)
        assertEquals("numbered_book_chapter", reloaded.selectedStrategyId)
        assertEquals(0.75f, reloaded.volume)
        assertTrue(reloaded.autoPlayNext)
    }

    @Test
    fun testAudioCodecRegistryAndExtensibility() {
        val mp3File = File("test.mp3")
        val wavFile = File("test.wav")
        val aiffFile = File("test.aiff")
        val unknownFile = File("test.xyz")

        val mp3Codec = AudioCodecRegistry.findCodec(mp3File)
        assertNotNull(mp3Codec)
        assertTrue(mp3Codec is Mp3AudioCodec)

        val wavCodec = AudioCodecRegistry.findCodec(wavFile)
        assertNotNull(wavCodec)
        assertTrue(wavCodec is WavAudioCodec)

        val aiffCodec = AudioCodecRegistry.findCodec(aiffFile)
        assertNotNull(aiffCodec)
        assertTrue(aiffCodec is WavAudioCodec)

        assertEquals(null, AudioCodecRegistry.findCodec(unknownFile))

        // Test registering an additional mock codec for future format extension (e.g. FLAC)
        class MockFlacCodec : AudioCodec {
            override val name: String = "FLAC"
            override val supportedExtensions: List<String> = listOf("flac")
            override fun createSession(file: File): AudioStreamSession = throw UnsupportedOperationException()
        }

        AudioCodecRegistry.register(MockFlacCodec())
        assertTrue(AudioCodecRegistry.getSupportedExtensions().contains("flac"))
        assertNotNull(AudioCodecRegistry.findCodec(File("test.flac")))
    }

    @Test
    fun testAudioBibleServiceIndexingAndReadingMatching() = runBlocking {
        val tempAppDir = Files.createTempDirectory("audio_service_app_dir").toFile()
        tempAppDir.deleteOnExit()

        val audioDir = Files.createTempDirectory("audio_bible_files").toFile()
        audioDir.deleteOnExit()

        // Create mock audio Bible files
        val gen1 = File(audioDir, "01_Genesis_01.mp3").apply { createNewFile() }

        val service = AudioBibleService(tempAppDir)
        service.setAudioDirectory(audioDir.absolutePath)
        service.rescan()

        assertEquals(3, service.totalAudioFilesFound)
        assertEquals(3, service.totalChaptersMatched)
        assertEquals(2, service.totalBooksMatched) // Genesis and Matthew

        // Match individual reading
        val readingGen1 = Reading("Genesis", 1)
        assertTrue(service.hasAudioForReading(readingGen1))
        val trackGen1 = service.findTrackForReading(readingGen1)
        assertNotNull(trackGen1)
        assertEquals(Bible.GENESIS, trackGen1.book)
        assertEquals(1, trackGen1.chapter)
        assertEquals(gen1.absolutePath, trackGen1.file.absolutePath)

        // Match daily reading
        val daily = DailyReading(
            date = LocalDate.of(2026, 1, 1),
            reading = listOf(
                Reading("Genesis", 1),
                Reading("Genesis", 2),
                Reading("Matthew", 1)
            )
        )

        assertTrue(service.hasAudioForDailyReading(daily))
        val dailyTracks = service.findTracksForDailyReading(daily)
        assertEquals(3, dailyTracks.size)
        assertEquals("Genesis 1", dailyTracks[0].title)
        assertEquals("Genesis 2", dailyTracks[1].title)
        assertEquals("Matthew 1", dailyTracks[2].title)
    }
}

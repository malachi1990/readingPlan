package org.bible.reading.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bible.reading.audio.model.AudioTrack
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.player.AudioCodec
import org.bible.reading.audio.player.AudioCodecRegistry
import org.bible.reading.audio.player.AudioStreamSession
import org.bible.reading.audio.player.MAX_PLAYBACK_SPEED
import org.bible.reading.audio.player.MIN_PLAYBACK_SPEED
import org.bible.reading.audio.player.Mp3AudioCodec
import org.bible.reading.audio.player.PcmTimeStretcher
import org.bible.reading.audio.player.WavAudioCodec
import org.bible.reading.audio.player.normalizePlaybackSpeed
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
import org.bible.reading.tts.TtsMp3Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
        assertEquals(1.0f, initial.playbackSpeed)

        val updated = AudioPreferences(
            audioDirectory = "/home/user/AudioBible",
            selectedStrategyId = "numbered_book_chapter",
            volume = 0.75f,
            playbackSpeed = 1.15f,
            autoPlayNext = true
        )
        manager.savePreferences(updated)

        val reloaded = manager.loadPreferences()
        assertEquals("/home/user/AudioBible", reloaded.audioDirectory)
        assertEquals("numbered_book_chapter", reloaded.selectedStrategyId)
        assertEquals(0.75f, reloaded.volume)
        assertEquals(1.15f, reloaded.playbackSpeed)
        assertTrue(reloaded.autoPlayNext)
    }

    @Test
    fun testPlaybackSpeedNormalizationAndStepping() {
        // Normalization & clamping
        assertEquals(MIN_PLAYBACK_SPEED, normalizePlaybackSpeed(0.10f))
        assertEquals(0.25f, normalizePlaybackSpeed(0.25f))
        assertEquals(0.95f, normalizePlaybackSpeed(0.95f))
        assertEquals(0.95f, normalizePlaybackSpeed(0.97f))
        assertEquals(1.00f, normalizePlaybackSpeed(0.98f))
        assertEquals(1.05f, normalizePlaybackSpeed(1.05f))
        assertEquals(1.10f, normalizePlaybackSpeed(1.10f))
        assertEquals(1.15f, normalizePlaybackSpeed(1.15f))
        assertEquals(MAX_PLAYBACK_SPEED, normalizePlaybackSpeed(2.50f))
        assertEquals(1.00f, normalizePlaybackSpeed(Float.NaN))

        // Stepping across entire 0.25x .. 2.00x range in 5% increments
        val player = AudioBiblePlayer(initialPlaybackSpeed = 0.25f)
        assertEquals(0.25f, player.playbackSpeed)

        val expectedSteps = (5..40).map { (it * 5) / 100f }
        val recordedUp = mutableListOf(player.playbackSpeed)
        repeat(40) {
            player.increasePlaybackSpeed()
            if (recordedUp.last() != player.playbackSpeed) {
                recordedUp.add(player.playbackSpeed)
            }
        }
        assertEquals(expectedSteps, recordedUp)
        assertEquals(2.00f, player.playbackSpeed)

        // Cannot go above 2.00x
        player.increasePlaybackSpeed()
        assertEquals(2.00f, player.playbackSpeed)

        // Step down cleanly from 1.15 -> 1.10 -> 1.05 -> 1.00 -> 0.95
        player.setPlaybackSpeed(1.15f)
        assertEquals(1.15f, player.playbackSpeed)
        player.decreasePlaybackSpeed()
        assertEquals(1.10f, player.playbackSpeed)
        player.decreasePlaybackSpeed()
        assertEquals(1.05f, player.playbackSpeed)
        player.decreasePlaybackSpeed()
        assertEquals(1.00f, player.playbackSpeed)
        player.decreasePlaybackSpeed()
        assertEquals(0.95f, player.playbackSpeed)

        // Reset returns to 1.00x
        player.resetPlaybackSpeed()
        assertEquals(1.00f, player.playbackSpeed)

        // Clamp down to 0.25x
        player.setPlaybackSpeed(0.25f)
        player.decreasePlaybackSpeed()
        assertEquals(0.25f, player.playbackSpeed)
    }

    @Test
    fun testAudioBibleServicePersistsPlaybackSpeed() = runBlocking {
        val tempAppDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("audio_speed_pref_dir")
        }.toFile()
        tempAppDir.deleteOnExit()

        val service = AudioBibleService(tempAppDir)
        assertEquals(1.00f, service.player.playbackSpeed)

        service.player.setPlaybackSpeed(1.35f)
        assertEquals(1.35f, service.preferences.playbackSpeed)

        // Recreate service to confirm persistence
        val reloadedService = AudioBibleService(tempAppDir)
        assertEquals(1.35f, reloadedService.preferences.playbackSpeed)
        assertEquals(1.35f, reloadedService.player.playbackSpeed)
    }

    @Test
    fun testPcmTimeStretcherSpeedScalingAndPitchPreservation() {
        val sampleRate = 22050
        val channels = 1
        val durationSec = 2
        val totalInputFrames = sampleRate * durationSec
        val freqHz = 440.0

        // Generate 2 seconds of 440Hz mono 16-bit PCM sine wave
        val inputPcm = ByteArray(totalInputFrames * 2)
        for (i in 0 until totalInputFrames) {
            val sample = (12000.0 * sin(2.0 * PI * freqHz * i / sampleRate)).roundToInt()
            inputPcm[i * 2] = (sample and 0xFF).toByte()
            inputPcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        // 1.00x should be lossless passthrough
        val passthroughStretcher = PcmTimeStretcher(sampleRate, channels)
        val passthroughOut = passthroughStretcher.process(inputPcm, inputPcm.size, 1.00f)
        assertContentEquals(inputPcm, passthroughOut)

        // Test key speeds across full range: 0.25x, 0.50x, 0.95x, 1.05x, 1.15x, 2.00x
        val testSpeeds = listOf(0.25f, 0.50f, 0.95f, 1.05f, 1.15f, 2.00f)
        for (speed in testSpeeds) {
            val stretcher = PcmTimeStretcher(sampleRate, channels)
            val baos = ByteArrayOutputStream()
            val chunkSize = 4096
            var off = 0
            while (off < inputPcm.size) {
                val len = minOf(chunkSize, inputPcm.size - off)
                val out = stretcher.process(inputPcm.copyOfRange(off, off + len), len, speed)
                if (out.isNotEmpty()) baos.write(out)
                off += len
            }
            val tail = stretcher.finish()
            if (tail.isNotEmpty()) baos.write(tail)

            val outBytes = baos.toByteArray()
            val outFrames = outBytes.size / 2
            val expectedFrames = (totalInputFrames / speed).toDouble()
            val ratio = outFrames / expectedFrames
            assertTrue(
                ratio in 0.90..1.10,
                "Speed $speed: expected ~$expectedFrames frames, got $outFrames (ratio=$ratio)"
            )

            // Verify pitch is preserved (~440Hz via zero-crossing rate in middle 1 second of output)
            val midStart = (outFrames / 4).coerceAtLeast(0)
            val midEnd = (midStart + sampleRate / 2).coerceAtMost(outFrames)
            var zeroCrossings = 0
            var prevSign = 0
            for (f in midStart until midEnd) {
                val lo = outBytes[f * 2].toInt() and 0xFF
                val hi = outBytes[f * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                val sign = if (s > 0) 1 else if (s < 0) -1 else 0
                if (prevSign != 0 && sign != 0 && sign != prevSign) {
                    zeroCrossings++
                }
                if (sign != 0) prevSign = sign
            }
            val measuredSec = (midEnd - midStart).toDouble() / sampleRate
            val estimatedFreq = zeroCrossings / (2.0 * measuredSec)
            assertTrue(
                abs(estimatedFreq - freqHz) < 35.0,
                "Speed $speed: pitch should stay near ${freqHz}Hz, measured ${estimatedFreq}Hz"
            )
        }

        // Dynamic speed transitions (1.00 -> 1.25 -> 0.50 -> 1.00) must not crash or drop stream
        val dynamicStretcher = PcmTimeStretcher(sampleRate, channels)
        val dynamicOut = ByteArrayOutputStream()
        val speeds = listOf(1.00f, 1.25f, 0.50f, 1.00f)
        val segSize = inputPcm.size / speeds.size
        for (idx in speeds.indices) {
            val start = idx * segSize
            val end = if (idx == speeds.lastIndex) inputPcm.size else (idx + 1) * segSize
            val alignedEnd = end - ((end - start) % 2)
            val chunk = inputPcm.copyOfRange(start, alignedEnd)
            val out = dynamicStretcher.process(chunk, chunk.size, speeds[idx])
            if (out.isNotEmpty()) dynamicOut.write(out)
        }
        val rem = dynamicStretcher.finish()
        if (rem.isNotEmpty()) dynamicOut.write(rem)
        assertTrue(dynamicOut.size() > inputPcm.size / 2)
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
        val tempAppDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("audio_service_app_dir")
        }.toFile()
        tempAppDir.deleteOnExit()

        val audioDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("audio_bible_files")
        }.toFile()
        audioDir.deleteOnExit()

        // Create mock audio Bible files
        val gen1 = File(audioDir, "01_Genesis_01.mp3").apply { createNewFile() }
        val gen2 = File(audioDir, "01_Genesis_02.mp3").apply { createNewFile() }
        val mat1 = File(audioDir, "40_Matthew_01.mp3").apply { createNewFile() }

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

    private fun createSyntheticWav(durationSeconds: Int = 2, sampleRate: Float = 44100f): File {
        val tempFile = File.createTempFile("test_audio_", ".wav")
        tempFile.deleteOnExit()
        val numFrames = (sampleRate * durationSeconds).toInt()
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        val pcmData = ByteArray(numFrames * 2)
        val ais = AudioInputStream(ByteArrayInputStream(pcmData), format, numFrames.toLong())
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tempFile)
        return tempFile
    }

    @Test
    fun testWavAudioCodecSeeking() {
        val wav = createSyntheticWav(durationSeconds = 2)
        val codec = WavAudioCodec()
        val session = codec.createSession(wav)
        assertTrue(session.durationMs >= 1900L, "Expected duration around 2000ms but got ${session.durationMs}")

        // Test setting speed on session directly
        session.setPlaybackSpeed(1.15f)
        session.setPlaybackSpeed(0.25f)
        session.setPlaybackSpeed(2.00f)

        // Test seeking before play
        session.seekTo(1000L)
        assertEquals(1000L, session.currentPositionMs)

        // Seek to another position
        session.seekTo(1500L)
        assertEquals(1500L, session.currentPositionMs)

        // Seek back
        session.seekTo(500L)
        assertEquals(500L, session.currentPositionMs)

        session.stop()
        session.release()
    }

    @Test
    fun testAudioBiblePlayerSeeking() = runBlocking {
        val wav = createSyntheticWav(durationSeconds = 3)
        val player = AudioBiblePlayer()
        val track = AudioTrack(
            file = wav,
            title = "Synthetic Track",
            durationMs = 3000L
        )

        player.playTrack(track)
        assertEquals(3000L, player.durationMs)

        // Seek to 1000ms
        player.seekTo(1000L)
        assertEquals(1000L, player.currentPositionMs)

        // Relative seek by +500ms
        player.seekBy(500L)
        assertEquals(1500L, player.currentPositionMs)

        // seekForward by 500ms
        player.seekForward(500L)
        assertEquals(2000L, player.currentPositionMs)

        // seekBackward by 800ms
        player.seekBackward(800L)
        assertEquals(1200L, player.currentPositionMs)

        // Adjust speed during active track
        player.setPlaybackSpeed(1.15f)
        assertEquals(1.15f, player.playbackSpeed)
        player.increasePlaybackSpeed()
        assertEquals(1.20f, player.playbackSpeed)

        // Out of bounds seeking (negative clamped to 0)
        player.seekTo(-500L)
        assertEquals(0L, player.currentPositionMs)

        // Out of bounds seeking (past duration clamped to durationMs)
        player.seekTo(5000L)
        assertEquals(3000L, player.currentPositionMs)

        player.stop()
    }

    @Test
    fun testMp3AudioCodecSeeking() {
        val tempWav = createSyntheticWav(durationSeconds = 3)
        val tempMp3 = File.createTempFile("test_sine_", ".mp3").apply { deleteOnExit() }
        try {
            assertTrue(TtsMp3Encoder.encodeWavToMp3(tempWav, tempMp3).isSuccess)
            assertTrue(tempMp3.exists() && tempMp3.length() > 0L)

            val codec = Mp3AudioCodec()
            val session = codec.createSession(tempMp3)
            try {
                assertTrue(session.durationMs > 0, "Duration should be estimated for MP3")

                session.setPlaybackSpeed(1.05f)
                session.setPlaybackSpeed(2.00f)

                // Seek before play
                session.seekTo(1000L)
                assertEquals(1000L, session.currentPositionMs)

                session.seekTo(2000L)
                assertEquals(2000L, session.currentPositionMs)

                session.seekTo(500L)
                assertEquals(500L, session.currentPositionMs)
            } finally {
                session.stop()
                session.release()
            }
        } finally {
            tempWav.delete()
            tempMp3.delete()
        }
    }

    @Test
    fun testMp3RewindDuringQueuedPlaybackDoesNotAdvanceToNextTrack() = runBlocking {
        // Check if an audio output line is available in this test environment
        val testFormat = AudioFormat(44100f, 16, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, testFormat)
        val lineAvailable = try {
            if (!AudioSystem.isLineSupported(info)) {
                false
            } else {
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(testFormat, 8192)
                line.close()
                true
            }
        } catch (_: Exception) {
            false
        }
        if (!lineAvailable) {
            return@runBlocking
        }

        val wav1 = createSyntheticWav(durationSeconds = 4)
        val wav2 = createSyntheticWav(durationSeconds = 4)
        val mp31 = File.createTempFile("test_rewind_1_", ".mp3").apply { deleteOnExit() }
        val mp32 = File.createTempFile("test_rewind_2_", ".mp3").apply { deleteOnExit() }

        assertTrue(TtsMp3Encoder.encodeWavToMp3(wav1, mp31).isSuccess)
        assertTrue(TtsMp3Encoder.encodeWavToMp3(wav2, mp32).isSuccess)

        val player = AudioBiblePlayer(initialVolume = 0f, scope = this)
        try {
            val track1 = AudioTrack(file = mp31, title = "Track 1", durationMs = 4000L)
            val track2 = AudioTrack(file = mp32, title = "Track 2", durationMs = 4000L)

            player.playTracks(listOf(track1, track2))

            // Wait until playback has decoded past several frames
            var waited = 0
            while (player.currentPositionMs < 250L && waited < 3000) {
                delay(50.milliseconds)
                waited += 50
            }
            assertTrue(player.isPlaying, "Track 1 should be playing")
            assertEquals(0, player.currentTrackIndex)

            // Rewind to beginning via seekBackward
            player.seekBackward(10_000L)
            delay(500.milliseconds)

            assertNull(player.errorMessage, "Unexpected playback error after rewind: ${player.errorMessage}")
            assertEquals(0, player.currentTrackIndex, "Rewinding should stay on Track 1, not skip to next track")
            assertEquals("Track 1", player.currentTrack?.title)
            assertTrue(player.isPlaying, "Track 1 should remain playing after rewind")

            // Seek forward, then rewind partway back at 1.25x speed
            player.setPlaybackSpeed(1.25f)
            player.seekTo(1500L)
            delay(300.milliseconds)
            player.seekBackward(800L)
            delay(400.milliseconds)

            assertNull(player.errorMessage, "Unexpected playback error after partial rewind: ${player.errorMessage}")
            assertEquals(0, player.currentTrackIndex, "Partial rewind should stay on Track 1")
            assertEquals("Track 1", player.currentTrack?.title)
            assertTrue(player.isPlaying, "Track 1 should remain playing after partial rewind")

            // Rewind while paused and then resume
            player.pause()
            delay(150.milliseconds)
            player.seekBackward(500L)
            delay(200.milliseconds)
            player.resume()
            delay(300.milliseconds)

            assertNull(player.errorMessage, "Unexpected playback error after paused rewind: ${player.errorMessage}")
            assertEquals(0, player.currentTrackIndex, "Paused rewind should stay on Track 1")
            assertEquals("Track 1", player.currentTrack?.title)
            assertTrue(player.isPlaying, "Track 1 should remain playing after paused rewind")
        } finally {
            player.release()
        }
    }
}

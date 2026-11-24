package org.bible.reading.tts

import javazoom.jl.decoder.Bitstream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bible.reading.audio.model.AudioTrack
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.player.AudioCodecRegistry
import org.bible.reading.tts.ui.TtsFileUtils
import org.bible.reading.ui.PlanState
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class TextToSpeechIntegrationTest {

    @Test
    fun testTextCleanerRemovesStrongsAndTags() {
        val rawSwordText = """
            <w lemma="strong:H7225" morph="oshm:HR/Ncfsa">In the beginning</w> 
            <w lemma="strong:H0430" morph="oshm:HNcmpa">God</w> 
            <w lemma="strong:H1254" morph="oshm:HVqp3ms">created</w> 
            [1] the heaven and the earth. {Heb. bara}
        """.trimIndent()

        val cleaned = TtsTextCleaner.cleanForSpeech(rawSwordText)

        assertFalse(cleaned.contains("strong"), "Should not contain 'strong'")
        assertFalse(cleaned.contains("H7225"), "Should not contain Strong's number H7225")
        assertFalse(cleaned.contains("<w"), "Should not contain opening XML tag")
        assertFalse(cleaned.contains("</w>"), "Should not contain closing XML tag")
        assertFalse(cleaned.contains("[1]"), "Should not contain footnote [1]")
        assertFalse(cleaned.contains("{Heb. bara}"), "Should not contain bracketed annotation")

        assertTrue(cleaned.contains("In the beginning God created the heaven and the earth"))
    }

    @Test
    fun testFormatPassageForSpeech() {
        val formatted = TtsTextCleaner.formatPassageForSpeech("Genesis", 1, "In the beginning God created the heaven and the earth.")
        assertTrue(formatted.startsWith("Genesis, chapter 1."))
        assertTrue(formatted.contains("In the beginning God created the heaven and the earth."))
    }

    @Test
    fun testFormatVerseForSpeech() {
        val formatted = TtsTextCleaner.formatVerseForSpeech("John 3:16", "For God so loved the world...")
        assertTrue(formatted.startsWith("John 3:16."))
        assertTrue(formatted.contains("For God so loved the world"))
    }

    @Test
    fun testFormatSectionForSpeech() {
        val formatted = TtsTextCleaner.formatSectionForSpeech(
            title = "Chapter I - The City of Destruction",
            subtitle = "The Pilgrim's Progress",
            text = "As I walked through the wilderness of this world..."
        )
        assertTrue(formatted.contains("Chapter I - The City of Destruction"))
        assertTrue(formatted.contains("The Pilgrim's Progress"))
        assertTrue(formatted.contains("As I walked through the wilderness"))
    }

    @Test
    fun testSplitIntoParagraphsAndFormatParagraphForSpeech() {
        val content = "\n\n\tFirst indented paragraph.\nSecond line of first paragraph.\n\n   \nSecond paragraph [1] with — pause.\r\n\r\n   \r\n\t* List item A\n\t* List item B\n\n"
        val paragraphs = TtsTextCleaner.splitIntoParagraphs(content)

        assertEquals(3, paragraphs.size)
        assertEquals("\tFirst indented paragraph.\nSecond line of first paragraph.", paragraphs[0])
        assertEquals("Second paragraph [1] with — pause.", paragraphs[1])
        assertEquals("\t* List item A\n\t* List item B", paragraphs[2])

        val speech1 = TtsTextCleaner.formatParagraphForSpeech(paragraphs[1])
        assertEquals("Second paragraph with , pause.", speech1)
        assertTrue(TtsTextCleaner.splitIntoParagraphs("   \n\n  \t ").isEmpty())
    }

    @Test
    fun testParagraphTrackTitleAndTtsServiceActions() = runBlocking {
        val tempDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("tts_paragraph_test")
        }.toFile()
        try {
            val service = TtsService(tempDir)
            val player = AudioBiblePlayer()

            assertEquals(
                "Chapter I • ¶1 • Pilgrim's Progress",
                service.buildParagraphTrackTitle("Chapter I", "Pilgrim's Progress", 0)
            )
            assertEquals(
                "Section • ¶3",
                service.buildParagraphTrackTitle("", "", 2)
            )

            // Non-speakable paragraph updates status without synthesizing
            service.readParagraph(
                sectionTitle = "Chapter I",
                subtitle = "Book",
                paragraphIndex = 1,
                paragraphText = "   ***   ",
                player = player
            )
            assertEquals("No speakable text available in paragraph 2.", service.statusMessage)

            // Queuing when idle triggers read/synthesis status
            service.queueParagraph(
                sectionTitle = "Chapter I",
                subtitle = "Book",
                paragraphIndex = 0,
                paragraphText = "As I walked through the wilderness of this world.",
                player = player
            )
            delay(100.milliseconds)
            assertTrue(service.isSynthesizing || service.statusMessage != null || player.currentTrack != null)
            service.stopReading(player)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVoiceCatalog() {
        val piperVoices = TtsVoiceCatalog.PIPER_VOICES
        assertTrue(piperVoices.isNotEmpty())

        val lessac = TtsVoiceCatalog.findVoice("en_US-lessac-medium")
        assertEquals(TtsEngineType.PIPER, lessac.engineType)
        assertTrue(lessac.downloadUrl.contains("huggingface.co"))
        assertTrue(lessac.modelFileName.endsWith(".onnx"))
        assertTrue(lessac.configFileName.endsWith(".json"))

        val amy = TtsVoiceCatalog.findVoice("en_US-amy-medium")
        assertEquals("Female", amy.gender)

        val kokoroVoices = TtsVoiceCatalog.KOKORO_VOICES
        assertTrue(kokoroVoices.isNotEmpty())
        assertEquals(TtsEngineType.KOKORO, kokoroVoices.first().engineType)

        val systemVoice = TtsVoiceCatalog.SYSTEM_VOICE
        assertEquals(TtsEngineType.SYSTEM, systemVoice.engineType)
    }

    @Test
    fun testPreferencesSaveAndLoad() {
        val tempDir = Files.createTempDirectory("tts_pref_test").toFile()
        try {
            val prefMgr = TtsPreferencesManager(tempDir)
            val defaultPrefs = prefMgr.loadPreferences()
            assertEquals(TtsEngineType.PIPER, defaultPrefs.selectedEngine)
            assertEquals("en_US-lessac-medium", defaultPrefs.selectedVoiceId)
            assertEquals("", defaultPrefs.kokoroPythonPath)
            assertEquals("", defaultPrefs.kokoroBinaryPath)
            assertEquals("", defaultPrefs.kokoroVoicesPath)
            assertFalse(defaultPrefs.kokoroUseCuda)

            val updated = TtsPreferences(
                selectedEngine = TtsEngineType.KOKORO,
                selectedVoiceId = "af_bella",
                speechRate = 1.2f,
                sentenceSilence = 0.3f,
                autoDownloadVoice = false,
                kokoroPythonPath = "/usr/bin/python3",
                kokoroBinaryPath = "/models/kokoro.onnx",
                kokoroVoicesPath = "/models/voices.bin",
                kokoroUseCuda = true
            )
            prefMgr.savePreferences(updated)

            val reloaded = prefMgr.loadPreferences()
            assertEquals(TtsEngineType.KOKORO, reloaded.selectedEngine)
            assertEquals("af_bella", reloaded.selectedVoiceId)
            assertEquals(1.2f, reloaded.speechRate, 0.01f)
            assertEquals(0.3f, reloaded.sentenceSilence, 0.01f)
            assertFalse(reloaded.autoDownloadVoice)
            assertEquals("/usr/bin/python3", reloaded.kokoroPythonPath)
            assertEquals("/models/kokoro.onnx", reloaded.kokoroBinaryPath)
            assertEquals("/models/voices.bin", reloaded.kokoroVoicesPath)
            assertTrue(reloaded.kokoroUseCuda)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testKokoroPathResolutionAndCustomConfiguration() {
        val tempDir = Files.createTempDirectory("kokoro_path_test").toFile()
        try {
            val kokoroMgr = KokoroManager(tempDir)

            // Test custom python path resolution
            val fakePython = File(tempDir, "custom_python3").apply { writeText("#!/bin/sh") }
            val resolvedPy = kokoroMgr.resolvePythonPath(fakePython.absolutePath)
            assertEquals(fakePython.absolutePath, resolvedPy)

            // Test default fallback
            val defaultPy = kokoroMgr.resolvePythonPath("")
            assertTrue(defaultPy.isNotBlank())

            // Test custom model path resolution
            val fakeModel = File(tempDir, "custom_kokoro.onnx").apply { writeBytes(ByteArray(10)) }
            val resolvedModel = kokoroMgr.resolveModelFile(fakeModel.absolutePath)
            assertEquals(fakeModel.absolutePath, resolvedModel.absolutePath)

            // Test custom voices path resolution
            val fakeVoices = File(tempDir, "custom_voices.bin").apply { writeBytes(ByteArray(10)) }
            val resolvedVoices = kokoroMgr.resolveVoicesFile(fakeVoices.absolutePath)
            assertEquals(fakeVoices.absolutePath, resolvedVoices.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testAudioTrackGeneralSupport() {
        val tempFile = File.createTempFile("tts_track_test", ".mp3").apply { deleteOnExit() }
        tempFile.writeBytes(ByteArray(100))

        val track = AudioTrack(
            file = tempFile,
            title = "Matthew Henry Commentary - Gen 1:1",
            subtitle = "Commentary",
            durationMs = 54000L
        )

        assertEquals("Matthew Henry Commentary - Gen 1:1", track.title)
        assertEquals("Commentary", track.subtitle)
        assertEquals(54000L, track.durationMs)
        assertEquals(tempFile, track.file)
        assertNull(track.book)
    }

    @Test
    fun testAudioBiblePlayerTitleTrackingAndQueue() {
        val player = AudioBiblePlayer()
        val tempFile1 = File.createTempFile("test_player1", ".mp3").apply { deleteOnExit() }
        val tempFile2 = File.createTempFile("test_player2", ".mp3").apply { deleteOnExit() }
        tempFile1.writeBytes(ByteArray(100))
        tempFile2.writeBytes(ByteArray(100))

        val track1 = AudioTrack(file = tempFile1, title = "John 3:16 (KJV)")
        val track2 = AudioTrack(file = tempFile2, title = "John 3:17 (KJV)")

        assertFalse(player.isTrackActive("John 3:16 (KJV)"))
        assertFalse(player.isTrackQueued("John 3:16 (KJV)"))

        // Enqueue first track
        player.enqueueTrack(track1)
        assertEquals("John 3:16 (KJV)", player.currentTrack?.title)
        assertTrue(player.isTrackQueued("John 3:16 (KJV)"))

        // Enqueue second track to playlist queue
        player.enqueueTrack(track2)
        assertEquals(2, player.playlist.size)
        assertTrue(player.isTrackQueued("John 3:17 (KJV)"))
        assertEquals("John 3:16 (KJV)", player.currentTrack?.title)

        // Remove track
        player.removeTrack(1)
        assertEquals(1, player.playlist.size)
        assertFalse(player.isTrackQueued("John 3:17 (KJV)"))

        player.stop()
        assertFalse(player.isTrackActive("John 3:16 (KJV)"))
    }

    @Test
    fun testSaveGeneratedAudioToFile() = runBlocking {
        val tempDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("tts_save_test")
        }.toFile()
        try {
            val service = TtsService(tempDir)
            val sourceMp3 = File(tempDir, "source.mp3").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
            }
            val destinationMp3 = File(tempDir, "saved/output_narration.mp3")

            val result = service.saveAudioToFile(sourceMp3, destinationMp3)
            assertTrue(result.isSuccess)
            assertTrue(destinationMp3.exists())
            assertEquals(sourceMp3.length(), destinationMp3.length())

            val track = AudioTrack(file = sourceMp3, title = "Genesis 1:1")
            val trackDest = File(tempDir, "saved/track_exported.mp3")
            val trackRes = service.saveTrackToFile(track, trackDest)
            assertTrue(trackRes.isSuccess)
            assertTrue(trackDest.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTtsMp3EncoderProduces128kbpsMp3ForPiperAndKokoroSampleRates() {
        val tempDir = Files.createTempDirectory("tts_mp3_encode_test").toFile()
        try {
            val service = TtsService(tempDir)
            listOf(22050f, 24000f).forEach { sampleRate ->
                val wavFile = File(tempDir, "sine_${sampleRate.toInt()}.wav")
                val mp3File = File(tempDir, "sine_${sampleRate.toInt()}.mp3")
                writeSineWav(wavFile, sampleRate = sampleRate, durationSeconds = 1.0)

                val encoded = TtsMp3Encoder.encodeWavToMp3(wavFile, mp3File, TtsMp3Encoder.DEFAULT_BITRATE_KBPS)
                assertTrue(
                    encoded.isSuccess,
                    "Encoding WAV at ${sampleRate}Hz should succeed: ${encoded.exceptionOrNull()?.message}"
                )
                assertTrue(mp3File.exists() && mp3File.length() > 1024, "Encoded MP3 should be non-empty")

                // Verify MPEG frame bitrate is 128000 bps (128 kbps)
                val bitrateBps = readFirstMp3FrameBitrate(mp3File)
                assertEquals(128_000, bitrateBps, "Encoded MP3 at ${sampleRate}Hz must be 128 kbps")

                // Verify TtsService computes positive MP3 duration
                val durationMs = service.computeDurationMs(mp3File)
                assertTrue(durationMs in 800L..1300L, "Expected ~1000ms MP3 duration, got ${durationMs}ms")

                // Verify AudioCodecRegistry selects MP3 codec and creates a valid session
                val codec = AudioCodecRegistry.findCodec(mp3File)
                assertNotNull(codec, "AudioCodecRegistry should handle synthesized MP3 file")
                assertEquals("MP3", codec.name)
                val session = codec.createSession(mp3File)
                try {
                    assertTrue(session.durationMs > 0L, "Decoded MP3 session should have positive duration")
                } finally {
                    session.release()
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTtsFileUtilsNormalizesToMp3() {
        assertEquals("John_3.mp3", TtsFileUtils.normalizeSaveAudioFilename("John_3"))
        assertEquals("John_3.mp3", TtsFileUtils.normalizeSaveAudioFilename("John_3.wav"))
        assertEquals("John_3.mp3", TtsFileUtils.normalizeSaveAudioFilename("John_3.WAV"))
        assertEquals("John_3.mp3", TtsFileUtils.normalizeSaveAudioFilename("John_3.mp3"))
        assertEquals("John_3.MP3", TtsFileUtils.normalizeSaveAudioFilename("John_3.MP3"))
        assertEquals("tts_audio.mp3", TtsFileUtils.normalizeSaveAudioFilename(""))
        assertEquals("tts_audio.mp3", TtsFileUtils.normalizeSaveAudioFilename("   "))
    }

    @Test
    fun testSectionQueuingInTtsService() = runBlocking {
        val tempDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("tts_queue_test")
        }.toFile()
        try {
            val service = TtsService(tempDir)
            val player = AudioBiblePlayer()

            // Test queuing when idle
            service.queueSection(
                title = "Section I: Introduction",
                subtitle = "Study Guide",
                content = "This is the content of section one.",
                player = player
            )

            delay(100.milliseconds)
            assertTrue(service.isSynthesizing || service.statusMessage != null || player.currentTrack != null)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPlanStateIncludesTtsService() {
        val state = PlanState()
        assertNotNull(state.ttsService)
        assertNotNull(state.audioService.player)
        assertFalse(state.showTtsSettingsDialog)
        state.showTtsSettingsDialog = true
        assertTrue(state.showTtsSettingsDialog)
    }

    @Test
    fun testKokoroCUDAandNonCUDASynthesisIfAvailable() = runBlocking {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".bibleReadingPlan")
        val service = TtsService(appDir)

        val kokoroVoice = TtsVoiceCatalog.KOKORO_VOICES.first()
        if (service.kokoroManager.isAvailable()) {
            val sampleText = "In the beginning was the Word."

            // 1. Non-CUDA synthesis (existing script logic)
            service.setKokoroUseCuda(false)
            val nonCudaOut = File(service.cacheDir, "test_non_cuda.wav")
            val nonCudaRes = service.kokoroManager.synthesize(
                text = sampleText,
                outputWav = nonCudaOut,
                voice = kokoroVoice,
                useCuda = false
            )
            assertTrue(nonCudaRes.isSuccess, "Non-CUDA synthesis should succeed: ${nonCudaRes.exceptionOrNull()?.message}")
            assertTrue(nonCudaOut.exists() && nonCudaOut.length() > 1024)

            // 2. CUDA synthesis
            service.setKokoroUseCuda(true)
            val cudaOut = File(service.cacheDir, "test_cuda.wav")
            val cudaRes = service.kokoroManager.synthesize(
                text = sampleText,
                outputWav = cudaOut,
                voice = kokoroVoice,
                useCuda = true
            )
            assertTrue(cudaRes.isSuccess, "CUDA synthesis should succeed or fallback gracefully: ${cudaRes.exceptionOrNull()?.message}")
            assertTrue(cudaOut.exists() && cudaOut.length() > 1024)

            nonCudaOut.delete()
            cudaOut.delete()
        }
    }

    @Test
    fun testPiperSynthesisAndCachingIfInstalled() = runBlocking {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".bibleReadingPlan")
        val service = TtsService(appDir)

        val voice = service.getActiveVoice()
        if (service.isVoiceReady(voice)) {
            val testSentence = "Let there be light: and there was light."
            val trackTitle = "Test Genesis 1:3"

            // 1. First synthesis
            val result = service.synthesizeAudio(testSentence, trackTitle)
            assertTrue(result.isSuccess, "Synthesis should succeed: ${result.exceptionOrNull()?.message}")
            val audioFile = result.getOrThrow()
            assertTrue(audioFile.exists())
            assertEquals("mp3", audioFile.extension.lowercase(), "Synthesized audio file should be .mp3")
            assertTrue(audioFile.length() > 1024, "Audio file should be non-empty MP3")

            // Verify 128kbps MP3 header and computed duration
            assertEquals(128_000, readFirstMp3FrameBitrate(audioFile))
            assertTrue(service.computeDurationMs(audioFile) > 0L, "MP3 duration should be positive")

            // 2. Second synthesis should be instantaneous cache hit
            val startMs = System.currentTimeMillis()
            val cachedResult = service.synthesizeAudio(testSentence, trackTitle)
            val elapsed = System.currentTimeMillis() - startMs
            assertTrue(cachedResult.isSuccess)
            assertEquals(audioFile.absolutePath, cachedResult.getOrThrow().absolutePath)
            assertTrue(elapsed < 100, "Cached retrieval should take less than 100ms, took ${elapsed}ms")

            // 3. AudioCodecRegistry can decode this MP3 audio file
            val codec = AudioCodecRegistry.findCodec(audioFile)
            assertNotNull(codec, "AudioCodecRegistry should handle synthesized MP3 file")
            assertEquals("MP3", codec.name)
        }
    }

    @Test
    fun testSwordPassageSynthesisAndPlayerIntegration() = runBlocking {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".bibleReadingPlan")
        val service = TtsService(appDir)
        val player = AudioBiblePlayer()

        val voice = service.getActiveVoice()
        if (service.isVoiceReady(voice)) {
            val passageText = "In the beginning was the Word, and the Word was with God, and the Word was God."
            val cleanSpeech = TtsTextCleaner.formatPassageForSpeech("John", 1, passageText)

            val trackTitle = "John 1 (KJV)"
            val result = service.synthesizeAudio(cleanSpeech, trackTitle)
            assertTrue(result.isSuccess)

            val mp3File = result.getOrThrow()
            assertEquals("mp3", mp3File.extension.lowercase())
            val track = AudioTrack(file = mp3File, title = trackTitle)

            player.playTrack(track)
            assertEquals(trackTitle, player.currentTrack?.title)
            assertTrue(player.isPlaying)
            assertTrue(player.isTrackActive(trackTitle))

            // Pause & Resume
            player.pause()
            assertTrue(player.isPaused)
            player.resume()
            assertTrue(player.isPlaying)

            assertNotNull(player.currentTrack, "currentTrack should be populated while playing so AudioPlayerBar displays")

            player.stop()
            assertFalse(player.isPlaying)
            assertNull(player.currentTrack, "currentTrack should be null when stopped to dismiss AudioPlayerBar")
        }
    }

    private fun readFirstMp3FrameBitrate(mp3File: File): Int {
        FileInputStream(mp3File).use { fis ->
            BufferedInputStream(fis).use { bis ->
                val bitstream = Bitstream(bis)
                try {
                    val header = bitstream.readFrame()
                    assertNotNull(header, "Expected valid MPEG audio frame header in ${mp3File.name}")
                    return header.bitrate()
                } finally {
                    bitstream.close()
                }
            }
        }
    }

    private fun writeSineWav(outputFile: File, sampleRate: Float, durationSeconds: Double) {
        val numFrames = (sampleRate * durationSeconds).toInt()
        val pcmBytes = ByteArray(numFrames * 2)
        val freqHz = 440.0
        for (i in 0 until numFrames) {
            val sample = (sin(2.0 * PI * freqHz * i / sampleRate) * 16000.0).toInt().toShort()
            pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val format = AudioFormat(sampleRate, 16, 1, true, false)
        ByteArrayInputStream(pcmBytes).use { bais ->
            AudioInputStream(bais, format, numFrames.toLong()).use { ais ->
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile)
            }
        }
    }
}

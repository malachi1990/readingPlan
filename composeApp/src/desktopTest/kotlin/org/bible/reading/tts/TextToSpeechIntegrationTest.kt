package org.bible.reading.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bible.reading.audio.model.AudioTrack
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.player.AudioCodecRegistry
import org.bible.reading.ui.PlanState
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
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
        val tempFile = File.createTempFile("tts_track_test", ".wav").apply { deleteOnExit() }
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
        val tempFile1 = File.createTempFile("test_player1", ".wav").apply { deleteOnExit() }
        val tempFile2 = File.createTempFile("test_player2", ".wav").apply { deleteOnExit() }
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
            val sourceWav = File(tempDir, "source.wav").apply {
                writeText("RIFF....WAVEfmt ....data....")
            }
            val destinationWav = File(tempDir, "saved/output_narration.wav")

            val result = service.saveAudioToFile(sourceWav, destinationWav)
            assertTrue(result.isSuccess)
            assertTrue(destinationWav.exists())
            assertEquals(sourceWav.length(), destinationWav.length())

            val track = AudioTrack(file = sourceWav, title = "Genesis 1:1")
            val trackDest = File(tempDir, "saved/track_exported.wav")
            val trackRes = service.saveTrackToFile(track, trackDest)
            assertTrue(trackRes.isSuccess)
            assertTrue(trackDest.exists())
        } finally {
            tempDir.deleteRecursively()
        }
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
            assertTrue(audioFile.length() > 1024, "Audio file should be non-empty WAV")

            // Verify WAV header
            withContext(Dispatchers.IO) {
                AudioSystem.getAudioInputStream(audioFile)
            }.use { ais ->
                val format = ais.format
                assertEquals("PCM_SIGNED", format.encoding.toString())
                assertTrue(ais.frameLength > 0, "WAV frame count should be positive")
            }

            // 2. Second synthesis should be instantaneous cache hit
            val startMs = System.currentTimeMillis()
            val cachedResult = service.synthesizeAudio(testSentence, trackTitle)
            val elapsed = System.currentTimeMillis() - startMs
            assertTrue(cachedResult.isSuccess)
            assertEquals(audioFile.absolutePath, cachedResult.getOrThrow().absolutePath)
            assertTrue(elapsed < 100, "Cached retrieval should take less than 100ms, took ${elapsed}ms")

            // 3. AudioCodecRegistry can decode this audio file
            val codec = AudioCodecRegistry.findCodec(audioFile)
            assertNotNull(codec, "AudioCodecRegistry should handle synthesized WAV file")
            assertEquals("WAV", codec.name)
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

            val wavFile = result.getOrThrow()
            val track = AudioTrack(file = wavFile, title = trackTitle)

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
}

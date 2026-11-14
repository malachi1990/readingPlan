package org.bible.reading.tts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

class TtsPreferencesManager(appDir: File) {

    private val ttsDir = File(appDir, "tts").apply { mkdirs() }
    private val prefsFile = File(ttsDir, "tts_preferences.properties")

    fun loadPreferences(): TtsPreferences {
        if (!prefsFile.exists()) {
            return TtsPreferences()
        }

        val props = Properties()
        try {
            FileInputStream(prefsFile).use { props.load(it) }
        } catch (_: Exception) {
            return TtsPreferences()
        }

        val engineStr = props.getProperty("selectedEngine", TtsEngineType.PIPER.name)
        val engine = try {
            TtsEngineType.valueOf(engineStr)
        } catch (_: Exception) {
            TtsEngineType.PIPER
        }

        val voiceId = props.getProperty("selectedVoiceId", "en_US-lessac-medium")
        val rate = props.getProperty("speechRate", "1.0").toFloatOrNull() ?: 1.0f
        val silence = props.getProperty("sentenceSilence", "0.2").toFloatOrNull() ?: 0.2f
        val autoDl = props.getProperty("autoDownloadVoice", "true").toBooleanStrictOrNull() ?: true

        return TtsPreferences(
            selectedEngine = engine,
            selectedVoiceId = voiceId,
            speechRate = rate,
            sentenceSilence = silence,
            autoDownloadVoice = autoDl
        )
    }

    fun savePreferences(prefs: TtsPreferences) {
        val props = Properties()
        props.setProperty("selectedEngine", prefs.selectedEngine.name)
        props.setProperty("selectedVoiceId", prefs.selectedVoiceId)
        props.setProperty("speechRate", prefs.speechRate.toString())
        props.setProperty("sentenceSilence", prefs.sentenceSilence.toString())
        props.setProperty("autoDownloadVoice", prefs.autoDownloadVoice.toString())

        try {
            FileOutputStream(prefsFile).use { props.store(it, "Bible Reading Plan TTS Settings") }
        } catch (_: Exception) {}
    }
}

package org.bible.reading.audio.preferences

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

data class AudioPreferences(
    var audioDirectory: String = "",
    var selectedStrategyId: String = "auto_detect",
    var customRegex: String = """(?i)^([1-3]?\s*[a-zA-Z]+)[_\s-]+(\d+)""",
    var customBookGroup: Int = 1,
    var customChapterGroup: Int = 2,
    var volume: Float = 0.85f,
    var autoPlayNext: Boolean = true
)

/**
 * Manages persisting and loading audio Bible preferences to ~/.bibleReadingPlan/audio_preferences.properties.
 */
class AudioPreferencesManager(private val appDir: File) {

    private val prefFile = File(appDir, "audio_preferences.properties")

    fun loadPreferences(): AudioPreferences {
        val prefs = AudioPreferences()

        if (prefFile.exists()) {
            try {
                val props = Properties()
                FileInputStream(prefFile).use { props.load(it) }

                prefs.audioDirectory = props.getProperty("audioDirectory", "")
                prefs.selectedStrategyId = props.getProperty("selectedStrategyId", "auto_detect")
                prefs.customRegex = props.getProperty("customRegex", prefs.customRegex)
                prefs.customBookGroup = props.getProperty("customBookGroup", "1").toIntOrNull() ?: 1
                prefs.customChapterGroup = props.getProperty("customChapterGroup", "2").toIntOrNull() ?: 2
                prefs.volume = props.getProperty("volume", "0.85").toFloatOrNull() ?: 0.85f
                prefs.autoPlayNext = props.getProperty("autoPlayNext", "true").toBooleanStrictOrNull() ?: true
            } catch (e: Exception) {
                println("Failed to read audio_preferences.properties: ${e.message}")
            }
        }

        return prefs
    }

    fun savePreferences(prefs: AudioPreferences) {
        try {
            if (!appDir.exists()) appDir.mkdirs()
            val props = Properties().apply {
                setProperty("audioDirectory", prefs.audioDirectory)
                setProperty("selectedStrategyId", prefs.selectedStrategyId)
                setProperty("customRegex", prefs.customRegex)
                setProperty("customBookGroup", prefs.customBookGroup.toString())
                setProperty("customChapterGroup", prefs.customChapterGroup.toString())
                setProperty("volume", prefs.volume.toString())
                setProperty("autoPlayNext", prefs.autoPlayNext.toString())
            }
            FileOutputStream(prefFile).use { props.store(it, "Bible Reading Plan - Audio Preferences") }
        } catch (e: Exception) {
            println("Failed to write audio_preferences.properties: ${e.message}")
        }
    }
}

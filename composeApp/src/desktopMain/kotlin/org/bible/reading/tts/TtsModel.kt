package org.bible.reading.tts

enum class TtsEngineType(
    val id: String,
    val displayName: String,
    val description: String
) {
    PIPER(
        id = "piper",
        displayName = "Piper TTS (Neural)",
        description = "High-quality, fast local neural text-to-speech. Self-contained and requires no background services."
    ),
    KOKORO(
        id = "kokoro",
        displayName = "Kokoro-82M TTS",
        description = "Modern 82M open-weight text-to-speech model known for expressive and human-like natural delivery."
    ),
    SYSTEM(
        id = "system",
        displayName = "System Speech (Fallback)",
        description = "Built-in operating system speech engine (e.g., spd-say on Linux, say on macOS, SAPI on Windows)."
    )
}

data class TtsVoice(
    val id: String,
    val name: String,
    val displayName: String,
    val language: String,
    val gender: String,
    val engineType: TtsEngineType,
    val quality: String = "medium",
    val modelFileName: String = "",
    val configFileName: String = "",
    val downloadUrl: String = "",
    val configDownloadUrl: String = "",
    val description: String = "",
    val sizeMb: Double = 60.0
)

object TtsVoiceCatalog {
    val PIPER_VOICES = listOf(
        TtsVoice(
            id = "en_US-lessac-medium",
            name = "Lessac (US)",
            displayName = "Lessac • US English (Natural Narrator)",
            language = "en-US",
            gender = "Neutral/Female",
            engineType = TtsEngineType.PIPER,
            quality = "medium",
            modelFileName = "en_US-lessac-medium.onnx",
            configFileName = "en_US-lessac-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
            description = "Clear, articulate, and calm narration. Highly recommended for scripture and commentary.",
            sizeMb = 61.0
        ),
        TtsVoice(
            id = "en_US-amy-medium",
            name = "Amy (US)",
            displayName = "Amy • US English (Warm Female)",
            language = "en-US",
            gender = "Female",
            engineType = TtsEngineType.PIPER,
            quality = "medium",
            modelFileName = "en_US-amy-medium.onnx",
            configFileName = "en_US-amy-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json",
            description = "Natural, pleasant, and expressive female voice.",
            sizeMb = 61.0
        ),
        TtsVoice(
            id = "en_US-ryan-medium",
            name = "Ryan (US)",
            displayName = "Ryan • US English (Clear Male)",
            language = "en-US",
            gender = "Male",
            engineType = TtsEngineType.PIPER,
            quality = "medium",
            modelFileName = "en_US-ryan-medium.onnx",
            configFileName = "en_US-ryan-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx.json",
            description = "Confident and articulate male narrator voice.",
            sizeMb = 61.0
        ),
        TtsVoice(
            id = "en_GB-alan-medium",
            name = "Alan (UK)",
            displayName = "Alan • British English (Classic Male)",
            language = "en-GB",
            gender = "Male",
            engineType = TtsEngineType.PIPER,
            quality = "medium",
            modelFileName = "en_GB-alan-medium.onnx",
            configFileName = "en_GB-alan-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json",
            description = "Distinguished British English male voice suitable for historical and theological works.",
            sizeMb = 62.0
        ),
        TtsVoice(
            id = "en_US-bryce-medium",
            name = "Bryce (US)",
            displayName = "Bryce • US English (Narrator Male)",
            language = "en-US",
            gender = "Male",
            engineType = TtsEngineType.PIPER,
            quality = "medium",
            modelFileName = "en_US-bryce-medium.onnx",
            configFileName = "en_US-bryce-medium.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/bryce/medium/en_US-bryce-medium.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/bryce/medium/en_US-bryce-medium.onnx.json",
            description = "Warm storytelling male voice.",
            sizeMb = 58.0
        ),
        TtsVoice(
            id = "en_US-lessac-low",
            name = "Lessac Low (US)",
            displayName = "Lessac Low • US English (Lightweight)",
            language = "en-US",
            gender = "Neutral/Female",
            engineType = TtsEngineType.PIPER,
            quality = "low",
            modelFileName = "en_US-lessac-low.onnx",
            configFileName = "en_US-lessac-low.onnx.json",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/low/en_US-lessac-low.onnx",
            configDownloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/low/en_US-lessac-low.onnx.json",
            description = "Fast, lightweight model with smaller download footprint.",
            sizeMb = 28.0
        )
    )

    val KOKORO_VOICES = listOf(
        TtsVoice(
            id = "af_bella",
            name = "Bella (US)",
            displayName = "Bella • Kokoro US (Soft Female)",
            language = "en-US",
            gender = "Female",
            engineType = TtsEngineType.KOKORO,
            quality = "high",
            modelFileName = "kokoro-v0_19.onnx",
            description = "Expressive, delicate female voice with natural cadence.",
            sizeMb = 86.0
        ),
        TtsVoice(
            id = "af_sarah",
            name = "Sarah (US)",
            displayName = "Sarah • Kokoro US (Bright Female)",
            language = "en-US",
            gender = "Female",
            engineType = TtsEngineType.KOKORO,
            quality = "high",
            modelFileName = "kokoro-v0_19.onnx",
            description = "Lively, clear female voice.",
            sizeMb = 86.0
        ),
        TtsVoice(
            id = "am_adam",
            name = "Adam (US)",
            displayName = "Adam • Kokoro US (Deep Male)",
            language = "en-US",
            gender = "Male",
            engineType = TtsEngineType.KOKORO,
            quality = "high",
            modelFileName = "kokoro-v0_19.onnx",
            description = "Warm, authoritative baritone male voice.",
            sizeMb = 86.0
        ),
        TtsVoice(
            id = "am_michael",
            name = "Michael (US)",
            displayName = "Michael • Kokoro US (Standard Male)",
            language = "en-US",
            gender = "Male",
            engineType = TtsEngineType.KOKORO,
            quality = "high",
            modelFileName = "kokoro-v0_19.onnx",
            description = "Evenly paced, natural conversational male voice.",
            sizeMb = 86.0
        )
    )

    val SYSTEM_VOICE = TtsVoice(
        id = "system-default",
        name = "System Voice",
        displayName = "System Default Speech Engine",
        language = "System",
        gender = "System",
        engineType = TtsEngineType.SYSTEM,
        quality = "standard",
        description = "Standard system text-to-speech fallback requiring zero downloads.",
        sizeMb = 0.0
    )

    val ALL_VOICES = PIPER_VOICES + KOKORO_VOICES + listOf(SYSTEM_VOICE)

    fun findVoice(id: String): TtsVoice {
        return ALL_VOICES.firstOrNull { it.id == id } ?: PIPER_VOICES.first()
    }

    fun getVoicesForEngine(engine: TtsEngineType): List<TtsVoice> {
        return when (engine) {
            TtsEngineType.PIPER -> PIPER_VOICES
            TtsEngineType.KOKORO -> KOKORO_VOICES
            TtsEngineType.SYSTEM -> listOf(SYSTEM_VOICE)
        }
    }
}

data class TtsPreferences(
    val selectedEngine: TtsEngineType = TtsEngineType.PIPER,
    val selectedVoiceId: String = "en_US-lessac-medium",
    val speechRate: Float = 1.0f,
    val sentenceSilence: Float = 0.2f,
    val autoDownloadVoice: Boolean = true
)

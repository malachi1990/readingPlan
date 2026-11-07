package org.bible.reading.audio.strategy

/**
 * Registry of available [AudioFilenameStrategy] implementations.
 * Enables plug-and-play extension with new audio naming schemes.
 */
object AudioFilenameStrategyRegistry {
    private val strategies = mutableMapOf<String, AudioFilenameStrategy>()

    init {
        register(AutoDetectAudioStrategy())
        register(NumberedBookChapterStrategy())
        register(StandardBookChapterStrategy())
        register(SequentialBibleStrategy())
        register(CustomRegexAudioStrategy())
    }

    fun register(strategy: AudioFilenameStrategy) {
        strategies[strategy.id] = strategy
    }

    fun getStrategy(id: String): AudioFilenameStrategy {
        return strategies[id] ?: strategies["auto_detect"] ?: AutoDetectAudioStrategy()
    }

    fun getAllStrategies(): List<AudioFilenameStrategy> {
        return strategies.values.toList()
    }
}

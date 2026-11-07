package org.bible.reading.audio.strategy

import org.bible.reading.audio.model.AudioChapterMatch
import java.io.File

/**
 * Strategy interface for parsing audio file names into a Bible book and chapter.
 * Implements a plug-and-play architecture to support diverse audio Bible naming conventions.
 */
interface AudioFilenameStrategy {
    /** Unique identifier for the strategy, saved in user preferences */
    val id: String

    /** Human-readable name shown in strategy selection UI */
    val displayName: String

    /** Detailed explanation of expected file naming format */
    val description: String

    /**
     * Parses the given audio file into an [AudioChapterMatch].
     * Returns null if the file name does not match this strategy or does not correspond
     * to a valid Bible chapter.
     */
    fun parse(file: File): AudioChapterMatch?
}

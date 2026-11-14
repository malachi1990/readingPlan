package org.bible.reading.audio.model

import org.bible.reading.dto.Bible
import java.io.File

/**
 * Result of successfully parsing an audio file name into a Bible book and chapter.
 */
data class AudioChapterMatch(
    val book: Bible,
    val chapter: Int,
    val file: File,
    val strategyName: String
)

/**
 * Playable audio track representing a Bible chapter, commentary passage, general book section,
 * or synthesized TTS reading.
 */
data class AudioTrack(
    val file: File,
    val title: String,
    val subtitle: String = "",
    val book: Bible? = null,
    val chapter: Int = 1,
    val durationMs: Long = -1L
) {
    /**
     * Legacy constructor for Bible reading plan audio playback.
     */
    constructor(
        book: Bible,
        chapter: Int,
        file: File,
        title: String = "${book.bookName} $chapter",
        durationMs: Long = -1L
    ) : this(
        file = file,
        title = title,
        subtitle = book.bookName,
        book = book,
        chapter = chapter,
        durationMs = durationMs
    )
}

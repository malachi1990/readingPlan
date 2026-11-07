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
 * Playable audio track representing a Bible chapter.
 */
data class AudioTrack(
    val book: Bible,
    val chapter: Int,
    val file: File,
    val title: String = "${book.bookName} $chapter",
    val durationMs: Long = -1L
)

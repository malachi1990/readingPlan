package org.bible.reading.audio.strategy

import org.bible.reading.audio.model.AudioChapterMatch
import org.bible.reading.dto.Bible
import java.io.File

/**
 * Strategy for files formatted as:
 * - `01_Genesis_001.mp3`
 * - `40-Matthew-01.mp3`
 * - `01 Genesis Chapter 1.mp3`
 * - `B01___01_Genesis_____ENGESVN2DA.mp3` (Faith Comes By Hearing)
 * - `01_Gen_01.mp3`
 */
class NumberedBookChapterStrategy : AudioFilenameStrategy {
    override val id: String = "numbered_book_chapter"
    override val displayName: String = "Numbered: [01_Genesis_001]"
    override val description: String = "Files with book order numbers, book name/abbr, and chapter (e.g. 01_Genesis_001.mp3, 01 Genesis Chapter 1.mp3, 40_Matthew_01.mp3)"

    // Pattern 1: [BookNumber] [BookName] Chapter [ChapterNumber] (e.g. "01 Genesis Chapter 1", "40 Matthew Chapter 28")
    private val pChapter = Regex("""(?i)^B?(\d{1,2})[_\s.\-]+(.+?)[_\s.\-]+(?:chapter|ch)[_\s.\-]+(\d{1,3})(?:[_\s.\-].*)?$""")

    // Pattern 2: [BookNumber]_[BookName]_[Chapter] (e.g. 01_Genesis_001, 40-Matthew-28)
    private val p1 = Regex("""(?i)^B?(\d{1,2})[_\s-]+([1-3]?\s*[a-zA-Z]+)[_\s-]+0*(\d{1,3})""")

    // Pattern 3: [BookNumber]_[Chapter]_[BookName] (e.g. B01___01_Genesis_____ENGESVN2DA)
    private val p2 = Regex("""(?i)^B?(\d{1,2})[_\s-]+0*(\d{1,3})[_\s-]+([1-3]?\s*[a-zA-Z]+)""")

    override fun parse(file: File): AudioChapterMatch? {
        val name = file.nameWithoutExtension.trim()

        val mChapter = pChapter.find(name)
        if (mChapter != null) {
            val bookPart = mChapter.groupValues[2].trim()
            val chapterPart = mChapter.groupValues[3].trim().toIntOrNull() ?: return null
            val book = BibleBookMatcher.matchBook(bookPart) ?: BibleBookMatcher.matchBook(mChapter.groupValues[1]) ?: return null
            if (chapterPart in 1..book.chapters) {
                return AudioChapterMatch(book, chapterPart, file, displayName)
            }
        }

        val m1 = p1.find(name)
        if (m1 != null) {
            val bookPart = m1.groupValues[2].trim()
            val chapterPart = m1.groupValues[3].trim().toIntOrNull() ?: return null
            val book = BibleBookMatcher.matchBook(bookPart) ?: BibleBookMatcher.matchBook(m1.groupValues[1]) ?: return null
            if (chapterPart in 1..book.chapters) {
                return AudioChapterMatch(book, chapterPart, file, displayName)
            }
        }

        val m2 = p2.find(name)
        if (m2 != null) {
            val bookPart = m2.groupValues[3].trim()
            val chapterPart = m2.groupValues[2].trim().toIntOrNull() ?: return null
            val book = BibleBookMatcher.matchBook(bookPart) ?: BibleBookMatcher.matchBook(m2.groupValues[1]) ?: return null
            if (chapterPart in 1..book.chapters) {
                return AudioChapterMatch(book, chapterPart, file, displayName)
            }
        }

        return null
    }
}

/**
 * Strategy for files formatted as:
 * - `Genesis 1.mp3`
 * - `Genesis_01.mp3`
 * - `Genesis Chapter 1.mp3`
 * - `1 John 3.mp3`
 * - `1_Kings_05.mp3`
 * - `Psalm 23.mp3`
 */
class StandardBookChapterStrategy : AudioFilenameStrategy {
    override val id: String = "standard_book_chapter"
    override val displayName: String = "Standard: [Genesis 1]"
    override val description: String = "Files named with book name/abbr followed by chapter number (e.g. Genesis 1.mp3, Romans_8.mp3, Genesis Chapter 1.mp3)"

    private val pChapter = Regex("""(?i)^([1-3]?\s*[a-zA-Z]+(?:[_\s]+[a-zA-Z]+)?)[_\s.\-]+(?:chapter|ch)[_\s.\-]+(\d{1,3})(?:[_\s.\-].*)?$""")
    private val regex = Regex("""(?i)^([1-3]?\s*[a-zA-Z]+(?:[_\s]+[a-zA-Z]+)?)[_\s-]+0*(\d{1,3})(?:[_\s-].*)?$""")

    override fun parse(file: File): AudioChapterMatch? {
        val name = file.nameWithoutExtension.trim()
        val match = pChapter.find(name) ?: regex.find(name) ?: return null

        val bookPart = match.groupValues[1].trim()
        val chapterPart = match.groupValues[2].trim().toIntOrNull() ?: return null

        val book = BibleBookMatcher.matchBook(bookPart) ?: return null
        if (chapterPart in 1..book.chapters) {
            return AudioChapterMatch(book, chapterPart, file, displayName)
        }
        return null
    }
}

/**
 * Strategy for files numbered sequentially from 1 to 1189 across the whole Bible:
 * - `0001.mp3` (Genesis 1)
 * - `0050.mp3` (Genesis 50)
 * - `0051.mp3` (Exodus 1)
 * - `1189.mp3` (Revelation 22)
 */
class SequentialBibleStrategy : AudioFilenameStrategy {
    override val id: String = "sequential_1_1189"
    override val displayName: String = "Sequential: [0001–1189]"
    override val description: String = "Files numbered globally from 1 to 1189 (e.g. 0001.mp3 = Gen 1, 1189.mp3 = Rev 22)"

    private val regex = Regex("""(?i)(?:^|[_\s-])0*(\d{1,4})(?:[_\s-].*)?$""")

    override fun parse(file: File): AudioChapterMatch? {
        val name = file.nameWithoutExtension.trim()
        val match = regex.find(name) ?: return null
        val globalIndex = match.groupValues[1].toIntOrNull() ?: return null

        if (globalIndex in 1..1189) {
            var remaining = globalIndex
            for (book in Bible.getBooksInOrder()) {
                if (remaining <= book.chapters) {
                    return AudioChapterMatch(book, remaining, file, displayName)
                }
                remaining -= book.chapters
            }
        }
        return null
    }
}

/**
 * Strategy allowing custom user-defined regular expressions with configurable
 * capture groups for book name and chapter number.
 */
class CustomRegexAudioStrategy(
    var customPattern: String = """(?i)^([1-3]?\s*[a-zA-Z]+)[_\s-]+(\d+)""",
    var bookGroupIndex: Int = 1,
    var chapterGroupIndex: Int = 2
) : AudioFilenameStrategy {
    override val id: String = "custom_regex"
    override val displayName: String = "Custom Regular Expression"
    override val description: String = "User-defined regex pattern with capture groups for book and chapter"

    override fun parse(file: File): AudioChapterMatch? {
        if (customPattern.isBlank()) return null
        val name = file.nameWithoutExtension.trim()
        val regex = try {
            Regex(customPattern)
        } catch (_: Exception) {
            return null
        }

        val match = regex.find(name) ?: return null
        if (bookGroupIndex > match.groupValues.lastIndex || chapterGroupIndex > match.groupValues.lastIndex) {
            return null
        }

        val bookPart = match.groupValues[bookGroupIndex].trim()
        val chapterPart = match.groupValues[chapterGroupIndex].trim().toIntOrNull() ?: return null

        val book = BibleBookMatcher.matchBook(bookPart) ?: return null
        if (chapterPart in 1..book.chapters) {
            return AudioChapterMatch(book, chapterPart, file, displayName)
        }
        return null
    }
}

/**
 * Default composite auto-detect strategy. Intelligently queries all registered
 * standard strategies in priority order until a match is found.
 */
class AutoDetectAudioStrategy : AudioFilenameStrategy {
    override val id: String = "auto_detect"
    override val displayName: String = "Auto-Detect (Recommended)"
    override val description: String = "Automatically identifies file naming format across standard patterns"

    private val subStrategies = listOf(
        NumberedBookChapterStrategy(),
        StandardBookChapterStrategy(),
        SequentialBibleStrategy()
    )

    override fun parse(file: File): AudioChapterMatch? {
        for (strategy in subStrategies) {
            val match = strategy.parse(file)
            if (match != null) {
                return match.copy(strategyName = "${match.strategyName} (Auto-detected)")
            }
        }
        return null
    }
}

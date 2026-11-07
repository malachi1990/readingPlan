package org.bible.reading.jsword

import org.bible.reading.dto.Bible
import org.crosswire.jsword.passage.Verse
import kotlin.test.Test
import kotlin.test.assertTrue

class SwordManagerTest {

    @Test
    fun testAllBiblesOneChapterBooks() {
        val manager = SwordManager()
        val oneChapterBooks = Bible.getBooksInOrder().filter { it.chapters == 1 }

        for (bible in manager.installedBibles) {
            println("Testing bible: ${bible.initials}")
            for (b in oneChapterBooks) {
                val key = try {
                    bible.getKey(b.bookName)
                } catch (_: Exception) {
                    continue
                }
                if (key.cardinality == 0 || !bible.contains(key)) continue // Skip books not in this Bible (e.g. OT books in NT-only bibles)
                val verses = mutableListOf<VerseItem>()
                for (k in key) {
                    if (k is Verse && k.verse == 0) continue
                    val vData = org.crosswire.jsword.book.BookData(bible, k)
                    val rawText = org.crosswire.jsword.book.OSISUtil.getPlainText(vData.osis).trim()
                    var cleanText = rawText.removePrefix(k.name).trim()
                    // Also handle alternative reference formats like "Jude 1:1" vs "Jude 1"
                    if (k is Verse) {
                        cleanText = cleanText.removePrefix("${b.bookName} 1:${k.verse}").trim()
                        cleanText = cleanText.removePrefix("${b.bookName} ${k.verse}").trim()
                    }
                    val verseNum = if (k is Verse) k.verse else 0
                    if (cleanText.isNotBlank()) {
                        verses.add(VerseItem(verseNumber = verseNum, text = cleanText, reference = k.name))
                    }
                }

                // Check that verses are read
                when (b) {
                    Bible.OBADIAH -> assertTrue(verses.size in 20..25, "Obadiah in ${bible.initials} had ${verses.size}")
                    Bible.PHILEMON -> assertTrue(verses.size in 24..26, "Philemon in ${bible.initials} had ${verses.size}")
                    Bible.SECOND_JOHN -> assertTrue(verses.size in 12..14, "2 John in ${bible.initials} had ${verses.size}")
                    Bible.THIRD_JOHN -> assertTrue(verses.size in 13..15, "3 John in ${bible.initials} had ${verses.size}")
                    Bible.JUDE -> assertTrue(verses.size in 24..26, "Jude in ${bible.initials} had ${verses.size}")
                    else -> {}
                }
            }
        }
    }
}

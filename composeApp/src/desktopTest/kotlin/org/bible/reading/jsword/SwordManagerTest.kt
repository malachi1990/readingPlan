package org.bible.reading.jsword

import org.bible.reading.dto.Bible
import org.bible.reading.dto.Reading
import org.crosswire.jsword.book.BookData
import org.crosswire.jsword.book.OSISUtil
import org.crosswire.jsword.passage.Verse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    val vData = BookData(bible, k)
                    val rawText = OSISUtil.getPlainText(vData.osis).trim()
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

    @Test
    fun testReadChapterSingleDomMatchesCanonicalVersesAndCaches() {
        val manager = SwordManager()
        val bibles = manager.installedBibles
        if (bibles.isEmpty()) return

        val bible = bibles.first()
        val genesisReading = Reading("Genesis", 1)
        val firstRead = manager.readChapter(genesisReading, bible)
        assertEquals(null, firstRead.error, "Genesis 1 should load without error")
        assertTrue(firstRead.verses.isNotEmpty(), "Genesis 1 should have verses")

        // Verify against single-verse canonical extraction on first 5 verses
        val key = bible.getKey("Genesis 1")
        var checked = 0
        for (k in key) {
            if (k is Verse && k.verse in 1..5) {
                val expectedText = OSISUtil.getCanonicalText(BookData(bible, k).osis)
                    .trim()
                    .removePrefix(k.name)
                    .trim()
                val actual = firstRead.verses.firstOrNull { it.verseNumber == k.verse }
                assertEquals(expectedText, actual?.text, "Verse ${k.verse} text should match JSword canonical text")
                checked++
            }
        }
        assertTrue(checked > 0, "Should verify at least one verse")

        // Also test a single-chapter book if present in this Bible
        val judeReading = Reading("Jude", 1)
        val judeRead = manager.readChapter(judeReading, bible)
        if (judeRead.error == null && judeRead.verses.isNotEmpty()) {
            assertTrue(judeRead.verses.size >= 20, "Jude 1 should read all verses, got ${judeRead.verses.size}")
        }

        // Cache hit should return exact cached ChapterPassage instance
        val cachedRead = manager.readChapter(genesisReading, bible)
        assertTrue(firstRead === cachedRead, "Second readChapter call should hit in-memory LRU cache")

        // Refresh clears chapter cache
        manager.refreshInstalledBooks()
        val postRefresh = manager.getCachedChapter(genesisReading, bible)
        assertEquals(null, postRefresh, "Chapter cache must be cleared on refreshInstalledBooks()")
    }

    @Test
    fun testSelectedBiblePersistsAcrossRestarts() {
        val tempAppDir = Files.createTempDirectory("sword_active_bible_pref_test").toFile().apply {
            deleteOnExit()
        }

        val firstManager = SwordManager(appDir = tempAppDir)
        val bibles = firstManager.installedBibles
        if (bibles.isEmpty()) return

        val targetBible = if (bibles.size >= 2) bibles[1] else bibles.first()
        firstManager.selectedBible = targetBible

        val prefFile = File(tempAppDir, SwordManager.BIBLE_PREFERENCES_FILE_NAME)
        assertTrue(prefFile.exists(), "Changing selectedBible should persist preference file")
        assertTrue(prefFile.readText().contains(targetBible.initials))

        // Simulate app restart with a fresh SwordManager pointing at the same appDir
        val restartedManager = SwordManager(appDir = tempAppDir)
        assertEquals(
            targetBible.initials,
            restartedManager.selectedBible?.initials,
            "Restarted SwordManager should restore previously selected active Bible"
        )

        // If saved preference points to a missing Bible, startup should fall back cleanly
        prefFile.writeText("selectedBibleInitials=NONEXISTENT_BIBLE_XYZ\n")
        val fallbackManager = SwordManager(appDir = tempAppDir)
        assertEquals(
            bibles.first().initials,
            fallbackManager.selectedBible?.initials,
            "Missing saved Bible should fall back to first installed Bible"
        )
    }
}

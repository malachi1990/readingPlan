package org.bible.reading.jsword

import org.bible.reading.dto.Reading
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwordManagerTest {

    @Test
    fun testAppDirectorySetup() {
        val manager = SwordManager()
        val expected = File(System.getProperty("user.home"), ".bibleReadingPlan")
        assertEquals(expected.absolutePath, manager.appDir.absolutePath)
        assertTrue(manager.appDir.exists())
    }

    @Test
    fun testInstalledBooksAndBibles() {
        val manager = SwordManager()
        println("Installed books: ${manager.installedBooks.map { it.initials }}")
        println("Installed bibles: ${manager.installedBibles.map { it.initials }}")
        assertTrue(manager.installedBooks.isNotEmpty(), "Expected at least one installed book")
    }

    @Test
    fun testReadChapter() {
        val manager = SwordManager()
        val bible = manager.installedBibles.firstOrNull() ?: manager.installedBooks.first()
        val passage = manager.readChapter(Reading("Genesis", 1), bible)
        assertNotNull(passage)
        println("Read chapter passage: ${passage.bookName} ${passage.chapterNumber}, verses count: ${passage.verses.size}")
        assertTrue(passage.verses.isNotEmpty() || passage.fullText.isNotEmpty())
        if (passage.verses.isNotEmpty()) {
            println("Verse 1: ${passage.verses[0].reference} -> ${passage.verses[0].text}")
        }
    }

    @Test
    fun testInstallersList() {
        val manager = SwordManager()
        assertTrue(manager.availableInstallers.contains("CrossWire"))
    }
}

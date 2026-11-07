package org.bible.reading.ui

import org.bible.reading.dto.Reading
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.BookCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FurtherStudyTest {

    @Test
    fun testTabNames() {
        assertEquals("Bible Reading plan", MainAppTab.READING_PLAN.title)
        assertEquals("Further Study", MainAppTab.FURTHER_STUDY.title)
    }

    @Test
    fun testPlanStateTabSwitching() {
        val state = PlanState()
        assertEquals(MainAppTab.READING_PLAN, state.currentTab)

        state.currentTab = MainAppTab.FURTHER_STUDY
        assertEquals(MainAppTab.FURTHER_STUDY, state.currentTab)

        state.currentTab = MainAppTab.READING_PLAN
        assertEquals(MainAppTab.READING_PLAN, state.currentTab)
    }

    @Test
    fun testSwordModulesAvailableForFurtherStudy() {
        val manager = SwordManager()
        val allModules = manager.installedBooks
        assertTrue(allModules.isNotEmpty(), "Should have installed Sword modules")

        // Check Bibles
        val bibles = manager.getInstalledBooksByCategory(BookCategory.BIBLE)
        assertTrue(bibles.isNotEmpty(), "Should have installed Bibles")

        // Test reading chapter in Bible
        val bible = bibles.first()
        val biblePassage = manager.readChapter(Reading("John", 3), bible)
        assertNotNull(biblePassage)
        assertTrue(biblePassage.verses.isNotEmpty() || biblePassage.fullText.isNotEmpty())

        // Check Commentaries if available
        val commentaries = manager.getInstalledBooksByCategory(BookCategory.COMMENTARY)
        if (commentaries.isNotEmpty()) {
            val commentary = commentaries.first()
            val commPassage = manager.readChapter(Reading("John", 3), commentary)
            assertNotNull(commPassage)
        }
    }

    @Test
    fun testParallelModuleStudy() {
        val manager = SwordManager()
        val bibles = manager.installedBibles
        if (bibles.size >= 2) {
            val primary = bibles[0]
            val secondary = bibles[1]

            val pass1 = manager.readChapter(Reading("Genesis", 1), primary)
            val pass2 = manager.readChapter(Reading("Genesis", 1), secondary)

            assertNotNull(pass1)
            assertNotNull(pass2)
            assertEquals("Genesis", pass1.bookName)
            assertEquals(1, pass1.chapterNumber)
            assertEquals("Genesis", pass2.bookName)
            assertEquals(1, pass2.chapterNumber)
        }
    }

    @Test
    fun testLocationLabel() {
        val manager = SwordManager()
        manager.installedBooks.firstOrNull()?.let { book ->
            val label = manager.getBookLocationLabel(book)
            assertTrue(label.contains("~/.sword") || label.contains("~/.bibleReadingPlan"))
        }
    }
}

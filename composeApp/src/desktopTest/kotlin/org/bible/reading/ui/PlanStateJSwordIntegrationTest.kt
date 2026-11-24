package org.bible.reading.ui

import org.bible.reading.dto.Bible
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.BookMetaData
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.book.basic.DefaultBookMetaData
import org.crosswire.jsword.book.filter.SourceFilter
import org.crosswire.jsword.book.sword.SwordBookDriver
import org.crosswire.jsword.book.sword.processing.RawTextToXmlProcessor
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.PassageKeyFactory
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.system.Versifications
import org.jdom2.Content
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanStateJSwordIntegrationTest {

    private fun tempPlanDir(): File = Files.createTempDirectory("plan_state_jsword_test").toFile().apply {
        deleteOnExit()
    }

    private fun createPartialPassageBook(initials: String, passageSpec: String): AbstractPassageBook {
        val bmd = DefaultBookMetaData(SwordBookDriver.instance(), initials, BookCategory.BIBLE).apply {
            setInitials(initials)
            putProperty(BookMetaData.KEY_VERSIFICATION, "KJV")
        }
        val v11n = Versifications.instance().getVersification("KJV")
        val populated = if (passageSpec.isBlank()) {
            PassageKeyFactory.instance().createEmptyKeyList(v11n)
        } else {
            PassageKeyFactory.instance().getKey(v11n, passageSpec)
        }
        return object : AbstractPassageBook(bmd, null) {
            override fun getGlobalKeyList(): Key = populated
            override fun contains(key: Key): Boolean = populated.contains(key)
            override fun getRawText(key: Key): String = ""
            override fun getFilter(): SourceFilter? = null
            override fun getOsis(key: Key, processor: RawTextToXmlProcessor): MutableList<Content> = mutableListOf()
            override fun setRawText(key: Key, rawData: String) {}
            override fun setAliasKey(alias: Key, source: Key) {}
        }
    }

    @Test
    fun testAppFolderCreationAndConfig() {
        val state = PlanState(planStorageDir = tempPlanDir())
        val expectedDir = File(System.getProperty("user.home"), ".bibleReadingPlan")
        assertEquals(expectedDir.absolutePath, state.swordManager.appDir.absolutePath)
        assertTrue(state.swordManager.appDir.exists())
    }

    @Test
    fun testDownloadDialogState() {
        val state = PlanState(planStorageDir = tempPlanDir())
        assertFalse(state.showDownloadDialog)
        state.showDownloadDialog = true
        assertTrue(state.showDownloadDialog)
        state.showDownloadDialog = false
        assertFalse(state.showDownloadDialog)
    }

    @Test
    fun testClickDailyReadingOpensViewAndNavigates() {
        val state = PlanState(planStorageDir = tempPlanDir())
        state.selectedStrategyChoice = PlanStrategyChoice.SIMPLE_SEQUENTIAL
        state.chaptersPerDay = 3
        val plan = state.generatePlan()
        assertTrue(plan.readings.size >= 2)

        // User clicks on first day reading
        state.viewingDailyReading = plan.readings[0]
        assertNotNull(state.viewingDailyReading)
        assertEquals(plan.readings[0].date, state.viewingDailyReading?.date)

        // Test navigation
        assertTrue(state.hasNextDailyReading())
        assertFalse(state.hasPreviousDailyReading())

        state.navigateDailyReading(1)
        assertEquals(plan.readings[1].date, state.viewingDailyReading?.date)
        assertTrue(state.hasPreviousDailyReading())

        state.navigateDailyReading(-1)
        assertEquals(plan.readings[0].date, state.viewingDailyReading?.date)
    }

    @Test
    fun testDailyReadingPassageResolution() {
        val state = PlanState(planStorageDir = tempPlanDir())
        state.clearDay(DayOfWeek.MONDAY)
        state.addBookToDay(DayOfWeek.MONDAY, Bible.JONAH)
        val plan = state.generatePlan()

        val firstDay = plan.readings[0]
        state.viewingDailyReading = firstDay

        // Read passage for the first reading of the day
        val reading = firstDay.reading[0]
        val passage = state.swordManager.readChapter(reading)
        assertNotNull(passage)
        assertEquals(reading.book, passage.bookName)
        assertEquals(reading.chapterNumber, passage.chapterNumber)
        assertTrue(passage.verses.isNotEmpty() || passage.fullText.isNotEmpty())
    }

    @Test
    fun testGetModuleBooksExcludesEmptyBooksAndTracksPopulatedChapters() {
        val state = PlanState(planStorageDir = tempPlanDir())

        // NT-only module should omit all Old Testament books
        val ntOnlyBook = createPartialPassageBook("TestNT", "Matt-Rev")
        val ntToc = state.swordManager.getModuleBooks(ntOnlyBook)
        assertEquals(27, ntToc.size)
        assertEquals(BibleBook.MATT, ntToc.first().bibleBook)
        assertEquals(BibleBook.REV, ntToc.last().bibleBook)
        assertFalse(ntToc.any { it.bibleBook == BibleBook.GEN })

        // Sparse partial module should only list populated books and chapters
        val sparseBook = createPartialPassageBook("TestSparse", "Gen 1:1; John 3:16; John 5:1; Jude 1:1")
        val sparseToc = state.swordManager.getModuleBooks(sparseBook)
        assertEquals(listOf(BibleBook.GEN, BibleBook.JOHN, BibleBook.JUDE), sparseToc.map { it.bibleBook })
        assertEquals(listOf(1), sparseToc[0].chapters)
        assertEquals(listOf(3, 5), sparseToc[1].chapters)
        assertEquals(listOf(1), sparseToc[2].chapters)

        // Empty module should return an empty TOC
        val emptyBook = createPartialPassageBook("TestEmpty", "")
        assertTrue(state.swordManager.getModuleBooks(emptyBook).isEmpty())
    }
}

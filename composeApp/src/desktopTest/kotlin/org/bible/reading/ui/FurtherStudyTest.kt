package org.bible.reading.ui

import org.bible.reading.dto.Reading
import org.bible.reading.jsword.ModuleBook
import org.bible.reading.jsword.SwordManager
import org.crosswire.common.util.Language
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
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun createDummyBook(initials: String, name: String, langCode: String): org.crosswire.jsword.book.Book {
        val bmd = object : DefaultBookMetaData(SwordBookDriver.instance(), name, BookCategory.BIBLE) {
            override fun getInitials(): String = initials
            override fun getName(): String = name
            override fun getLanguage(): Language = Language(langCode)
        }.apply {
            putProperty(BookMetaData.KEY_VERSIFICATION, "KJV")
        }
        val v11n = Versifications.instance().getVersification("KJV")
        val emptyKey = PassageKeyFactory.instance().createEmptyKeyList(v11n)
        return object : AbstractPassageBook(bmd, null) {
            override fun getGlobalKeyList(): Key = emptyKey
            override fun contains(key: Key): Boolean = false
            override fun getRawText(key: Key): String = ""
            override fun getFilter(): SourceFilter? = null
            override fun getOsis(key: Key, processor: RawTextToXmlProcessor): MutableList<Content> = mutableListOf()
            override fun setRawText(key: Key, rawData: String) {}
            override fun setAliasKey(alias: Key, source: Key) {}
        }
    }

    @Test
    fun testFilterBooksByQueryMatchesTitleInitialsAndLanguage() {
        val kjv = createDummyBook("KJV", "King James Version", "en")
        val rvr = createDummyBook("RVR1960", "Reina-Valera 1960", "es")
        val luther = createDummyBook("GerLut", "Lutherbibel 1912", "de")
        val books = listOf(kjv, rvr, luther)

        assertEquals("RVR1960", rvr.initials)
        assertTrue(luther.language?.name?.contains("German") == true)

        assertEquals(books, filterBooksByQuery(books, "   "))
        assertEquals(listOf(kjv), filterBooksByQuery(books, "james"))
        assertEquals(listOf(rvr), filterBooksByQuery(books, "  rvr1960 "))
        assertEquals(listOf(luther), filterBooksByQuery(books, "german"))
        assertTrue(filterBooksByQuery(books, "nonexistent").isEmpty())
    }

    @Test
    fun testSessionReadingStateTracksModulesChaptersAndSectionScrolls() {
        val state = PlanState()
        val session = state.sessionReadingState

        assertNull(session.furtherStudySelectedModuleInitials)
        assertNull(session.furtherStudyCategoryTabName)
        assertEquals(16, session.furtherStudyFontSizeSp)

        // Track top-level UI selections
        session.furtherStudyCategoryTabName = ModuleCategoryTab.COMMENTARIES.name
        session.furtherStudySelectedModuleInitials = "MHC"
        session.furtherStudyIsParallelMode = true
        session.furtherStudySecondaryModuleInitials = "KJV"
        session.furtherStudyIsNotesMode = true
        session.furtherStudyFontSizeSp = 18
        session.furtherStudyGenBookSearchQuery = "grace"

        // Track verse-based position per module independently
        session.saveVersePosition("MHC", bookOsis = "Rom", chapter = 8, verseFilter = 28)
        session.saveVersePosition("KJV", bookOsis = "John", chapter = 3, verseFilter = 0)

        val mhcPos = session.getModulePosition("MHC")
        assertNotNull(mhcPos)
        assertEquals("Rom", mhcPos.bookOsis)
        assertEquals(8, mhcPos.chapter)
        assertEquals(28, mhcPos.selectedVerseFilter)

        val kjvPos = session.getModulePosition("KJV")
        assertNotNull(kjvPos)
        assertEquals("John", kjvPos.bookOsis)
        assertEquals(3, kjvPos.chapter)
        assertEquals(0, kjvPos.selectedVerseFilter)

        // Track general book / dictionary section independently
        session.saveGenBookPosition("Institutes", keyIndex = 12, keyName = "Book 2 Chapter 1")
        val genPos = session.getModulePosition("Institutes")
        assertNotNull(genPos)
        assertEquals(12, genPos.selectedKeyIndex)
        assertEquals("Book 2 Chapter 1", genPos.selectedKeyName)

        // Track exact scroll positions per section independently
        val mhcSection = session.verseSectionKey("primary", "MHC", "Rom", 8, 28)
        val kjvSection = session.verseSectionKey("secondary", "KJV", "Rom", 8, 28)
        val genBookSection = session.genBookSectionKey("Institutes", 12, "Book 2 Chapter 1")

        session.saveSectionLazyScroll("$mhcSection|paras", index = 5, offset = 140)
        session.saveSectionLazyScroll("$kjvSection|verses", index = 12, offset = 45)
        session.saveSectionVerticalScroll("$genBookSection|text", value = 620)

        assertEquals(SessionScrollPosition(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 140), session.getSectionScrollPosition("$mhcSection|paras"))
        assertEquals(SessionScrollPosition(firstVisibleItemIndex = 12, firstVisibleItemScrollOffset = 45), session.getSectionScrollPosition("$kjvSection|verses"))
        assertEquals(SessionScrollPosition(verticalScrollValue = 620), session.getSectionScrollPosition("$genBookSection|text"))
        assertEquals(SessionScrollPosition(), session.getSectionScrollPosition("unknown_section"))
    }

    @Test
    fun testSessionReadingStateTracksDailyAndReadingPlanScrolls() {
        val session = SessionReadingState()
        val day1 = LocalDate.of(2026, 3, 15)
        val day2 = LocalDate.of(2026, 3, 16)

        assertEquals(-1, session.getDailySelectedIndex(day1))
        session.saveDailySelectedIndex(day1, 1)
        session.saveDailySelectedIndex(day2, 0)
        assertEquals(1, session.getDailySelectedIndex(day1))
        assertEquals(0, session.getDailySelectedIndex(day2))

        session.saveDailyScrollPosition(day1, readingIndex = 1, index = 0, offset = 380)
        session.saveDailyScrollPosition(day1, readingIndex = -1, index = 2, offset = 95)
        assertEquals(SessionScrollPosition(0, 380), session.getDailyScrollPosition(day1, 1))
        assertEquals(SessionScrollPosition(2, 95), session.getDailyScrollPosition(day1, -1))
        assertEquals(SessionScrollPosition(), session.getDailyScrollPosition(day2, 0))

        session.readingPlanConfigScrollValue = 250
        session.readingPlanResultsScrollPosition = SessionScrollPosition(firstVisibleItemIndex = 14, firstVisibleItemScrollOffset = 60)
        assertEquals(250, session.readingPlanConfigScrollValue)
        assertEquals(SessionScrollPosition(14, 60), session.readingPlanResultsScrollPosition)
    }

    @Test
    fun testParseQuickReferenceResolvesBookChapterAndVerse() {
        val moduleBooks = listOf(
            ModuleBook(BibleBook.GEN, "Genesis", 50, (1..50).toList()),
            ModuleBook(BibleBook.JOHN, "John", 21, (1..21).toList()),
            ModuleBook(BibleBook.ROM, "Romans", 16, (1..16).toList())
        )

        assertEquals(Triple(2, 8, 28), parseQuickReference("Rom 8:28", moduleBooks))
        assertEquals(Triple(1, 3, 0), parseQuickReference("John 3", moduleBooks))
        assertEquals(Triple(0, 1, 0), parseQuickReference("Genesis", moduleBooks))
        assertNull(parseQuickReference("NonexistentBook 1:1", moduleBooks))
    }
}

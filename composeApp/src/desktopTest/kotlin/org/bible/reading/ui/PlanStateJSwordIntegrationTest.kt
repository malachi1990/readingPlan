package org.bible.reading.ui

import org.bible.reading.dto.Bible
import java.io.File
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanStateJSwordIntegrationTest {

    @Test
    fun testAppFolderCreationAndConfig() {
        val state = PlanState()
        val expectedDir = File(System.getProperty("user.home"), ".bibleReadingPlan")
        assertEquals(expectedDir.absolutePath, state.swordManager.appDir.absolutePath)
        assertTrue(state.swordManager.appDir.exists())
    }

    @Test
    fun testDownloadDialogState() {
        val state = PlanState()
        assertFalse(state.showDownloadDialog)
        state.showDownloadDialog = true
        assertTrue(state.showDownloadDialog)
        state.showDownloadDialog = false
        assertFalse(state.showDownloadDialog)
    }

    @Test
    fun testClickDailyReadingOpensViewAndNavigates() {
        val state = PlanState()
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
        val state = PlanState()
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
}

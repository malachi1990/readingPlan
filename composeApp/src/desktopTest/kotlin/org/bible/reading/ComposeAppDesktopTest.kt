package org.bible.reading

import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.ExportFormat
import org.bible.reading.strategy.*
import org.bible.reading.ui.PlanState
import org.bible.reading.ui.PlanStrategyChoice
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.*

class ComposeAppDesktopTest {

    // =========================================================================
    // 1) UI & Configuration: Setting Books on Specific Days
    // =========================================================================

    @Test
    fun testSetBooksOnSpecificDaysViaState() {
        val state = PlanState()

        // Clear all days initially to start fresh
        DayOfWeek.entries.forEach { dow -> state.clearDay(dow) }
        DayOfWeek.entries.forEach { dow ->
            assertTrue(state.booksByDay[dow]?.isEmpty() == true)
        }

        // Set specific books on specific days
        state.addBookToDay(DayOfWeek.MONDAY, Bible.GENESIS)
        state.addBookToDay(DayOfWeek.MONDAY, Bible.EXODUS)
        state.addBookToDay(DayOfWeek.WEDNESDAY, Bible.PSALMS)
        state.addBookToDay(DayOfWeek.FRIDAY, Bible.MATTHEW)
        state.addBookToDay(DayOfWeek.SUNDAY, Bible.REVELATION)

        assertEquals(listOf(Bible.GENESIS, Bible.EXODUS), state.booksByDay[DayOfWeek.MONDAY]?.toList())
        assertEquals(listOf(Bible.PSALMS), state.booksByDay[DayOfWeek.WEDNESDAY]?.toList())
        assertEquals(listOf(Bible.MATTHEW), state.booksByDay[DayOfWeek.FRIDAY]?.toList())
        assertEquals(listOf(Bible.REVELATION), state.booksByDay[DayOfWeek.SUNDAY]?.toList())

        // Verify chapter calculation for days
        assertEquals(50 + 40, state.getTotalChaptersForDay(DayOfWeek.MONDAY))
        assertEquals(150, state.getTotalChaptersForDay(DayOfWeek.WEDNESDAY))
        assertEquals(28, state.getTotalChaptersForDay(DayOfWeek.FRIDAY))
        assertEquals(22, state.getTotalChaptersForDay(DayOfWeek.SUNDAY))
        assertEquals(0, state.getTotalChaptersForDay(DayOfWeek.TUESDAY))

        // Remove a book
        state.removeBookFromDay(DayOfWeek.MONDAY, Bible.EXODUS)
        assertEquals(listOf(Bible.GENESIS), state.booksByDay[DayOfWeek.MONDAY]?.toList())
        assertEquals(50, state.getTotalChaptersForDay(DayOfWeek.MONDAY))
    }

    @Test
    fun testAddEntireCollectionToSpecificDay() {
        val state = PlanState()
        state.clearDay(DayOfWeek.TUESDAY)

        state.addCollectionToDay(DayOfWeek.TUESDAY, BookCollection.Gospels_Acts)

        val assigned = state.booksByDay[DayOfWeek.TUESDAY]?.toList() ?: emptyList()
        val expected = listOf(Bible.MATTHEW, Bible.MARK, Bible.LUKE, Bible.JOHN, Bible.ACTS)
        assertEquals(expected, assigned)
        assertEquals(28 + 16 + 24 + 21 + 28, state.getTotalChaptersForDay(DayOfWeek.TUESDAY))
    }

    @Test
    fun testResetToCanonicalSchedule() {
        val state = PlanState()
        state.clearDay(DayOfWeek.MONDAY)
        assertTrue(state.booksByDay[DayOfWeek.MONDAY]?.isEmpty() == true)

        state.resetToCanonicalSchedule()
        val mondayBooks = state.booksByDay[DayOfWeek.MONDAY]?.toList() ?: emptyList()
        assertEquals(
            listOf(Bible.GENESIS, Bible.EXODUS, Bible.LEVITICUS, Bible.NUMBERS, Bible.DEUTERONOMY),
            mondayBooks
        )
    }

    @Test
    fun testPlanExecutionWithCustomBooksOnSpecificDays() {
        // Assign Jonah (4 chapters) to Monday only and Jude (1 chapter) to Tuesday only
        val booksByDay = mapOf(
            DayOfWeek.MONDAY to listOf(Bible.JONAH),
            DayOfWeek.TUESDAY to listOf(Bible.JUDE)
        )

        val strategy = DayOfWeekStrategy(
            chaptersPerDay = 2,
            booksByDay = booksByDay,
            continuationStrategy = ContinuationStrategy.SKIP
        )

        // Starting on Monday 2026-01-05
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5))

        // Day 1: Monday Jan 5 -> Jonah 1, 2
        assertEquals(LocalDate.of(2026, 1, 5), plan.readings[0].date)
        assertEquals(listOf("Jonah" to 1, "Jonah" to 2), plan.readings[0].reading.map { it.book to it.chapterNumber })

        // Day 2: Tuesday Jan 6 -> Jude 1
        assertEquals(LocalDate.of(2026, 1, 6), plan.readings[1].date)
        assertEquals(listOf("Jude" to 1), plan.readings[1].reading.map { it.book to it.chapterNumber })

        // Day 3: Next Monday Jan 12 -> Jonah 3, 4 (since Wed-Sun have no books and continuation is SKIP)
        assertEquals(LocalDate.of(2026, 1, 12), plan.readings[2].date)
        assertEquals(listOf("Jonah" to 3, "Jonah" to 4), plan.readings[2].reading.map { it.book to it.chapterNumber })

        // Total chapters read must be 5 (4 from Jonah + 1 from Jude)
        val totalChapters = plan.readings.flatMap { it.reading }.size
        assertEquals(5, totalChapters)
    }

    // =========================================================================
    // 2) Configuration: Setting Chapters Per Day
    // =========================================================================

    @Test
    fun testConfigurableGlobalChaptersPerDay() {
        val strategy = SimpleSequentialStrategy(
            chaptersPerDay = 5,
            chaptersOnSunday = 5,
            books = listOf(Bible.GENESIS) // 50 chapters
        )

        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 1))
        // 50 chapters / 5 per day = exactly 10 days
        assertEquals(10, plan.readings.size)
        plan.readings.forEach { daily ->
            assertEquals(5, daily.reading.size)
        }
    }

    @Test
    fun testConfigurableSundayChapters() {
        // Start on Sunday 2026-01-04 with 10 chapters on Sunday, 2 chapters on other days
        val strategy = SimpleSequentialStrategy(
            chaptersPerDay = 2,
            chaptersOnSunday = 10,
            books = listOf(Bible.MARK) // 16 chapters
        )

        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 4))
        // Sunday: 10 chapters (Mark 1..10)
        assertEquals(LocalDate.of(2026, 1, 4), plan.readings[0].date)
        assertEquals(10, plan.readings[0].reading.size)

        // Monday: 2 chapters (Mark 11..12)
        assertEquals(LocalDate.of(2026, 1, 5), plan.readings[1].date)
        assertEquals(2, plan.readings[1].reading.size)

        // Tuesday: 2 chapters (Mark 13..14)
        assertEquals(LocalDate.of(2026, 1, 6), plan.readings[2].date)
        assertEquals(2, plan.readings[2].reading.size)

        // Wednesday: 2 chapters (Mark 15..16)
        assertEquals(LocalDate.of(2026, 1, 7), plan.readings[3].date)
        assertEquals(2, plan.readings[3].reading.size)

        assertEquals(4, plan.readings.size)
    }

    @Test
    fun testCustomPerDayOfWeekChaptersMap() {
        val perDayMap = mapOf(
            DayOfWeek.MONDAY to 1,
            DayOfWeek.TUESDAY to 2,
            DayOfWeek.WEDNESDAY to 3,
            DayOfWeek.THURSDAY to 4,
            DayOfWeek.FRIDAY to 5,
            DayOfWeek.SATURDAY to 6,
            DayOfWeek.SUNDAY to 7
        )

        val strategy = SimpleSequentialStrategy(
            chaptersPerDayOfWeek = perDayMap,
            books = listOf(Bible.GENESIS) // 50 chapters
        )

        // Starts Monday 2026-01-05
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5))
        assertEquals(1, plan.readings[0].reading.size) // Mon
        assertEquals(2, plan.readings[1].reading.size) // Tue
        assertEquals(3, plan.readings[2].reading.size) // Wed
        assertEquals(4, plan.readings[3].reading.size) // Thu
        assertEquals(5, plan.readings[4].reading.size) // Fri
        assertEquals(6, plan.readings[5].reading.size) // Sat
        assertEquals(7, plan.readings[6].reading.size) // Sun
    }

    // =========================================================================
    // 3) Custom Strategies Aside From Already Implemented Strategies
    // =========================================================================

    @Test
    fun testContinuationStrategyLookback() {
        // Monday has Jonah (4 chapters), Tuesday has Obadiah (1 chapter)
        val booksByDay = mapOf(
            DayOfWeek.MONDAY to listOf(Bible.JONAH),
            DayOfWeek.TUESDAY to listOf(Bible.OBADIAH)
        )

        val strategy = DayOfWeekStrategy(
            chaptersPerDay = 3,
            booksByDay = booksByDay,
            continuationStrategy = ContinuationStrategy.LOOKBACK
        )

        // Starting Monday 2026-01-05:
        // Day 1: Monday Jan 5 -> Jonah 1, 2, 3 (Monday has 1 chapter left: Jonah 4)
        // Day 2: Tuesday Jan 6 -> Obadiah 1. Tuesday's pool is now empty!
        // Continuation LOOKBACK looks back 1 day to Monday, which has Jonah 4!
        // So Tuesday Jan 6 continues by pulling Jonah 4!
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5))

        assertEquals(2, plan.readings.size)

        // Day 1: Monday Jan 5
        val day1 = plan.readings[0]
        assertEquals(LocalDate.of(2026, 1, 5), day1.date)
        assertEquals(3, day1.reading.size)
        assertEquals(listOf(1, 2, 3), day1.reading.map { it.chapterNumber })

        // Day 2: Tuesday Jan 6 (Obadiah 1 + lookback to Monday for Jonah 4)
        val day2 = plan.readings[1]
        assertEquals(LocalDate.of(2026, 1, 6), day2.date)
        assertEquals(2, day2.reading.size)
        assertEquals("Obadiah", day2.reading[0].book)
        assertEquals(1, day2.reading[0].chapterNumber)
        assertEquals("Jonah", day2.reading[1].book)
        assertEquals(4, day2.reading[1].chapterNumber)
    }

    @Test
    fun testContinuationStrategyLookforward() {
        // Monday has Obadiah (1 chapter), Tuesday has Jonah (4 chapters)
        val booksByDay = mapOf(
            DayOfWeek.MONDAY to listOf(Bible.OBADIAH),
            DayOfWeek.TUESDAY to listOf(Bible.JONAH)
        )

        val strategy = DayOfWeekStrategy(
            chaptersPerDay = 3,
            booksByDay = booksByDay,
            continuationStrategy = ContinuationStrategy.LOOKFORWARD
        )

        // Monday 2026-01-05:
        // Day 1: Monday has Obadiah (1 ch). Needs 2 more chapters.
        // LOOKFORWARD checks upcoming days (Tuesday). Tuesday has Jonah!
        // So Monday gets Obadiah 1, plus Jonah 1 and Jonah 2!
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5))
        val day1 = plan.readings[0]
        assertEquals(3, day1.reading.size)
        assertEquals("Obadiah", day1.reading[0].book)
        assertEquals(1, day1.reading[0].chapterNumber)
        assertEquals("Jonah", day1.reading[1].book)
        assertEquals(1, day1.reading[1].chapterNumber)
        assertEquals("Jonah", day1.reading[2].book)
        assertEquals(2, day1.reading[2].chapterNumber)
    }

    @Test
    fun testContinuationStrategySkip() {
        // Monday has Obadiah (1 ch), Tuesday has Jonah (4 ch)
        val booksByDay = mapOf(
            DayOfWeek.MONDAY to listOf(Bible.OBADIAH),
            DayOfWeek.TUESDAY to listOf(Bible.JONAH)
        )

        val strategy = DayOfWeekStrategy(
            chaptersPerDay = 3,
            booksByDay = booksByDay,
            continuationStrategy = ContinuationStrategy.SKIP
        )

        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5))
        // Monday only reads Obadiah 1, without borrowing from Tuesday!
        assertEquals(1, plan.readings[0].reading.size)
        assertEquals("Obadiah", plan.readings[0].reading[0].book)
    }

    @Test
    fun testCustomContinuationStrategyLambda() {
        // Custom continuation: Always borrow from Sunday first if available, else null
        val customContinuation = ContinuationStrategy { _, readingsByDay ->
            if (readingsByDay[DayOfWeek.SUNDAY]?.isNotEmpty() == true) DayOfWeek.SUNDAY else null
        }

        val booksByDay = mapOf(
            DayOfWeek.MONDAY to listOf(Bible.OBADIAH), // 1 ch
            DayOfWeek.SUNDAY to listOf(Bible.PHILEMON) // 1 ch
        )

        val strategy = DayOfWeekStrategy(
            chaptersPerDay = 2,
            booksByDay = booksByDay,
            continuationStrategy = customContinuation
        )

        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 5)) // Monday
        // Monday gets Obadiah 1, then custom continuation pulls Philemon 1 from Sunday!
        assertEquals(2, plan.readings[0].reading.size)
        assertEquals("Obadiah", plan.readings[0].reading[0].book)
        assertEquals("Philemon", plan.readings[0].reading[1].book)
    }

    @Test
    fun testWeekdayOnlyStrategy() {
        val strategy = WeekdayOnlyStrategy(
            chaptersPerWeekday = 3,
            books = listOf(Bible.GALATIANS) // 6 chapters -> 2 weekdays
        )

        // Start on Friday 2026-01-09
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 9))

        assertEquals(2, plan.readings.size)
        // Day 1: Friday Jan 9
        assertEquals(LocalDate.of(2026, 1, 9), plan.readings[0].date)
        assertEquals(DayOfWeek.FRIDAY, plan.readings[0].date.dayOfWeek)

        // Day 2: Next Monday Jan 12 (Skipped Saturday and Sunday!)
        assertEquals(LocalDate.of(2026, 1, 12), plan.readings[1].date)
        assertEquals(DayOfWeek.MONDAY, plan.readings[1].date.dayOfWeek)
    }

    @Test
    fun testArbitraryCustomStrategy() {
        val custom = CustomStrategy("One Chapter Every Other Day", "Reads 1 chapter every 2 days") { startDate ->
            val readings = listOf(
                org.bible.reading.dto.DailyReading(startDate, listOf(org.bible.reading.dto.Reading("Genesis", 1))),
                org.bible.reading.dto.DailyReading(startDate.plusDays(2), listOf(org.bible.reading.dto.Reading("Genesis", 2)))
            )
            org.bible.reading.dto.ReadingPlan(readings)
        }

        val plan = custom.generateReadingPlan(LocalDate.of(2026, 1, 1))
        assertEquals(2, plan.readings.size)
        assertEquals(LocalDate.of(2026, 1, 1), plan.readings[0].date)
        assertEquals(LocalDate.of(2026, 1, 3), plan.readings[1].date)
    }

    @Test
    fun testStrategyRegistry() {
        StrategyRegistry.clear()
        val custom = WeekdayOnlyStrategy(chaptersPerWeekday = 5)
        StrategyRegistry.registerStrategy("MyWeekdayPlan", custom)

        assertEquals(StrategyRegistry.getStrategy("MyWeekdayPlan"), custom)
        assertTrue(StrategyRegistry.getAllCustomStrategies().containsKey("MyWeekdayPlan"))

        StrategyRegistry.unregisterStrategy("MyWeekdayPlan")
        assertNull(StrategyRegistry.getStrategy("MyWeekdayPlan"))
    }

    @Test
    fun testFullBibleCanonicalPlanChapterIntegrity() {
        // Verify that the standard DayOfWeekStrategy with 3 ch/day and 5 on Sunday
        // assigns all 1189 chapters of the Bible exactly once with no duplicates
        val strategy = DayOfWeekStrategy()
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 1))

        val allReadings = plan.readings.flatMap { it.reading }
        assertEquals(1189, allReadings.size, "Total chapters read must equal Bible total of 1189")

        // Check for uniqueness of every (book, chapterNumber) pair
        val uniqueReadings = allReadings.map { "${it.book}:${it.chapterNumber}" }.toSet()
        assertEquals(1189, uniqueReadings.size, "All 1189 chapters must be distinct without repetition")

        // Verify first day is Isaiah 1 on Thursday 2026-01-01
        assertEquals("Isaiah", plan.readings[0].reading[0].book)
        assertEquals(1, plan.readings[0].reading[0].chapterNumber)
    }

    @Test
    fun testPlanStateEndToEndWorkflow() {
        val state = PlanState()

        // 1. Configure Strategy
        state.selectedStrategyChoice = PlanStrategyChoice.DAY_OF_WEEK

        // 2. Configure chapters per day
        state.chaptersPerDay = 4
        state.chaptersOnSunday = 6
        state.continuationStrategyType = ContinuationStrategyType.FIRST_AVAILABLE

        // 3. Customize books for a specific day
        state.clearDay(DayOfWeek.MONDAY)
        state.addBookToDay(DayOfWeek.MONDAY, Bible.GENESIS)

        // 4. Generate plan
        val plan = state.generatePlan()
        assertNotNull(state.readingPlan)
        assertTrue(plan.readings.isNotEmpty())

        // 5. Test search filter in state
        state.planSearchQuery = "Genesis"
        val filtered = plan.readings.filter { daily ->
            daily.reading.any { it.book.contains("Genesis", ignoreCase = true) }
        }
        assertTrue(filtered.isNotEmpty())
    }

    // =========================================================================
    // 4) Saving Reading Plan to File (User-Specified Directory, Default ~/downloads)
    // =========================================================================

    @Test
    fun testSaveReadingPlanToUserSpecifiedDirectory() {
        val tempDir = Files.createTempDirectory("reading_plan_test").toFile()
        tempDir.deleteOnExit()

        val strategy = SimpleSequentialStrategy(
            chaptersPerDay = 3,
            books = listOf(Bible.JONAH) // 4 chapters -> 2 days
        )
        val plan = strategy.generateReadingPlan(LocalDate.of(2026, 1, 1))

        // Save to custom directory in plain text
        val textFile = plan.saveToFile(tempDir.absolutePath, "jonah_plan", ExportFormat.TEXT)
        assertTrue(textFile.exists())
        assertEquals("jonah_plan.txt", textFile.name)
        val textContent = textFile.readText()
        assertTrue(textContent.contains("BIBLE READING PLAN"))
        assertTrue(textContent.contains("Jonah 1, 2, 3"))
        assertTrue(textContent.contains("Jonah 4"))

        // Save to custom directory in markdown
        val mdFile = plan.saveToFile(tempDir.absolutePath, "jonah_plan", ExportFormat.MARKDOWN)
        assertTrue(mdFile.exists())
        assertEquals("jonah_plan.md", mdFile.name)
        val mdContent = mdFile.readText()
        assertTrue(mdContent.contains("# Bible Reading Plan"))
        assertTrue(mdContent.contains("| Day | Date | Readings | Chapters |"))

        // Save to custom directory in CSV
        val csvFile = plan.saveToFile(tempDir.absolutePath, "jonah_plan", ExportFormat.CSV)
        assertTrue(csvFile.exists())
        assertEquals("jonah_plan.csv", csvFile.name)
        val csvContent = csvFile.readText()
        assertTrue(csvContent.contains("Day,Date,DayOfWeek,Book,ChapterNumber"))
        assertTrue(csvContent.contains("1,2026-01-01,THURSDAY,\"Jonah\",1"))
    }

    @Test
    fun testSaveReadingPlanDefaultDirectoryResolution() {
        val state = PlanState()
        // Default save directory is ~/downloads
        assertEquals("~/downloads", state.saveDirectory)

        // Generate a small plan
        state.clearDay(DayOfWeek.MONDAY)
        state.addBookToDay(DayOfWeek.MONDAY, Bible.OBADIAH)
        state.generatePlan()

        // Test saving with default ~/downloads
        val savedFile = state.savePlanToFile()
        assertNotNull(savedFile)
        assertTrue(savedFile.exists())

        val expectedDir = File(System.getProperty("user.home"), "downloads")
        assertEquals(expectedDir.absolutePath, savedFile.parentFile.absolutePath)
        assertTrue(state.isSaveSuccess)
        assertTrue(state.saveStatusMessage?.contains("successfully") == true)

        // Clean up generated test file
        savedFile.delete()
    }

    @Test
    fun testPlanStateSaveToCustomDirectoryWorkflow() {
        val tempDir = Files.createTempDirectory("plan_custom_dir").toFile()
        tempDir.deleteOnExit()

        val state = PlanState()
        state.saveDirectory = tempDir.absolutePath
        state.saveFileName = "my_custom_plan"
        state.saveFormat = ExportFormat.MARKDOWN

        state.clearDay(DayOfWeek.MONDAY)
        state.addBookToDay(DayOfWeek.MONDAY, Bible.PHILEMON)

        val savedFile = state.savePlanToFile()
        assertNotNull(savedFile)
        assertTrue(savedFile.exists())
        assertEquals("my_custom_plan.md", savedFile.name)
        assertEquals(tempDir.absolutePath, savedFile.parentFile.absolutePath)
        assertTrue(savedFile.readText().contains("# Bible Reading Plan"))
        assertTrue(savedFile.readText().contains("Philemon"))

        savedFile.delete()
    }
}

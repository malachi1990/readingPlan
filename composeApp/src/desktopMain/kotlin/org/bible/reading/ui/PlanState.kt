package org.bible.reading.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.ExportFormat
import org.bible.reading.dto.ReadingPlan
import org.bible.reading.jsword.SwordManager
import org.bible.reading.strategy.ContinuationStrategy
import org.bible.reading.strategy.ContinuationStrategyType
import org.bible.reading.strategy.DayOfWeekStrategy
import org.bible.reading.strategy.SimpleSequentialStrategy
import org.bible.reading.strategy.Strategy
import org.bible.reading.strategy.StrategyRegistry
import org.bible.reading.strategy.WeekdayOnlyStrategy
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

enum class PlanStrategyChoice(val displayName: String, val description: String) {
    DAY_OF_WEEK("Day of Week Plan", "Assign books to specific days of the week with configurable continuation rules"),
    SIMPLE_SEQUENTIAL("Simple Sequential", "Read straight through books from start to finish"),
    WEEKDAY_ONLY("Weekday Only", "Read on weekdays (Monday-Friday) and rest on weekends"),
    CUSTOM_STRATEGY("Custom Pluggable Strategy", "Use a registered or user-defined custom strategy")
}

class PlanState {
    // JSword Manager instance
    val swordManager = SwordManager()

    // Navigation & View states
    var viewingDailyReading by mutableStateOf<DailyReading?>(null)
    var showDownloadDialog by mutableStateOf(false)

    // Strategy selection
    var selectedStrategyChoice by mutableStateOf(PlanStrategyChoice.DAY_OF_WEEK)

    // Chapters per day configuration
    var chaptersPerDay by mutableStateOf(3)
    var chaptersOnSunday by mutableStateOf(5)
    var useCustomPerDayChapters by mutableStateOf(false)
    val perDayChapters = mutableStateMapOf<DayOfWeek, Int>().apply {
        DayOfWeek.entries.forEach { day ->
            put(day, if (day == DayOfWeek.SUNDAY) 5 else 3)
        }
    }

    // Books by day configuration (for Day of Week and custom day plans)
    val booksByDay = mutableStateMapOf<DayOfWeek, MutableList<Bible>>().apply {
        val defaultMap = DayOfWeekStrategy.defaultBooksByDay()
        DayOfWeek.entries.forEach { day ->
            put(day, (defaultMap[day] ?: emptyList()).toMutableList())
        }
    }

    // Currently focused day in the UI
    var activeDayOfWeek by mutableStateOf(DayOfWeek.MONDAY)

    // Continuation strategy when a day's assigned readings are finished
    var continuationStrategyType by mutableStateOf(ContinuationStrategyType.LOOKBACK)

    // Custom Completion by Day Order mappings (e.g. Sunday -> Wednesday, Friday -> Monday)
    val customDayOrderMapping = mutableStateMapOf<DayOfWeek, DayOfWeek>().apply {
        putAll(ContinuationStrategy.defaultDayOrderMapping())
    }

    // Custom Completion by Book Collection mappings (e.g. Wisdom -> Gospels_Acts)
    val customCollectionOrderMapping = mutableStateMapOf<BookCollection, BookCollection>().apply {
        putAll(ContinuationStrategy.defaultCollectionOrderMapping())
    }

    // Start date
    var startDate by mutableStateOf(LocalDate.of(2026, 1, 1))

    // Generated reading plan result
    var readingPlan by mutableStateOf<ReadingPlan?>(null)

    // Selected registered custom strategy name
    var selectedCustomStrategyName by mutableStateOf<String?>(null)

    // Filter/search in generated plan
    var planSearchQuery by mutableStateOf("")

    // File saving configuration (defaulting to ~/downloads)
    var saveDirectory by mutableStateOf("~/downloads")
    var saveFileName by mutableStateOf("reading_plan")
    var saveFormat by mutableStateOf(ExportFormat.TEXT)
    var saveStatusMessage by mutableStateOf<String?>(null)
    var isSaveSuccess by mutableStateOf(true)
    var lastSavedFile by mutableStateOf<File?>(null)

    fun navigateDailyReading(offset: Int) {
        val plan = readingPlan ?: return
        val current = viewingDailyReading ?: return
        val currentIndex = plan.readings.indexOf(current)
        if (currentIndex != -1) {
            val newIndex = currentIndex + offset
            if (newIndex in plan.readings.indices) {
                viewingDailyReading = plan.readings[newIndex]
            }
        }
    }

    fun hasPreviousDailyReading(): Boolean {
        val plan = readingPlan ?: return false
        val current = viewingDailyReading ?: return false
        val currentIndex = plan.readings.indexOf(current)
        return currentIndex > 0
    }

    fun hasNextDailyReading(): Boolean {
        val plan = readingPlan ?: return false
        val current = viewingDailyReading ?: return false
        val currentIndex = plan.readings.indexOf(current)
        return currentIndex != -1 && currentIndex < plan.readings.lastIndex
    }

    fun addBookToDay(day: DayOfWeek, book: Bible) {
        val list = booksByDay.getOrPut(day) { mutableListOf() }
        if (!list.contains(book)) {
            list.add(book)
            booksByDay[day] = list.toMutableList()
        }
    }

    fun removeBookFromDay(day: DayOfWeek, book: Bible) {
        val list = booksByDay[day] ?: return
        list.remove(book)
        booksByDay[day] = list.toMutableList()
    }

    fun addCollectionToDay(day: DayOfWeek, collection: BookCollection) {
        val booksToAdd = Bible.getBooksInOrder().filter { it.collection == collection }
        val list = booksByDay.getOrPut(day) { mutableListOf() }
        for (b in booksToAdd) {
            if (!list.contains(b)) {
                list.add(b)
            }
        }
        booksByDay[day] = list.toMutableList()
    }

    fun clearDay(day: DayOfWeek) {
        booksByDay[day] = mutableListOf()
    }

    fun resetToCanonicalSchedule() {
        val defaultMap = DayOfWeekStrategy.defaultBooksByDay()
        DayOfWeek.entries.forEach { day ->
            booksByDay[day] = (defaultMap[day] ?: emptyList()).toMutableList()
        }
    }

    fun setPerDayChapter(day: DayOfWeek, chapters: Int) {
        perDayChapters[day] = chapters.coerceAtLeast(1)
    }

    fun getTotalChaptersForDay(day: DayOfWeek): Int {
        return booksByDay[day]?.sumOf { it.chapters } ?: 0
    }

    fun buildContinuationStrategy(): ContinuationStrategy {
        return when (continuationStrategyType) {
            ContinuationStrategyType.LOOKBACK -> ContinuationStrategy.LOOKBACK
            ContinuationStrategyType.LOOKFORWARD -> ContinuationStrategy.LOOKFORWARD
            ContinuationStrategyType.FIRST_AVAILABLE -> ContinuationStrategy.FIRST_AVAILABLE
            ContinuationStrategyType.SKIP -> ContinuationStrategy.SKIP
            ContinuationStrategyType.CUSTOM_DAY_ORDER -> ContinuationStrategy.byDayOrder(customDayOrderMapping.toMap())
            ContinuationStrategyType.CUSTOM_BOOK_COLLECTION -> ContinuationStrategy.byBookCollection(
                booksByDay.mapValues { it.value.toList() },
                customCollectionOrderMapping.toMap()
            )
            ContinuationStrategyType.CUSTOM -> ContinuationStrategy.byDayOrder(customDayOrderMapping.toMap())
        }
    }

    fun buildCurrentStrategy(): Strategy {
        val contStrategy = buildContinuationStrategy()
        return when (selectedStrategyChoice) {
            PlanStrategyChoice.DAY_OF_WEEK -> {
                val dayMap = if (useCustomPerDayChapters) perDayChapters.toMap() else null
                DayOfWeekStrategy(
                    chaptersPerDay = chaptersPerDay,
                    chaptersOnSunday = chaptersOnSunday,
                    chaptersPerDayOfWeek = dayMap,
                    booksByDay = booksByDay.mapValues { it.value.toList() },
                    continuationStrategy = contStrategy
                )
            }
            PlanStrategyChoice.SIMPLE_SEQUENTIAL -> {
                val dayMap = if (useCustomPerDayChapters) perDayChapters.toMap() else null
                SimpleSequentialStrategy(
                    chaptersPerDay = chaptersPerDay,
                    chaptersOnSunday = chaptersOnSunday,
                    chaptersPerDayOfWeek = dayMap
                )
            }
            PlanStrategyChoice.WEEKDAY_ONLY -> {
                WeekdayOnlyStrategy(chaptersPerWeekday = chaptersPerDay)
            }
            PlanStrategyChoice.CUSTOM_STRATEGY -> {
                selectedCustomStrategyName?.let { StrategyRegistry.getStrategy(it) }
                    ?: DayOfWeekStrategy(
                        chaptersPerDay = chaptersPerDay,
                        chaptersOnSunday = chaptersOnSunday,
                        booksByDay = booksByDay.mapValues { it.value.toList() },
                        continuationStrategy = contStrategy
                    )
            }
        }
    }

    fun generatePlan(): ReadingPlan {
        val strategy = buildCurrentStrategy()
        val plan = strategy.generateReadingPlan(startDate)
        readingPlan = plan
        return plan
    }

    fun savePlanToFile(): File? {
        val plan = readingPlan ?: generatePlan()
        return try {
            val file = plan.saveToFile(
                targetDirectory = saveDirectory.ifBlank { "~/downloads" },
                fileName = saveFileName.ifBlank { "reading_plan" },
                format = saveFormat
            )
            lastSavedFile = file
            isSaveSuccess = true
            saveStatusMessage = "Plan successfully saved to ${file.absolutePath}"
            file
        } catch (e: Exception) {
            isSaveSuccess = false
            saveStatusMessage = "Failed to save plan: ${e.message}"
            null
        }
    }
}

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
import org.bible.reading.audio.service.AudioBibleService
import org.bible.reading.strategy.ContinuationStrategy
import org.bible.reading.strategy.ContinuationStrategyType
import org.bible.reading.strategy.DayOfWeekStrategy
import org.bible.reading.strategy.SimpleSequentialStrategy
import org.bible.reading.strategy.Strategy
import org.bible.reading.strategy.WeekdayOnlyStrategy
import org.bible.reading.tts.TtsService
import org.bible.reading.ui.theme.AppThemeMode
import org.bible.reading.ui.theme.AppearancePreferences
import org.bible.reading.ui.theme.AppearancePreferencesManager
import org.bible.reading.ui.theme.CustomThemeColors
import org.bible.reading.ui.theme.FontCatalog
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

enum class MainAppTab(val title: String) {
    READING_PLAN("Bible Reading plan"),
    FURTHER_STUDY("Further Study")
}

enum class PlanStrategyChoice(val displayName: String, val description: String) {
    DAY_OF_WEEK("Day of Week Plan", "Assign books to specific days of the week with configurable continuation rules"),
    SIMPLE_SEQUENTIAL("Sequential", "Read straight through books from start to finish"),
    WEEKDAY_ONLY("Weekday Only", "Read on weekdays (Monday-Friday) and rest on weekends")
}

class PlanState(planStorageDir: File? = null) {
    // JSword Manager instance
    val swordManager = SwordManager()

    // Repository for persisting/loading the active reading plan in the app directory
    val planRepository = ReadingPlanRepository(planStorageDir ?: swordManager.appDir)

    // Navigation Tab
    var currentTab by mutableStateOf(MainAppTab.READING_PLAN)

    // Navigation & View states
    var viewingDailyReading by mutableStateOf<DailyReading?>(null)
    var showDownloadDialog by mutableStateOf(false)

    // In-session module, chapter/section, and scroll tracking across tab switches
    val sessionReadingState = SessionReadingState()
    var addBooksInitialTab by mutableStateOf(0)

    fun openAddBooksDialog(tab: Int = 0) {
        addBooksInitialTab = tab.coerceIn(0, 2)
        showDownloadDialog = true
    }

    // Audio Bible Service & settings dialog
    val audioService: AudioBibleService = AudioBibleService(swordManager.appDir)
    var showAudioSettingsDialog by mutableStateOf(false)

    // Text-to-Speech Service & settings dialog
    val ttsService: TtsService = TtsService(swordManager.appDir)
    var showTtsSettingsDialog by mutableStateOf(false)
    var audioTtsSettingsInitialTab by mutableStateOf(0)

    fun openAudioTtsSettingsDialog(tab: Int = 0) {
        audioTtsSettingsInitialTab = tab.coerceIn(0, 1)
        showTtsSettingsDialog = true
    }

    fun openSettingsDialog(tab: Int = 2) {
        audioTtsSettingsInitialTab = tab.coerceIn(0, 2)
        showTtsSettingsDialog = true
    }

    // Appearance (Theme & Font) preferences
    private val appearancePreferencesManager = AppearancePreferencesManager(planStorageDir ?: swordManager.appDir)
    var appearancePreferences by mutableStateOf(appearancePreferencesManager.loadPreferences())
        private set

    fun setThemeMode(mode: AppThemeMode) {
        val updated = appearancePreferences.copy(themeMode = mode)
        appearancePreferences = updated
        appearancePreferencesManager.savePreferences(updated)
    }

    fun setFontSelection(fontSelection: String) {
        val updated = appearancePreferences.copy(fontSelection = FontCatalog.normalizeSelection(fontSelection))
        appearancePreferences = updated
        appearancePreferencesManager.savePreferences(updated)
    }

    fun setCustomThemeColors(colors: CustomThemeColors, persist: Boolean = true) {
        val updated = appearancePreferences.copy(customColors = colors)
        appearancePreferences = updated
        if (persist) {
            appearancePreferencesManager.savePreferences(updated)
        }
    }

    fun persistAppearancePreferences() {
        appearancePreferencesManager.savePreferences(appearancePreferences)
    }

    fun resetAppearancePreferences() {
        val defaults = AppearancePreferences()
        appearancePreferences = defaults
        appearancePreferencesManager.savePreferences(defaults)
    }

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

    init {
        loadSavedPlanIfPresent()
    }

    /**
     * Checks the app directory for a previously saved reading plan and loads it (along with its configuration).
     * Returns true if a saved plan was found and loaded.
     */
    fun loadSavedPlanIfPresent(): Boolean {
        val snapshot = planRepository.loadPlan() ?: return false
        snapshot.selectedStrategyChoice?.let { selectedStrategyChoice = it }
        snapshot.chaptersPerDay?.let { chaptersPerDay = it }
        snapshot.chaptersOnSunday?.let { chaptersOnSunday = it }
        snapshot.useCustomPerDayChapters?.let { useCustomPerDayChapters = it }
        snapshot.perDayChapters.forEach { (day, ch) -> perDayChapters[day] = ch }
        snapshot.booksByDay.forEach { (day, books) -> booksByDay[day] = books.toMutableList() }
        snapshot.continuationStrategyType?.let { continuationStrategyType = it }
        snapshot.customDayOrderMapping.forEach { (day, target) -> customDayOrderMapping[day] = target }
        snapshot.customCollectionOrderMapping.forEach { (col, target) -> customCollectionOrderMapping[col] = target }
        snapshot.startDate?.let { startDate = it }
        selectedCustomStrategyName = snapshot.selectedCustomStrategyName
        readingPlan = snapshot.plan
        return true
    }

    /**
     * Persists [plan] (or the current [readingPlan]) and current configuration into the app directory.
     */
    fun persistPlanToAppDirectory(plan: ReadingPlan? = readingPlan): File? {
        val targetPlan = plan ?: return null
        return planRepository.savePlan(
            SavedPlanSnapshot(
                plan = targetPlan,
                selectedStrategyChoice = selectedStrategyChoice,
                chaptersPerDay = chaptersPerDay,
                chaptersOnSunday = chaptersOnSunday,
                useCustomPerDayChapters = useCustomPerDayChapters,
                perDayChapters = perDayChapters.toMap(),
                booksByDay = booksByDay.mapValues { it.value.toList() },
                continuationStrategyType = continuationStrategyType,
                customDayOrderMapping = customDayOrderMapping.toMap(),
                customCollectionOrderMapping = customCollectionOrderMapping.toMap(),
                startDate = startDate,
                selectedCustomStrategyName = selectedCustomStrategyName
            )
        )
    }

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
        val current = booksByDay[day].orEmpty()
        if (!current.contains(book)) {
            booksByDay[day] = (current + book).toMutableList()
        }
    }

    fun removeBookFromDay(day: DayOfWeek, book: Bible) {
        val current = booksByDay[day] ?: return
        val updated = current.filterTo(mutableListOf()) { it != book }
        if (updated.size != current.size) {
            booksByDay[day] = updated
        }
    }

    fun addCollectionToDay(day: DayOfWeek, collection: BookCollection) {
        val booksToAdd = Bible.getBooksInOrder().filter { it.collection == collection }
        val updated = booksByDay[day].orEmpty().toMutableList()
        var changed = false
        for (b in booksToAdd) {
            if (!updated.contains(b)) {
                updated.add(b)
                changed = true
            }
        }
        if (changed) {
            booksByDay[day] = updated
        }
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
        }
    }

    fun generatePlan(): ReadingPlan {
        val strategy = buildCurrentStrategy()
        val plan = strategy.generateReadingPlan(startDate)
        readingPlan = plan
        persistPlanToAppDirectory(plan)
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

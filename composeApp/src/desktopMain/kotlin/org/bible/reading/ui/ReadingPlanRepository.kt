package org.bible.reading.ui

import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import org.bible.reading.strategy.ContinuationStrategyType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.LocalDate

data class SavedPlanSnapshot(
    val plan: ReadingPlan,
    val selectedStrategyChoice: PlanStrategyChoice? = null,
    val chaptersPerDay: Int? = null,
    val chaptersOnSunday: Int? = null,
    val useCustomPerDayChapters: Boolean? = null,
    val perDayChapters: Map<DayOfWeek, Int> = emptyMap(),
    val booksByDay: Map<DayOfWeek, List<Bible>> = emptyMap(),
    val continuationStrategyType: ContinuationStrategyType? = null,
    val customDayOrderMapping: Map<DayOfWeek, DayOfWeek> = emptyMap(),
    val customCollectionOrderMapping: Map<BookCollection, BookCollection> = emptyMap(),
    val startDate: LocalDate? = null,
    val selectedCustomStrategyName: String? = null
)

/**
 * Persists and loads the active generated [ReadingPlan] and its configuration in `<appDir>/saved_reading_plan.dat`.
 */
class ReadingPlanRepository(private val appDir: File) {

    companion object {
        const val SAVED_PLAN_FILE_NAME = "saved_reading_plan.dat"
        private const val READINGS_SEPARATOR = "---READINGS---"
    }

    val planFile: File
        get() = File(appDir, SAVED_PLAN_FILE_NAME)

    fun hasSavedPlan(): Boolean = planFile.exists() && planFile.isFile && planFile.length() > 0L

    fun deleteSavedPlan(): Boolean {
        return if (planFile.exists()) planFile.delete() else false
    }

    fun savePlan(snapshot: SavedPlanSnapshot): File? {
        if (snapshot.plan.readings.isEmpty()) return null
        return try {
            if (!appDir.exists()) {
                appDir.mkdirs()
            }

            val content = buildString {
                appendLine("version=1")
                snapshot.selectedStrategyChoice?.let {
                    appendLine("selectedStrategyChoice=${it.name}")
                }
                snapshot.chaptersPerDay?.let {
                    appendLine("chaptersPerDay=$it")
                }
                snapshot.chaptersOnSunday?.let {
                    appendLine("chaptersOnSunday=$it")
                }
                snapshot.useCustomPerDayChapters?.let {
                    appendLine("useCustomPerDayChapters=$it")
                }
                snapshot.continuationStrategyType?.let {
                    appendLine("continuationStrategyType=${it.name}")
                }
                snapshot.startDate?.let {
                    appendLine("startDate=$it")
                }
                appendLine("selectedCustomStrategyName=${encodeSingleLine(snapshot.selectedCustomStrategyName.orEmpty())}")

                DayOfWeek.entries.forEach { day ->
                    snapshot.perDayChapters[day]?.let { ch ->
                        appendLine("perDayChapters.${day.name}=$ch")
                    }
                }

                DayOfWeek.entries.forEach { day ->
                    if (snapshot.booksByDay.containsKey(day)) {
                        val booksStr = snapshot.booksByDay[day].orEmpty().joinToString(",") { it.name }
                        appendLine("booksByDay.${day.name}=$booksStr")
                    }
                }

                DayOfWeek.entries.forEach { day ->
                    snapshot.customDayOrderMapping[day]?.let { targetDay ->
                        appendLine("customDayOrderMapping.${day.name}=${targetDay.name}")
                    }
                }

                BookCollection.entries.forEach { col ->
                    snapshot.customCollectionOrderMapping[col]?.let { targetCol ->
                        appendLine("customCollectionOrderMapping.${col.name}=${targetCol.name}")
                    }
                }

                appendLine(READINGS_SEPARATOR)

                for (daily in snapshot.plan.readings) {
                    val entries = daily.reading.joinToString("\t") { reading ->
                        "${encodeReadingBook(reading.book)}:${reading.chapterNumber}"
                    }
                    if (entries.isNotEmpty()) {
                        appendLine("${daily.date}\t$entries")
                    } else {
                        appendLine("${daily.date}")
                    }
                }
            }

            val tmpFile = File(appDir, "$SAVED_PLAN_FILE_NAME.tmp")
            tmpFile.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    tmpFile.toPath(),
                    planFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    tmpFile.toPath(),
                    planFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            planFile
        } catch (e: Exception) {
            println("Failed to save reading plan to ${planFile.absolutePath}: ${e.message}")
            null
        }
    }

    fun loadPlan(): SavedPlanSnapshot? {
        if (!hasSavedPlan()) return null
        return try {
            val lines = planFile.readLines(Charsets.UTF_8)
            val separatorIdx = lines.indexOf(READINGS_SEPARATOR)
            if (separatorIdx < 0) return null

            val headerMap = mutableMapOf<String, String>()
            for (i in 0 until separatorIdx) {
                val line = lines[i]
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0) {
                    val key = line.substring(0, eqIdx).trim()
                    val value = line.substring(eqIdx + 1)
                    headerMap[key] = value
                }
            }

            val dailyReadings = mutableListOf<DailyReading>()
            for (i in (separatorIdx + 1) until lines.size) {
                val line = lines[i].trimEnd('\r')
                if (line.isBlank()) continue
                val parts = line.split('\t')
                val date = try {
                    LocalDate.parse(parts[0].trim())
                } catch (_: Exception) {
                    continue
                }
                val readings = mutableListOf<Reading>()
                for (j in 1 until parts.size) {
                    val token = parts[j]
                    val colonIdx = token.lastIndexOf(':')
                    if (colonIdx > 0) {
                        val book = decodeReadingBook(token.substring(0, colonIdx))
                        val chapter = token.substring(colonIdx + 1).toIntOrNull()
                        if (book.isNotEmpty() && chapter != null) {
                            readings.add(Reading(book = book, chapterNumber = chapter))
                        }
                    }
                }
                dailyReadings.add(DailyReading(date = date, reading = readings))
            }

            if (dailyReadings.isEmpty()) return null

            val selectedStrategyChoice = headerMap["selectedStrategyChoice"]?.let {
                runCatching { PlanStrategyChoice.valueOf(it) }.getOrNull()
            }
            val chaptersPerDay = headerMap["chaptersPerDay"]?.toIntOrNull()?.coerceAtLeast(1)
            val chaptersOnSunday = headerMap["chaptersOnSunday"]?.toIntOrNull()?.coerceAtLeast(1)
            val useCustomPerDayChapters = headerMap["useCustomPerDayChapters"]?.toBooleanStrictOrNull()
            val continuationStrategyType = headerMap["continuationStrategyType"]?.let {
                runCatching { ContinuationStrategyType.valueOf(it) }.getOrNull()
            }
            val startDate = headerMap["startDate"]?.let {
                runCatching { LocalDate.parse(it.trim()) }.getOrNull()
            }
            val selectedCustomStrategyName = headerMap["selectedCustomStrategyName"]
                ?.let { decodeSingleLine(it) }
                ?.takeIf { it.isNotBlank() }

            val perDayChapters = mutableMapOf<DayOfWeek, Int>()
            val booksByDay = mutableMapOf<DayOfWeek, List<Bible>>()
            val customDayOrderMapping = mutableMapOf<DayOfWeek, DayOfWeek>()
            DayOfWeek.entries.forEach { day ->
                headerMap["perDayChapters.${day.name}"]?.toIntOrNull()?.let { ch ->
                    perDayChapters[day] = ch.coerceAtLeast(1)
                }
                if (headerMap.containsKey("booksByDay.${day.name}")) {
                    val rawBooks = headerMap["booksByDay.${day.name}"].orEmpty()
                    val parsedBooks = if (rawBooks.isBlank()) {
                        emptyList()
                    } else {
                        rawBooks.split(',').mapNotNull { token ->
                            runCatching { Bible.valueOf(token.trim()) }.getOrNull()
                        }
                    }
                    booksByDay[day] = parsedBooks
                }
                headerMap["customDayOrderMapping.${day.name}"]?.let { target ->
                    runCatching { DayOfWeek.valueOf(target.trim()) }.getOrNull()?.let { targetDay ->
                        customDayOrderMapping[day] = targetDay
                    }
                }
            }

            val customCollectionOrderMapping = mutableMapOf<BookCollection, BookCollection>()
            BookCollection.entries.forEach { col ->
                headerMap["customCollectionOrderMapping.${col.name}"]?.let { target ->
                    runCatching { BookCollection.valueOf(target.trim()) }.getOrNull()?.let { targetCol ->
                        customCollectionOrderMapping[col] = targetCol
                    }
                }
            }

            SavedPlanSnapshot(
                plan = ReadingPlan(dailyReadings),
                selectedStrategyChoice = selectedStrategyChoice,
                chaptersPerDay = chaptersPerDay,
                chaptersOnSunday = chaptersOnSunday,
                useCustomPerDayChapters = useCustomPerDayChapters,
                perDayChapters = perDayChapters,
                booksByDay = booksByDay,
                continuationStrategyType = continuationStrategyType,
                customDayOrderMapping = customDayOrderMapping,
                customCollectionOrderMapping = customCollectionOrderMapping,
                startDate = startDate,
                selectedCustomStrategyName = selectedCustomStrategyName
            )
        } catch (e: Exception) {
            println("Failed to load reading plan from ${planFile.absolutePath}: ${e.message}")
            null
        }
    }

    private fun encodeSingleLine(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun decodeSingleLine(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c).append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun encodeReadingBook(book: String): String =
        book.replace("%", "%25").replace("\t", "%09").replace(":", "%3A")

    private fun decodeReadingBook(encoded: String): String =
        encoded.replace("%3A", ":").replace("%09", "\t").replace("%25", "%")
}

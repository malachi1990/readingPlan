package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.Reading
import java.time.DayOfWeek
import java.time.LocalDate

enum class ContinuationStrategyType(val displayName: String, val description: String) {
    LOOKBACK("Look Back", "Continue from previous days in reverse chronological order"),
    LOOKFORWARD("Look Forward", "Continue from upcoming days in forward chronological order"),
    FIRST_AVAILABLE("First Available Day", "Continue from the earliest day in the week (Monday through Sunday)"),
    SKIP("Skip / Rest", "Do not continue readings on days whose assigned books are finished"),
    CUSTOM_DAY_ORDER("Custom: By Day Order", "When a day completes, select the configured next day (e.g. Sunday -> Wednesday)"),
    CUSTOM_BOOK_COLLECTION("Custom: By Book Collection", "When a collection completes, select the configured next collection (e.g. Wisdom -> Gospels)"),
    CUSTOM("Custom Strategy", "User-defined continuation strategy rule")
}

fun interface ContinuationStrategy {
    /**
     * Determines which DayOfWeek to pull readings from when the currentDay's reading pool is exhausted.
     * Returns null if no readings should be pulled (e.g. SKIP or no remaining books).
     */
    fun findNextDay(
        currentDate: LocalDate,
        readingsByDay: Map<DayOfWeek, List<Reading>>
    ): DayOfWeek?

    companion object {
        /**
         * Look backwards from currentDate (minusDays(1), minusDays(2)...)
         * until a day with remaining readings is found.
         */
        val LOOKBACK: ContinuationStrategy = ContinuationStrategy { currentDate, readingsByDay ->
            for (offset in 1L..7L) {
                val candidate = currentDate.minusDays(offset).dayOfWeek
                if (readingsByDay[candidate]?.isNotEmpty() == true) {
                    return@ContinuationStrategy candidate
                }
            }
            null
        }

        /**
         * Look forward from currentDate (plusDays(1), plusDays(2)...)
         * until a day with remaining readings is found.
         */
        val LOOKFORWARD: ContinuationStrategy = ContinuationStrategy { currentDate, readingsByDay ->
            for (offset in 1L..7L) {
                val candidate = currentDate.plusDays(offset).dayOfWeek
                if (readingsByDay[candidate]?.isNotEmpty() == true) {
                    return@ContinuationStrategy candidate
                }
            }
            null
        }

        /**
         * Pick the earliest day in the week (Monday -> Sunday) that has remaining readings.
         */
        val FIRST_AVAILABLE: ContinuationStrategy = ContinuationStrategy { _, readingsByDay ->
            DayOfWeek.entries.firstOrNull { readingsByDay[it]?.isNotEmpty() == true }
        }

        /**
         * Skip: Do not pull readings from other days once today's assigned books are exhausted.
         */
        val SKIP: ContinuationStrategy = ContinuationStrategy { _, _ -> null }

        /**
         * Completion by Day Order:
         * Follows user-defined fallback mappings for each day of the week.
         * E.g. "if Sunday readings are complete, select the next Wednesday reading."
         * E.g. "if Friday readings are done, select the next Monday reading."
         */
        fun byDayOrder(
            fallbackOrder: Map<DayOfWeek, DayOfWeek>
        ): ContinuationStrategy = ContinuationStrategy { currentDate, readingsByDay ->
            val startDay = currentDate.dayOfWeek
            var candidate: DayOfWeek? = fallbackOrder[startDay] ?: startDay
            val visited = mutableSetOf<DayOfWeek>()

            while (candidate != null && !visited.contains(candidate)) {
                visited.add(candidate)
                if (readingsByDay[candidate]?.isNotEmpty() == true) {
                    return@ContinuationStrategy candidate
                }
                candidate = fallbackOrder[candidate]
            }

            // Fallback: any day that still has readings
            DayOfWeek.entries.firstOrNull { readingsByDay[it]?.isNotEmpty() == true }
        }

        /**
         * Completion by Book Collection:
         * When a collection completes, select readings from the user-configured target collection.
         * E.g. "if wisdom literature is done, select the next reading from the gospels."
         */
        fun byBookCollection(
            booksByDay: Map<DayOfWeek, List<Bible>>,
            collectionFallbackOrder: Map<BookCollection, BookCollection>
        ): ContinuationStrategy = ContinuationStrategy { currentDate, readingsByDay ->
            val todayBooks = booksByDay[currentDate.dayOfWeek] ?: emptyList()
            val todayCollection = todayBooks.firstOrNull()?.collection
                ?: BookCollection.entries.firstOrNull()

            var targetCollection: BookCollection? = collectionFallbackOrder[todayCollection]
            val visited = mutableSetOf<BookCollection>()

            while (targetCollection != null && !visited.contains(targetCollection)) {
                visited.add(targetCollection)
                // Search across days for one that has readings from targetCollection
                val dayWithTarget = DayOfWeek.entries.firstOrNull { day ->
                    val pool = readingsByDay[day] ?: emptyList()
                    pool.any { r ->
                        val bookEnum = Bible.fromBookName(r.book)
                        bookEnum?.collection == targetCollection
                    }
                }
                if (dayWithTarget != null) {
                    return@ContinuationStrategy dayWithTarget
                }
                targetCollection = collectionFallbackOrder[targetCollection]
            }

            // Fallback: any day that still has readings
            DayOfWeek.entries.firstOrNull { readingsByDay[it]?.isNotEmpty() == true }
        }

        fun fromType(type: ContinuationStrategyType): ContinuationStrategy = when (type) {
            ContinuationStrategyType.LOOKBACK -> LOOKBACK
            ContinuationStrategyType.LOOKFORWARD -> LOOKFORWARD
            ContinuationStrategyType.FIRST_AVAILABLE -> FIRST_AVAILABLE
            ContinuationStrategyType.SKIP -> SKIP
            ContinuationStrategyType.CUSTOM_DAY_ORDER -> byDayOrder(defaultDayOrderMapping())
            ContinuationStrategyType.CUSTOM_BOOK_COLLECTION -> byBookCollection(DayOfWeekStrategy.defaultBooksByDay(), defaultCollectionOrderMapping())
            ContinuationStrategyType.CUSTOM -> byDayOrder(defaultDayOrderMapping())
        }

        fun defaultDayOrderMapping(): Map<DayOfWeek, DayOfWeek> = mapOf(
            DayOfWeek.SUNDAY to DayOfWeek.WEDNESDAY,
            DayOfWeek.MONDAY to DayOfWeek.TUESDAY,
            DayOfWeek.TUESDAY to DayOfWeek.WEDNESDAY,
            DayOfWeek.WEDNESDAY to DayOfWeek.THURSDAY,
            DayOfWeek.THURSDAY to DayOfWeek.FRIDAY,
            DayOfWeek.FRIDAY to DayOfWeek.MONDAY,
            DayOfWeek.SATURDAY to DayOfWeek.SUNDAY
        )

        fun defaultCollectionOrderMapping(): Map<BookCollection, BookCollection> = mapOf(
            BookCollection.Torah to BookCollection.History_1,
            BookCollection.History_1 to BookCollection.History_2,
            BookCollection.History_2 to BookCollection.Prophets,
            BookCollection.Prophets to BookCollection.Minor_Prophets,
            BookCollection.Minor_Prophets to BookCollection.Gospels_Acts,
            BookCollection.Wisdom to BookCollection.Gospels_Acts,
            BookCollection.Gospels_Acts to BookCollection.Epistles,
            BookCollection.Epistles to BookCollection.Wisdom
        )
    }
}

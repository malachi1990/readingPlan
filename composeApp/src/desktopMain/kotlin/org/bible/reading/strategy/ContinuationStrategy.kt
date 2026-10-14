package org.bible.reading.strategy

import org.bible.reading.dto.Reading
import java.time.DayOfWeek
import java.time.LocalDate

enum class ContinuationStrategyType(val displayName: String, val description: String) {
    LOOKBACK("Look Back", "Continue from previous days in reverse chronological order"),
    LOOKFORWARD("Look Forward", "Continue from upcoming days in forward chronological order"),
    FIRST_AVAILABLE("First Available Day", "Continue from the earliest day in the week (Monday through Sunday)"),
    SKIP("Skip / Rest", "Do not continue readings on days whose assigned books are finished"),
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

        fun fromType(type: ContinuationStrategyType): ContinuationStrategy = when (type) {
            ContinuationStrategyType.LOOKBACK -> LOOKBACK
            ContinuationStrategyType.LOOKFORWARD -> LOOKFORWARD
            ContinuationStrategyType.FIRST_AVAILABLE -> FIRST_AVAILABLE
            ContinuationStrategyType.SKIP -> SKIP
            ContinuationStrategyType.CUSTOM -> LOOKBACK
        }
    }
}

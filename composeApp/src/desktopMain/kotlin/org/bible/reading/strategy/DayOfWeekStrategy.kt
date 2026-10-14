package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.EnumMap

open class DayOfWeekStrategy(
    val chaptersPerDay: Int = 3,
    val chaptersOnSunday: Int = 5,
    val chaptersPerDayOfWeek: Map<DayOfWeek, Int>? = null,
    val booksByDay: Map<DayOfWeek, List<Bible>> = defaultBooksByDay(),
    val continuationStrategy: ContinuationStrategy = ContinuationStrategy.LOOKBACK
) : Strategy {

    companion object {
        fun defaultBooksByDay(): Map<DayOfWeek, List<Bible>> {
            val books = Bible.getBooksInOrder()
            return mapOf(
                DayOfWeek.MONDAY to books.filter { it.collection == BookCollection.Torah },
                DayOfWeek.TUESDAY to books.filter { it.collection == BookCollection.History_1 },
                DayOfWeek.WEDNESDAY to books.filter { it.collection == BookCollection.History_2 },
                DayOfWeek.THURSDAY to books.filter { it.collection == BookCollection.Prophets },
                DayOfWeek.FRIDAY to books.filter { it.collection == BookCollection.Gospels_Acts },
                DayOfWeek.SATURDAY to books.filter { it.collection == BookCollection.Epistles },
                DayOfWeek.SUNDAY to books.filter { it.collection == BookCollection.Wisdom },
            )
        }
    }

    override fun generateReadingPlan(startDate: LocalDate): ReadingPlan {
        val readingsByDay = getReadings()
        val dailyReadings = mutableListOf<DailyReading>()
        var currentDate = startDate

        while (readingsByDay.values.any { it.isNotEmpty() }) {
            val targetChapters = chaptersPerDayOfWeek?.get(currentDate.dayOfWeek)
                ?: if (currentDate.dayOfWeek == DayOfWeek.SUNDAY) chaptersOnSunday else chaptersPerDay

            val currentReading = mutableListOf<Reading>()
            var activeDay: DayOfWeek? = currentDate.dayOfWeek

            while (currentReading.size < targetChapters && readingsByDay.values.any { it.isNotEmpty() }) {
                val pool = activeDay?.let { readingsByDay[it] }
                if (pool != null && pool.isNotEmpty()) {
                    val needed = targetChapters - currentReading.size
                    val count = needed.coerceAtMost(pool.size)
                    for (i in 0 until count) {
                        currentReading.add(pool.removeAt(0))
                    }
                } else {
                    activeDay = continuationStrategy.findNextDay(currentDate, readingsByDay)
                    if (activeDay == null || readingsByDay[activeDay]?.isEmpty() == true) {
                        break
                    }
                }
            }

            if (currentReading.isNotEmpty()) {
                dailyReadings.add(DailyReading(currentDate, currentReading))
            }
            currentDate = currentDate.plusDays(1)
        }

        return ReadingPlan(dailyReadings)
    }

    private fun getReadings(): Map<DayOfWeek, MutableList<Reading>> {
        val readingsByDay = EnumMap<DayOfWeek, MutableList<Reading>>(DayOfWeek::class.java)
        for (day in DayOfWeek.entries) {
            val books = booksByDay[day] ?: emptyList()
            readingsByDay[day] = generateReadings(books)
        }
        return readingsByDay
    }
}

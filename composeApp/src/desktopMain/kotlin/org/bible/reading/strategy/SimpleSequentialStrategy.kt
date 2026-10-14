package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.DayOfWeek
import java.time.LocalDate

class SimpleSequentialStrategy(
    val chaptersPerDay: Int = 3,
    val chaptersOnSunday: Int = 5,
    val chaptersPerDayOfWeek: Map<DayOfWeek, Int>? = null,
    val books: List<Bible> = Bible.getBooksInOrder()
) : Strategy {

    override fun generateReadingPlan(startDate: LocalDate): ReadingPlan {
        val readings = generateReadings(books)
        var iterator = 0
        var currentDate = startDate
        val dailyReadings = mutableListOf<DailyReading>()

        while (iterator < readings.size) {
            val chaptersToRead = chaptersPerDayOfWeek?.get(currentDate.dayOfWeek)
                ?: if (currentDate.dayOfWeek == DayOfWeek.SUNDAY) chaptersOnSunday else chaptersPerDay

            val currentReading = mutableListOf<Reading>()
            for (i in 1..chaptersToRead) {
                currentReading.add(readings[iterator])
                iterator++
                if (iterator >= readings.size) {
                    break
                }
            }
            dailyReadings.add(DailyReading(currentDate, currentReading))
            currentDate = currentDate.plusDays(1)
        }
        return ReadingPlan(dailyReadings)
    }
}

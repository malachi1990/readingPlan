package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Strategy that assigns readings only on weekdays (Monday through Friday),
 * resting on weekends (Saturday and Sunday).
 */
class WeekdayOnlyStrategy(
    val chaptersPerWeekday: Int = 4,
    val books: List<Bible> = Bible.getBooksInOrder()
) : Strategy {
    override fun generateReadingPlan(startDate: LocalDate): ReadingPlan {
        val readings = generateReadings(books)
        var iterator = 0
        var currentDate = startDate
        val dailyReadings = mutableListOf<DailyReading>()

        while (iterator < readings.size) {
            val dow = currentDate.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                val currentReading = mutableListOf<Reading>()
                for (i in 0 until chaptersPerWeekday) {
                    currentReading.add(readings[iterator])
                    iterator++
                    if (iterator >= readings.size) break
                }
                dailyReadings.add(DailyReading(currentDate, currentReading))
            }
            currentDate = currentDate.plusDays(1)
        }
        return ReadingPlan(dailyReadings)
    }
}

package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.DayOfWeek
import java.time.LocalDate

class SimpleSequentialStrategy : Strategy {

    val chaptersPerDay = 3
    val chaptersOnSunday = 5

    override fun generateReadingPlan(startDate : LocalDate) : ReadingPlan {
        val books = Bible.getBooksInOrder()
        val readings = generateReadings(books)
        var iterator = 0
        var currentDate = startDate
        val dailyReadings = mutableListOf<DailyReading>()
        do {
            var chaptersToRead = chaptersPerDay
            var currentReading = mutableListOf<Reading>()
            if(currentDate.dayOfWeek.equals(DayOfWeek.SUNDAY)){
                chaptersToRead = chaptersOnSunday
            }

            for (i in 1..chaptersToRead){
                currentReading.add(readings.get(iterator))
                iterator++
                if(iterator >= readings.size){
                    break
                }
            }
            dailyReadings.add(DailyReading(currentDate, currentReading))
            currentDate = currentDate.plusDays(1)
        } while (iterator < readings.size)
        return ReadingPlan(dailyReadings)
    }



}
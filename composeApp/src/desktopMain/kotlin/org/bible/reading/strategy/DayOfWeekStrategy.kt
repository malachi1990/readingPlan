package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.BookCollection
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.EnumMap

class DayOfWeekStrategy : Strategy{

    val chaptersPerDay = 3
    val chaptersOnSunday = 5

    override fun generateReadingPlan(startDate : LocalDate) : ReadingPlan {
        val readingsByDay = getReadings()
        var completed = false;
        var currentDate = startDate
        val dailyReadings = mutableListOf<DailyReading>()
        do {
            var chaptersToRead = chaptersPerDay
            val currentDayOfWeek = currentDate.dayOfWeek

            if(currentDayOfWeek.equals(DayOfWeek.SUNDAY)){
                chaptersToRead = chaptersOnSunday
            }
            var readingList = readingsByDay[currentDayOfWeek]
            var dateOffset = 1L
            val currentReading = mutableListOf<Reading>()

            for(i in 0 until chaptersToRead){
                var emptyReadings = 0
                for(i in readingsByDay){
                    if (i.value.isEmpty()){
                        emptyReadings++
                    }
                }
                if(emptyReadings == 7){
                    completed = true
                    break
                }
                while(readingList!!.isEmpty()) {
                    val offsetDateDayOfWeek = currentDate.minusDays(dateOffset).dayOfWeek
                    readingList = readingsByDay[offsetDateDayOfWeek]
                    dateOffset++
                    //println("size of reading list for $offsetDateDayOfWeek is ${readingList?.size}")
                }
                val upperIndex: Int = when(currentReading.isEmpty()) {
                    true -> chaptersToRead.coerceAtMost(readingList.size)
                    false -> chaptersToRead - currentReading.size
                }

                currentReading.addAll(readingList.subList(0, upperIndex).toList())

                //println(currentReading)
                for (j in currentReading){
                    readingList.remove(j)
                }
                dailyReadings.add(DailyReading(currentDate, currentReading))
                if(currentReading.size == chaptersToRead){
                    break
                }
            }

            currentDate = currentDate.plusDays(1)

        } while (!completed)
        return ReadingPlan(dailyReadings)
    }

    private fun getReadings(): Map<DayOfWeek, MutableList<Reading>> {
        val readingsByDay = EnumMap<DayOfWeek, MutableList<Reading>>(DayOfWeek::class.java)

        val books = Bible.getBooksInOrder()
        val mondayBooks = books.stream().filter { b -> b.collection == BookCollection.Torah }.toList()
        val tuesdayBooks = books.stream().filter { b -> b.collection == BookCollection.History_1 }.toList()
        val wednesdayBooks = books.stream().filter { b -> b.collection == BookCollection.History_2 }.toList()

        val thursdayBooks = books.stream().filter { b -> b.collection == BookCollection.Prophets }.toList()
        val fridayBooks = books.stream().filter { b -> b.collection == BookCollection.Gospels_Acts }.toList()
        val saturdayBooks = books.stream().filter { b -> b.collection == BookCollection.Epistles }.toList()
        val sundayBooks = books.stream().filter { b -> b.collection == BookCollection.Wisdom }.toList()

        val mondayReadings = generateReadings(mondayBooks)
        val tuesdayReadings = generateReadings(tuesdayBooks)
        val wednesdayReadings = generateReadings(wednesdayBooks)

        val thursdayReadings = generateReadings(thursdayBooks)
        val fridayReadings = generateReadings(fridayBooks)
        val saturdayReadings = generateReadings(saturdayBooks)
        val sundayReadings = generateReadings(sundayBooks)

        readingsByDay.put(DayOfWeek.MONDAY, mondayReadings)
        readingsByDay.put(DayOfWeek.TUESDAY, tuesdayReadings)
        readingsByDay.put(DayOfWeek.WEDNESDAY, wednesdayReadings)

        readingsByDay.put(DayOfWeek.THURSDAY, thursdayReadings)
        readingsByDay.put(DayOfWeek.FRIDAY, fridayReadings)
        readingsByDay.put(DayOfWeek.SATURDAY, saturdayReadings)
        readingsByDay.put(DayOfWeek.SUNDAY, sundayReadings)
        return readingsByDay
    }
}
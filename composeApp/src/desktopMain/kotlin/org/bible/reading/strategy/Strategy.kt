package org.bible.reading.strategy

import org.bible.reading.dto.Bible
import org.bible.reading.dto.Reading
import org.bible.reading.dto.ReadingPlan
import java.time.LocalDate

interface Strategy {
    fun generateReadingPlan(startDate : LocalDate) : ReadingPlan

    fun generateReadings(books : List<Bible>) : MutableList<Reading>{
        val readingList = ArrayList<Reading>()
        for(book in books){
            for(i in 1.. book.chapters){
                readingList.add(Reading(book.bookName, i))
            }
        }
        return readingList
    }
}
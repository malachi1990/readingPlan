package org.bible.reading.dto

import java.time.LocalDate

data class DailyReading(val date : LocalDate,
                        val reading : List<Reading>) {

    override fun toString(): String {
        var readingString = ""
        for(chapter in reading){
            readingString += chapter.book + " - " + chapter.chapterNumber +", "
        }
        return "(date : $date -- $readingString)\n"
    }
}
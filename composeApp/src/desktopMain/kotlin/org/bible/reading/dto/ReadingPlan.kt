package org.bible.reading.dto

data class ReadingPlan(val readings : List<DailyReading>) {

    override fun toString(): String {
        return "ReadingPlan(readings=$readings)"
    }
}
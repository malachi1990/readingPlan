package org.bible.reading.strategy

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DayOfWeekStrategyTest {

    @Test
    fun testStrategy(){
        val result = DayOfWeekStrategy().generateReadingPlan(LocalDate.of(2026, 1, 1))
        println(result)
        assertEquals("Isaiah", result.readings[0].reading[0].book)
        assertEquals(1, result.readings[0].reading[0].chapterNumber)

    }
}
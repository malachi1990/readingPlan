package org.bible.reading.strategy

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleSequentialStrategyTest {

    @Test
    fun testStrategy(){
        val result = SimpleSequentialStrategy().generateReadingPlan(LocalDate.of(2025, 1, 1))
        println(result)
        assertEquals("Genesis", result.readings[0].reading[0].book)
        assertEquals(1, result.readings[0].reading[0].chapterNumber)

    }
}
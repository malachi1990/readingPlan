package org.bible.reading.strategy

import org.bible.reading.dto.ReadingPlan
import java.time.LocalDate

/**
 * A flexible custom strategy allowing arbitrary reading plan generation logic.
 */
class CustomStrategy(
    val name: String = "Custom Strategy",
    val description: String = "User-defined custom reading plan strategy",
    private val generator: (LocalDate) -> ReadingPlan
) : Strategy {
    override fun generateReadingPlan(startDate: LocalDate): ReadingPlan = generator(startDate)
}

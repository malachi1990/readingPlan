package org.bible.reading.dto

import org.bible.reading.strategy.Strategy
import java.time.LocalDate

data class PlanParameters(val startDate : LocalDate,
    val planStrategy: Strategy) {
}
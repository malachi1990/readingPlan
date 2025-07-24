package org.bible.reading.dto

data class Reading(val book : String, val chapterNumber : Int) {
    override fun toString(): String {
        return "Reading(book='$book' - $chapterNumber)"
    }
}
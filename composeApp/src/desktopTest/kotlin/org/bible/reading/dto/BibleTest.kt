package org.bible.reading.dto

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleTest {

    @Test
    fun testCompanionMethods(){
        val bookList = Bible.getBooksInOrder()
        assertEquals(66, bookList.size)
    }
}
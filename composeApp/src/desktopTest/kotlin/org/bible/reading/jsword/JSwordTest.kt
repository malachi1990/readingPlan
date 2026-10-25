package org.bible.reading.jsword

import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordBookPath
import java.io.File
import kotlin.test.Test

class JSwordTest {

    @Test
    fun testBookOriginLocation() {
        val appDir = File(System.getProperty("user.home"), ".bibleReadingPlan")
        SwordBookPath.setAugmentPath(arrayOf(appDir))
        SwordBookPath.setDownloadDir(appDir)
        Books.installed().registerDriver(org.crosswire.jsword.book.sword.SwordBookDriver.instance())

        for (book in Books.installed().books) {
            val md = book.bookMetaData
            val uri = (md as? SwordBookMetaData)?.library ?: md.location
            val path = uri?.path ?: ""
            val isAppDir = path.contains(".bibleReadingPlan")
            println("${book.initials}: isAppDir=$isAppDir, uri=$uri")
        }
    }
}

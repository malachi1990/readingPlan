package org.bible.reading.importer

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.bible.reading.importer.pdf.PdfImporter
import org.bible.reading.importer.pdf.PdfReader
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.BookCategory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfImportTest {

    private fun createSyntheticPdf(
        title: String = "Test Pilgrim Progress PDF",
        author: String = "John Bunyan",
        hasOutline: Boolean = true
    ): File {
        val tempFile = File.createTempFile("test_book", ".pdf")
        tempFile.deleteOnExit()

        PDDocument().use { doc ->
            val info = PDDocumentInformation().apply {
                this.title = title
                this.author = author
                this.subject = "An allegorical PDF work"
            }
            doc.documentInformation = info

            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val fontRegular = PDType1Font(Standard14Fonts.FontName.HELVETICA)

            // Page 1
            val page1 = PDPage()
            doc.addPage(page1)
            PDPageContentStream(doc, page1).use { cs ->
                cs.beginText()
                cs.setFont(fontBold, 16f)
                cs.newLineAtOffset(50f, 720f)
                cs.showText("Chapter 1: The Den")
                cs.newLineAtOffset(0f, -30f)
                cs.setFont(fontRegular, 12f)
                cs.showText("As I walked through the wilderness of this world, I lighted on a certain place where was a Den.")
                cs.newLineAtOffset(0f, -20f)
                cs.showText("And I laid me down in that place to sleep: and, as I slept, I dreamed a dream.")
                cs.endText()
            }

            // Page 2
            val page2 = PDPage()
            doc.addPage(page2)
            PDPageContentStream(doc, page2).use { cs ->
                cs.beginText()
                cs.setFont(fontBold, 16f)
                cs.newLineAtOffset(50f, 720f)
                cs.showText("Chapter 2: The Slough of Despond")
                cs.newLineAtOffset(0f, -30f)
                cs.setFont(fontRegular, 12f)
                cs.showText("Now, I saw in my dream, that just as they had ended this talk they drew near to a very miry slough.")
                cs.endText()
            }

            if (hasOutline) {
                val outline = PDDocumentOutline()
                doc.documentCatalog.documentOutline = outline

                val item1 = PDOutlineItem().apply {
                    this.title = "Chapter 1: The Den"
                    val dest = PDPageFitWidthDestination()
                    dest.page = page1
                    this.destination = dest
                }
                outline.addLast(item1)

                val item2 = PDOutlineItem().apply {
                    this.title = "Chapter 2: The Slough of Despond"
                    val dest = PDPageFitWidthDestination()
                    dest.page = page2
                    this.destination = dest
                }
                outline.addLast(item2)
            }

            doc.save(tempFile)
        }
        return tempFile
    }

    @Test
    fun testPdfImporterRegistration() {
        val pdfFile = createSyntheticPdf()
        val nonPdfFile = File("some_audio.mp3")

        assertTrue(BookImportRegistry.canImport(pdfFile))
        val importer = BookImportRegistry.findImporter(pdfFile)
        assertNotNull(importer)
        assertEquals("PDF", importer.formatName)
        assertTrue(importer.supportedExtensions.contains("pdf"))
        assertTrue(BookImportRegistry.getSupportedExtensions().contains("pdf"))
        assertTrue(BookImportRegistry.getSupportedExtensions().contains("epub"))

        assertEquals(null, BookImportRegistry.findImporter(nonPdfFile))
    }

    @Test
    fun testPdfReaderWithOutline() = runBlocking {
        val pdf = createSyntheticPdf(hasOutline = true)
        val importer = PdfImporter()
        val imported = importer.parse(pdf)

        assertEquals("Test Pilgrim Progress PDF", imported.metadata.title)
        assertEquals("John Bunyan", imported.metadata.author)
        assertEquals("An allegorical PDF work", imported.metadata.description)
        assertEquals(2, imported.chapters.size)

        val ch1 = imported.chapters[0]
        assertEquals("Chapter 1: The Den", ch1.title)
        assertTrue(ch1.rawContent.contains("wilderness of this world"))

        val ch2 = imported.chapters[1]
        assertEquals("Chapter 2: The Slough of Despond", ch2.title)
        assertTrue(ch2.rawContent.contains("miry slough"))
    }

    @Test
    fun testPdfReaderWithoutOutlineHeadingsDetection() = runBlocking {
        val pdf = createSyntheticPdf(hasOutline = false)
        val imported = PdfReader.parse(pdf)

        assertEquals("Test Pilgrim Progress PDF", imported.metadata.title)
        assertEquals(2, imported.chapters.size)

        val ch1 = imported.chapters[0]
        assertTrue(ch1.title.contains("Chapter 1"))
        assertTrue(ch1.rawContent.contains("wilderness of this world"))

        val ch2 = imported.chapters[1]
        assertTrue(ch2.title.contains("Chapter 2"))
        assertTrue(ch2.rawContent.contains("miry slough"))
    }

    @Test
    fun testEndToEndPdfImportAndJSwordReading() = runBlocking {
        val pdf = createSyntheticPdf()

        val tempSwordDir = Files.createTempDirectory("sword_pdf_e2e_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        var progressReported = false

        val result = manager.importPdf(pdf) { step ->
            progressReported = true
        }

        assertTrue(result.isSuccess, "Import should succeed: ${result.exceptionOrNull()?.message}")
        assertTrue(progressReported, "Progress updates should have been reported")

        val book = result.getOrThrow()
        assertEquals(BookCategory.GENERAL_BOOK, book.bookCategory)
        assertTrue(manager.isBookInstalled(book.initials))

        // Read keys via SwordManager
        val keys = manager.getBookKeys(book)
        assertTrue(keys.isNotEmpty(), "Module should have keys")
        assertTrue(keys.any { it.name.contains("Chapter 1") }, "Should have Chapter 1 key")

        // Read content of Chapter 1
        val ch1Key = keys.first { it.name.contains("Chapter 1") }
        val ch1Content = manager.readKeyContent(book, ch1Key)
        assertTrue(ch1Content.contains("wilderness of this world"), "Chapter 1 content should contain expected text")

        // Read content of Chapter 2
        val ch2Key = keys.first { it.name.contains("Chapter 2") }
        val ch2Content = manager.readKeyContent(book, ch2Key)
        assertTrue(ch2Content.contains("miry slough"), "Chapter 2 content should contain expected text")
    }
}

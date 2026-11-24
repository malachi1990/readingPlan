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
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import org.bible.reading.importer.osis.OsisConverter
import org.bible.reading.importer.pdf.PdfImporter
import org.bible.reading.importer.pdf.PdfReader
import org.bible.reading.importer.sword.SwordGenBookWriter
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.BookCategory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                cs.newLineAtOffset(0f, -24f)
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

    private fun createStructuredFormattingPdf(): File {
        val tempFile = File.createTempFile("test_structured_pdf", ".pdf")
        tempFile.deleteOnExit()

        PDDocument().use { doc ->
            doc.documentInformation = PDDocumentInformation().apply {
                title = "Structured Formatting PDF"
                author = "Test Author"
            }

            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val fontRegular = PDType1Font(Standard14Fonts.FontName.HELVETICA)

            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(fontBold, 14f)
                cs.newLineAtOffset(50f, 740f)
                cs.showText("Chapter 1: Doctrine")

                // Paragraph 1: multi-line column-wrapped prose (14pt leading -> same paragraph)
                cs.setFont(fontRegular, 11f)
                cs.newLineAtOffset(0f, -28f)
                cs.showText("In this first paragraph, the prose reaches near the right margin of the")
                cs.newLineAtOffset(0f, -14f)
                cs.showText("page so that the sentence continues naturally onto the second line.")

                // Paragraph 2: separated by a larger vertical gap (28pt -> distinct paragraph)
                cs.newLineAtOffset(0f, -28f)
                cs.showText("Question: What is the chief end of the second paragraph in this document?")
                cs.newLineAtOffset(0f, -14f)
                cs.showText("Answer: To verify intentional line breaks are kept for Q and A sections.")

                // Paragraph 3: list items separated by normal leading inside a paragraph
                cs.newLineAtOffset(0f, -28f)
                cs.showText("1. First numbered point in the outline.")
                cs.newLineAtOffset(0f, -14f)
                cs.showText("2. Second numbered point in the outline.")
                cs.endText()
            }

            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply {
                this.title = "Chapter 1: Doctrine"
                this.destination = PDPageFitWidthDestination().apply { this.page = page }
            })

            doc.save(tempFile)
        }
        return tempFile
    }

    private fun createPdfWithRunningHeadersAndFootnotes(): File {
        val tempFile = File.createTempFile("test_running_headers", ".pdf")
        tempFile.deleteOnExit()

        PDDocument().use { doc ->
            doc.documentInformation = PDDocumentInformation().apply {
                title = "Systematics with Headers"
                author = "Reformed Author"
            }

            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val fontRegular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val fontItalic = PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE)

            // Page 1: Chapter 1 opening (real chapter heading, larger font, NO running header) + footnote at bottom
            val page1 = PDPage()
            doc.addPage(page1)
            PDPageContentStream(doc, page1).use { cs ->
                cs.beginText()
                cs.setFont(fontBold, 16f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Chapter 1: Of Creation")
                cs.setFont(fontRegular, 12f)
                cs.newLineAtOffset(0f, -30f)
                cs.showText("God created the world out of nothing by the word of his power in six days.")
                cs.setFont(fontRegular, 9f)
                cs.newLineAtOffset(0f, -610f) // bottom of page (y=60 in PDF coords, baselineY ~ 732/792)
                cs.showText("1. See Genesis 1:1 and Hebrews 11:3 for further study.")
                cs.endText()
            }

            // Pages 2..4: Chapter 1 continuation pages with running header at top (y=755 in PDF coords -> baselineY ~ 37/792)
            for (p in 2..4) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(fontItalic, 10f)
                    cs.newLineAtOffset(50f, 755f)
                    cs.showText("Chapter 1: Of Creation   $p")
                    cs.setFont(fontRegular, 12f)
                    cs.newLineAtOffset(0f, -45f)
                    cs.showText("Continuation prose on page $p explaining providence and creation in detail.")
                    cs.endText()
                }
            }

            // Page 5: Chapter 2 opening (real chapter heading)
            val page5 = PDPage()
            doc.addPage(page5)
            PDPageContentStream(doc, page5).use { cs ->
                cs.beginText()
                cs.setFont(fontBold, 16f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Chapter 2: Of Providence")
                cs.setFont(fontRegular, 12f)
                cs.newLineAtOffset(0f, -30f)
                cs.showText("God upholds, directs, disposes, and governs all creatures and actions.")
                cs.endText()
            }

            // Pages 6..8: Chapter 2 continuation pages with alternating running headers + bottom note
            val ch2Headers = listOf("6   Reformed Author", "Chapter 2: Of Providence   7", "8   Reformed Author")
            for ((idx, headerText) in ch2Headers.withIndex()) {
                val pageNum = 6 + idx
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(fontItalic, 10f)
                    cs.newLineAtOffset(50f, 755f)
                    cs.showText(headerText)
                    cs.setFont(fontRegular, 12f)
                    cs.newLineAtOffset(0f, -45f)
                    cs.showText("Providence body paragraph on page $pageNum without any header noise.")
                    if (pageNum == 7) {
                        cs.setFont(fontRegular, 9f)
                        cs.newLineAtOffset(0f, -650f)
                        cs.showText("2. Preserved bottom footnote should still be imported.")
                    }
                    cs.endText()
                }
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
    fun testHeaderNormalizationAndRunningHeaderRemoval() {
        assertEquals("the geneva catechism", PdfReader.normalizeHeaderKey("The Geneva Catechism   12").key)
        assertEquals("the geneva catechism", PdfReader.normalizeHeaderKey("13   The Geneva Catechism").key)
        assertEquals("john calvin", PdfReader.normalizeHeaderKey("John Calvin | Page 14").key)
        assertEquals("chapter 1: of faith", PdfReader.normalizeHeaderKey("Chapter 1: Of Faith   45").key)
        // Unique structural chapter numbers must remain distinct
        assertEquals("chapter 1", PdfReader.normalizeHeaderKey("Chapter 1").key)
        assertEquals("chapter 2", PdfReader.normalizeHeaderKey("Chapter 2").key)
        assertEquals("<PAGE_NUM>", PdfReader.normalizeHeaderKey("Page 42").key)
        assertEquals("<ROMAN_PAGE_NUM>", PdfReader.normalizeHeaderKey("xiv").key)

        // Synthetic page sequence testing alternating running headers vs unique headings & bottom footnotes
        val pages = listOf(
            PdfReader.ExtractedPage(
                pageIndex = 0,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("Chapter 1", true, true, 120f, 792f, 12f),
                    PdfReader.ExtractedLine("First page body text.", true, true, 150f, 792f, 12f)
                )
            ),
            PdfReader.ExtractedPage(
                pageIndex = 1,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("2   Reformed Author", true, false, 38f, 792f, 10f),
                    PdfReader.ExtractedLine("Second page body text.", false, true, 88f, 792f, 12f),
                    PdfReader.ExtractedLine("1. Footnote at bottom of page 2.", true, true, 735f, 792f, 9f)
                )
            ),
            PdfReader.ExtractedPage(
                pageIndex = 2,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("Chapter 2", true, true, 82f, 792f, 12f),
                    PdfReader.ExtractedLine("Third page body text.", true, true, 112f, 792f, 12f)
                )
            ),
            PdfReader.ExtractedPage(
                pageIndex = 3,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("4   Reformed Author", true, true, 38f, 792f, 10f),
                    PdfReader.ExtractedLine("Fourth page body text.", true, true, 88f, 792f, 12f)
                )
            ),
            PdfReader.ExtractedPage(
                pageIndex = 4,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("Chapter 3", true, true, 82f, 792f, 12f),
                    PdfReader.ExtractedLine("Fifth page body text.", true, true, 112f, 792f, 12f)
                )
            ),
            PdfReader.ExtractedPage(
                pageIndex = 5,
                pageHeight = 792f,
                lines = listOf(
                    PdfReader.ExtractedLine("6   Reformed Author", true, true, 38f, 792f, 10f),
                    PdfReader.ExtractedLine("Sixth page body text.", true, true, 88f, 792f, 12f)
                )
            )
        )

        val cleaned = PdfReader.removeRunningHeaders(pages)

        // Real chapter headings preserved
        assertTrue(cleaned[0].lines.any { it.text == "Chapter 1" })
        assertTrue(cleaned[2].lines.any { it.text == "Chapter 2" })
        assertTrue(cleaned[4].lines.any { it.text == "Chapter 3" })

        // Alternating running headers stripped cleanly and paragraph start carried onto next body line
        assertFalse(cleaned[1].lines.any { it.text.contains("Reformed Author") })
        assertTrue(cleaned[1].lines.first().text == "Second page body text.")
        assertTrue(cleaned[1].lines.first().startsParagraph)
        assertFalse(cleaned[3].lines.any { it.text.contains("Reformed Author") })
        assertFalse(cleaned[5].lines.any { it.text.contains("Reformed Author") })

        // Bottom footnote preserved untouched
        assertTrue(cleaned[1].lines.any { it.text == "1. Footnote at bottom of page 2." })
    }

    @Test
    fun testPdfReaderStripsRunningHeadersWhileKeepingHeadingsAndFootnotes() = runBlocking {
        val pdf = createPdfWithRunningHeadersAndFootnotes()
        val imported = PdfReader.parse(pdf)

        // Running headers matched "Chapter 1: Of Creation" on pages 2..4; stripping them ensures only 2 real chapters are detected!
        assertEquals(2, imported.chapters.size, "Expected 2 chapters, got: ${imported.chapters.map { it.title }}")

        val ch1 = imported.chapters[0]
        assertEquals("Chapter 1: Of Creation", ch1.title)
        assertTrue(ch1.rawContent.contains("Chapter 1: Of Creation"), "Opening chapter heading should remain in chapter 1")
        assertTrue(ch1.rawContent.contains("Continuation prose on page 2"))
        assertTrue(ch1.rawContent.contains("Continuation prose on page 4"))
        assertFalse(ch1.rawContent.contains("Chapter 1: Of Creation   2"), "Running header on page 2 should be stripped")
        assertFalse(ch1.rawContent.contains("Chapter 1: Of Creation   3"), "Running header on page 3 should be stripped")
        assertFalse(ch1.rawContent.contains("Chapter 1: Of Creation   4"), "Running header on page 4 should be stripped")
        assertTrue(ch1.rawContent.contains("1. See Genesis 1:1 and Hebrews 11:3 for further study."), "Bottom footnote should be preserved")

        val ch2 = imported.chapters[1]
        assertEquals("Chapter 2: Of Providence", ch2.title)
        assertTrue(ch2.rawContent.contains("Providence body paragraph on page 6"))
        assertTrue(ch2.rawContent.contains("Providence body paragraph on page 8"))
        assertFalse(ch2.rawContent.contains("6   Reformed Author"), "Even-page running header on page 6 should be stripped")
        assertFalse(ch2.rawContent.contains("8   Reformed Author"), "Even-page running header on page 8 should be stripped")
        assertTrue(ch2.rawContent.contains("2. Preserved bottom footnote should still be imported."), "Bottom footnote on page 7 should be preserved")
    }

    @Test
    fun testPdfTextToHtmlFormattingAndSwordRoundTrip() = runBlocking {
        val rawPdfText = "\uE000\u00A0\n\uE001" +
            "\uE000In the beginning of this long prose paragraph, the words extend to the right edge of the\n" +
            "column and wrap smoothly across hyphen-\n" +
            "ated lines without creating artificial breaks.\n\uE001" +
            "\uE000\tIndented paragraph with\ttab and\r\ncarriage return line ending.\n\uE001" +
            "\uE000  Two-space indented paragraph.\n\tTab-indented second line.\n\uE001" +
            "\uE000Short heading line\n" +
            "1. First numbered item\n" +
            "2. Second numbered item\n" +
            "  Sub-indented detail line\n\uE001" +
            "\uE000Question: How does this format?\n" +
            "Answer: Each Q&A line stays on its own line.\n\uE001"

        val html = PdfReader.textToHtml("Section One", rawPdfText)
        assertTrue(html.startsWith("<h1>Section One</h1>\n"))
        assertFalse(html.contains("<p>\u00A0</p>"), "Spacer NBSP paragraph should be skipped: $html")
        assertTrue(
            html.contains("the words extend to the right edge of the column and wrap smoothly across hyphenated lines without creating artificial breaks."),
            "Wrapped prose and hyphenated words should unwrap smoothly:\n$html"
        )
        assertTrue(
            html.contains("<p>\tIndented paragraph with\ttab and\r<br/>carriage return line ending.</p>"),
            "Tabs, leading tab, and CR should be preserved:\n$html"
        )
        assertTrue(
            html.contains("<p>  Two-space indented paragraph.<br/>\tTab-indented second line.</p>"),
            "Leading 2-space indent and tab-indented second line should be preserved:\n$html"
        )
        assertTrue(
            html.contains("<p>Short heading line<br/>1. First numbered item<br/>2. Second numbered item<br/>  Sub-indented detail line</p>"),
            "Short lines, list items, and indented lines should keep <br/>:\n$html"
        )
        assertTrue(
            html.contains("<p>Question: How does this format?<br/>Answer: Each Q&amp;A line stays on its own line.</p>"),
            "Q&A lines should keep <br/>:\n$html"
        )

        // Feed through OsisConverter -> SwordGenBookWriter -> SwordManager to verify end-to-end preservation
        val osisSection = OsisConverter.toOsisSection(
            ImportedChapter(id = "s1", title = "Section One", rawContent = html, order = 1)
        )
        assertFalse(osisSection.contains("</p>\n\n"), "OSIS XML must use single newline between paragraphs:\n$osisSection")
        assertTrue(osisSection.contains("</p>\n<p>"), "OSIS XML should separate paragraphs with single newline:\n$osisSection")

        val tempSwordDir = Files.createTempDirectory("sword_pdf_text_roundtrip").toFile()
        tempSwordDir.deleteOnExit()
        val bookModel = ImportedBook(
            metadata = ImportedBookMetadata(
                title = "PDF Formatting Roundtrip",
                author = "Tester",
                identifier = "urn:pdf:roundtrip",
                language = "en",
                description = "",
                about = "PDF formatting test"
            ),
            chapters = listOf(ImportedChapter(id = "s1", title = "Section One", rawContent = html, order = 1))
        )
        val compilation = SwordGenBookWriter.write(tempSwordDir, bookModel)
        val bdtXml = compilation.bdtFile.readText(Charsets.UTF_8)
        assertFalse(bdtXml.contains("</p>\n\n"), "BDT XML must not have extra blank lines:\n$bdtXml")
        assertTrue(bdtXml.contains("</p>\n<p>"), "BDT XML must separate paragraphs with single newline:\n$bdtXml")

        val manager = SwordManager(appDir = tempSwordDir)
        val installedBook = manager.installedBooks.first { it.initials.equals(compilation.initials, ignoreCase = true) }
        val key = manager.getBookKeys(installedBook).first()
        val content = manager.readKeyContent(installedBook, key)

        val expected = "Section One\n\n" +
            "In the beginning of this long prose paragraph, the words extend to the right edge of the column and wrap smoothly across hyphenated lines without creating artificial breaks.\n\n" +
            "\tIndented paragraph with\ttab and\r\ncarriage return line ending.\n\n" +
            "  Two-space indented paragraph.\n\tTab-indented second line.\n\n" +
            "Short heading line\n1. First numbered item\n2. Second numbered item\n  Sub-indented detail line\n\n" +
            "Question: How does this format?\nAnswer: Each Q&A line stays on its own line."

        assertEquals(expected, content)
    }

    @Test
    fun testEndToEndPdfImportAndJSwordReading() = runBlocking {
        val pdf = createSyntheticPdf()

        val tempSwordDir = Files.createTempDirectory("sword_pdf_e2e_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        var progressReported = false

        val result = manager.importPdf(pdf) { _ ->
            progressReported = true
        }

        assertTrue(result.isSuccess, "Import should succeed: ${result.exceptionOrNull()?.message}")
        assertTrue(progressReported, "Progress updates should have been reported")

        val book = result.getOrThrow()
        assertEquals(BookCategory.GENERAL_BOOK, book.bookCategory)
        assertTrue(manager.isBookInstalled(book.initials))

        // Verify .bdt uses single newline between paragraphs
        val initialsLower = book.initials.lowercase()
        val bdtFile = File(tempSwordDir, "modules/genbook/rawgenbook/$initialsLower/$initialsLower.bdt")
        val bdtXml = bdtFile.readText(Charsets.UTF_8)
        assertFalse(bdtXml.contains("</p>\n\n"), "BDT XML should not contain multiple newlines after </p>:\n$bdtXml")

        // Read keys via SwordManager
        val keys = manager.getBookKeys(book)
        assertTrue(keys.isNotEmpty(), "Module should have keys")
        assertTrue(keys.any { it.name.contains("Chapter 1") }, "Should have Chapter 1 key")

        // Read content of Chapter 1
        val ch1Key = keys.first { it.name.contains("Chapter 1") }
        val ch1Content = manager.readKeyContent(book, ch1Key)
        assertTrue(ch1Content.contains("wilderness of this world"), "Chapter 1 content should contain expected text")
        assertFalse(
            Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(ch1Content),
            "Chapter 1 content must not contain multiple consecutive blank lines:\n$ch1Content"
        )

        // Read content of Chapter 2
        val ch2Key = keys.first { it.name.contains("Chapter 2") }
        val ch2Content = manager.readKeyContent(book, ch2Key)
        assertTrue(ch2Content.contains("miry slough"), "Chapter 2 content should contain expected text")
        assertFalse(
            Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(ch2Content),
            "Chapter 2 content must not contain multiple consecutive blank lines:\n$ch2Content"
        )
    }

    @Test
    fun testEndToEndStructuredPdfParagraphAndLineBreakFormatting() = runBlocking {
        val pdf = createStructuredFormattingPdf()

        val tempSwordDir = Files.createTempDirectory("sword_pdf_structured_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        val result = manager.importPdf(pdf)
        assertTrue(result.isSuccess, "Structured PDF import should succeed: ${result.exceptionOrNull()?.message}")

        val book = result.getOrThrow()
        val initialsLower = book.initials.lowercase()
        val bdtFile = File(tempSwordDir, "modules/genbook/rawgenbook/$initialsLower/$initialsLower.bdt")
        val bdtXml = bdtFile.readText(Charsets.UTF_8)
        assertFalse(bdtXml.contains("</p>\n\n"), "BDT XML should not have multiple blank lines:\n$bdtXml")
        assertTrue(bdtXml.contains("</p>\n<p>"), "BDT XML should separate paragraphs with single newline:\n$bdtXml")

        val key = manager.getBookKeys(book).first()
        val content = manager.readKeyContent(book, key)

        assertTrue(
            content.contains("margin of the page so that"),
            "Wrapped prose lines within a paragraph should unwrap smoothly:\n$content"
        )
        assertTrue(
            content.contains("document?\nAnswer:"),
            "Q&A lines should stay on distinct lines:\n$content"
        )
        assertTrue(
            content.contains("1. First numbered point in the outline.\n2. Second numbered point in the outline."),
            "Numbered list items should stay on distinct lines:\n$content"
        )
        assertFalse(
            Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(content),
            "Rendered content must not contain multiple consecutive blank lines:\n$content"
        )
    }

    @Test
    fun testInspectRealPdfsParagraphSpacing() = runBlocking {
        val pdfFiles = listOf(
            File("/media/nvmd1/books/theology/The Geneva Catechism - John Calvin.pdf"),
            File("/media/nvmd1/books/theology/Love God With All Your Mind - Matthew Everhard.pdf")
        ).filter { it.exists() }

        if (pdfFiles.isEmpty()) return@runBlocking

        for (pdfFile in pdfFiles) {
            val tempSwordDir = Files.createTempDirectory("sword_real_pdf_test").toFile()
            tempSwordDir.deleteOnExit()

            val manager = SwordManager(appDir = tempSwordDir)
            val result = manager.importPdf(pdfFile)
            assertTrue(result.isSuccess, "Import of ${pdfFile.name} should succeed: ${result.exceptionOrNull()?.message}")
            val book = result.getOrThrow()

            val initialsLower = book.initials.lowercase()
            val bdtFile = File(tempSwordDir, "modules/genbook/rawgenbook/$initialsLower/$initialsLower.bdt")
            val bdtXml = bdtFile.readText(Charsets.UTF_8)
            assertFalse(
                bdtXml.contains("</p>\n\n"),
                "${pdfFile.name}: BDT XML should not contain multiple newlines after </p>"
            )

            val keys = manager.getBookKeys(book)
            assertTrue(keys.isNotEmpty(), "${pdfFile.name} should produce keys")

            val multiParaKeys = keys.take(10).map { manager.readKeyContent(book, it) }
            assertTrue(
                multiParaKeys.any { it.contains("\n\n") },
                "${pdfFile.name} should preserve paragraph breaks (\\n\\n)"
            )
            for ((idx, content) in multiParaKeys.withIndex()) {
                assertFalse(
                    Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(content),
                    "${pdfFile.name} key #$idx should not contain multiple consecutive blank lines"
                )
            }
        }
    }
}

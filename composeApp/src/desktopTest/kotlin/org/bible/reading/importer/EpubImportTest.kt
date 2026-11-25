package org.bible.reading.importer

import kotlinx.coroutines.runBlocking
import org.bible.reading.importer.epub.EpubImporter
import org.bible.reading.importer.epub.EpubReader
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import org.bible.reading.importer.service.ImportItemStatus
import org.bible.reading.importer.osis.OsisConverter
import org.bible.reading.importer.sword.SwordGenBookWriter
import org.bible.reading.jsword.SwordManager
import org.bible.reading.ui.parseDroppedFileUris
import org.bible.reading.ui.parseDroppedTextPaths
import org.crosswire.jsword.book.BookCategory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubImportTest {

    private fun createSyntheticEpub(
        title: String = "Test Pilgrim Progress",
        author: String = "John Bunyan",
        description: String = "An allegorical work",
        language: String = "en"
    ): File {
        val tempFile = File.createTempFile("test_book", ".epub")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            // 1. mimetype
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            // 2. META-INF/container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>""".trimIndent()
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. OEBPS/content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val opfContent = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
    <dc:language>$language</dc:language>
    <dc:description>$description</dc:description>
    <dc:identifier id="BookId">urn:uuid:12345-67890</dc:identifier>
  </metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>""".trimIndent()
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. OEBPS/ch1.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            val ch1Content = """<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter 1: The Den</title></head>
<body>
  <h1>Chapter 1: The Den</h1>
  <p>As I walked through the wilderness of this world, I lighted on a certain place where was a Den.</p>
  <p>And I laid me down in that place to sleep: and, as I slept, I dreamed a dream.</p>
</body>
</html>""".trimIndent()
            zos.write(ch1Content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5. OEBPS/ch2.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            val ch2Content = """<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter 2: The Slough of Despond</title></head>
<body>
  <h2>Chapter 2: The Slough of Despond</h2>
  <p>Now, I saw in my dream, that just as they had ended this talk they drew near to a very miry slough.</p>
  <p>Here was an explanatory note <aside class="footnote">A classic metaphor for doubt</aside> embedded.</p>
</body>
</html>""".trimIndent()
            zos.write(ch2Content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return tempFile
    }

    private fun createFormattingEpub(): File {
        val tempFile = File.createTempFile("formatting_book", ".epub")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>""".trimIndent()
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val opfContent = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Whitespace Test Book</dc:title>
    <dc:creator>Formatting Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:description>Testing whitespace and formatting preservation</dc:description>
    <dc:identifier id="BookId">urn:uuid:ws-test-12345</dc:identifier>
  </metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>""".trimIndent()
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            // Chapter with tabs (\t), newlines (\n), carriage returns (\r\n and \r),
            // preformatted text, <br class="calibre"/>, paragraph breaks, and whitespace entities
            val ch1Content = "<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                "<head><title>Chapter 1: Formatted Content</title></head>\n" +
                "<body>\n" +
                "  <h1>Chapter 1: Formatted Content</h1>\n" +
                "  <p>First paragraph with\ttab character and\r\ncarriage return line feed.</p>\n" +
                "  <p>Line with break<br class=\"calibre\"/>followed by second line<br/>and third line.</p>\n" +
                "  <pre>\n" +
                "\tindented code line 1\r\n" +
                "\t\tdoubly indented line 2\n" +
                "\tindented code line 3\n" +
                "  </pre>\n" +
                "  <p>Entities test: non-breaking&nbsp;space and em-space&emsp;and en-space&ensp;and tab&tab;done.</p>\n" +
                "  <ul>\n" +
                "    <li>First item</li>\n" +
                "    <li>Second item</li>\n" +
                "  </ul>\n" +
                "</body>\n" +
                "</html>"
            zos.write(ch1Content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return tempFile
    }

    private fun createMessyParagraphSpacingEpub(): File {
        val tempFile = File.createTempFile("messy_spacing_book", ".epub")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>""".trimIndent()
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val opfContent = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Paragraph Spacing Book</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="BookId">urn:uuid:para-spacing-123</dc:identifier>
  </metadata>
  <manifest>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>""".trimIndent()
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            val ch1Content = "<!DOCTYPE html>\r\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\">\r\n" +
                "<head><title>Sermon One</title></head>\r\n" +
                "<body>\r\n" +
                "  <h2 class=\"centered\">&nbsp;</h2>\r\n" +
                "  <p class=\"calibre5\">\u00A0</p>\r\n" +
                "  <h3>Sermon One</h3>\r\n" +
                "  <blockquote>\r\n" +
                "    <p>Quote paragraph text.<br/>\r\n" +
                "    Reference 1:1</p>\r\n" +
                "  </blockquote>\r\n" +
                "  <p>First real paragraph.</p>\r\n" +
                "\t\t\t\r\n" +
                "  <p class=\"calibre5\"></p>\r\n" +
                "  <p class=\"calibre5\">&nbsp;</p>\r\n" +
                "  <strong class=\"calibre6\"> </strong>\r\n" +
                "  <p class=\"calibre5\"><br/></p>\r\n" +
                "  <p>Second real paragraph.</p>\r\n\r\n\r\n" +
                "  <div>\r\n" +
                "    <p>Third real paragraph.</p>\r\n" +
                "  </div>\r\n" +
                "</body>\r\n" +
                "</html>"
            zos.write(ch1Content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        return tempFile
    }

    @Test
    fun testBookImportRegistry() {
        val epubFile = createSyntheticEpub()
        val txtFile = File("sample.txt")

        assertTrue(BookImportRegistry.canImport(epubFile))
        val importer = BookImportRegistry.findImporter(epubFile)
        assertNotNull(importer)
        assertEquals("EPUB", importer.formatName)
        assertTrue(BookImportRegistry.getSupportedExtensions().contains("epub"))
        assertEquals(null, BookImportRegistry.findImporter(txtFile))
    }

    @Test
    fun testEpubReaderParsing() = runBlocking {
        val epub = createSyntheticEpub()
        val importer = EpubImporter()
        val imported = importer.parse(epub)

        assertEquals("Test Pilgrim Progress", imported.metadata.title)
        assertEquals("John Bunyan", imported.metadata.author)
        assertEquals("An allegorical work", imported.metadata.description)
        assertEquals("en", imported.metadata.language)
        assertEquals(2, imported.chapters.size)

        val ch1 = imported.chapters[0]
        assertEquals("Chapter 1: The Den", ch1.title)
        assertTrue(ch1.rawContent.contains("wilderness of this world"))

        val ch2 = imported.chapters[1]
        assertEquals("Chapter 2: The Slough of Despond", ch2.title)
        assertTrue(ch2.rawContent.contains("miry slough"))
    }

    @Test
    fun testOsisConverter() {
        val rawHtml = """
            <h1>Section Title</h1>
            <p>This is a paragraph with <b>bold text</b>, <i>italic text</i>, and <u>underlined text</u>.</p>
            <blockquote>Here is a profound quote.</blockquote>
            <aside class="footnote">Important detail</aside>
        """.trimIndent()

        val chapter = ImportedChapter(
            id = "c1",
            title = "Section Title",
            rawContent = rawHtml,
            order = 1
        )

        val osisSection = OsisConverter.toOsisSection(chapter)
        assertTrue(osisSection.contains("<title canonical=\"true\" level=\"1\">Section Title</title>"))
        assertTrue(osisSection.contains("""<hi type="bold">bold text</hi>"""))
        assertTrue(osisSection.contains("""<hi type="italic">italic text</hi>"""))
        assertTrue(osisSection.contains("""<hi type="underline">underlined text</hi>"""))
        assertTrue(osisSection.contains("<q type=\"block\">"))
        assertTrue(osisSection.contains("Here is a profound quote."))
        assertTrue(osisSection.contains("""<note type="explanation">Important detail</note>"""))

        // Test full OSIS document generation
        val book = ImportedBook(
            metadata = ImportedBookMetadata(
                title = "Test Book",
                author = "Author Name",
                description = "Sample Description",
                language = "en",
                identifier = "urn:uuid:test-123"
            ),
            chapters = listOf(
                ImportedChapter(
                    id = "ch1",
                    title = "Chapter 1",
                    rawContent = "<p>First chapter body</p>",
                    order = 1
                )
            )
        )

        val fullOsis = OsisConverter.toFullOsisDocument(book)
        assertTrue(fullOsis.contains("<osis"))
        assertTrue(fullOsis.contains("<header>"))
        assertTrue(fullOsis.contains("<work osisWork=\"TestBook\">"))
        assertTrue(fullOsis.contains("<title>Test Book</title>"))
        assertTrue(fullOsis.contains("<creator role=\"aut\">Author Name</creator>"))
        assertTrue(fullOsis.contains("<div type=\"book\">"))
        assertTrue(fullOsis.contains("<div type=\"section\" osisID=\"ch1\">"))
    }

    @Test
    fun testOsisConverterSingleNewlineBetweenParagraphs() {
        val rawHtml = "  <h2 class=\"centered\">&nbsp;</h2>\r\n" +
            "  <p class=\"calibre5\">\u00A0</p>\r\n" +
            "  <h1>Title</h1>\r\n\r\n" +
            "  <p>First paragraph.</p>\r\n" +
            "\t\t\r\n" +
            "  <p></p>\r\n" +
            "  <p>&nbsp;</p>\r\n" +
            "  <strong class=\"calibre6\"> </strong>\r\n" +
            "  <p>Second paragraph.</p>\r\n\r\n" +
            "  <div>\r\n    <p>Third paragraph.</p>\r\n  </div>\r\n"

        val section = OsisConverter.toOsisSection(
            ImportedChapter(id = "c1", title = "Title", rawContent = rawHtml, order = 1)
        )

        val expected = """
<div type="section" osisID="c1">
<title canonical="true" level="1">Title</title>
<p>First paragraph.</p>
<p>Second paragraph.</p>
<p>Third paragraph.</p>
</div>
        """.trimIndent()

        assertEquals(expected, section)
    }

    @Test
    fun testOsisConverterEntityNormalizationAndEdgeCases() {
        val rawHtml = """
            <h2>Chapter Two &amp; Beyond</h2>
            <p>Q&A: &ldquo;Hello&rdquo;&mdash;test&hellip; &nbsp; &#169; &#x2014; &unknownEntity; &copy; &reg; &trade;</p>
            <p>See <a osisRef="John.3.16">John 3:16</a> and <a href="https://example.com">link text</a>.</p>
        """.trimIndent()

        val section = OsisConverter.toOsisSection(
            ImportedChapter(id = "2_intro", title = "Chapter 2", rawContent = rawHtml, order = 2)
        )

        assertTrue(section.contains("<div type=\"section\" osisID=\"doc_2_intro\">"), section)
        assertTrue(section.contains("<title canonical=\"true\" level=\"2\">Chapter Two &amp; Beyond</title>"), section)
        assertTrue(section.contains("Q&amp;A:"), section)
        assertTrue(section.contains("&#8220;Hello&#8221;&#8212;test&#8230;"), section)
        assertTrue(section.contains("&#160; &#169; &#x2014; &amp;unknownEntity; &#169; &#174; &#8482;"), section)
        assertTrue(section.contains("<reference osisRef=\"John.3.16\">John 3:16</reference>"), section)
        assertTrue(section.contains("and link text."), section)
        assertFalse(section.contains("<a "), section)
    }

    @Test
    fun testPreserveWhitespaceAndFormattingInOsisConverter() {
        // Test tabs, newlines, carriage returns, <pre>, <br with attributes>, lists, and entities
        val rawHtml = "<pre>\tdef foo():\r\n\t\treturn 42\n</pre>\n" +
            "<p>Paragraph with\ttab and\r\ncarriage return.</p>\n" +
            "<p>Line 1<br class=\"calibre4\"/>Line 2<br style=\"margin:0\"/>Line 3</p>\n" +
            "<p>Spacing: a&emsp;b&ensp;c&tab;d&NewLine;e</p>"

        val chapter = ImportedChapter(
            id = "c1",
            title = "Formatting Chapter",
            rawContent = rawHtml,
            order = 1
        )

        val osisSection = OsisConverter.toOsisSection(chapter)

        // 1. Title has canonical="true" and newline separation
        assertTrue(osisSection.contains("<title canonical=\"true\" level=\"1\">Formatting Chapter</title>"))

        // 2. Tabs preserved
        assertTrue(osisSection.contains("\tdef foo():") || osisSection.contains("&#9;def foo():"))
        assertTrue(osisSection.contains("Paragraph with\ttab"))

        // 3. Carriage returns preserved (encoded as &#13; so W3C XML parsers don't normalize them to \n)
        assertTrue(osisSection.contains("&#13;"))

        // 4. <br class="calibre4"/> preserved as <lb/>
        assertTrue(osisSection.contains("<lb/>\nLine 2") || osisSection.contains("<lb/>"))

        // 5. Preformatted text preserved in <p>
        assertTrue(osisSection.contains("return 42"))

        // 6. Entities converted to whitespace / XML characters
        assertTrue(!osisSection.contains("&amp;emsp;"), "Entities should not be double-escaped")
        assertTrue(osisSection.contains("&#8195;"), "Em-space should be converted to &#8195;")
        assertTrue(osisSection.contains("&#8194;"), "En-space should be converted to &#8194;")
    }

    @Test
    fun testEndToEndWhitespaceAndFormattingPreservation() = runBlocking {
        val epub = createFormattingEpub()
        val tempSwordDir = Files.createTempDirectory("sword_formatting_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        val result = manager.importBook(epub)

        assertTrue(result.isSuccess, "Import of formatted EPUB should succeed: ${result.exceptionOrNull()?.message}")
        val book = result.getOrThrow()

        val keys = manager.getBookKeys(book)
        assertTrue(keys.isNotEmpty(), "Module should have keys")
        val ch1Key = keys.first()

        val content = manager.readKeyContent(book, ch1Key)

        // 1. Verify title is present
        assertTrue(content.contains("Chapter 1: Formatted Content"), "Content must preserve chapter title")

        // 2. Verify tabs (\t) are preserved
        assertTrue(content.contains("\ttab character") || content.contains("\t"), "Content must preserve tabs")
        assertTrue(content.contains("\tindented code line 1"), "Content must preserve tabs in preformatted blocks")

        // 3. Verify newlines (\n) are preserved between paragraphs and lines
        assertTrue(content.contains("\n"), "Content must preserve newlines")
        assertTrue(
            content.contains("followed by second line\nand third line"),
            "Content must preserve single line breaks (<br/>) without extra blank lines"
        )

        // 4. Verify carriage returns (\r) are preserved
        assertTrue(content.contains("\r"), "Content must preserve carriage returns (\\r)")

        // 5. Verify lists are formatted with items on separate lines
        assertTrue(content.contains("First item\nSecond item"), "Content must preserve list items on consecutive lines")

        // 6. Verify no triple newlines or whitespace-only blank lines exist
        assertFalse(
            Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(content),
            "Content must not contain multiple consecutive blank lines:\n$content"
        )
    }

    @Test
    fun testEndToEndParagraphSpacingInSwordModule() = runBlocking {
        val epub = createMessyParagraphSpacingEpub()
        val tempSwordDir = Files.createTempDirectory("sword_para_spacing_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        val result = manager.importBook(epub)
        assertTrue(result.isSuccess, "Import should succeed: ${result.exceptionOrNull()?.message}")
        val book = result.getOrThrow()

        // Verify .bdt file itself has clean </p>\n<p> with no multi-line whitespace
        val initialsLower = book.initials.lowercase()
        val bdtFile = File(tempSwordDir, "modules/genbook/rawgenbook/$initialsLower/$initialsLower.bdt")
        val bdtXml = bdtFile.readText(Charsets.UTF_8)
        assertFalse(bdtXml.contains("</p>\n\n"), "BDT XML should not contain multiple newlines after </p>: $bdtXml")
        assertTrue(bdtXml.contains("</p>\n<p>"), "BDT XML should separate paragraphs with a single newline: $bdtXml")

        // Verify rendered content via SwordManager has exactly one blank line (\n\n) between paragraphs
        val key = manager.getBookKeys(book).first()
        val content = manager.readKeyContent(book, key)

        val expectedContent = "Sermon One\n\n" +
            "Quote paragraph text.\nReference 1:1\n\n" +
            "First real paragraph.\n\n" +
            "Second real paragraph.\n\n" +
            "Third real paragraph."

        assertEquals(expectedContent, content)
    }

    @Test
    fun testSwordGenBookWriterBinary() = runBlocking {
        val epub = createSyntheticEpub()
        val imported = EpubReader.parse(epub)

        val tempDir = Files.createTempDirectory("sword_writer_test").toFile()
        tempDir.deleteOnExit()

        val compilation = SwordGenBookWriter.write(tempDir, imported)
        assertTrue(compilation.confFile.exists(), "Configuration file should exist")

        val initials = compilation.initials.lowercase()
        assertEquals("testpilgri", initials)

        // Validate .conf contents
        val confLines = compilation.confFile.readLines()
        assertTrue(confLines.any { it.startsWith("[TESTPILGRI]") })
        assertTrue(confLines.any { it == "ModDrv=RawGenBook" })
        assertTrue(confLines.any { it == "SourceType=OSIS" })
        assertTrue(confLines.any { it == "Category=General Books" })
        assertTrue(confLines.any { it == "Encoding=UTF-8" })

        // Validate module binary files
        assertTrue(compilation.moduleDir.exists(), "Module directory should exist")
        assertTrue(compilation.bdtFile.exists(), "BDT file should exist")
        assertTrue(compilation.datFile.exists(), "DAT file should exist")
        assertTrue(compilation.idxFile.exists(), "IDX file should exist")

        // Root node + 2 chapters = 3 entries in .idx, each 4 bytes LE32 = 12 bytes
        assertEquals(12L, compilation.idxFile.length(), "Index file should have 12 bytes (3 entries * 4 bytes)")
        assertTrue(compilation.datFile.length() > 0, "DAT file should not be empty")
        assertTrue(compilation.bdtFile.length() > 0, "BDT text file should not be empty")
    }

    @Test
    fun testEndToEndImportAndJSwordReading() = runBlocking {
        val epub = createSyntheticEpub()

        val tempSwordDir = Files.createTempDirectory("sword_e2e_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        var progressReported = false

        val result = manager.importBook(epub) { step ->
            progressReported = true
        }

        assertTrue(result.isSuccess, "Import should succeed: ${result.exceptionOrNull()?.message}")
        assertTrue(progressReported, "Progress updates should have been reported")

        val book = result.getOrThrow()
        assertEquals("TESTPILGRI", book.initials)
        assertEquals(BookCategory.GENERAL_BOOK, book.bookCategory)

        // Verify book is recognized in manager's installedBooks
        assertTrue(
            manager.installedBooks.any { it.initials.equals("TESTPILGRI", ignoreCase = true) },
            "Imported book should be in manager.installedBooks"
        )

        // Verify reading keys via SwordManager
        val keys = manager.getBookKeys(book)
        assertTrue(keys.isNotEmpty(), "Module should have keys")
        assertTrue(keys.any { it.name.contains("Chapter 1") }, "Should have Chapter 1 key")

        // Read content of Chapter 1
        val ch1Key = keys.first { it.name.contains("Chapter 1") }
        val ch1Content = manager.readKeyContent(book, ch1Key)
        assertEquals(
            "Chapter 1: The Den\n\n" +
                "As I walked through the wilderness of this world, I lighted on a certain place where was a Den.\n\n" +
                "And I laid me down in that place to sleep: and, as I slept, I dreamed a dream.",
            ch1Content
        )

        // Read content of Chapter 2
        val ch2Key = keys.first { it.name.contains("Chapter 2") }
        val ch2Content = manager.readKeyContent(book, ch2Key)
        assertTrue(ch2Content.contains("miry slough"), "Chapter 2 content should contain expected text")
        assertFalse(
            Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(ch2Content),
            "Chapter 2 content must not contain multiple blank lines"
        )
    }

    @Test
    fun testBatchImportMultipleBooks() = runBlocking {
        val epub1 = createSyntheticEpub(title = "Multi Book Volume One", author = "Author One")
        val epub2 = createSyntheticEpub(title = "Multi Book Volume Two", author = "Author Two")

        val tempSwordDir = Files.createTempDirectory("sword_batch_e2e_app_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        val progressUpdates = mutableListOf<String>()

        val results = manager.importBooks(listOf(epub1, epub2)) { fileIdx, total, file, step ->
            progressUpdates.add("$fileIdx/$total - ${file.name}: $step")
        }

        assertEquals(2, results.size, "Should return results for 2 files")
        assertTrue(results.all { it.isSuccess }, "All imports in batch should succeed")
        assertTrue(progressUpdates.isNotEmpty(), "Progress updates should have been reported")

        val book1 = results[0].getOrThrow()
        val book2 = results[1].getOrThrow()

        assertTrue(book1.initials.isNotBlank())
        assertTrue(book2.initials.isNotBlank())
        assertTrue(
            book1.initials != book2.initials,
            "Module initials should be distinct: ${book1.initials} vs ${book2.initials}"
        )

        // Both books should be in manager.installedBooks
        assertTrue(manager.installedBooks.any { it.initials.equals(book1.initials, ignoreCase = true) })
        assertTrue(manager.installedBooks.any { it.initials.equals(book2.initials, ignoreCase = true) })

        // Check content of both
        val keys1 = manager.getBookKeys(book1)
        val keys2 = manager.getBookKeys(book2)
        assertTrue(keys1.isNotEmpty())
        assertTrue(keys2.isNotEmpty())
    }

    @Test
    fun testInspectCalvinEpub() = runBlocking {
        val calvinFile = File("/media/nvmd1/books/theology/Sermons on Ephesians - John Calvin.epub")
        if (!calvinFile.exists()) return@runBlocking

        val imported = EpubReader.parse(calvinFile)
        assertTrue(imported.chapters.isNotEmpty())

        val tempSwordDir = Files.createTempDirectory("sword_calvin_test").toFile()
        tempSwordDir.deleteOnExit()
        val manager = SwordManager(appDir = tempSwordDir)
        val result = manager.importBook(calvinFile)
        assertTrue(result.isSuccess)
        val book = result.getOrThrow()

        val keys = manager.getBookKeys(book)
        assertTrue(keys.isNotEmpty())

        // Verify no section in the real EPUB has multiple consecutive blank lines
        for (k in keys.take(10)) {
            val content = manager.readKeyContent(book, k)
            assertFalse(
                Regex("""\n[ \t]*\n[ \t]*\n""").containsMatchIn(content),
                "Section '${k.name}' should not have multiple consecutive blank lines"
            )
        }
    }

    @Test
    fun testBackgroundImportQueueProcessesAndDeduplicates() = runBlocking {
        val epub1 = createSyntheticEpub(title = "Queue Volume One", author = "Author One")
        val epub2 = createSyntheticEpub(title = "Queue Volume Two", author = "Author Two")
        val epub3 = createSyntheticEpub(title = "Queue Volume Three", author = "Author Three")
        val missingFile = File(Files.createTempDirectory("missing_queue_dir").toFile().apply { deleteOnExit() }, "missing.epub")

        val tempSwordDir = Files.createTempDirectory("sword_queue_test_dir").toFile()
        tempSwordDir.deleteOnExit()

        val manager = SwordManager(appDir = tempSwordDir)
        val service = manager.importService

        val firstBatch = manager.enqueueBookImports(listOf(epub1, missingFile, epub2))
        assertEquals(3, firstBatch.size)

        // Re-enqueuing an active file should be ignored while pending/in-progress
        val duplicate = manager.enqueueBookImport(epub2)
        assertEquals(null, duplicate)

        // Enqueue an additional book while background worker is active
        val appended = manager.enqueueBookImport(epub3)
        assertNotNull(appended)

        service.awaitIdle()

        assertFalse(service.isProcessing, "Worker should be idle after awaiting completion")
        assertEquals(4, service.queueItems.size)

        val successItems = service.queueItems.filter { it.status is ImportItemStatus.Success }
        val failedItems = service.queueItems.filter { it.status is ImportItemStatus.Failed }
        assertEquals(3, successItems.size)
        assertEquals(1, failedItems.size)
        assertEquals(missingFile, failedItems.first().file)

        val importedInitials = successItems.map { (it.status as ImportItemStatus.Success).book.initials }
        assertEquals(3, importedInitials.toSet().size)
        importedInitials.forEach { initials ->
            assertTrue(manager.installedBooks.any { it.initials.equals(initials, ignoreCase = true) })
        }

        assertTrue(service.removeItem(failedItems.first().id))
        assertEquals(3, service.queueItems.size)

        service.clearFinished()
        assertTrue(service.queueItems.isEmpty())
    }

    @Test
    fun testRetryFailedQueueItem() = runBlocking {
        val tempDir = Files.createTempDirectory("sword_queue_retry_dir").toFile().apply { deleteOnExit() }
        val targetFile = File(tempDir, "retry_target.epub")
        // Initially missing -> should fail
        val manager = SwordManager(appDir = Files.createTempDirectory("sword_retry_app_dir").toFile().apply { deleteOnExit() })
        val service = manager.importService

        val item = manager.enqueueBookImport(targetFile)
        assertNotNull(item)
        service.awaitIdle()

        assertTrue(service.queueItems.single().status is ImportItemStatus.Failed)

        // Populate valid EPUB at targetFile and retry
        val validEpub = createSyntheticEpub(title = "Retried Queue Book", author = "Retry Author")
        validEpub.copyTo(targetFile, overwrite = true)

        assertTrue(service.retryItem(item.id))
        service.awaitIdle()

        val finalStatus = service.queueItems.single().status
        assertTrue(finalStatus is ImportItemStatus.Success, "Retried item should succeed")
        assertEquals("Retried Queue Book", (finalStatus as ImportItemStatus.Success).book.name)
    }

    @Test
    fun testParseDroppedFileUrisAndTextPaths() {
        val tempDir = Files.createTempDirectory("dnd_parse_test").toFile().apply { deleteOnExit() }
        val epubWithSpaces = File(tempDir, "My Study Book.epub").apply { writeText("dummy") }
        val pdfFile = File(tempDir, "notes.PDF").apply { writeText("dummy") }
        val unsupportedTxt = File(tempDir, "readme.txt").apply { writeText("ignore") }
        val missingEpub = File(tempDir, "does_not_exist.epub")

        val uriResult = parseDroppedFileUris(
            listOf(
                epubWithSpaces.toURI().toString(),
                pdfFile.absolutePath,
                unsupportedTxt.toURI().toString(),
                missingEpub.toURI().toString(),
                "   "
            )
        )
        assertEquals(listOf(epubWithSpaces, pdfFile), uriResult.validFiles)
        assertEquals(2, uriResult.skippedCount)

        val textPayload = buildString {
            appendLine("# Comment line in text/uri-list")
            appendLine(epubWithSpaces.toURI().toString())
            appendLine("")
            appendLine(unsupportedTxt.absolutePath)
            appendLine(pdfFile.toURI().toString())
        }
        val textResult = parseDroppedTextPaths(textPayload)
        assertEquals(listOf(epubWithSpaces, pdfFile), textResult.validFiles)
        assertEquals(1, textResult.skippedCount)
    }

    @Test
    fun testBackgroundQueueRetainsLoadedFileAndModuleReference() = runBlocking {
        val epub = createSyntheticEpub(title = "Persistent Reference Book", author = "Test Author")
        val tempSwordDir = Files.createTempDirectory("sword_ref_retain_dir").toFile().apply { deleteOnExit() }
        val manager = SwordManager(appDir = tempSwordDir)

        val item = manager.enqueueBookImport(epub)
        assertNotNull(item)
        manager.importService.awaitIdle()

        val retained = manager.importService.queueItems.single()
        assertEquals(epub.absolutePath, retained.file.absolutePath)
        val status = retained.status as? ImportItemStatus.Success
        assertNotNull(status, "Loaded queue item should keep Success status with Book reference")
        assertEquals("Persistent Reference Book", status.book.name)
    }
}

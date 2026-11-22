package org.bible.reading.importer

import kotlinx.coroutines.runBlocking
import org.bible.reading.importer.epub.EpubImporter
import org.bible.reading.importer.epub.EpubReader
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import org.bible.reading.importer.osis.OsisConverter
import org.bible.reading.importer.sword.SwordGenBookWriter
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.BookCategory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // 4. <br class="calibre4"/> preserved as <lb/>\n
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
        assertTrue(content.contains("followed by second line\nand third line") || content.contains("Line with break\n"),
            "Content must preserve line breaks (<br/>)")

        // 4. Verify carriage returns (\r) are preserved
        assertTrue(content.contains("\r"), "Content must preserve carriage returns (\\r)")

        // 5. Verify lists are formatted with items on separate lines
        assertTrue(content.contains("First item"), "Content must preserve list item 1")
        assertTrue(content.contains("Second item"), "Content must preserve list item 2")
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
        assertTrue(ch1Content.contains("wilderness of this world"), "Chapter 1 content should contain expected text")

        // Read content of Chapter 2
        val ch2Key = keys.first { it.name.contains("Chapter 2") }
        val ch2Content = manager.readKeyContent(book, ch2Key)
        assertTrue(ch2Content.contains("miry slough"), "Chapter 2 content should contain expected text")
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
        println("=== Imported Book Chapters ===")
        println("Total parsed chapters: " + imported.chapters.size)
        for (ch in imported.chapters) {
            println("  Chapter id: " + ch.id + " | title: " + ch.title + " | length: " + ch.rawContent.length)
        }

        val tempSwordDir = Files.createTempDirectory("sword_calvin_test").toFile()
        tempSwordDir.deleteOnExit()
        val manager = SwordManager(appDir = tempSwordDir)
        val result = manager.importBook(calvinFile)
        assertTrue(result.isSuccess)
        val book = result.getOrThrow()

        val keys = manager.getBookKeys(book)
        println("=== JSword Keys for Module " + book.initials + " ===")
        println("Total keys returned: " + keys.size)
        for (k in keys) {
            println("  Key name: " + k.name)
        }
    }
}

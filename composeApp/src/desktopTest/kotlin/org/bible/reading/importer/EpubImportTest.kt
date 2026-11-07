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
        assertTrue(osisSection.contains("<title level=\"1\">Section Title</title>"))
        assertTrue(osisSection.contains("""<hi type="bold">bold text</hi>"""))
        assertTrue(osisSection.contains("""<hi type="italic">italic text</hi>"""))
        assertTrue(osisSection.contains("""<hi type="underline">underlined text</hi>"""))
        assertTrue(osisSection.contains("""<q type="block">Here is a profound quote.</q>"""))
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
}

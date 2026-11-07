package org.bible.reading.importer.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.bible.reading.importer.BookImporter
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import java.io.File

/**
 * Implements [BookImporter] for PDF documents (.pdf).
 */
class PdfImporter : BookImporter {
    override val formatName: String = "PDF"
    override val supportedExtensions: List<String> = listOf("pdf")

    override suspend fun parse(file: File): ImportedBook {
        return PdfReader.parse(file)
    }
}

/**
 * Document reader and structure extractor for PDF documents using Apache PDFBox.
 * Extracts metadata, document bookmarks/outline if available, or partitions pages/headings
 * into chapters.
 */
object PdfReader {

    suspend fun parse(file: File): ImportedBook = withContext(Dispatchers.IO) {
        require(file.exists()) { "PDF file does not exist: ${file.absolutePath}" }

        Loader.loadPDF(file).use { document ->
            val totalPages = document.numberOfPages
            if (totalPages <= 0) {
                throw IllegalArgumentException("PDF document has no pages: ${file.name}")
            }

            val metadata = parseMetadata(file, document)
            val chapters = extractChapters(document, metadata.title)

            if (chapters.isEmpty()) {
                throw IllegalArgumentException("PDF contains no readable text content: ${file.name}")
            }

            ImportedBook(metadata = metadata, chapters = chapters)
        }
    }

    private fun parseMetadata(file: File, document: PDDocument): ImportedBookMetadata {
        val info = document.documentInformation
        val rawTitle = info?.title?.trim()
        val title = if (!rawTitle.isNullOrBlank()) {
            rawTitle
        } else {
            file.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
        }

        val author = info?.author?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown"
        val description = info?.subject?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val identifier = "urn:pdf:${file.nameWithoutExtension.lowercase()}"

        val about = buildString {
            append("Imported from PDF: ").append(title)
            if (author != "Unknown") append(" by ").append(author)
            if (description.isNotBlank()) append("\n\n").append(description)
            append("\nTotal Pages: ").append(document.numberOfPages)
        }

        return ImportedBookMetadata(
            title = title,
            author = author,
            identifier = identifier,
            language = "en",
            description = description,
            about = about
        )
    }

    private data class OutlineEntry(val title: String, val pageIndex: Int)

    private fun extractChapters(document: PDDocument, defaultTitle: String): List<ImportedChapter> {
        val totalPages = document.numberOfPages

        // 1. Try reading document outline (bookmarks / TOC)
        val outline = document.documentCatalog?.documentOutline
        if (outline != null && outline.hasChildren()) {
            val outlineEntries = extractOutlineEntries(document, outline)
            if (outlineEntries.isNotEmpty()) {
                val chapters = extractChaptersFromOutline(document, outlineEntries, totalPages)
                if (chapters.isNotEmpty()) {
                    return chapters
                }
            }
        }

        // 2. Extract page by page text
        val pageTexts = (1..totalPages).map { pageNum ->
            val stripper = PDFTextStripper().apply {
                this.startPage = pageNum
                this.endPage = pageNum
                sortByPosition = true
            }
            stripper.getText(document).trim()
        }

        // 3. Check for chapter headings across pages
        val headingRegex = Regex("""(?im)^\s*((?:chapter|section|part|book|psalm|act|canto)\s+(?:\d+|[ivxlcdm]+|[a-z]+))[^\r\n]*""")
        val chapterStarts = mutableListOf<Pair<String, Int>>() // title to startPageIndex

        for (p in 0 until totalPages) {
            val text = pageTexts[p]
            if (text.isBlank()) continue
            // Check the first few lines
            val firstLines = text.lineSequence().take(4).joinToString("\n")
            val match = headingRegex.find(firstLines)
            if (match != null) {
                val headingTitle = match.value.trim()
                chapterStarts.add(headingTitle to p)
            }
        }

        // If we detected at least 2 distinct chapter headings, use them
        if (chapterStarts.size >= 2) {
            val chapters = mutableListOf<ImportedChapter>()
            // If there are pages before the first detected heading
            if (chapterStarts.first().second > 0) {
                val frontPages = (0 until chapterStarts.first().second).map { pageTexts[it] }
                val frontText = frontPages.filter { it.isNotBlank() }.joinToString("\n\n")
                if (frontText.isNotBlank()) {
                    chapters.add(
                        ImportedChapter(
                            id = "ch_0",
                            title = "Introduction",
                            rawContent = textToHtml("Introduction", frontText),
                            order = 0
                        )
                    )
                }
            }

            for (i in 0 until chapterStarts.size) {
                val (title, startPage) = chapterStarts[i]
                val endPage = if (i + 1 < chapterStarts.size) chapterStarts[i + 1].second else totalPages
                val chapterBody = (startPage until endPage).map { pageTexts[it] }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")

                chapters.add(
                    ImportedChapter(
                        id = "ch_${chapters.size + 1}",
                        title = title,
                        rawContent = textToHtml(title, chapterBody),
                        order = chapters.size + 1
                    )
                )
            }
            return chapters
        }

        // 4. Fallback: Page-based partitioning
        if (totalPages == 1) {
            return listOf(
                ImportedChapter(
                    id = "ch_1",
                    title = defaultTitle,
                    rawContent = textToHtml(defaultTitle, pageTexts.firstOrNull() ?: ""),
                    order = 1
                )
            )
        }

        // For <= 50 pages, 1 chapter per page
        if (totalPages <= 50) {
            return pageTexts.mapIndexedNotNull { index, text ->
                val title = "Page ${index + 1}"
                ImportedChapter(
                    id = "page_${index + 1}",
                    title = title,
                    rawContent = textToHtml(title, text),
                    order = index + 1
                )
            }
        }

        // For > 50 pages, chunk by 10 pages
        val chapters = mutableListOf<ImportedChapter>()
        val chunkSize = 10
        for (start in 0 until totalPages step chunkSize) {
            val end = (start + chunkSize).coerceAtMost(totalPages)
            val title = if (start + 1 == end) "Page $end" else "Pages ${start + 1}–$end"
            val combinedText = (start until end).map { pageTexts[it] }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            chapters.add(
                ImportedChapter(
                    id = "part_${chapters.size + 1}",
                    title = title,
                    rawContent = textToHtml(title, combinedText),
                    order = chapters.size + 1
                )
            )
        }
        return chapters
    }

    private fun extractOutlineEntries(
        document: PDDocument,
        outline: PDDocumentOutline
    ): List<OutlineEntry> {
        val entries = mutableListOf<OutlineEntry>()
        val totalPages = document.numberOfPages

        fun walk(item: PDOutlineItem?) {
            var current = item
            while (current != null) {
                val title = current.title?.trim()
                if (!title.isNullOrBlank()) {
                    val page = try {
                        current.findDestinationPage(document)
                    } catch (_: Exception) {
                        null
                    }
                    val pageIndex = if (page != null) document.pages.indexOf(page) else -1
                    if (pageIndex in 0 until totalPages) {
                        entries.add(OutlineEntry(title, pageIndex))
                    }
                }
                if (current.hasChildren()) {
                    walk(current.firstChild)
                }
                current = current.nextSibling
            }
        }

        walk(outline.firstChild)
        return entries.sortedBy { it.pageIndex }
    }

    private fun extractChaptersFromOutline(
        document: PDDocument,
        entries: List<OutlineEntry>,
        totalPages: Int
    ): List<ImportedChapter> {
        val chapters = mutableListOf<ImportedChapter>()

        // Check for front matter before first outline entry
        if (entries.first().pageIndex > 0) {
            val stripper = PDFTextStripper().apply {
                this.startPage = 1
                this.endPage = entries.first().pageIndex
                sortByPosition = true
            }
            val frontText = stripper.getText(document).trim()
            if (frontText.isNotBlank()) {
                chapters.add(
                    ImportedChapter(
                        id = "sec_0",
                        title = "Introduction",
                        rawContent = textToHtml("Introduction", frontText),
                        order = 0
                    )
                )
            }
        }

        for (i in entries.indices) {
            val entry = entries[i]
            val startPage = entry.pageIndex
            val endPage = if (i + 1 < entries.size) {
                (entries[i + 1].pageIndex - 1).coerceAtLeast(startPage)
            } else {
                totalPages - 1
            }

            val stripper = PDFTextStripper().apply {
                this.startPage = startPage + 1
                this.endPage = endPage + 1
                sortByPosition = true
            }
            val text = stripper.getText(document).trim()

            chapters.add(
                ImportedChapter(
                    id = "sec_${chapters.size + 1}",
                    title = entry.title,
                    rawContent = textToHtml(entry.title, text),
                    order = chapters.size + 1
                )
            )
        }

        return chapters
    }

    private fun textToHtml(title: String, rawText: String): String {
        val escapedTitle = escapeHtml(title)
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val rawParagraphs = normalized.split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return buildString {
            append("<h1>").append(escapedTitle).append("</h1>\n")
            if (rawParagraphs.isEmpty()) {
                val single = normalized.trim()
                if (single.isNotBlank()) {
                    append("<p>").append(escapeHtml(single)).append("</p>\n")
                } else {
                    append("<p></p>\n")
                }
            } else {
                for (p in rawParagraphs) {
                    var cleaned = p.replace(Regex("""(\w+)-\n(\w+)"""), "$1$2")
                    cleaned = cleaned.replace('\n', ' ').trim()
                    if (cleaned.isNotBlank()) {
                        append("<p>").append(escapeHtml(cleaned)).append("</p>\n")
                    }
                }
            }
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

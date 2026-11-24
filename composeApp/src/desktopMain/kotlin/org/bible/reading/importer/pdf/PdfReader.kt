package org.bible.reading.importer.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.bible.reading.importer.BookImporter
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedBookMetadata
import org.bible.reading.importer.model.ImportedChapter
import java.io.File
import java.io.Writer
import kotlin.math.abs

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
 * into chapters while filtering out running page headers and preserving paragraph boundaries,
 * tabs, indentation, intentional line breaks, and bottom-of-page footnotes.
 */
object PdfReader {

    private const val PARA_START_MARKER = "\uE000"
    private const val PARA_END_MARKER = "\uE001"

    private const val HEADER_MAX_Y_RATIO = 0.14f
    private const val HEADER_STRICT_Y_RATIO = 0.12f
    private const val HEADER_MAX_LINE_SLOT = 2
    private const val HEADER_MAX_LENGTH = 140
    private const val HEADER_FONT_RATIO_LIMIT = 1.12f
    private const val HEADER_Y_TOLERANCE_PT = 10f

    private const val STANDALONE_ARABIC_PAGE_TOKEN = "<PAGE_NUM>"
    private const val STANDALONE_ROMAN_PAGE_TOKEN = "<ROMAN_PAGE_NUM>"

    private val listOrQaLineRegex = Regex(
        """^(?:[-•*▪◦]|\d+[.)]|[A-Za-z][.)]|(?:Reply|Illustration|Note|Answer|Question|Q\d*|A\d*):)\s+"""
    )
    private val hyphenWrapRegex = Regex("""[A-Za-z]-$""")

    private val standaloneArabicPageRegex = Regex(
        """^(?:page\s+|p\.?\s*)?\d{1,5}$""",
        RegexOption.IGNORE_CASE
    )
    private val standaloneLowerRomanPageRegex = Regex("""^[ivxlcdm]{1,7}$""")
    private val leadingPageNumberRegex = Regex("""^\d{1,5}(?:\s*[|\-–—•·]\s*|\s+)(?=\S)""")
    private val trailingPageNumberRegex = Regex(
        """^(.+?)(?:\s*[|\-–—•·]\s*|\s+)(?:page\s+|p\.?\s*)?(\d{1,5})$""",
        RegexOption.IGNORE_CASE
    )
    private val trailingExplicitDelimiterRegex = Regex("""^(.+?)\s*[|\-–—•·]\s*(?:page\s+|p\.?\s*)?\d{1,5}$""", RegexOption.IGNORE_CASE)
    private val structuralNumberPrefixWords = setOf(
        "chapter", "chap", "ch", "section", "sec", "part", "pt", "book", "bk",
        "psalm", "ps", "act", "scene", "canto", "lesson", "question", "q",
        "article", "art", "sermon", "homily", "letter", "volume", "vol",
        "no", "nr", "figure", "fig", "table", "appendix", "p", "pp"
    )

    internal data class ExtractedLine(
        val text: String,
        val startsParagraph: Boolean,
        val endsParagraph: Boolean,
        val baselineY: Float,
        val pageHeight: Float,
        val avgFontSize: Float
    ) {
        val baselineRatio: Float
            get() = if (pageHeight > 0f) baselineY / pageHeight else 1f
    }

    internal data class ExtractedPage(
        val pageIndex: Int,
        val pageHeight: Float,
        val lines: List<ExtractedLine>
    )

    internal data class NormalizedHeaderKey(
        val key: String,
        val isStandalonePageNumber: Boolean,
        val hadAttachedPageNumber: Boolean
    )

    private data class HeaderCandidate(
        val pageIndex: Int,
        val lineIndex: Int,
        val line: ExtractedLine,
        val normalized: NormalizedHeaderKey,
        val gapBelowPt: Float
    )

    private class PageLineCollectingStripper : PDFTextStripper() {
        val pages = mutableListOf<ExtractedPage>()

        private var currentPageIndex = 0
        private var currentPageHeight = 792f
        private val currentPageLines = mutableListOf<ExtractedLine>()
        private val lineBuilder = StringBuilder()
        private val linePositions = mutableListOf<TextPosition>()
        private var pendingParagraphStart = false

        init {
            sortByPosition = true
            lineSeparator = "\n"
            paragraphStart = PARA_START_MARKER
            paragraphEnd = PARA_END_MARKER
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            currentPageIndex = (currentPageNo - 1).coerceAtLeast(0)
            currentPageHeight = page.cropBox?.height ?: page.mediaBox?.height ?: 792f
            currentPageLines.clear()
            lineBuilder.setLength(0)
            linePositions.clear()
            pendingParagraphStart = false
        }

        override fun writeParagraphStart() {
            pendingParagraphStart = true
        }

        override fun writeParagraphSeparator() {
            writeParagraphEnd()
            writeParagraphStart()
        }

        override fun writeParagraphEnd() {
            if (lineBuilder.isNotEmpty() || linePositions.isNotEmpty()) {
                flushLine(endsParagraph = true)
            } else if (currentPageLines.isNotEmpty()) {
                val last = currentPageLines.last()
                currentPageLines[currentPageLines.lastIndex] = last.copy(endsParagraph = true)
            }
            pendingParagraphStart = false
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            lineBuilder.append(text)
            linePositions.addAll(textPositions)
        }

        override fun writeWordSeparator() {
            lineBuilder.append(wordSeparator)
        }

        override fun writeLineSeparator() {
            flushLine(endsParagraph = false)
        }

        override fun endPage(page: PDPage) {
            if (lineBuilder.isNotEmpty() || linePositions.isNotEmpty()) {
                flushLine(endsParagraph = true)
            }
            pages.add(
                ExtractedPage(
                    pageIndex = currentPageIndex,
                    pageHeight = currentPageHeight,
                    lines = currentPageLines.toList()
                )
            )
            super.endPage(page)
        }

        private fun flushLine(endsParagraph: Boolean) {
            val text = lineBuilder.toString()
            val nonSpacePositions = linePositions.filter { it.unicode.isNotBlank() }
                .ifEmpty { linePositions }

            val baselineY = if (nonSpacePositions.isNotEmpty()) {
                val sortedY = nonSpacePositions.map { it.yDirAdj }.sorted()
                sortedY[sortedY.size / 2]
            } else {
                currentPageHeight
            }

            val avgFontSize = if (nonSpacePositions.isNotEmpty()) {
                nonSpacePositions.map { pos ->
                    val pt = pos.fontSizeInPt
                    if (pt > 0f) pt else pos.heightDir
                }.filter { it > 0f }.average().toFloat().takeIf { !it.isNaN() && it > 0f } ?: 12f
            } else {
                12f
            }

            currentPageLines.add(
                ExtractedLine(
                    text = text,
                    startsParagraph = pendingParagraphStart,
                    endsParagraph = endsParagraph,
                    baselineY = baselineY,
                    pageHeight = currentPageHeight,
                    avgFontSize = avgFontSize
                )
            )

            lineBuilder.setLength(0)
            linePositions.clear()
            pendingParagraphStart = false
        }
    }

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

    internal fun extractPagesWithMetadata(document: PDDocument): List<ExtractedPage> {
        val stripper = PageLineCollectingStripper().apply {
            startPage = 1
            endPage = document.numberOfPages
        }
        stripper.writeText(document, Writer.nullWriter())
        return stripper.pages
    }

    internal fun normalizeHeaderKey(rawText: String): NormalizedHeaderKey {
        val collapsed = rawText.replace('\u00A0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (collapsed.isBlank()) {
            return NormalizedHeaderKey("", isStandalonePageNumber = false, hadAttachedPageNumber = false)
        }

        if (standaloneArabicPageRegex.matches(collapsed)) {
            return NormalizedHeaderKey(
                key = STANDALONE_ARABIC_PAGE_TOKEN,
                isStandalonePageNumber = true,
                hadAttachedPageNumber = true
            )
        }

        if (standaloneLowerRomanPageRegex.matches(collapsed)) {
            return NormalizedHeaderKey(
                key = STANDALONE_ROMAN_PAGE_TOKEN,
                isStandalonePageNumber = true,
                hadAttachedPageNumber = true
            )
        }

        var working = collapsed
        var hadAttachedPageNum = false

        val leadMatch = leadingPageNumberRegex.find(working)
        if (leadMatch != null) {
            working = working.substring(leadMatch.value.length).trim()
            hadAttachedPageNum = true
        }

        val explicitTrailMatch = trailingExplicitDelimiterRegex.find(working)
        if (explicitTrailMatch != null) {
            working = explicitTrailMatch.groupValues[1].trim()
            hadAttachedPageNum = true
        } else {
            val trailMatch = trailingPageNumberRegex.find(working)
            if (trailMatch != null) {
                val prefix = trailMatch.groupValues[1].trimEnd()
                val lastWord = prefix.substringAfterLast(' ')
                    .trimEnd('.', ':', '-', '–', '—')
                    .lowercase()
                if (lastWord !in structuralNumberPrefixWords) {
                    working = prefix.trim()
                    hadAttachedPageNum = true
                }
            }
        }

        val cleanedKey = working
            .trim(' ', '|', '-', '–', '—', '•', '·')
            .lowercase()

        return NormalizedHeaderKey(
            key = cleanedKey,
            isStandalonePageNumber = false,
            hadAttachedPageNumber = hadAttachedPageNum
        )
    }

    /**
     * Detects and strips repeated running page headers near the top of pages while preserving:
     * - Unique chapter/section headings (even at the top of a page)
     * - Headings rendered noticeably larger than the document's median body font size
     * - Bottom-of-page lines (so footnotes and endnotes are never accidentally removed).
     */
    internal fun removeRunningHeaders(pages: List<ExtractedPage>): List<ExtractedPage> {
        val totalPages = pages.size
        if (totalPages < 3) return pages

        // 1. Estimate median body font size across non-blank lines (preferring main body band)
        val bodyBandFontSizes = pages.flatMap { page ->
            page.lines.filter {
                it.text.replace('\u00A0', ' ').isNotBlank() &&
                    it.baselineRatio in 0.14f..0.88f
            }.map { it.avgFontSize }
        }
        val allFontSizes = bodyBandFontSizes.ifEmpty {
            pages.flatMap { page ->
                page.lines.filter { it.text.replace('\u00A0', ' ').isNotBlank() }.map { it.avgFontSize }
            }
        }
        if (allFontSizes.isEmpty()) return pages
        val sortedFontSizes = allFontSizes.sorted()
        val bodyFontSize = sortedFontSizes[sortedFontSizes.size / 2]

        // 2. Collect candidate lines strictly from the top margin band (first 2 non-blank lines only)
        val candidates = mutableListOf<HeaderCandidate>()
        for (page in pages) {
            val nonBlankIndices = page.lines.withIndex()
                .filter { it.value.text.replace('\u00A0', ' ').isNotBlank() }

            var slot = 0
            for (posInNonBlank in nonBlankIndices.indices) {
                if (slot >= HEADER_MAX_LINE_SLOT) break
                val (lineIndex, line) = nonBlankIndices[posInNonBlank]
                val trimmed = line.text.replace('\u00A0', ' ').trim()

                // Stop scanning down the page once we exit the top margin or encounter large headings / long prose
                if (line.baselineRatio > HEADER_MAX_Y_RATIO) break
                if (line.avgFontSize > bodyFontSize * HEADER_FONT_RATIO_LIMIT) {
                    slot++
                    continue
                }
                if (trimmed.length > HEADER_MAX_LENGTH) break

                val normalized = normalizeHeaderKey(trimmed)
                if (normalized.key.isNotBlank()) {
                    val nextLine = nonBlankIndices.getOrNull(posInNonBlank + 1)?.value
                    val gapBelow = if (nextLine != null) {
                        (nextLine.baselineY - line.baselineY).coerceAtLeast(0f)
                    } else {
                        0f
                    }
                    candidates.add(
                        HeaderCandidate(
                            pageIndex = page.pageIndex,
                            lineIndex = lineIndex,
                            line = line,
                            normalized = normalized,
                            gapBelowPt = gapBelow
                        )
                    )
                }
                slot++
            }
        }

        if (candidates.isEmpty()) return pages

        // 3. Group by normalized key and cluster tightly by Y baseline so same-text chapter titles lower on a page aren't conflated
        val flaggedIndicesByPage = mutableMapOf<Int, MutableSet<Int>>()
        val confirmedHeaderBaselines = mutableListOf<Float>()
        val groupedByKey = candidates.groupBy { it.normalized.key }

        for ((_, group) in groupedByKey) {
            val clusters = clusterByBaselineY(group)
            for (cluster in clusters) {
                val distinctByPage = cluster.groupBy { it.pageIndex }
                    .mapValues { (_, list) -> list.first() }
                if (distinctByPage.size < 2) continue

                val pageIndices = distinctByPage.keys.sorted()
                val isStandalonePageNum = cluster.all { it.normalized.isStandalonePageNumber }
                val hasNearConsecutiveRun = hasNearConsecutivePageRun(pageIndices, maxStep = 2)
                val distinctPageFraction = pageIndices.size.toDouble() / totalPages.toDouble()

                val allSmallerFont = distinctByPage.values.all { it.line.avgFontSize <= bodyFontSize * 0.96f }
                val attachedPageNumCount = distinctByPage.values.count { it.normalized.hadAttachedPageNumber }
                val largeGapCount = distinctByPage.values.count { it.gapBelowPt >= bodyFontSize * 1.65f }
                val mostlyStrictTopBand = distinctByPage.values.all { it.line.baselineRatio <= HEADER_STRICT_Y_RATIO }
                val sameParity = pageIndices.map { it % 2 }.distinct().size == 1

                val visiblyHeaderLike = allSmallerFont ||
                    attachedPageNumCount >= 2 ||
                    largeGapCount >= (distinctByPage.size + 1) / 2

                val isRunningHeader = when {
                    isStandalonePageNum && pageIndices.size >= 2 && mostlyStrictTopBand -> true
                    pageIndices.size >= 3 && (distinctPageFraction >= 0.20 || hasNearConsecutiveRun || visiblyHeaderLike) -> true
                    pageIndices.size == 2 && hasNearConsecutiveRun && (
                        visiblyHeaderLike || (totalPages <= 6 && mostlyStrictTopBand && sameParity)
                    ) -> true
                    else -> false
                }

                if (isRunningHeader) {
                    val sortedY = cluster.map { it.line.baselineY }.sorted()
                    confirmedHeaderBaselines.add(sortedY[sortedY.size / 2])
                    for (cand in cluster) {
                        flaggedIndicesByPage
                            .getOrPut(cand.pageIndex) { mutableSetOf() }
                            .add(cand.lineIndex)
                    }
                }
            }
        }

        // 4. Catch single-page / short-chapter running headers sitting at the exact confirmed running-header Y slot
        if (confirmedHeaderBaselines.isNotEmpty()) {
            for (cand in candidates) {
                val alreadyFlagged = flaggedIndicesByPage[cand.pageIndex]?.contains(cand.lineIndex) == true
                if (alreadyFlagged) continue

                val matchesHeaderSlot = confirmedHeaderBaselines.any { bandY ->
                    abs(cand.line.baselineY - bandY) <= 5f
                }
                if (matchesHeaderSlot &&
                    cand.line.baselineRatio <= HEADER_STRICT_Y_RATIO &&
                    cand.line.avgFontSize <= bodyFontSize * 1.05f
                ) {
                    val isSmaller = cand.line.avgFontSize <= bodyFontSize * 0.96f
                    val hasLargeGap = cand.gapBelowPt >= bodyFontSize * 1.65f
                    if (cand.normalized.hadAttachedPageNumber || cand.normalized.isStandalonePageNumber || isSmaller || hasLargeGap) {
                        flaggedIndicesByPage
                            .getOrPut(cand.pageIndex) { mutableSetOf() }
                            .add(cand.lineIndex)
                    }
                }
            }
        }

        if (flaggedIndicesByPage.isEmpty()) return pages

        return pages.map { page ->
            val flagged = flaggedIndicesByPage[page.pageIndex] ?: return@map page
            val rebuiltLines = mutableListOf<ExtractedLine>()
            var carryParagraphStart = false

            for ((idx, line) in page.lines.withIndex()) {
                if (idx in flagged) {
                    if (line.startsParagraph && !line.endsParagraph) {
                        carryParagraphStart = true
                    } else if (line.endsParagraph) {
                        if (rebuiltLines.isNotEmpty() && !rebuiltLines.last().endsParagraph) {
                            val prev = rebuiltLines.last()
                            rebuiltLines[rebuiltLines.lastIndex] = prev.copy(endsParagraph = true)
                        }
                        carryParagraphStart = false
                    }
                } else {
                    val effectiveStarts = line.startsParagraph || carryParagraphStart
                    rebuiltLines.add(
                        if (effectiveStarts != line.startsParagraph) {
                            line.copy(startsParagraph = true)
                        } else {
                            line
                        }
                    )
                    carryParagraphStart = false
                }
            }

            if (rebuiltLines.isNotEmpty() && !rebuiltLines.last().endsParagraph) {
                val last = rebuiltLines.last()
                rebuiltLines[rebuiltLines.lastIndex] = last.copy(endsParagraph = true)
            }

            page.copy(lines = rebuiltLines)
        }
    }

    private fun clusterByBaselineY(candidates: List<HeaderCandidate>): List<List<HeaderCandidate>> {
        val sorted = candidates.sortedBy { it.line.baselineY }
        val clusters = mutableListOf<MutableList<HeaderCandidate>>()
        for (cand in sorted) {
            val target = clusters.firstOrNull { cluster ->
                val medianY = cluster[cluster.size / 2].line.baselineY
                abs(cand.line.baselineY - medianY) <= HEADER_Y_TOLERANCE_PT
            }
            if (target != null) {
                target.add(cand)
            } else {
                clusters.add(mutableListOf(cand))
            }
        }
        return clusters
    }

    private fun hasNearConsecutivePageRun(sortedPageIndices: List<Int>, maxStep: Int): Boolean {
        for (i in 1 until sortedPageIndices.size) {
            if (sortedPageIndices[i] - sortedPageIndices[i - 1] <= maxStep) {
                return true
            }
        }
        return false
    }

    internal fun renderPageText(page: ExtractedPage): String {
        if (page.lines.isEmpty()) return ""
        return buildString {
            var inParagraph = false
            for (line in page.lines) {
                if (line.startsParagraph && !inParagraph) {
                    append(PARA_START_MARKER)
                    inParagraph = true
                }
                append(line.text)
                if (line.endsParagraph) {
                    if (inParagraph) {
                        append(PARA_END_MARKER)
                        inParagraph = false
                    }
                    append('\n')
                } else {
                    append('\n')
                }
            }
            if (inParagraph) {
                append(PARA_END_MARKER)
                append('\n')
            }
        }
    }

    private fun stripStructureMarkers(text: String): String {
        return text.replace(PARA_START_MARKER, "").replace(PARA_END_MARKER, "")
    }

    private fun extractChapters(document: PDDocument, defaultTitle: String): List<ImportedChapter> {
        val totalPages = document.numberOfPages
        val rawPages = extractPagesWithMetadata(document)
        val cleanedPages = removeRunningHeaders(rawPages)
        val pageTexts = cleanedPages.map { renderPageText(it) }

        // 1. Try reading document outline (bookmarks / TOC)
        val outline = document.documentCatalog?.documentOutline
        if (outline != null && outline.hasChildren()) {
            val outlineEntries = extractOutlineEntries(document, outline)
            if (outlineEntries.isNotEmpty()) {
                val chapters = extractChaptersFromOutline(outlineEntries, pageTexts, totalPages)
                if (chapters.isNotEmpty()) {
                    return chapters
                }
            }
        }

        // 2. Check for chapter headings across header-cleaned pages
        val headingRegex = Regex("""(?im)^\s*((?:chapter|section|part|book|psalm|act|canto)\s+(?:\d+|[ivxlcdm]+|[a-z]+))[^\r\n]*""")
        val chapterStarts = mutableListOf<Pair<String, Int>>() // title to startPageIndex

        for (p in 0 until totalPages) {
            val plain = stripStructureMarkers(pageTexts[p])
            if (plain.isBlank()) continue
            val firstLines = plain.lineSequence().take(4).joinToString("\n")
            val match = headingRegex.find(firstLines)
            if (match != null) {
                val headingTitle = match.value.trim()
                chapterStarts.add(headingTitle to p)
            }
        }

        // If we detected at least 2 distinct chapter headings, use them
        if (chapterStarts.size >= 2) {
            val chapters = mutableListOf<ImportedChapter>()
            if (chapterStarts.first().second > 0) {
                val frontPages = (0 until chapterStarts.first().second).map { pageTexts[it] }
                val frontText = frontPages.filter { stripStructureMarkers(it).isNotBlank() }.joinToString("\n\n")
                if (stripStructureMarkers(frontText).isNotBlank()) {
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
                    .filter { stripStructureMarkers(it).isNotBlank() }
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

        // 3. Fallback: Page-based partitioning
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
            return pageTexts.mapIndexed { index, text ->
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
                .filter { stripStructureMarkers(it).isNotBlank() }
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
        entries: List<OutlineEntry>,
        pageTexts: List<String>,
        totalPages: Int
    ): List<ImportedChapter> {
        val chapters = mutableListOf<ImportedChapter>()

        // Check for front matter before first outline entry
        if (entries.first().pageIndex > 0) {
            val frontText = pageTexts.subList(0, entries.first().pageIndex)
                .filter { stripStructureMarkers(it).isNotBlank() }
                .joinToString("\n\n")
            if (stripStructureMarkers(frontText).isNotBlank()) {
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

            val text = pageTexts.subList(startPage, endPage + 1)
                .filter { stripStructureMarkers(it).isNotBlank() }
                .joinToString("\n\n")

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

    /**
     * Converts extracted PDF text (with optional [PARA_START_MARKER]/[PARA_END_MARKER] delimiters)
     * into structured HTML paragraphs while:
     * - Preserving paragraph boundaries detected by PDFBox or blank lines
     * - Preserving tabs (`\t`), carriage returns (`\r`), indentation, and intentional line breaks
     *   (headings, list/outline items, Q&A lines, short standalone lines) as `<br/>`
     * - Unwrapping mid-paragraph PDF column wraps and rejoining hyphenated wrapped words
     * - Skipping empty/non-breaking-space-only spacer paragraphs.
     */
    internal fun textToHtml(title: String, rawText: String): String {
        val escapedTitle = escapeHtml(title)
        val columnWidth = estimateColumnWidth(rawText)
        val rawParagraphs = splitIntoParagraphBlocks(rawText)

        return buildString {
            append("<h1>").append(escapedTitle).append("</h1>\n")
            val emittedParagraphs = mutableListOf<String>()

            for (block in rawParagraphs) {
                val lines = block.split('\n')
                val formatted = formatParagraphLines(lines, columnWidth)
                if (formatted.replace("\u00A0", "").isNotBlank()) {
                    emittedParagraphs.add(formatted)
                }
            }

            if (emittedParagraphs.isEmpty()) {
                val fallback = stripStructureMarkers(rawText).trim()
                if (fallback.replace("\u00A0", "").isNotBlank()) {
                    append("<p>").append(escapeHtml(fallback).replace("\n", "<br/>")).append("</p>\n")
                } else {
                    append("<p></p>\n")
                }
            } else {
                for (p in emittedParagraphs) {
                    val htmlParagraph = escapeHtml(p).replace("\n", "<br/>")
                    append("<p>").append(htmlParagraph).append("</p>\n")
                }
            }
        }
    }

    private fun estimateColumnWidth(rawText: String): Int {
        val plain = stripStructureMarkers(rawText).replace("\r\n", "\n").replace('\r', '\n')
        val longLineLengths = plain.lineSequence()
            .map { it.trimEnd() }
            .filter { it.trim().length >= 30 }
            .map { it.length }
            .sorted()
            .toList()

        return if (longLineLengths.isNotEmpty()) {
            val p90Index = ((longLineLengths.size - 1) * 0.9).toInt().coerceIn(longLineLengths.indices)
            maxOf(longLineLengths[p90Index], 55)
        } else {
            65
        }
    }

    private fun splitIntoParagraphBlocks(rawText: String): List<String> {
        val blankLineRegex = Regex("""\r?\n(?:[ \t\u00A0]*\r?\n)+""")
        val hasMarkers = rawText.contains(PARA_START_MARKER) || rawText.contains(PARA_END_MARKER)

        if (!hasMarkers) {
            return rawText.split(blankLineRegex).filter { it.replace("\u00A0", "").isNotBlank() }
        }

        val blocks = mutableListOf<String>()
        val markerRegex = Regex("""${Regex.escape(PARA_START_MARKER)}(.*?)${Regex.escape(PARA_END_MARKER)}""", RegexOption.DOT_MATCHES_ALL)
        var lastEnd = 0

        for (match in markerRegex.findAll(rawText)) {
            if (match.range.first > lastEnd) {
                val between = rawText.substring(lastEnd, match.range.first)
                for (part in between.split(blankLineRegex)) {
                    if (part.replace("\u00A0", "").isNotBlank()) {
                        blocks.add(part)
                    }
                }
            }
            val inner = match.groupValues[1]
            for (part in inner.split(blankLineRegex)) {
                if (part.replace("\u00A0", "").isNotBlank()) {
                    blocks.add(part)
                }
            }
            lastEnd = match.range.last + 1
        }

        if (lastEnd < rawText.length) {
            val trailing = rawText.substring(lastEnd)
            for (part in trailing.split(blankLineRegex)) {
                if (part.replace("\u00A0", "").isNotBlank()) {
                    blocks.add(part)
                }
            }
        }

        return blocks
    }

    private fun formatParagraphLines(lines: List<String>, columnWidth: Int): String {
        val nonBlankLines = lines.filter { it.replace("\u00A0", "").isNotBlank() }
        if (nonBlankLines.isEmpty()) return ""

        val sb = StringBuilder()
        val first = nonBlankLines[0].trimEnd(' ', '\u00A0')
        val firstFormatted = if (first.startsWith('\t') || first.startsWith("  ")) {
            first
        } else {
            first.trimStart(' ', '\u00A0')
        }
        sb.append(firstFormatted)

        for (i in 1 until nonBlankLines.size) {
            val rawNext = nonBlankLines[i].trimEnd(' ', '\u00A0')
            val lastNl = sb.lastIndexOf('\n')
            val currLine = if (lastNl >= 0) sb.substring(lastNl + 1) else sb.toString()
            val currTrimmed = currLine.trimEnd('\r', ' ', '\u00A0')

            val nextTrimmedStart = rawNext.trimStart(' ', '\u00A0')
            val firstWord = nextTrimmedStart.split(Regex("""\s+"""), limit = 2).firstOrNull() ?: ""

            val isIndented = rawNext.startsWith('\t') || rawNext.startsWith("  ")
            val hasTab = currLine.contains('\t') || rawNext.contains('\t')
            val isListOrQa = listOrQaLineRegex.containsMatchIn(nextTrimmedStart)
            val isShortBreak = (currTrimmed.trim().length + 1 + firstWord.length) < (columnWidth * 0.78)
            val hadCarriageReturn = currLine.endsWith('\r')

            if (isIndented || hasTab || isListOrQa || isShortBreak || hadCarriageReturn) {
                sb.append('\n')
                sb.append(if (isIndented || hasTab) rawNext else nextTrimmedStart)
            } else if (hyphenWrapRegex.containsMatchIn(currTrimmed) &&
                nextTrimmedStart.isNotEmpty() &&
                nextTrimmedStart[0].isLowerCase()
            ) {
                while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\u00A0')) {
                    sb.setLength(sb.length - 1)
                }
                if (sb.isNotEmpty() && sb.last() == '-') {
                    sb.setLength(sb.length - 1)
                }
                sb.append(nextTrimmedStart)
            } else {
                while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\u00A0')) {
                    sb.setLength(sb.length - 1)
                }
                sb.append(' ').append(nextTrimmedStart)
            }
        }

        return sb.toString()
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

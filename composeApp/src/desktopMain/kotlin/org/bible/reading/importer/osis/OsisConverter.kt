package org.bible.reading.importer.osis

import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedChapter

/**
 * Transforms XHTML/HTML chapter contents from imported documents into
 * Open Scripture Information Standard (OSIS) 2.1.1 compliant XML.
 *
 * Ensures faithful preservation of text formatting and whitespace:
 * - Tabs (`\t`), newlines (`\n`), and carriage returns (`\r` encoded as `&#13;` to survive XML normalization)
 * - Line breaks (`<br>`, `<hr>`) converted to `<lb/>`
 * - Paragraph separation (`<p>`, `<div>`) with a single `\n` between block elements in OSIS XML
 * - Preformatted blocks (`<pre>`) with internal whitespace and indentation
 * - Section headings (`<h1>`..`<h6>`) with `canonical="true"`
 * - Blockquotes (`<blockquote>`) and lists (`<ul>`, `<ol>`, `<li>`)
 * - Tables (`<table>`, `<tr>`, `<td>`, `<th>`)
 * - Whitespace entities (`&nbsp;`, `&ensp;`, `&emsp;`, `&thinsp;`, `&tab;`, `&NewLine;`)
 */
object OsisConverter {

    private const val PRE_PLACEHOLDER_PREFIX = "\u0000PRE_BLOCK_"
    private const val PRE_PLACEHOLDER_SUFFIX = "\u0000"

    private val allowedTags = setOf(
        "p", "title", "hi", "q", "list", "item", "note", "reference", "lb", "div", "table", "row", "cell"
    )

    /**
     * Converts a single chapter's HTML/XHTML content into an OSIS XML fragment
     * suitable for storing in a Sword RawGenBook section (.bdt file).
     */
    fun toOsisSection(chapter: ImportedChapter): String {
        val convertedBody = convertHtmlToOsisFragment(chapter.rawContent)

        val safeId = sanitizeOsisId(if (chapter.id.isNotBlank()) chapter.id else "ch${chapter.order}")
        val safeTitle = escapeXml(chapter.title.ifBlank { "Chapter ${chapter.order}" })

        return buildString {
            append("<div type=\"section\" osisID=\"").append(safeId).append("\">\n")
            // Include title if not already present in the converted body
            if (!convertedBody.contains("<title", ignoreCase = true)) {
                append("<title canonical=\"true\" level=\"1\">").append(safeTitle).append("</title>\n")
            }
            append(convertedBody).append("\n")
            append("</div>")
        }
    }

    /**
     * Converts an entire [ImportedBook] into a complete, standalone OSIS 2.1.1 XML document
     * conforming to osisCore.2.1.1.xsd.
     */
    fun toFullOsisDocument(book: ImportedBook, initials: String = ""): String {
        val workId = sanitizeOsisId(if (initials.isNotBlank()) initials else book.metadata.title)
        val lang = if (book.metadata.language.isNotBlank()) book.metadata.language.take(2).lowercase() else "en"

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
            append("""<osis xmlns="http://www.bibletechnologies.net/2003/OSIS/namespace" """).append("\n")
            append("""      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """).append("\n")
            append("""      xsi:schemaLocation="http://www.bibletechnologies.net/2003/OSIS/namespace https://crosswire.org/osis/osisCore.2.1.1.xsd">""").append("\n")
            append("""  <osisText osisIDWork="${escapeXml(workId)}" osisRefWork="defaultReferenceSystem" xml:lang="$lang">""").append("\n")
            append("""    <header>""").append("\n")
            append("""      <work osisWork="${escapeXml(workId)}">""").append("\n")
            append("""        <title>${escapeXml(book.metadata.title)}</title>""").append("\n")
            if (book.metadata.author.isNotBlank() && book.metadata.author != "Unknown") {
                append("""        <creator role="aut">${escapeXml(book.metadata.author)}</creator>""").append("\n")
            }
            append("""        <language type="ISO-639-1">$lang</language>""").append("\n")
            append("""        <refSystem>Bible</refSystem>""").append("\n")
            append("""      </work>""").append("\n")
            append("""    </header>""").append("\n")
            append("""    <body>""").append("\n")
            append("""      <div type="book">""").append("\n")

            for (ch in book.chapters) {
                append("   ").append(toOsisSection(ch).replace("\n", "\n  ")).append("\n")
            }

            append("""      </div>""").append("\n")
            append("""    </body>""").append("\n")
            append("""  </osisText>""").append("\n")
            append("""</osis>""")
        }
    }

    /**
     * Converts raw XHTML/HTML text into valid OSIS elements while preserving whitespace and formatting:
     * - Preformatted text <pre> -> preserves tabs, carriage returns, and newlines with <lb/>
     * - Headings <h1>..<h6> -> <title canonical="true" level="...">
     * - Line breaks <br>, <hr> -> <lb/>
     * - Paragraphs <p> -> <p> with a single newline between block tags
     * - <em>, <i> -> <hi type="italic">
     * - <strong>, <b> -> <hi type="bold">
     * - <u> -> <hi type="underline">
     * - <blockquote> -> <q type="block">
     * - <ul>, <ol>, <li> -> <list>, <item>
     * - <table>, <tr>, <td>, <th> -> <table>, <row>, <cell>
     * - <aside>, class="footnote" -> <note type="explanation">
     * - Literal whitespace such as tabs (\t), new lines (\n), and carriage returns (\r) inside paragraphs
     * - Strips scripts, styles, iframes, invalid tags, and empty spacer paragraphs.
     */
    fun convertHtmlToOsisFragment(rawHtml: String): String {
        var text = rawHtml

        // 1. Strip comments
        text = text.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")

        // 2. Strip <head>...</head>, <script>...</script>, <style>...</style>
        text = text.replace(Regex("""<head[^>]*>.*?</head>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        text = text.replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        text = text.replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        // 3. Extract <body> if present
        val bodyMatch = Regex("""<body[^>]*>(.*?)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }

        // 4. Decode / normalize common HTML whitespace and typography entities
        text = normalizeHtmlEntities(text)

        // 5. Preformatted blocks: preserve internal whitespace (tabs, carriage returns, newlines) inside <pre>.
        //    Protect with placeholders so inter-block whitespace normalization leaves <pre> untouched.
        val preBlocks = mutableListOf<String>()
        text = text.replace(Regex("""<pre\b[^>]*>(.*?)</pre>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            var preContent = m.groupValues[1]
            if (preContent.startsWith("\r\n")) {
                preContent = preContent.substring(2)
            } else if (preContent.startsWith("\n") || preContent.startsWith("\r")) {
                preContent = preContent.substring(1)
            }
            preContent = stripDisallowedTags(preContent)
            val withLb = preContent.replace(Regex("""\r\n|\r|\n""")) { nl ->
                when (nl.value) {
                    "\r\n", "\r" -> "&#13;<lb/>"
                    else -> "<lb/>"
                }
            }
            val index = preBlocks.size
            preBlocks.add("<p>$withLb</p>")
            "\n$PRE_PLACEHOLDER_PREFIX$index$PRE_PLACEHOLDER_SUFFIX\n"
        }

        // 6. Preserve raw carriage returns in XML character data (so XML parsers don't normalize \r to \n)
        text = preserveCarriageReturns(text)

        // 7. Line break / horizontal rule: emit <lb/> and consume following HTML source indentation newline if any
        text = text.replace(Regex("""<br\b[^>]*\/?>(?:[ \t]*(?:&#13;)?\n[ \t]*)?""", RegexOption.IGNORE_CASE), "<lb/>")
        text = text.replace(Regex("""<hr\b[^>]*\/?>(?:[ \t]*(?:&#13;)?\n[ \t]*)?""", RegexOption.IGNORE_CASE), "<lb/>")

        // 8. Footnotes / Notes: <aside...> -> <note type="explanation">
        text = text.replace(Regex("""<aside\b[^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")
        text = text.replace(Regex("""</aside>""", RegexOption.IGNORE_CASE), "</note>")
        text = text.replace(Regex("""<div[^>]*class=["'][^"']*footnote[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")

        // 9. Headings <h1>..<h6> -> <title canonical="true" level="1..6">
        for (i in 1..6) {
            text = text.replace(Regex("""<h$i\b[^>]*>(.*?)</h$i>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                "\n<title canonical=\"true\" level=\"$i\">${m.groupValues[1].trim()}</title>\n"
            }
        }

        // 10. Blockquotes -> <q type="block">
        text = text.replace(Regex("""<blockquote\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<q type=\"block\">")
        text = text.replace(Regex("""</blockquote>""", RegexOption.IGNORE_CASE), "</q>\n")

        // 11. Lists -> <list> and <item>
        text = text.replace(Regex("""<ul\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<list>\n")
        text = text.replace(Regex("""</ul>""", RegexOption.IGNORE_CASE), "\n</list>\n")
        text = text.replace(Regex("""<ol\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<list>\n")
        text = text.replace(Regex("""</ol>""", RegexOption.IGNORE_CASE), "\n</list>\n")
        text = text.replace(Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<item>")
        text = text.replace(Regex("""</li>""", RegexOption.IGNORE_CASE), "</item>\n")

        // 12. Tables -> <table>, <row>, <cell>
        text = text.replace(Regex("""<table\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<table>\n")
        text = text.replace(Regex("""</table>""", RegexOption.IGNORE_CASE), "\n</table>\n")
        text = text.replace(Regex("""<tr\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<row>\n")
        text = text.replace(Regex("""</tr>""", RegexOption.IGNORE_CASE), "\n</row>")
        text = text.replace(Regex("""<th\b[^>]*>""", RegexOption.IGNORE_CASE), "<cell>")
        text = text.replace(Regex("""</th>""", RegexOption.IGNORE_CASE), "\t</cell>")
        text = text.replace(Regex("""<td\b[^>]*>""", RegexOption.IGNORE_CASE), "<cell>")
        text = text.replace(Regex("""</td>""", RegexOption.IGNORE_CASE), "\t</cell>")

        // 13. Paragraphs: ensure separation between paragraphs
        text = text.replace(Regex("""<p\b[^>]*>""", RegexOption.IGNORE_CASE), "\n<p>")
        text = text.replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "</p>\n")

        // 14. Divisions: if <div> is used as block divider
        text = text.replace(Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""</div>""", RegexOption.IGNORE_CASE), "\n")

        // 15. Styles: italic, bold, underline
        text = text.replace(Regex("""<(?:em|i)\b[^>]*>(.*?)</(?:em|i)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"italic\">${m.groupValues[1]}</hi>"
        }
        text = text.replace(Regex("""<(?:strong|b)\b[^>]*>(.*?)</(?:strong|b)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"bold\">${m.groupValues[1]}</hi>"
        }
        text = text.replace(Regex("""<u\b[^>]*>(.*?)</u>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"underline\">${m.groupValues[1]}</hi>"
        }

        // 16. Bible reference anchors or hyperlinks
        text = text.replace(Regex("""<a\b[^>]*osisRef=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<reference osisRef=\"${escapeXml(m.groupValues[1])}\">${m.groupValues[2]}</reference>"
        }
        // General anchors: strip the tag, keep inner text
        text = text.replace(Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            m.groupValues[1]
        }

        // 17. Strip all remaining disallowed tags while keeping their text contents
        text = stripDisallowedTags(text)

        // 18. Unwrap empty or whitespace-only <hi> tags (e.g. <strong class="calibre6"> </strong>)
        text = text.replace(Regex("""<hi\b[^>]*>([\s\u00A0]*(?:(?:&#160;|&#13;)[\s\u00A0]*)*)</hi>""", RegexOption.IGNORE_CASE)) { m ->
            m.groupValues[1]
        }

        // 19. Remove empty / spacer-only block elements (e.g. <p></p>, <p>&nbsp;</p>, <p><br/></p>, <h2>&nbsp;</h2>)
        val emptyContentPattern = """(?:[\s\u00A0]|&#160;|&#13;|<lb\s*/?>)*"""
        text = text.replace(Regex("""<p>$emptyContentPattern</p>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""<title\b[^>]*>$emptyContentPattern</title>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""<item>$emptyContentPattern</item>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""<q\b[^>]*>$emptyContentPattern</q>""", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("""<list>$emptyContentPattern</list>""", RegexOption.IGNORE_CASE), "\n")

        // 20. Trim leading/trailing HTML formatting whitespace inside <p>, <title>, and <item>
        //     (preserve intentional leading tabs or 2+ space indentation on <p> when not preceded by an HTML newline)
        text = text.replace(Regex("""<p>(?:(?:[ \t\u00A0]|&#13;)*\n[ \t\u00A0]*|(?:\u00A0|&#13;)+| (?![ \u00A0]))+"""), "<p>")
        text = text.replace(Regex("""(?:[\s\u00A0]|&#13;)+</p>"""), "</p>")
        text = text.replace(Regex("""(<title\b[^>]*>)(?:[\s\u00A0]|&#13;)+""")) { m -> m.groupValues[1] }
        text = text.replace(Regex("""(?:[\s\u00A0]|&#13;)+</title>"""), "</title>")
        text = text.replace(Regex("""<item>(?:[\s\u00A0]|&#13;)+"""), "<item>")
        text = text.replace(Regex("""(?:[\s\u00A0]|&#13;)+</item>"""), "</item>")

        // 21. Normalize all whitespace/indentation/CRLF between block tags to a single newline
        val blockBoundary = """(?:</?(?:p|title|q|list|item|table|row)\b[^>]*>|${Regex.escape(PRE_PLACEHOLDER_PREFIX)}\d+${Regex.escape(PRE_PLACEHOLDER_SUFFIX)})"""
        val wsAndCr = """(?:[\s\u00A0]|&#13;)+"""
        text = text.replace(Regex("""($blockBoundary)$wsAndCr(?=$blockBoundary)""")) { m ->
            "${m.groupValues[1]}\n"
        }

        // Collapse any remaining runs of 2+ newlines outside <pre> to a single newline
        text = text.replace(Regex("""(?:(?:&#13;)?\n[ \t]*){2,}"""), "\n")

        // 22. Restore <pre> blocks
        for ((index, preOsis) in preBlocks.withIndex()) {
            text = text.replace("$PRE_PLACEHOLDER_PREFIX$index$PRE_PLACEHOLDER_SUFFIX", preOsis)
        }

        // 23. Ensure text is enclosed in at least <p> tags if no block tags exist
        val trimmed = text.replace(Regex("""^(?:[\s\u00A0]|&#13;)+|(?:[\s\u00A0]|&#13;)+$"""), "")
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("<p") &&
            !trimmed.startsWith("<title") &&
            !trimmed.startsWith("<div") &&
            !trimmed.startsWith("<q") &&
            !trimmed.startsWith("<list") &&
            !trimmed.startsWith("<table")
        ) {
            return "<p>$trimmed</p>"
        }

        return if (trimmed.isBlank()) "<p></p>" else trimmed
    }

    private fun stripDisallowedTags(input: String): String {
        return input.replace(Regex("""</?([a-zA-Z0-9]+)(\s+[^>]*)?/?>""")) { m ->
            val tagName = m.groupValues[1].lowercase()
            if (allowedTags.contains(tagName)) {
                sanitizeTagAttributes(tagName, m.value)
            } else {
                ""
            }
        }
    }

    private fun sanitizeTagAttributes(tagName: String, fullTag: String): String {
        val isClosing = fullTag.startsWith("</")
        if (isClosing) return "</$tagName>"

        val isSelfClosing = fullTag.endsWith("/>")

        return when (tagName) {
            "title" -> {
                val level = Regex("""level=["']?(\d)["']?""").find(fullTag)?.groupValues?.get(1) ?: "1"
                "<title canonical=\"true\" level=\"$level\">"
            }
            "hi" -> {
                val type = Regex("""type=["']?([a-zA-Z]+)["']?""").find(fullTag)?.groupValues?.get(1) ?: "italic"
                "<hi type=\"$type\">"
            }
            "q" -> "<q type=\"block\">"
            "note" -> "<note type=\"explanation\">"
            "lb" -> "<lb/>"
            "list" -> "<list>"
            "item" -> "<item>"
            "p" -> "<p>"
            "div" -> "<div type=\"section\">"
            "table" -> "<table>"
            "row" -> "<row>"
            "cell" -> "<cell>"
            "reference" -> {
                val osisRef = Regex("""osisRef=["']([^"']+)["']""").find(fullTag)?.groupValues?.get(1)
                if (osisRef != null) "<reference osisRef=\"$osisRef\">" else "<reference>"
            }
            else -> if (isSelfClosing) "<$tagName/>" else "<$tagName>"
        }
    }

    private fun preserveCarriageReturns(text: String): String {
        // Encode literal carriage returns as character reference &#13; so that
        // W3C XML parsers (which normalize \r\n and \r to \n) preserve them faithfully
        return text.replace("\r", "&#13;")
    }

    private fun normalizeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", "&#160;")
            .replace("&ensp;", "&#8194;")
            .replace("&emsp;", "&#8195;")
            .replace("&thinsp;", "&#8201;")
            .replace("&zwnj;", "&#8204;")
            .replace("&zwj;", "&#8205;")
            .replace("&tab;", "\t")
            .replace("&NewLine;", "\n")
            .replace("&mdash;", "&#8212;")
            .replace("&ndash;", "&#8211;")
            .replace("&hellip;", "&#8230;")
            .replace("&ldquo;", "&#8220;")
            .replace("&rdquo;", "&#8221;")
            .replace("&lsquo;", "&#8216;")
            .replace("&rsquo;", "&#8217;")
            .replace("&laquo;", "&#171;")
            .replace("&raquo;", "&#187;")
            .replace("&copy;", "&#169;")
            .replace("&reg;", "&#174;")
            .replace("&trade;", "&#8482;")
            .replace(Regex("""&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)"""), "&amp;")
    }

    private fun sanitizeOsisId(raw: String): String {
        val cleaned = raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "")
        return if (cleaned.isNotEmpty() && (cleaned[0].isLetter() || cleaned[0] == '_')) cleaned else "doc_$cleaned"
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

package org.bible.reading.importer.osis

import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedChapter

/**
 * Transforms XHTML/HTML chapter contents from imported documents into
 * Open Scripture Information Standard (OSIS) 2.1.1 compliant XML.
 *
 * Ensures faithful preservation of text formatting and whitespace:
 * - Tabs (`\t`), newlines (`\n`), and carriage returns (`\r` encoded as `&#13;` to survive XML normalization)
 * - Line breaks (`<br>`, `<hr>`) converted to `<lb/>\n`
 * - Paragraph separation (`<p>`, `<div>`)
 * - Preformatted blocks (`<pre>`) with internal whitespace and indentation
 * - Section headings (`<h1>`..`<h6>`) with `canonical="true"` and paragraph spacing
 * - Blockquotes (`<blockquote>`) and lists (`<ul>`, `<ol>`, `<li>`)
 * - Tables (`<table>`, `<tr>`, `<td>`, `<th>`)
 * - Whitespace entities (`&nbsp;`, `&ensp;`, `&emsp;`, `&thinsp;`, `&tab;`, `&NewLine;`)
 */
object OsisConverter {

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
                append("  <title canonical=\"true\" level=\"1\">").append(safeTitle).append("</title>\n\n")
            }
            append("  ").append(convertedBody).append("\n")
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
     * - Preformatted text <pre> -> preserves tabs, carriage returns, and newlines with <lb/>\n
     * - Headings <h1>..<h6> -> <title canonical="true" level="...">
     * - Line breaks <br>, <hr> -> <lb/>\n
     * - Paragraphs <p> -> <p> with block separation
     * - <em>, <i> -> <hi type="italic">
     * - <strong>, <b> -> <hi type="bold">
     * - <u> -> <hi type="underline">
     * - <blockquote> -> <q type="block">
     * - <ul>, <ol>, <li> -> <list>, <item>
     * - <table>, <tr>, <td>, <th> -> <table>, <row>, <cell>
     * - <aside>, class="footnote" -> <note type="explanation">
     * - Literal whitespace such as tabs (\t), new lines (\n), and carriage returns (\r)
     * - Strips scripts, styles, iframes, invalid tags.
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

        // 5. Preformatted blocks: preserve whitespace (tabs, carriage returns, new lines) inside <pre>
        text = text.replace(Regex("""<pre\b[^>]*>(.*?)</pre>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val preContent = m.groupValues[1]
            val withLb = preContent
                .replace("\r\n", "&#13;\n<lb/>")
                .replace("\r", "&#13;<lb/>\n")
                .replace("\n", "\n<lb/>\n")
            "\n\n<p>$withLb</p>\n\n"
        }

        // 6. Preserve raw carriage returns in XML character data (so XML parsers don't normalize \r to \n)
        text = preserveCarriageReturns(text)

        // 7. Line break / horizontal rule (match any attributes, e.g. <br class="calibre"/>)
        text = text.replace(Regex("""<br\b[^>]*\/?>""", RegexOption.IGNORE_CASE), "<lb/>\n")
        text = text.replace(Regex("""<hr\b[^>]*\/?>""", RegexOption.IGNORE_CASE), "<lb/>\n")

        // 8. Footnotes / Notes: <aside...> -> <note type="explanation">
        text = text.replace(Regex("""<aside\b[^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")
        text = text.replace(Regex("""</aside>""", RegexOption.IGNORE_CASE), "</note>")
        text = text.replace(Regex("""<div[^>]*class=["'][^"']*footnote[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")

        // 9. Headings <h1>..<h6> -> <title canonical="true" level="1..6">
        for (i in 1..6) {
            text = text.replace(Regex("""<h$i\b[^>]*>(.*?)</h$i>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                "\n\n<title canonical=\"true\" level=\"$i\">${m.groupValues[1].trim()}</title>\n\n"
            }
        }

        // 10. Blockquotes -> <q type="block">
        text = text.replace(Regex("""<blockquote\b[^>]*>""", RegexOption.IGNORE_CASE), "\n\n<q type=\"block\">")
        text = text.replace(Regex("""</blockquote>""", RegexOption.IGNORE_CASE), "\n</q>\n")

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
        // Allow valid OSIS fragment tags
        val allowedTags = setOf("p", "title", "hi", "q", "list", "item", "note", "reference", "lb", "div", "table", "row", "cell")

        text = text.replace(Regex("""</?([a-zA-Z0-9]+)(\s+[^>]*)?/?>""")) { m ->
            val tagName = m.groupValues[1].lowercase()
            if (allowedTags.contains(tagName)) {
                val rawTag = m.value
                sanitizeTagAttributes(tagName, rawTag)
            } else {
                ""
            }
        }

        // Normalize excessive newlines (keep up to 2 for paragraph breaks)
        text = text.replace(Regex("""\n{3,}"""), "\n")

        // 18. Ensure text is enclosed in at least <p> tags if no paragraphs or titles exist
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && !trimmed.startsWith("<p") && !trimmed.startsWith("<title") && !trimmed.startsWith("<div")) {
            return "<p>$trimmed</p>"
        }

        return if (trimmed.isBlank()) "<p></p>" else trimmed
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
            "lb" -> "<lb/>\n"
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

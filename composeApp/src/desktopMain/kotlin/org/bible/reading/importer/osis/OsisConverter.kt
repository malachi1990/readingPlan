package org.bible.reading.importer.osis

import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.model.ImportedChapter

/**
 * Transforms XHTML/HTML chapter contents from imported documents into
 * Open Scripture Information Standard (OSIS) 2.1.1 compliant XML.
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
                append("  <title level=\"1\">").append(safeTitle).append("</title>\n")
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
                append("        ").append(toOsisSection(ch).replace("\n", "\n        ")).append("\n")
            }

            append("""      </div>""").append("\n")
            append("""    </body>""").append("\n")
            append("""  </osisText>""").append("\n")
            append("""</osis>""")
        }
    }

    /**
     * Converts raw XHTML/HTML text into valid OSIS elements:
     * - Headings <h1>..<h6> -> <title level="...">
     * - <p> -> <p>
     * - <em>, <i> -> <hi type="italic">
     * - <strong>, <b> -> <hi type="bold">
     * - <u> -> <hi type="underline">
     * - <blockquote> -> <q type="block">
     * - <ul>, <ol>, <li> -> <list>, <item>
     * - <aside>, class="footnote" -> <note type="explanation">
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

        // 4. Decode / normalize common HTML entities to standard XML characters
        text = normalizeHtmlEntities(text)

        // 5. Line break / horizontal rule
        text = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "<lb/>")
        text = text.replace(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE), "<lb/>")

        // 6. Footnotes / Notes: <aside...> -> <note type="explanation">
        text = text.replace(Regex("""<aside[^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")
        text = text.replace(Regex("""</aside>""", RegexOption.IGNORE_CASE), "</note>")
        text = text.replace(Regex("""<div[^>]*class=["'][^"']*footnote[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE), "<note type=\"explanation\">")

        // 7. Headings <h1>..<h6> -> <title level="1..6">
        for (i in 1..6) {
            text = text.replace(Regex("""<h$i[^>]*>(.*?)</h$i>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                "<title level=\"$i\">${m.groupValues[1].trim()}</title>"
            }
        }

        // 8. Blockquotes -> <q type="block">
        text = text.replace(Regex("""<blockquote[^>]*>""", RegexOption.IGNORE_CASE), "<q type=\"block\">")
        text = text.replace(Regex("""</blockquote>""", RegexOption.IGNORE_CASE), "</q>")

        // 9. Lists -> <list> and <item>
        text = text.replace(Regex("""<ul[^>]*>""", RegexOption.IGNORE_CASE), "<list>")
        text = text.replace(Regex("""</ul>""", RegexOption.IGNORE_CASE), "</list>")
        text = text.replace(Regex("""<ol[^>]*>""", RegexOption.IGNORE_CASE), "<list>")
        text = text.replace(Regex("""</ol>""", RegexOption.IGNORE_CASE), "</list>")
        text = text.replace(Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE), "<item>")
        text = text.replace(Regex("""</li>""", RegexOption.IGNORE_CASE), "</item>")

        // 10. Styles: italic & bold & underline
        text = text.replace(Regex("""<(?:em|i)\b[^>]*>(.*?)</(?:em|i)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"italic\">${m.groupValues[1]}</hi>"
        }
        text = text.replace(Regex("""<(?:strong|b)\b[^>]*>(.*?)</(?:strong|b)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"bold\">${m.groupValues[1]}</hi>"
        }
        text = text.replace(Regex("""<u\b[^>]*>(.*?)</u>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<hi type=\"underline\">${m.groupValues[1]}</hi>"
        }

        // 11. Bible reference anchors or hyperlinks
        text = text.replace(Regex("""<a\b[^>]*osisRef=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            "<reference osisRef=\"${escapeXml(m.groupValues[1])}\">${m.groupValues[2]}</reference>"
        }
        // General anchors: strip the tag, keep inner text
        text = text.replace(Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            m.groupValues[1]
        }

        // 12. Strip all remaining disallowed tags while keeping their text contents
        // Allow only valid OSIS fragment tags: p, title, hi, q, list, item, note, reference, lb, div
        val allowedTags = setOf("p", "title", "hi", "q", "list", "item", "note", "reference", "lb", "div")

        text = text.replace(Regex("""</?([a-zA-Z0-9]+)(\s+[^>]*)?/?>""")) { m ->
            val tagName = m.groupValues[1].lowercase()
            if (allowedTags.contains(tagName)) {
                // Keep the tag if allowed, but sanitize attributes
                val rawTag = m.value
                sanitizeTagAttributes(tagName, rawTag)
            } else {
                // Disallowed tag: strip out the markup
                ""
            }
        }

        // 13. Ensure text is enclosed in at least <p> tags if no paragraphs or titles exist
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && !trimmed.startsWith("<")) {
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
                "<title level=\"$level\">"
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
            "reference" -> {
                val osisRef = Regex("""osisRef=["']([^"']+)["']""").find(fullTag)?.groupValues?.get(1)
                if (osisRef != null) "<reference osisRef=\"$osisRef\">" else "<reference>"
            }
            else -> if (isSelfClosing) "<$tagName/>" else "<$tagName>"
        }
    }

    private fun normalizeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", "&#160;")
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

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

    private val IGNORE_CASE_DOT_ALL = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val COMMENT_REGEX = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val HEAD_REGEX = Regex("""<head[^>]*>.*?</head>""", IGNORE_CASE_DOT_ALL)
    private val SCRIPT_REGEX = Regex("""<script[^>]*>.*?</script>""", IGNORE_CASE_DOT_ALL)
    private val STYLE_REGEX = Regex("""<style[^>]*>.*?</style>""", IGNORE_CASE_DOT_ALL)
    private val BODY_REGEX = Regex("""<body[^>]*>(.*?)</body>""", IGNORE_CASE_DOT_ALL)

    private val PRE_REGEX = Regex("""<pre\b[^>]*>(.*?)</pre>""", IGNORE_CASE_DOT_ALL)
    private val PRE_NEWLINE_REGEX = Regex("""\r\n|\r|\n""")

    private val BR_REGEX = Regex("""<br\b[^>]*/?>(?:[ \t]*(?:&#13;)?\n[ \t]*)?""", RegexOption.IGNORE_CASE)
    private val HR_REGEX = Regex("""<hr\b[^>]*/?>(?:[ \t]*(?:&#13;)?\n[ \t]*)?""", RegexOption.IGNORE_CASE)

    private val ASIDE_OPEN_REGEX = Regex("""<aside\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ASIDE_CLOSE_REGEX = Regex("""</aside>""", RegexOption.IGNORE_CASE)
    private val FOOTNOTE_DIV_REGEX = Regex("""<div[^>]*class=["'][^"']*footnote[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)

    private val HEADING_REGEXES: List<Pair<Int, Regex>> = (1..6).map { level ->
        level to Regex("""<h$level\b[^>]*>(.*?)</h$level>""", IGNORE_CASE_DOT_ALL)
    }

    private val BLOCKQUOTE_OPEN_REGEX = Regex("""<blockquote\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCKQUOTE_CLOSE_REGEX = Regex("""</blockquote>""", RegexOption.IGNORE_CASE)

    private val UL_OPEN_REGEX = Regex("""<ul\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val UL_CLOSE_REGEX = Regex("""</ul>""", RegexOption.IGNORE_CASE)
    private val OL_OPEN_REGEX = Regex("""<ol\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val OL_CLOSE_REGEX = Regex("""</ol>""", RegexOption.IGNORE_CASE)
    private val LI_OPEN_REGEX = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LI_CLOSE_REGEX = Regex("""</li>""", RegexOption.IGNORE_CASE)

    private val TABLE_OPEN_REGEX = Regex("""<table\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TABLE_CLOSE_REGEX = Regex("""</table>""", RegexOption.IGNORE_CASE)
    private val TR_OPEN_REGEX = Regex("""<tr\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TR_CLOSE_REGEX = Regex("""</tr>""", RegexOption.IGNORE_CASE)
    private val TH_OPEN_REGEX = Regex("""<th\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TH_CLOSE_REGEX = Regex("""</th>""", RegexOption.IGNORE_CASE)
    private val TD_OPEN_REGEX = Regex("""<td\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TD_CLOSE_REGEX = Regex("""</td>""", RegexOption.IGNORE_CASE)

    private val P_OPEN_REGEX = Regex("""<p\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val P_CLOSE_REGEX = Regex("""</p>""", RegexOption.IGNORE_CASE)
    private val DIV_OPEN_REGEX = Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val DIV_CLOSE_REGEX = Regex("""</div>""", RegexOption.IGNORE_CASE)

    private val EM_I_REGEX = Regex("""<(?:em|i)\b[^>]*>(.*?)</(?:em|i)>""", IGNORE_CASE_DOT_ALL)
    private val STRONG_B_REGEX = Regex("""<(?:strong|b)\b[^>]*>(.*?)</(?:strong|b)>""", IGNORE_CASE_DOT_ALL)
    private val U_REGEX = Regex("""<u\b[^>]*>(.*?)</u>""", IGNORE_CASE_DOT_ALL)

    private val REF_ANCHOR_REGEX = Regex("""<a\b[^>]*osisRef=["']([^"']+)["'][^>]*>(.*?)</a>""", IGNORE_CASE_DOT_ALL)
    private val GENERIC_ANCHOR_REGEX = Regex("""<a\b[^>]*>(.*?)</a>""", IGNORE_CASE_DOT_ALL)

    private val EMPTY_HI_REGEX = Regex("""<hi\b[^>]*>([\s\u00A0]*(?:(?:&#160;|&#13;)[\s\u00A0]*)*)</hi>""", RegexOption.IGNORE_CASE)

    private const val EMPTY_CONTENT_PATTERN = """(?:[\s\u00A0]|&#160;|&#13;|<lb\s*/?>)*"""
    private val EMPTY_P_REGEX = Regex("""<p>$EMPTY_CONTENT_PATTERN</p>""", RegexOption.IGNORE_CASE)
    private val EMPTY_TITLE_REGEX = Regex("""<title\b[^>]*>$EMPTY_CONTENT_PATTERN</title>""", RegexOption.IGNORE_CASE)
    private val EMPTY_ITEM_REGEX = Regex("""<item>$EMPTY_CONTENT_PATTERN</item>""", RegexOption.IGNORE_CASE)
    private val EMPTY_Q_REGEX = Regex("""<q\b[^>]*>$EMPTY_CONTENT_PATTERN</q>""", RegexOption.IGNORE_CASE)
    private val EMPTY_LIST_REGEX = Regex("""<list>$EMPTY_CONTENT_PATTERN</list>""", RegexOption.IGNORE_CASE)

    private val TRIM_P_START_REGEX = Regex("""<p>(?:(?:[ \t\u00A0]|&#13;)*\n[ \t\u00A0]*|(?:\u00A0|&#13;)+| (?![ \u00A0]))+""")
    private val TRIM_P_END_REGEX = Regex("""(?:[\s\u00A0]|&#13;)+</p>""")
    private val TRIM_TITLE_START_REGEX = Regex("""(<title\b[^>]*>)(?:[\s\u00A0]|&#13;)+""")
    private val TRIM_TITLE_END_REGEX = Regex("""(?:[\s\u00A0]|&#13;)+</title>""")
    private val TRIM_ITEM_START_REGEX = Regex("""<item>(?:[\s\u00A0]|&#13;)+""")
    private val TRIM_ITEM_END_REGEX = Regex("""(?:[\s\u00A0]|&#13;)+</item>""")

    private val BLOCK_BOUNDARY_PATTERN =
        """(?:</?(?:p|title|q|list|item|table|row)\b[^>]*>|${Regex.escape(PRE_PLACEHOLDER_PREFIX)}\d+${Regex.escape(PRE_PLACEHOLDER_SUFFIX)})"""
    private const val WS_AND_CR_PATTERN = """(?:[\s\u00A0]|&#13;)+"""
    private val INTER_BLOCK_WS_REGEX = Regex("""($BLOCK_BOUNDARY_PATTERN)$WS_AND_CR_PATTERN(?=$BLOCK_BOUNDARY_PATTERN)""")
    private val COLLAPSE_NEWLINES_REGEX = Regex("""(?:(?:&#13;)?\n[ \t]*){2,}""")
    private val EDGE_TRIM_REGEX = Regex("""^(?:[\s\u00A0]|&#13;)+|(?:[\s\u00A0]|&#13;)+$""")

    private val ANY_TAG_REGEX = Regex("""</?([a-zA-Z0-9]+)(\s+[^>]*)?/?>""")
    private val TITLE_LEVEL_REGEX = Regex("""level=["']?(\d)["']?""")
    private val HI_TYPE_REGEX = Regex("""type=["']?([a-zA-Z]+)["']?""")
    private val OSIS_REF_ATTR_REGEX = Regex("""osisRef=["']([^"']+)["']""")
    private val DISALLOWED_OSIS_ID_REGEX = Regex("""[^a-zA-Z0-9._-]""")

    private val HTML_ENTITY_MAP = mapOf(
        "nbsp" to "&#160;",
        "ensp" to "&#8194;",
        "emsp" to "&#8195;",
        "thinsp" to "&#8201;",
        "zwnj" to "&#8204;",
        "zwj" to "&#8205;",
        "tab" to "\t",
        "NewLine" to "\n",
        "mdash" to "&#8212;",
        "ndash" to "&#8211;",
        "hellip" to "&#8230;",
        "ldquo" to "&#8220;",
        "rdquo" to "&#8221;",
        "lsquo" to "&#8216;",
        "rsquo" to "&#8217;",
        "laquo" to "&#171;",
        "raquo" to "&#187;",
        "copy" to "&#169;",
        "reg" to "&#174;",
        "trade" to "&#8482;"
    )
    private val ENTITY_OR_AMP_REGEX = Regex("""&(?:(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+)|([A-Za-z]+));|&""")

    /**
     * Converts a single chapter's HTML/XHTML content into an OSIS XML fragment
     * suitable for storing in a Sword RawGenBook section (.bdt file).
     */
    fun toOsisSection(chapter: ImportedChapter): String {
        val convertedBody = convertHtmlToOsisFragment(chapter.rawContent)

        val safeId = sanitizeOsisId(if (chapter.id.isNotBlank()) chapter.id else "ch${chapter.order}")
        val safeTitle = escapeXml(chapter.title.ifBlank { "Chapter ${chapter.order}" })

        return buildString(convertedBody.length + 128) {
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
        text = COMMENT_REGEX.replace(text, "")

        // 2. Strip <head>...</head>, <script>...</script>, <style>...</style>
        text = HEAD_REGEX.replace(text, "")
        text = SCRIPT_REGEX.replace(text, "")
        text = STYLE_REGEX.replace(text, "")

        // 3. Extract <body> if present
        val bodyMatch = BODY_REGEX.find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }

        // 4. Decode / normalize common HTML whitespace and typography entities
        text = normalizeHtmlEntities(text)

        // 5. Preformatted blocks: preserve internal whitespace (tabs, carriage returns, newlines) inside <pre>.
        //    Protect with placeholders so inter-block whitespace normalization leaves <pre> untouched.
        val preBlocks = mutableListOf<String>()
        text = PRE_REGEX.replace(text) { m ->
            var preContent = m.groupValues[1]
            if (preContent.startsWith("\r\n")) {
                preContent = preContent.substring(2)
            } else if (preContent.startsWith("\n") || preContent.startsWith("\r")) {
                preContent = preContent.substring(1)
            }
            preContent = stripDisallowedTags(preContent)
            val withLb = PRE_NEWLINE_REGEX.replace(preContent) { nl ->
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
        text = BR_REGEX.replace(text, "<lb/>")
        text = HR_REGEX.replace(text, "<lb/>")

        // 8. Footnotes / Notes: <aside...> -> <note type="explanation">
        text = ASIDE_OPEN_REGEX.replace(text, "<note type=\"explanation\">")
        text = ASIDE_CLOSE_REGEX.replace(text, "</note>")
        text = FOOTNOTE_DIV_REGEX.replace(text, "<note type=\"explanation\">")

        // 9. Headings <h1>..<h6> -> <title canonical="true" level="1..6">
        for ((level, regex) in HEADING_REGEXES) {
            text = regex.replace(text) { m ->
                "\n<title canonical=\"true\" level=\"$level\">${m.groupValues[1].trim()}</title>\n"
            }
        }

        // 10. Blockquotes -> <q type="block">
        text = BLOCKQUOTE_OPEN_REGEX.replace(text, "\n<q type=\"block\">")
        text = BLOCKQUOTE_CLOSE_REGEX.replace(text, "</q>\n")

        // 11. Lists -> <list> and <item>
        text = UL_OPEN_REGEX.replace(text, "\n<list>\n")
        text = UL_CLOSE_REGEX.replace(text, "\n</list>\n")
        text = OL_OPEN_REGEX.replace(text, "\n<list>\n")
        text = OL_CLOSE_REGEX.replace(text, "\n</list>\n")
        text = LI_OPEN_REGEX.replace(text, "\n<item>")
        text = LI_CLOSE_REGEX.replace(text, "</item>\n")

        // 12. Tables -> <table>, <row>, <cell>
        text = TABLE_OPEN_REGEX.replace(text, "\n<table>\n")
        text = TABLE_CLOSE_REGEX.replace(text, "\n</table>\n")
        text = TR_OPEN_REGEX.replace(text, "\n<row>\n")
        text = TR_CLOSE_REGEX.replace(text, "\n</row>")
        text = TH_OPEN_REGEX.replace(text, "<cell>")
        text = TH_CLOSE_REGEX.replace(text, "\t</cell>")
        text = TD_OPEN_REGEX.replace(text, "<cell>")
        text = TD_CLOSE_REGEX.replace(text, "\t</cell>")

        // 13. Paragraphs: ensure separation between paragraphs
        text = P_OPEN_REGEX.replace(text, "\n<p>")
        text = P_CLOSE_REGEX.replace(text, "</p>\n")

        // 14. Divisions: if <div> is used as block divider
        text = DIV_OPEN_REGEX.replace(text, "\n")
        text = DIV_CLOSE_REGEX.replace(text, "\n")

        // 15. Styles: italic, bold, underline
        text = EM_I_REGEX.replace(text) { m -> "<hi type=\"italic\">${m.groupValues[1]}</hi>" }
        text = STRONG_B_REGEX.replace(text) { m -> "<hi type=\"bold\">${m.groupValues[1]}</hi>" }
        text = U_REGEX.replace(text) { m -> "<hi type=\"underline\">${m.groupValues[1]}</hi>" }

        // 16. Bible reference anchors or hyperlinks
        text = REF_ANCHOR_REGEX.replace(text) { m ->
            "<reference osisRef=\"${escapeXml(m.groupValues[1])}\">${m.groupValues[2]}</reference>"
        }
        // General anchors: strip the tag, keep inner text
        text = GENERIC_ANCHOR_REGEX.replace(text) { m -> m.groupValues[1] }

        // 17. Strip all remaining disallowed tags while keeping their text contents
        text = stripDisallowedTags(text)

        // 18. Unwrap empty or whitespace-only <hi> tags (e.g. <strong class="calibre6"> </strong>)
        text = EMPTY_HI_REGEX.replace(text) { m -> m.groupValues[1] }

        // 19. Remove empty / spacer-only block elements (e.g. <p></p>, <p>&nbsp;</p>, <p><br/></p>, <h2>&nbsp;</h2>)
        text = EMPTY_P_REGEX.replace(text, "\n")
        text = EMPTY_TITLE_REGEX.replace(text, "\n")
        text = EMPTY_ITEM_REGEX.replace(text, "\n")
        text = EMPTY_Q_REGEX.replace(text, "\n")
        text = EMPTY_LIST_REGEX.replace(text, "\n")

        // 20. Trim leading/trailing HTML formatting whitespace inside <p>, <title>, and <item>
        //     (preserve intentional leading tabs or 2+ space indentation on <p> when not preceded by an HTML newline)
        text = TRIM_P_START_REGEX.replace(text, "<p>")
        text = TRIM_P_END_REGEX.replace(text, "</p>")
        text = TRIM_TITLE_START_REGEX.replace(text) { m -> m.groupValues[1] }
        text = TRIM_TITLE_END_REGEX.replace(text, "</title>")
        text = TRIM_ITEM_START_REGEX.replace(text, "<item>")
        text = TRIM_ITEM_END_REGEX.replace(text, "</item>")

        // 21. Normalize all whitespace/indentation/CRLF between block tags to a single newline
        text = INTER_BLOCK_WS_REGEX.replace(text) { m -> "${m.groupValues[1]}\n" }

        // Collapse any remaining runs of 2+ newlines outside <pre> to a single newline
        text = COLLAPSE_NEWLINES_REGEX.replace(text, "\n")

        // 22. Restore <pre> blocks
        if (preBlocks.isNotEmpty()) {
            for ((index, preOsis) in preBlocks.withIndex()) {
                text = text.replace("$PRE_PLACEHOLDER_PREFIX$index$PRE_PLACEHOLDER_SUFFIX", preOsis)
            }
        }

        // 23. Ensure text is enclosed in at least <p> tags if no block tags exist
        val trimmed = EDGE_TRIM_REGEX.replace(text, "")
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
        return ANY_TAG_REGEX.replace(input) { m ->
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
                val level = TITLE_LEVEL_REGEX.find(fullTag)?.groupValues?.get(1) ?: "1"
                "<title canonical=\"true\" level=\"$level\">"
            }
            "hi" -> {
                val type = HI_TYPE_REGEX.find(fullTag)?.groupValues?.get(1) ?: "italic"
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
                val osisRef = OSIS_REF_ATTR_REGEX.find(fullTag)?.groupValues?.get(1)
                if (osisRef != null) "<reference osisRef=\"$osisRef\">" else "<reference>"
            }
            else -> if (isSelfClosing) "<$tagName/>" else "<$tagName>"
        }
    }

    private fun preserveCarriageReturns(text: String): String {
        // Encode literal carriage returns as character reference &#13; so that
        // W3C XML parsers (which normalize \r\n and \r to \n) preserve them faithfully
        return if ('\r' in text) text.replace("\r", "&#13;") else text
    }

    private fun normalizeHtmlEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY_OR_AMP_REGEX.replace(text) { m ->
            val xmlOrNumeric = m.groupValues[1]
            if (xmlOrNumeric.isNotEmpty()) {
                m.value
            } else {
                val entityName = m.groupValues[2]
                if (entityName.isNotEmpty()) {
                    HTML_ENTITY_MAP[entityName] ?: "&amp;$entityName;"
                } else {
                    "&amp;"
                }
            }
        }
    }

    private fun sanitizeOsisId(raw: String): String {
        val cleaned = DISALLOWED_OSIS_ID_REGEX.replace(raw, "")
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

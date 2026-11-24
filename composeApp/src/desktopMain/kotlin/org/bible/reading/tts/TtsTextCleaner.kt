package org.bible.reading.tts

/**
 * Utility for normalizing and cleaning scripture, commentary, and general book texts
 * into fluent, natural-sounding speech for TTS engines.
 */
object TtsTextCleaner {

    private val xmlTagRegex = Regex("<[^>]*>")
    private val strongsRegex = Regex("\\b[GH]\\d{1,5}\\b")
    private val morphologyRegex = Regex("\\b(robinson|strong|morph):[A-Za-z0-9/-]+\\b", RegexOption.IGNORE_CASE)
    private val footnoteMarkerRegex = Regex("\\[[0-9a-zA-Z*†‡§ ]+]|\\([0-9a-zA-Z*†‡§ ]+\\)")
    private val bracketArtifactsRegex = Regex("[{}\\[\\]<>#*_~|]")
    private val multipleWhitespaceRegex = Regex("\\s+")
    private val paragraphSeparatorRegex = Regex("""\r?\n(?:[ \t]*\r?\n)+""")

    /**
     * Splits formatted section or passage text into individual non-blank paragraphs.
     * Preserves single internal line breaks and horizontal indentation within each paragraph.
     */
    fun splitIntoParagraphs(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        return content
            .split(paragraphSeparatorRegex)
            .map { it.trim('\r', '\n') }
            .filter { it.isNotBlank() }
    }

    /**
     * Cleans raw Sword or book module text into clean, pronounceable sentences.
     */
    fun cleanForSpeech(rawText: String): String {
        if (rawText.isBlank()) return ""

        var cleaned = rawText

        // 1. Strip XML/HTML tags
        cleaned = xmlTagRegex.replace(cleaned, " ")

        // 2. Strip Strong's numbers and morphology annotations
        cleaned = morphologyRegex.replace(cleaned, "")
        cleaned = strongsRegex.replace(cleaned, "")

        // 3. Remove footnote references e.g. [1], [a], (note 2)
        cleaned = footnoteMarkerRegex.replace(cleaned, "")

        // 4. Strip leftover markdown/bracket symbols
        cleaned = bracketArtifactsRegex.replace(cleaned, " ")

        // 5. Replace dashes and hyphens with soft pauses
        cleaned = cleaned.replace("—", ", ")
            .replace("–", ", ")
            .replace("...", ". ")
            .replace("…", ". ")

        // 6. Normalize quotes and apostrophes
        cleaned = cleaned.replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")

        // 7. Collapse repeated whitespace
        cleaned = multipleWhitespaceRegex.replace(cleaned, " ").trim()

        return cleaned
    }

    /**
     * Formats a chapter passage for smooth reading, prepending the book title and chapter header
     * so the listener knows what is being read.
     */
    fun formatPassageForSpeech(bookName: String, chapterNumber: Int, text: String): String {
        val cleanBody = cleanForSpeech(text)
        if (cleanBody.isBlank()) return ""
        val header = "$bookName, chapter $chapterNumber."
        return "$header $cleanBody"
    }

    /**
     * Formats an individual verse for speech, prepending the verse reference.
     */
    fun formatVerseForSpeech(reference: String, text: String): String {
        val cleanBody = cleanForSpeech(text)
        if (cleanBody.isBlank()) return ""
        return "$reference. $cleanBody"
    }

    /**
     * Formats a general book / dictionary section for speech.
     */
    fun formatSectionForSpeech(title: String, subtitle: String, text: String): String {
        val cleanBody = cleanForSpeech(text)
        if (cleanBody.isBlank()) return ""
        val intro = if (subtitle.isNotBlank() && !title.contains(subtitle, ignoreCase = true)) {
            "$title, from $subtitle."
        } else {
            "$title."
        }
        return "$intro $cleanBody"
    }

    /**
     * Formats an individual paragraph from a non-Bible book for natural speech synthesis.
     */
    fun formatParagraphForSpeech(paragraphText: String): String {
        return cleanForSpeech(paragraphText)
    }
}

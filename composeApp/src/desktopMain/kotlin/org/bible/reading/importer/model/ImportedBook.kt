package org.bible.reading.importer.model

/**
 * Metadata for an imported book document.
 */
data class ImportedBookMetadata(
    val title: String,
    val author: String = "Unknown",
    val identifier: String = "",
    val language: String = "en",
    val description: String = "",
    val about: String = ""
)

/**
 * A chapter or section within an imported book.
 */
data class ImportedChapter(
    val id: String,
    val title: String,
    val rawContent: String,
    val order: Int = 0
)

/**
 * Universal intermediate representation of an imported book, independent
 * of the source format (EPUB, MOBI, PDF, Markdown, etc.).
 */
data class ImportedBook(
    val metadata: ImportedBookMetadata,
    val chapters: List<ImportedChapter>
)

package org.bible.reading.importer

import org.bible.reading.importer.epub.EpubImporter
import org.bible.reading.importer.model.ImportedBook
import org.bible.reading.importer.pdf.PdfImporter
import java.io.File

/**
 * Interface for format-specific book parsers (e.g. EPUB, PDF, MOBI, Markdown).
 * Isolated to allow adding new formats in the future without modifying
 * the conversion or storage pipelines.
 */
interface BookImporter {
    /** Human-readable format name, e.g. "EPUB", "PDF" */
    val formatName: String

    /** List of supported file extensions (lowercase, without dot), e.g. ["epub", "pdf"] */
    val supportedExtensions: List<String>

    /** Checks if the importer can handle the specified file */
    fun canImport(file: File): Boolean {
        val ext = file.extension.lowercase()
        return supportedExtensions.contains(ext) && file.exists() && file.isFile
    }

    /**
     * Parses the file into the common [ImportedBook] representation.
     */
    suspend fun parse(file: File): ImportedBook
}

/**
 * Registry of all available format importers. Additional format converters
 * can be registered here in the future.
 */
object BookImportRegistry {
    private val importers = mutableListOf<BookImporter>()

    init {
        // Register default built-in format importers
        register(EpubImporter())
        register(PdfImporter())
    }

    fun register(importer: BookImporter) {
        if (!importers.contains(importer)) {
            importers.add(importer)
        }
    }

    fun canImport(file: File): Boolean {
        return findImporter(file) != null
    }

    fun findImporter(file: File): BookImporter? {
        return importers.firstOrNull { it.canImport(file) }
    }

    fun getSupportedExtensions(): List<String> {
        return importers.flatMap { it.supportedExtensions }.distinct()
    }

    fun getAllImporters(): List<BookImporter> = importers.toList()
}

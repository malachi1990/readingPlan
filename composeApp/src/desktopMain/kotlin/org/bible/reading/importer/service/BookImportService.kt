package org.bible.reading.importer.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bible.reading.importer.BookImportRegistry
import org.bible.reading.importer.sword.SwordGenBookWriter
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import java.io.File

/**
 * Service that orchestrates importing external documents (EPUB and future formats)
 * and converting them into CrossWire Sword modules.
 */
class BookImportService(
    private val manager: SwordManager
) {

    /**
     * Imports a book from a local file, compiles it into a Sword module in `manager.appDir`,
     * refreshes installed books, and returns the imported [Book].
     */
    suspend fun importFile(
        file: File,
        onProgress: ((step: String) -> Unit)? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            require(file.exists() && file.isFile) { "File does not exist: ${file.absolutePath}" }

            val importer = BookImportRegistry.findImporter(file)
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "No importer found for file '${file.name}'. Supported formats: " +
                                BookImportRegistry.getSupportedExtensions().joinToString { ".$it" }
                    )
                )

            onProgress?.invoke("Parsing ${importer.formatName} file...")
            val importedBook = importer.parse(file)

            onProgress?.invoke("Compiling into Sword module (${importedBook.chapters.size} sections)...")
            val compilation = SwordGenBookWriter.write(
                appDir = manager.appDir,
                book = importedBook
            )

            onProgress?.invoke("Refreshing installed modules...")
            withContext(Dispatchers.Main) {
                manager.refreshInstalledBooks()
            }

            // Look up the newly installed book
            val initials = compilation.initials
            val installedBook = manager.installedBooks.firstOrNull {
                it.initials.equals(initials, ignoreCase = true)
            } ?: try {
                Books.installed().getBook(initials)
            } catch (_: Exception) {
                null
            }

            if (installedBook != null) {
                withContext(Dispatchers.Main) {
                    manager.lastOperationMessage = "Successfully imported '${importedBook.metadata.title}' as Sword module $initials"
                    manager.isOperationSuccess = true
                }
                Result.success(installedBook)
            } else {
                Result.failure(
                    IllegalStateException(
                        "Module '$initials' was written to ${compilation.confFile.absolutePath} but not detected by JSword. Please restart the app."
                    )
                )
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                manager.lastOperationMessage = "Import failed: ${e.message}"
                manager.isOperationSuccess = false
            }
            Result.failure(e)
        }
    }
}

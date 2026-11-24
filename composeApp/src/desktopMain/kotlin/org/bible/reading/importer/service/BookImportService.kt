package org.bible.reading.importer.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bible.reading.importer.BookImportRegistry
import org.bible.reading.importer.sword.SwordGenBookWriter
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books
import java.io.File

sealed class ImportItemStatus {
    object Pending : ImportItemStatus()
    data class InProgress(val step: String) : ImportItemStatus()
    data class Success(val book: Book) : ImportItemStatus()
    data class Failed(val errorMessage: String) : ImportItemStatus()
}

data class ImportQueueItem(
    val id: Long,
    val file: File,
    val status: ImportItemStatus = ImportItemStatus.Pending
)

/**
 * Service that orchestrates importing external documents (EPUB and future formats)
 * and converting them into CrossWire Sword modules, including background queue execution.
 */
class BookImportService(
    private val manager: SwordManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    val queueItems = mutableStateListOf<ImportQueueItem>()
    var isProcessing by mutableStateOf(false)
        private set
    var isQueueDialogOpen by mutableStateOf(false)
    var lastImportedBook by mutableStateOf<Book?>(null)
        private set

    private val queueLock = Any()
    private var nextItemId = 1L
    private var workerJob: Job? = null

    /**
     * Adds one or more files to the background import queue and starts processing
     * sequentially on [scope] if not already running. Skips files that are already
     * pending or currently in progress.
     */
    fun enqueueFiles(files: List<File>): List<ImportQueueItem> {
        if (files.isEmpty()) return emptyList()
        val added = mutableListOf<ImportQueueItem>()
        synchronized(queueLock) {
            for (file in files) {
                val targetPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                val alreadyActive = queueItems.any { item ->
                    (item.status is ImportItemStatus.Pending || item.status is ImportItemStatus.InProgress) &&
                        runCatching { item.file.canonicalPath }.getOrElse { item.file.absolutePath } == targetPath
                }
                if (!alreadyActive) {
                    val item = ImportQueueItem(id = nextItemId++, file = file)
                    queueItems.add(item)
                    added.add(item)
                }
            }
            if (added.isNotEmpty() || queueItems.any { it.status is ImportItemStatus.Pending }) {
                ensureWorkerLocked()
            }
        }
        return added
    }

    fun enqueueFile(file: File): ImportQueueItem? = enqueueFiles(listOf(file)).firstOrNull()

    fun removeItem(itemId: Long): Boolean {
        synchronized(queueLock) {
            val idx = queueItems.indexOfFirst { it.id == itemId }
            if (idx < 0) return false
            if (queueItems[idx].status is ImportItemStatus.InProgress) return false
            queueItems.removeAt(idx)
            return true
        }
    }

    fun retryItem(itemId: Long): Boolean {
        synchronized(queueLock) {
            val idx = queueItems.indexOfFirst { it.id == itemId }
            if (idx < 0) return false
            val item = queueItems[idx]
            if (item.status !is ImportItemStatus.Failed) return false
            queueItems[idx] = item.copy(status = ImportItemStatus.Pending)
            ensureWorkerLocked()
            return true
        }
    }

    fun clearFinished() {
        synchronized(queueLock) {
            queueItems.removeAll { it.status is ImportItemStatus.Success || it.status is ImportItemStatus.Failed }
        }
    }

    suspend fun awaitIdle() {
        while (true) {
            val job = synchronized(queueLock) { workerJob }
            if (job == null || !job.isActive) {
                val stillActive = synchronized(queueLock) {
                    queueItems.any { it.status is ImportItemStatus.Pending || it.status is ImportItemStatus.InProgress }
                }
                if (!stillActive) break
            }
            job?.join()
        }
    }

    private fun updateItemStatusLocked(itemId: Long, newStatus: ImportItemStatus) {
        val idx = queueItems.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            queueItems[idx] = queueItems[idx].copy(status = newStatus)
        }
    }

    private fun ensureWorkerLocked() {
        if (workerJob?.isActive == true) return
        isProcessing = true
        workerJob = scope.launch {
            try {
                while (true) {
                    val nextItem = synchronized(queueLock) {
                        val item = queueItems.firstOrNull { it.status is ImportItemStatus.Pending }
                        if (item != null) {
                            updateItemStatusLocked(item.id, ImportItemStatus.InProgress("Starting import..."))
                            isProcessing = true
                        } else {
                            isProcessing = false
                        }
                        item
                    } ?: break

                    val result = importFile(nextItem.file) { step ->
                        synchronized(queueLock) {
                            updateItemStatusLocked(nextItem.id, ImportItemStatus.InProgress(step))
                        }
                    }

                    result.fold(
                        onSuccess = { book ->
                            synchronized(queueLock) {
                                updateItemStatusLocked(nextItem.id, ImportItemStatus.Success(book))
                                lastImportedBook = book
                            }
                        },
                        onFailure = { err ->
                            synchronized(queueLock) {
                                updateItemStatusLocked(
                                    nextItem.id,
                                    ImportItemStatus.Failed(err.message ?: "Unknown import error")
                                )
                            }
                        }
                    )
                }
            } finally {
                synchronized(queueLock) {
                    isProcessing = false
                    workerJob = null
                    if (queueItems.any { it.status is ImportItemStatus.Pending }) {
                        ensureWorkerLocked()
                    }
                }
            }
        }
    }

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

    /**
     * Imports multiple books sequentially from local files.
     */
    suspend fun importFiles(
        files: List<File>,
        onProgress: ((fileIndex: Int, totalFiles: Int, file: File, step: String) -> Unit)? = null
    ): List<Result<Book>> {
        val results = mutableListOf<Result<Book>>()
        for ((index, file) in files.withIndex()) {
            val result = importFile(file) { step ->
                onProgress?.invoke(index, files.size, file, step)
            }
            results.add(result)
        }
        return results
    }
}

package org.bible.reading.jsword

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bible.reading.dto.Reading
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.BookData
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.OSISUtil
import org.crosswire.jsword.book.install.InstallManager
import org.crosswire.jsword.book.install.Installer
import org.crosswire.jsword.book.sword.SwordBookDriver
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordBookPath
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.NoSuchKeyException
import org.crosswire.jsword.passage.Verse
import java.io.File

data class VerseItem(
    val verseNumber: Int,
    val text: String,
    val reference: String
)

data class ChapterPassage(
    val bookName: String,
    val chapterNumber: Int,
    val verses: List<VerseItem>,
    val fullText: String,
    val error: String? = null
)

class SwordManager {

    val appDir: File = File(System.getProperty("user.home"), ".bibleReadingPlan").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val installManager = InstallManager()

    // Observable list of installed books
    val installedBooks = mutableStateListOf<Book>()
    val installedBibles = mutableStateListOf<Book>()

    // Currently selected Bible for reading
    var selectedBible by mutableStateOf<Book?>(null)

    // Download / installer state
    val availableInstallers = mutableStateListOf<String>()
    var selectedInstallerName by mutableStateOf("CrossWire")
    val availableBooksForInstaller = mutableStateListOf<Book>()
    var isReloadingBookList by mutableStateOf(false)
    var reloadStatusMessage by mutableStateOf<String?>(null)

    // Downloading tracking: set of book initials currently being downloaded
    val downloadingBookInitials = mutableStateListOf<String>()
    var lastOperationMessage by mutableStateOf<String?>(null)
    var isOperationSuccess by mutableStateOf(true)

    init {
        configureBookPath()
        refreshInstalledBooks()
        refreshInstallers()
    }

    fun configureBookPath() {
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        SwordBookPath.setDownloadDir(appDir)
        SwordBookPath.setAugmentPath(arrayOf(appDir))
        try {
            Books.installed().registerDriver(SwordBookDriver.instance())
        } catch (_: Exception) {}
    }

    fun isAppDirectoryBook(book: Book): Boolean {
        val uri = (book.bookMetaData as? SwordBookMetaData)?.library ?: book.bookMetaData.location
        val path = uri?.path ?: ""
        return path.contains(".bibleReadingPlan") || path.startsWith(appDir.absolutePath)
    }

    fun getBookLocationLabel(book: Book): String {
        return if (isAppDirectoryBook(book)) {
            "App Data (~/.bibleReadingPlan)"
        } else {
            "System Module (~/.sword)"
        }
    }

    fun refreshInstalledBooks() {
        configureBookPath()

        installedBooks.clear()
        installedBibles.clear()

        val all = try {
            Books.installed().books
        } catch (_: Exception) {
            emptyList<Book>()
        }

        installedBooks.addAll(all)

        val bibles = all.filter { it.bookCategory == BookCategory.BIBLE }
        installedBibles.addAll(bibles)

        // If no Bible is selected or selected one was removed, select first available Bible
        if (selectedBible == null || !bibles.contains(selectedBible)) {
            selectedBible = bibles.firstOrNull() ?: all.firstOrNull()
        }
    }

    fun refreshInstallers() {
        availableInstallers.clear()
        val installers = installManager.installers
        availableInstallers.addAll(installers.keys)
        if (!availableInstallers.contains(selectedInstallerName) && availableInstallers.isNotEmpty()) {
            selectedInstallerName = availableInstallers.first()
        }
    }

    fun getInstaller(name: String = selectedInstallerName): Installer? {
        return installManager.installers[name]
    }

    suspend fun reloadBookListForInstaller(installerName: String = selectedInstallerName): List<Book> {
        return withContext(Dispatchers.IO) {
            isReloadingBookList = true
            reloadStatusMessage = "Refreshing book list from $installerName..."
            try {
                val installer = getInstaller(installerName)
                    ?: throw IllegalArgumentException("Installer '$installerName' not found")

                installer.reloadBookList()
                val books = installer.books ?: emptyList()

                withContext(Dispatchers.Main) {
                    availableBooksForInstaller.clear()
                    availableBooksForInstaller.addAll(books)
                    reloadStatusMessage = "Loaded ${books.size} books from $installerName."
                    isOperationSuccess = true
                }
                books
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    reloadStatusMessage = "Failed to load books: ${e.message}"
                    isOperationSuccess = false
                }
                emptyList()
            } finally {
                withContext(Dispatchers.Main) {
                    isReloadingBookList = false
                }
            }
        }
    }

    suspend fun downloadAndInstallBook(book: Book, installerName: String = selectedInstallerName): Boolean {
        return withContext(Dispatchers.IO) {
            val initials = book.initials
            withContext(Dispatchers.Main) {
                downloadingBookInitials.add(initials)
                lastOperationMessage = "Downloading and installing ${book.name} ($initials)..."
            }

            try {
                configureBookPath()
                val installer = getInstaller(installerName)
                    ?: throw IllegalArgumentException("Installer '$installerName' not found")

                installer.install(book)

                withContext(Dispatchers.Main) {
                    refreshInstalledBooks()
                    lastOperationMessage = "Successfully installed ${book.name} ($initials) to ~/.bibleReadingPlan"
                    isOperationSuccess = true
                    if (selectedBible == null && book.bookCategory == BookCategory.BIBLE) {
                        selectedBible = Books.installed().getBook(initials)
                    }
                }
                true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    lastOperationMessage = "Failed to install ${book.name}: ${e.message}"
                    isOperationSuccess = false
                }
                false
            } finally {
                withContext(Dispatchers.Main) {
                    downloadingBookInitials.remove(initials)
                }
            }
        }
    }

    suspend fun uninstallBook(book: Book): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val driver = Books.installed().getDriversByClass(SwordBookDriver::class.java).firstOrNull()
                if (driver != null) {
                    driver.delete(book)
                } else {
                    Books.installed().removeBook(book)
                }

                // Also clean up any lingering conf or module folder in appDir if present
                val confFile = File(appDir, "mods.d/${book.initials.lowercase()}.conf")
                if (confFile.exists()) {
                    confFile.delete()
                }

                withContext(Dispatchers.Main) {
                    refreshInstalledBooks()
                    lastOperationMessage = "Uninstalled ${book.name} (${book.initials})"
                    isOperationSuccess = true
                }
                true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    lastOperationMessage = "Failed to uninstall ${book.name}: ${e.message}"
                    isOperationSuccess = false
                }
                false
            }
        }
    }

    fun isBookInstalled(initials: String): Boolean {
        return installedBooks.any { it.initials.equals(initials, ignoreCase = true) }
    }

    fun readChapter(reading: Reading, book: Book? = selectedBible): ChapterPassage {
        val targetBook = book ?: selectedBible
        if (targetBook == null) {
            return ChapterPassage(
                bookName = reading.book,
                chapterNumber = reading.chapterNumber,
                verses = emptyList(),
                fullText = "",
                error = "No Bible or Book selected/installed. Please download a Bible first."
            )
        }

        val refString = "${reading.book} ${reading.chapterNumber}"
        return try {
            val key: Key = try {
                targetBook.getKey(refString)
            } catch (e: NoSuchKeyException) {
                // Try alternative forms like Psalm vs Psalms
                if (reading.book.equals("Psalms", ignoreCase = true)) {
                    targetBook.getKey("Psalm ${reading.chapterNumber}")
                } else if (reading.book.equals("Psalm", ignoreCase = true)) {
                    targetBook.getKey("Psalms ${reading.chapterNumber}")
                } else {
                    throw e
                }
            }

            val verses = mutableListOf<VerseItem>()
            for (k in key) {
                try {
                    val vData = BookData(targetBook, k)
                    val rawText = OSISUtil.getPlainText(vData.osis).trim()
                    val cleanText = rawText.removePrefix(k.name).trim()
                    val verseNum = if (k is Verse) k.verse else 0
                    if (cleanText.isNotBlank()) {
                        verses.add(VerseItem(verseNumber = verseNum, text = cleanText, reference = k.name))
                    }
                } catch (_: Exception) {
                    // Ignore individual verse failures
                }
            }

            val fullText = if (verses.isEmpty()) {
                val data = BookData(targetBook, key)
                OSISUtil.getPlainText(data.osis).trim()
            } else {
                verses.joinToString("\n\n") { "${it.verseNumber} ${it.text}" }
            }

            ChapterPassage(
                bookName = reading.book,
                chapterNumber = reading.chapterNumber,
                verses = verses,
                fullText = fullText
            )
        } catch (e: Exception) {
            ChapterPassage(
                bookName = reading.book,
                chapterNumber = reading.chapterNumber,
                verses = emptyList(),
                fullText = "",
                error = "Could not read '$refString' in ${targetBook.name} (${targetBook.initials}): ${e.message}"
            )
        }
    }
}

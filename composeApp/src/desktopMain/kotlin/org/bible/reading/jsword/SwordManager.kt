package org.bible.reading.jsword

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bible.reading.dto.Reading
import org.bible.reading.importer.service.ImportQueueItem
import org.bible.reading.importer.service.BookImportService
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.BookData
import org.crosswire.jsword.book.Books
import org.crosswire.jsword.book.OSISUtil
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.book.install.InstallManager
import org.crosswire.jsword.book.install.Installer
import org.crosswire.jsword.book.sword.SwordBookDriver
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordBookPath
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.NoSuchKeyException
import org.crosswire.jsword.passage.Passage
import org.crosswire.jsword.passage.RestrictionType
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.versification.BibleBook
import org.jdom2.Content
import org.jdom2.Element
import org.jdom2.Text
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

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

/**
 * Represents a book entry in a verse-based module's table of contents,
 * sourced directly from the module's own versification system.
 */
data class ModuleBook(
    val bibleBook: BibleBook,
    /** Display name as reported by JSword's BibleNames for this module's versification */
    val displayName: String,
    /** Number of chapters in this book, per the module's versification */
    val chapterCount: Int,
    /** Sorted list of populated chapter numbers actually present in this module */
    val chapters: List<Int> = (1..chapterCount).toList()
)

class SwordManager(
    val appDir: File = File(System.getProperty("user.home"), ".bibleReadingPlan").apply {
        if (!exists()) {
            mkdirs()
        }
    }
) {
    companion object {
        private const val MAX_CHAPTER_CACHE_ENTRIES = 32
        private const val MAX_KEY_CONTENT_CACHE_ENTRIES = 16
        const val BIBLE_PREFERENCES_FILE_NAME = "bible_preferences.properties"
        private const val KEY_SELECTED_BIBLE_INITIALS = "selectedBibleInitials"

        private val COLLAPSE_EXTRA_BLANK_LINES_REGEX = Regex("""\n(?:[ \t]*\n){2,}""")
        private val LEADING_AFTER_LB_REGEX = Regex("""^(?:\r?\n)+[ \t]*""")
        private val BLOCK_CONTAINER_CHILD_TAGS = setOf("p", "title", "q", "list", "table", "div")
        private val EXTRA_BIBLICAL_ELEMENTS = setOf(
            OSISUtil.OSIS_ELEMENT_NOTE,
            OSISUtil.OSIS_ELEMENT_TITLE,
            OSISUtil.OSIS_ELEMENT_REFERENCE,
            "work"
        )
        private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")
    }

    private val installManager = InstallManager()
    val importService by lazy { BookImportService(this) }
    private val moduleBooksCache = mutableMapOf<String, List<ModuleBook>>()
    private val chapterCache = object : LinkedHashMap<String, ChapterPassage>(MAX_CHAPTER_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterPassage>): Boolean {
            return size > MAX_CHAPTER_CACHE_ENTRIES
        }
    }
    private val keyContentCache = object : LinkedHashMap<String, String>(MAX_KEY_CONTENT_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > MAX_KEY_CONTENT_CACHE_ENTRIES
        }
    }

    // Observable list of installed books
    val installedBooks = mutableStateListOf<Book>()
    val installedBibles = mutableStateListOf<Book>()

    private val biblePreferencesFile = File(appDir, BIBLE_PREFERENCES_FILE_NAME)
    private var _selectedBible by mutableStateOf<Book?>(null)

    // Currently selected Bible for reading
    var selectedBible: Book?
        get() = _selectedBible
        set(value) {
            _selectedBible = value
            if (value != null && value.bookCategory == BookCategory.BIBLE) {
                saveSelectedBibleInitials(value.initials)
            }
        }

    // Optional book requested to be opened in Further Study (e.g. from Import Queue)
    var pendingStudyModule by mutableStateOf<Book?>(null)

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

        synchronized(moduleBooksCache) {
            moduleBooksCache.clear()
        }
        synchronized(chapterCache) {
            chapterCache.clear()
        }
        synchronized(keyContentCache) {
            keyContentCache.clear()
        }
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

        // Restore the user's saved active Bible on startup or preserve current selection across refreshes
        val preferredInitials = _selectedBible?.initials ?: loadSelectedBibleInitials()
        val preferredBook = preferredInitials?.let { initials ->
            bibles.firstOrNull { it.initials.equals(initials, ignoreCase = true) }
        }

        if (preferredBook != null) {
            if (_selectedBible != preferredBook) {
                _selectedBible = preferredBook
            }
        } else if (_selectedBible == null || !bibles.contains(_selectedBible)) {
            val fallback = bibles.firstOrNull() ?: all.firstOrNull()
            _selectedBible = fallback
            // If a previously saved Bible was removed, update stored initials when another Bible is active
            if (fallback != null && fallback.bookCategory == BookCategory.BIBLE && preferredInitials != null) {
                saveSelectedBibleInitials(fallback.initials)
            }
        }
    }

    private fun loadSelectedBibleInitials(): String? {
        if (!biblePreferencesFile.exists() || !biblePreferencesFile.isFile) return null
        return try {
            val props = Properties()
            FileInputStream(biblePreferencesFile).use { props.load(it) }
            props.getProperty(KEY_SELECTED_BIBLE_INITIALS)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSelectedBibleInitials(initials: String) {
        try {
            if (!appDir.exists()) appDir.mkdirs()
            val props = Properties().apply {
                setProperty(KEY_SELECTED_BIBLE_INITIALS, initials)
            }
            FileOutputStream(biblePreferencesFile).use {
                props.store(it, "Bible Reading Plan - Active Bible Preference")
            }
        } catch (e: Exception) {
            println("Failed to write $BIBLE_PREFERENCES_FILE_NAME: ${e.message}")
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
                if (e is CancellationException) {
                    ensureActive()
                }
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

    /**
     * Imports a book from a local file (EPUB or future supported formats),
     * converts it into an OSIS RawGenBook Sword module in ~/.bibleReadingPlan,
     * and refreshes the installed module list.
     */
    suspend fun importBook(file: File, onProgress: ((String) -> Unit)? = null): Result<Book> {
        return importService.importFile(file, onProgress)
    }

    /**
     * Imports multiple books from local files, converts each into an OSIS RawGenBook Sword module in ~/.bibleReadingPlan,
     * and refreshes the installed module list.
     */
    suspend fun importBooks(
        files: List<File>,
        onProgress: ((fileIndex: Int, totalFiles: Int, file: File, step: String) -> Unit)? = null
    ): List<Result<Book>> {
        return importService.importFiles(files, onProgress)
    }

    fun enqueueBookImports(files: List<File>): List<ImportQueueItem> {
        return importService.enqueueFiles(files)
    }

    fun enqueueBookImport(file: File): ImportQueueItem? {
        return importService.enqueueFile(file)
    }

    /**
     * Convenience method specifically for importing EPUB files.
     */
    suspend fun importEpub(file: File, onProgress: ((String) -> Unit)? = null): Result<Book> = importBook(file, onProgress)

    /**
     * Convenience method specifically for importing PDF files.
     */
    suspend fun importPdf(file: File, onProgress: ((String) -> Unit)? = null): Result<Book> = importBook(file, onProgress)

    fun isBookInstalled(initials: String): Boolean {
        return installedBooks.any { it.initials.equals(initials, ignoreCase = true) }
    }

    fun getInstalledBooksByCategory(category: BookCategory? = null): List<Book> {
        return if (category == null) {
            installedBooks.toList()
        } else {
            installedBooks.filter { it.bookCategory == category }
        }
    }

    /**
     * Returns the ordered list of books (with populated chapters) actually present in a
     * verse-based Sword module, filtered using the module's indexed keys so books/chapters
     * with no content are omitted from the table of contents.
     *
     * Skips intro pseudo-books (INTRO_BIBLE, INTRO_OT, INTRO_NT) that have no readable
     * content. Falls back to an empty list if the module is not a passage-based book.
     */
    fun getModuleBooks(book: Book): List<ModuleBook> {
        val passageBook = book as? AbstractPassageBook ?: return emptyList()
        val cacheKey = "${book.initials}:${book.bookMetaData?.location ?: ""}"
        synchronized(moduleBooksCache) {
            moduleBooksCache[cacheKey]?.let { return it }
        }
        return try {
            val v11n = passageBook.versification ?: return emptyList()
            val chaptersByBook: Map<BibleBook, List<Int>>? = try {
                val globalKeys = passageBook.globalKeyList
                val grouped = mutableMapOf<BibleBook, LinkedHashSet<Int>>()
                if (globalKeys is Passage) {
                    val rangeIter = globalKeys.rangeIterator(RestrictionType.CHAPTER)
                    while (rangeIter.hasNext()) {
                        val range = rangeIter.next()
                        val bb = range.start.book
                        val ch = range.start.chapter
                        if (ch >= 1) {
                            grouped.getOrPut(bb) { LinkedHashSet() }.add(ch)
                        }
                    }
                } else if (globalKeys != null) {
                    for (k in globalKeys) {
                        if (k is Verse && k.chapter >= 1) {
                            grouped.getOrPut(k.book) { LinkedHashSet() }.add(k.chapter)
                        }
                    }
                }
                grouped.mapValues { (_, chapters) -> chapters.sorted() }
            } catch (_: Exception) {
                null
            }

            val result = mutableListOf<ModuleBook>()
            val bookIter = v11n.bookIterator
            while (bookIter.hasNext()) {
                val bb = bookIter.next()
                // Skip intro pseudo-entries that have no actual chapters
                if (bb == BibleBook.INTRO_BIBLE || bb == BibleBook.INTRO_OT || bb == BibleBook.INTRO_NT) continue
                val chapterCount = v11n.getLastChapter(bb)
                if (chapterCount < 1) continue

                val populatedChapters = if (chaptersByBook != null) {
                    val found = chaptersByBook[bb]
                    if (found.isNullOrEmpty()) continue
                    found
                } else {
                    val hasContent = try {
                        passageBook.contains(Verse(v11n, bb, 1, 1)) ||
                            passageBook.contains(Verse(v11n, bb, 1, 2))
                    } catch (_: Exception) {
                        true
                    }
                    if (!hasContent) continue
                    (1..chapterCount).toList()
                }

                val displayName = try {
                    v11n.getBookName(bb)?.longName ?: bb.osis
                } catch (_: Exception) {
                    bb.osis
                }
                result.add(ModuleBook(bb, displayName, chapterCount, populatedChapters))
            }
            synchronized(moduleBooksCache) {
                moduleBooksCache[cacheKey] = result
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun chapterCacheKey(book: Book, reading: Reading): String =
        "ch|${book.initials}|${book.bookMetaData?.location ?: ""}|${reading.book}|${reading.chapterNumber}"

    private fun passageCacheKey(book: Book, reference: String): String =
        "ref|${book.initials}|${book.bookMetaData?.location ?: ""}|$reference"

    private fun keyContentCacheKey(book: Book, key: Key): String =
        "key|${book.initials}|${book.bookMetaData?.location ?: ""}|${key.osisRef.ifBlank { key.name }}"

    fun getCachedChapter(reading: Reading, book: Book? = selectedBible): ChapterPassage? {
        val targetBook = book ?: selectedBible ?: return null
        val key = chapterCacheKey(targetBook, reading)
        return synchronized(chapterCache) { chapterCache[key] }
    }

    fun areChaptersCached(readings: List<Reading>, book: Book? = selectedBible): Boolean {
        val targetBook = book ?: selectedBible ?: return false
        return synchronized(chapterCache) {
            readings.all { chapterCache.containsKey(chapterCacheKey(targetBook, it)) }
        }
    }

    fun getCachedKeyContent(book: Book?, key: Key?): String? {
        if (book == null || key == null) return null
        val cacheKey = keyContentCacheKey(book, key)
        return synchronized(keyContentCache) { keyContentCache[cacheKey] }
    }

    private fun resolveBibleKey(targetBook: Book, bookName: String, chapterNumber: Int, wholeBookOnly: Boolean = false): Key {
        val ref = if (wholeBookOnly) bookName else "$bookName $chapterNumber"
        return try {
            targetBook.getKey(ref)
        } catch (e: NoSuchKeyException) {
            when {
                bookName.equals("Psalms", ignoreCase = true) ->
                    targetBook.getKey(if (wholeBookOnly) "Psalm" else "Psalm $chapterNumber")
                bookName.equals("Psalm", ignoreCase = true) ->
                    targetBook.getKey(if (wholeBookOnly) "Psalms" else "Psalms $chapterNumber")
                else -> throw e
            }
        }
    }

    private fun resolveReferenceKey(targetBook: Book, reference: String): Key {
        return try {
            targetBook.getKey(reference)
        } catch (e: NoSuchKeyException) {
            if (reference.startsWith("Psalms ", ignoreCase = true)) {
                targetBook.getKey("Psalm " + reference.substring(7))
            } else if (reference.startsWith("Psalm ", ignoreCase = true)) {
                targetBook.getKey("Psalms " + reference.substring(6))
            } else {
                throw e
            }
        }
    }

    /**
     * Parses [key] from [targetBook] once into a single OSIS DOM tree and extracts canonical text
     * for every verse in [key], avoiding N per-verse JDOM parses per chapter.
     * Falls back to per-verse `BookData(targetBook, k)` only if the chapter DOM does not expose
     * `<verse>` elements (e.g. non-standard commentary modules).
     */
    private fun extractVersesSingleDom(targetBook: Book, key: Key): Pair<List<VerseItem>, Element> {
        val chapterData = BookData(targetBook, key)
        val chapterOsis = chapterData.osis

        val verseTextsByOsisId = extractVerseTextsFromOsisDom(chapterOsis)
        val verses = mutableListOf<VerseItem>()

        if (verseTextsByOsisId.isNotEmpty()) {
            for (k in key) {
                val verseObj = k as? Verse
                val osisId = verseObj?.osisID
                val rawText = if (osisId != null) verseTextsByOsisId[osisId] else null
                if (!rawText.isNullOrBlank()) {
                    val cleanText = rawText.trim().removePrefix(k.name).trim()
                    val verseNum = verseObj?.verse ?: 0
                    if (cleanText.isNotBlank()) {
                        verses.add(VerseItem(verseNumber = verseNum, text = cleanText, reference = k.name))
                    }
                }
            }
        }

        // Graceful fallback for modules whose OSIS does not wrap/mark individual <verse> nodes
        if (verses.isEmpty() && key.cardinality > 0) {
            for (k in key) {
                try {
                    val vData = BookData(targetBook, k)
                    val rawText = OSISUtil.getCanonicalText(vData.osis).trim()
                    val cleanText = rawText.removePrefix(k.name).trim()
                    val verseNum = if (k is Verse) k.verse else 0
                    if (cleanText.isNotBlank()) {
                        verses.add(VerseItem(verseNumber = verseNum, text = cleanText, reference = k.name))
                    }
                } catch (_: Exception) {
                    // Ignore individual verse failures
                }
            }
        }

        return verses to chapterOsis
    }

    private fun extractVerseTextsFromOsisDom(root: Element): Map<String, String> {
        val buffers = LinkedHashMap<String, StringBuilder>()
        var activeMilestoneIds: List<String> = emptyList()
        var activeMilestoneSid: String? = null

        fun appendCanonicalSegment(target: StringBuilder, segment: String) {
            if (segment.isEmpty()) return
            if (target.isNotEmpty() && !target[target.length - 1].isWhitespace() && !segment[0].isWhitespace()) {
                target.append(' ')
            }
            target.append(segment)
        }

        fun visit(content: Content) {
            when (content) {
                is Text -> {
                    if (activeMilestoneIds.isEmpty()) return
                    val txt = content.text
                    if (txt.isEmpty()) return
                    for (id in activeMilestoneIds) {
                        val sb = buffers.getOrPut(id) { StringBuilder() }
                        appendCanonicalSegment(sb, txt)
                    }
                }
                is Element -> {
                    val name = content.name
                    if (name in EXTRA_BIBLICAL_ELEMENTS && !content.getAttributeValue(OSISUtil.OSIS_ATTR_CANONICAL).toBoolean()) {
                        return
                    }
                    if (name == OSISUtil.OSIS_ELEMENT_VERSE) {
                        val sId = content.getAttributeValue(OSISUtil.OSIS_ATTR_SID)
                        val eId = content.getAttributeValue(OSISUtil.OSIS_ATTR_EID)
                        val osisIdAttr = content.getAttributeValue(OSISUtil.OSIS_ATTR_OSISID)
                        when {
                            sId != null -> {
                                activeMilestoneIds = osisIdAttr
                                    ?.split(WHITESPACE_SPLIT_REGEX)
                                    ?.filter { it.isNotBlank() }
                                    ?: emptyList()
                                activeMilestoneSid = sId
                                if (content.contentSize > 0) {
                                    for (child in content.content) {
                                        visit(child)
                                    }
                                }
                            }
                            eId != null -> {
                                if (activeMilestoneSid == null || activeMilestoneSid == eId) {
                                    activeMilestoneIds = emptyList()
                                    activeMilestoneSid = null
                                }
                            }
                            else -> {
                                val ids = osisIdAttr
                                    ?.split(WHITESPACE_SPLIT_REGEX)
                                    ?.filter { it.isNotBlank() }
                                    ?: emptyList()
                                if (ids.isNotEmpty()) {
                                    val text = OSISUtil.getCanonicalText(content).trim()
                                    if (text.isNotEmpty()) {
                                        for (id in ids) {
                                            val sb = buffers.getOrPut(id) { StringBuilder() }
                                            appendCanonicalSegment(sb, text)
                                        }
                                    }
                                }
                            }
                        }
                        return
                    }

                    for (child in content.content) {
                        visit(child)
                    }
                }
            }
        }

        visit(root)
        return buffers.mapValues { (_, sb) -> sb.toString().trim() }
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

        val cacheKey = chapterCacheKey(targetBook, reading)
        synchronized(chapterCache) {
            chapterCache[cacheKey]?.let { return it }
        }

        val refString = "${reading.book} ${reading.chapterNumber}"
        return try {
            var key = resolveBibleKey(targetBook, reading.book, reading.chapterNumber)

            // Single-chapter books (e.g. "Jude 1") may be parsed by JSword as a single verse (Jude 1:1);
            // detect that upfront before parsing XML so we only parse the whole book once.
            if (reading.chapterNumber == 1 && key.cardinality <= 1) {
                try {
                    val wholeBookKey = resolveBibleKey(targetBook, reading.book, reading.chapterNumber, wholeBookOnly = true)
                    if (wholeBookKey.cardinality > key.cardinality) {
                        key = wholeBookKey
                    }
                } catch (_: Exception) {
                    // Keep original key
                }
            }

            var (verses, chapterOsis) = extractVersesSingleDom(targetBook, key)

            // Preserve legacy guard in case key.cardinality differed from extracted verses.size
            if (verses.size == 1 && reading.chapterNumber == 1) {
                try {
                    val wholeBookKey = resolveBibleKey(targetBook, reading.book, reading.chapterNumber, wholeBookOnly = true)
                    if (wholeBookKey.cardinality > 1) {
                        key = wholeBookKey
                        val reExtracted = extractVersesSingleDom(targetBook, key)
                        verses = reExtracted.first
                        chapterOsis = reExtracted.second
                    }
                } catch (_: Exception) {
                    // Keep initial extraction
                }
            }

            val fullText = if (verses.isEmpty()) {
                OSISUtil.getCanonicalText(chapterOsis).trim()
            } else {
                verses.joinToString("\n\n") { "${it.verseNumber} ${it.text}" }
            }

            val passage = ChapterPassage(
                bookName = reading.book,
                chapterNumber = reading.chapterNumber,
                verses = verses,
                fullText = fullText
            )
            synchronized(chapterCache) {
                chapterCache[cacheKey] = passage
            }
            passage
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

    /**
     * Reads a chapter identified by a JSword [BibleBook] and chapter number from the given
     * Sword module. This overload is used by FurtherStudyView so that navigation is entirely
     * driven by the module's own versification rather than the static Bible enum.
     */
    fun readChapter(moduleBook: ModuleBook, chapter: Int, book: Book? = selectedBible): ChapterPassage {
        val bookName = moduleBook.displayName
        return readChapter(Reading(bookName, chapter), book)
    }

    fun readPassage(reference: String, book: Book? = selectedBible): ChapterPassage {
        val targetBook = book ?: selectedBible
        if (targetBook == null) {
            return ChapterPassage(
                bookName = reference,
                chapterNumber = 0,
                verses = emptyList(),
                fullText = "",
                error = "No Bible or Book selected/installed. Please download a Bible first."
            )
        }

        val cacheKey = passageCacheKey(targetBook, reference)
        synchronized(chapterCache) {
            chapterCache[cacheKey]?.let { return it }
        }

        return try {
            val key = resolveReferenceKey(targetBook, reference)
            val (verses, passageOsis) = extractVersesSingleDom(targetBook, key)

            val fullText = if (verses.isEmpty()) {
                OSISUtil.getCanonicalText(passageOsis).trim()
            } else {
                verses.joinToString("\n\n") { "${it.verseNumber} ${it.text}" }
            }

            val passage = ChapterPassage(
                bookName = reference,
                chapterNumber = 0,
                verses = verses,
                fullText = fullText
            )
            synchronized(chapterCache) {
                chapterCache[cacheKey] = passage
            }
            passage
        } catch (e: Exception) {
            ChapterPassage(
                bookName = reference,
                chapterNumber = 0,
                verses = emptyList(),
                fullText = "",
                error = "Could not read '$reference' in ${targetBook.name} (${targetBook.initials}): ${e.message}"
            )
        }
    }

    fun getBookKeys(book: Book, maxLimit: Int = 1000): List<Key> {
        val result = mutableListOf<Key>()
        val root = try {
            book.globalKeyList
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        fun collect(parent: Key) {
            if (result.size >= maxLimit) return@collect
            for (i in 0 until parent.childCount) {
                if (result.size >= maxLimit) return@collect
                val child = parent.get(i)
                if (child.name.isNotBlank()) {
                    result.add(child)
                }
                if (child.childCount > 0) {
                    collect(child)
                }
            }
        }

        if (root.childCount > 0) {
            collect(root)
        } else {
            for (k in root) {
                if (result.size >= maxLimit) break
                if (k.name.isNotBlank()) {
                    result.add(k)
                }
            }
        }
        return result
    }

    fun readKeyContent(book: Book, key: Key): String {
        val cacheKey = keyContentCacheKey(book, key)
        synchronized(keyContentCache) {
            keyContentCache[cacheKey]?.let { return it }
        }
        return try {
            val data = BookData(book, key)
            val content = extractFormattedKeyContent(data.osis).trim()
            synchronized(keyContentCache) {
                keyContentCache[cacheKey] = content
            }
            content
        } catch (e: Exception) {
            "Error loading content: ${e.message}"
        }
    }

    /**
     * Extracts readable text content from an OSIS XML DOM while preserving:
     * - Whitespace (tabs, newlines, carriage returns, indentation)
     * - Line breaks (<lb/>)
     * - Paragraph spacing (<p>, <title>, <q>, <list>, <table> separated by a single blank line)
     * - Lists (<item>) and tables (<row>, <cell>)
     * while ignoring inter-element XML formatting whitespace and empty spacer blocks,
     * and excluding non-canonical extra-biblical elements (<note>, <reference>).
     */
    private fun extractFormattedKeyContent(root: Element): String {
        return try {
            val sb = StringBuilder()
            extractFormattedNode(root, sb, parentIsContainer = true)
            val formatted = COLLAPSE_EXTRA_BLANK_LINES_REGEX.replace(sb.toString(), "\n\n").trim()
            if (formatted.isNotEmpty()) formatted else OSISUtil.getCanonicalText(root).trim()
        } catch (_: Exception) {
            OSISUtil.getCanonicalText(root).trim()
        }
    }

    private fun extractFormattedNode(
        content: Content,
        sb: StringBuilder,
        parentIsContainer: Boolean = false,
        afterLb: Boolean = false
    ): Boolean {
        if (content is Text) {
            var txt = content.text
            if (parentIsContainer && txt.isBlank()) {
                return false
            }
            if (afterLb) {
                txt = LEADING_AFTER_LB_REGEX.replace(txt, "")
                if (txt.isEmpty()) return true
            }
            sb.append(txt)
            return false
        } else if (content is Element) {
            val name = content.name.lowercase()

            // Exclude non-canonical extra-biblical notes and cross references
            if (name == "note" || name == "reference") {
                val canonical = content.getAttributeValue("canonical")
                if (!canonical.toBoolean()) {
                    return false
                }
            }

            if (name == "lb") {
                sb.append('\n')
                return true
            }

            val isParagraphBlock = name == "p" || name == "title" || name == "q" || name == "list" || name == "table"
            val isLineBlock = name == "item" || name == "row"
            val isBlock = isParagraphBlock || isLineBlock

            val isContainer = name == "osis" || name == "osistext" || name == "body" ||
                name == "div" || name == "list" || name == "table" || name == "row" ||
                (name == "q" && content.children.any {
                    it.name.lowercase() in BLOCK_CONTAINER_CHILD_TAGS
                })

            if (isBlock) {
                val childSb = StringBuilder()
                var childAfterLb = false
                for (child in content.content) {
                    childAfterLb = extractFormattedNode(
                        child,
                        childSb,
                        parentIsContainer = isContainer,
                        afterLb = childAfterLb
                    )
                }

                val childStr = childSb.toString().trimStart('\r', '\n')
                if (childStr.replace("\u00A0", "").isBlank()) {
                    return false
                }

                val requiredNewlines = if (isParagraphBlock) 2 else 1
                if (sb.isNotEmpty()) {
                    ensureTrailingNewlines(sb, requiredNewlines)
                }
                sb.append(childStr)
                ensureTrailingNewlines(sb, requiredNewlines)
                return false
            }

            var childAfterLb = false
            for (child in content.content) {
                childAfterLb = extractFormattedNode(
                    child,
                    sb,
                    parentIsContainer = isContainer,
                    afterLb = childAfterLb
                )
            }

            if (name == "cell" && (sb.isEmpty() || sb.last() != '\t')) {
                sb.append('\t')
            }
        }
        return false
    }

    private fun ensureTrailingNewlines(sb: StringBuilder, count: Int) {
        if (sb.isEmpty()) return
        var idx = sb.length - 1
        while (idx >= 0 && (sb[idx] == ' ' || sb[idx] == '\t' || sb[idx] == '\r')) {
            idx--
        }
        if (idx < sb.length - 1 && idx >= 0 && sb[idx] == '\n') {
            sb.setLength(idx + 1)
        }
        if (sb.isEmpty()) return

        var existingNewlines = 0
        var i = sb.length - 1
        while (i >= 0 && sb[i] == '\n') {
            existingNewlines++
            i--
        }
        if (existingNewlines < count) {
            repeat(count - existingNewlines) {
                sb.append('\n')
            }
        }
    }
}

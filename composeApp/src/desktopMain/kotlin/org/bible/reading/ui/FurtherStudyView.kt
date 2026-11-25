package org.bible.reading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bible.reading.dto.Reading
import org.bible.reading.jsword.ChapterPassage
import org.bible.reading.jsword.ModuleBook
import org.bible.reading.jsword.SwordManager
import org.bible.reading.notes.NotesRepository
import org.bible.reading.notes.ui.NotesPanel
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.tts.TtsService
import org.bible.reading.tts.TtsTextCleaner
import org.bible.reading.tts.ui.TtsFileUtils
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.passage.Key
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.milliseconds

private val SAFE_EXPORT_FILENAME_REGEX = Regex("[^a-zA-Z0-9.-]")
private val QUICK_REF_REGEX = Regex("""^([1-3]?\s*[A-Za-z]+(?:\s+[A-Za-z]+)?)\s*(\d+)?(?::(\d+))?""")

enum class ModuleCategoryTab(val displayName: String, val category: BookCategory?) {
    ALL("All Modules", null),
    BIBLES("Bibles", BookCategory.BIBLE),
    COMMENTARIES("Commentaries", BookCategory.COMMENTARY),
    GENERAL_BOOKS("General Books", BookCategory.GENERAL_BOOK),
    DICTIONARIES("Dictionaries", BookCategory.DICTIONARY),
    DEVOTIONALS("Devotionals", BookCategory.DAILY_DEVOTIONS)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FurtherStudyView(
    manager: SwordManager,
    ttsService: TtsService,
    audioPlayer: AudioBiblePlayer,
    sessionState: SessionReadingState? = null,
    onOpenDownload: () -> Unit,
    onOpenTtsSettings: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Category filter for module selector
    var categoryTab by remember {
        mutableStateOf(
            sessionState?.furtherStudyCategoryTabName?.let { name ->
                ModuleCategoryTab.entries.firstOrNull { it.name == name }
            } ?: ModuleCategoryTab.ALL
        )
    }
    LaunchedEffect(categoryTab) {
        sessionState?.furtherStudyCategoryTabName = categoryTab.name
    }

    // Selected primary module
    var selectedModule by remember(manager.installedBooks) {
        mutableStateOf<Book?>(
            manager.pendingStudyModule
                ?: sessionState?.furtherStudySelectedModuleInitials?.let { init ->
                    manager.installedBooks.firstOrNull { it.initials.equals(init, ignoreCase = true) }
                }
                ?: manager.selectedBible
                ?: manager.installedBooks.firstOrNull()
        )
    }
    LaunchedEffect(selectedModule) {
        selectedModule?.let { sessionState?.furtherStudySelectedModuleInitials = it.initials }
    }

    // Keep selectedModule valid if installedBooks changes
    LaunchedEffect(manager.installedBooks) {
        if (selectedModule == null || !manager.installedBooks.contains(selectedModule)) {
            selectedModule = sessionState?.furtherStudySelectedModuleInitials?.let { init ->
                manager.installedBooks.firstOrNull { it.initials.equals(init, ignoreCase = true) }
            } ?: manager.selectedBible ?: manager.installedBooks.firstOrNull()
        }
    }

    // Honor external requests to open a specific module (e.g. from ImportQueueDialog)
    LaunchedEffect(manager.pendingStudyModule) {
        val target = manager.pendingStudyModule ?: return@LaunchedEffect
        selectedModule = target
        sessionState?.furtherStudySelectedModuleInitials = target.initials
        if (target.bookCategory == BookCategory.GENERAL_BOOK) {
            categoryTab = ModuleCategoryTab.GENERAL_BOOKS
        }
        manager.pendingStudyModule = null
    }

    // Module info dialog state
    var showModuleInfoDialog by remember { mutableStateOf(false) }

    // Notes repository (disk-backed, shared for the lifetime of the view)
    val notesRepository = remember { NotesRepository(manager.appDir) }

    // Parallel comparison mode state
    var isParallelMode by remember { mutableStateOf(sessionState?.furtherStudyIsParallelMode ?: false) }
    LaunchedEffect(isParallelMode) {
        sessionState?.furtherStudyIsParallelMode = isParallelMode
    }
    var secondaryModule by remember(manager.installedBooks) {
        mutableStateOf<Book?>(
            sessionState?.furtherStudySecondaryModuleInitials?.let { init ->
                manager.installedBooks.firstOrNull { it.initials.equals(init, ignoreCase = true) }
            }
                ?: manager.installedBooks.firstOrNull { it.bookCategory == BookCategory.COMMENTARY }
                ?: manager.installedBooks.firstOrNull { it != selectedModule }
        )
    }
    LaunchedEffect(secondaryModule) {
        sessionState?.furtherStudySecondaryModuleInitials = secondaryModule?.initials
    }

    // Notes panel toggle
    var isNotesMode by remember { mutableStateOf(sessionState?.furtherStudyIsNotesMode ?: false) }
    LaunchedEffect(isNotesMode) {
        sessionState?.furtherStudyIsNotesMode = isNotesMode
    }

    // Check if the current module is verse-based (commentaries/devotionals only if backed by a passage driver)
    val isVerseBasedModule = selectedModule?.bookCategory == BookCategory.BIBLE ||
            (selectedModule?.bookCategory == BookCategory.COMMENTARY && selectedModule is AbstractPassageBook) ||
            (selectedModule?.bookCategory == BookCategory.DAILY_DEVOTIONS && selectedModule is AbstractPassageBook)

    // Module TOC: list of books with populated content for verse-based modules.
    // Recomputed whenever the selected module changes.
    var moduleBooks by remember(selectedModule) {
        mutableStateOf<List<ModuleBook>>(emptyList())
    }
    var isLoadingModuleBooks by remember(selectedModule) {
        mutableStateOf(selectedModule != null && isVerseBasedModule)
    }
    // Verse-based navigation state: index into moduleBooks + chapter number.
    var currentBookIndex by remember(selectedModule) { mutableStateOf(0) }
    var currentChapter by remember(selectedModule) { mutableStateOf(1) }
    var selectedVerseFilter by remember(selectedModule) { mutableStateOf(0) } // 0 means all verses

    LaunchedEffect(selectedModule) {
        val mod = selectedModule
        if (mod != null && isVerseBasedModule) {
            isLoadingModuleBooks = true
            val loadedBooks = withContext(Dispatchers.IO) {
                manager.getModuleBooks(mod)
            }
            val savedPos = sessionState?.getModulePosition(mod.initials)
            val restoredBookIdx = savedPos?.bookOsis?.let { osis ->
                loadedBooks.indexOfFirst { it.bibleBook.osis == osis }.takeIf { it >= 0 }
            } ?: 0
            val mb = loadedBooks.getOrNull(restoredBookIdx)
            val restoredChapter = if (mb != null && savedPos != null && savedPos.chapter in mb.chapters) {
                savedPos.chapter
            } else {
                mb?.chapters?.firstOrNull() ?: 1
            }
            currentBookIndex = restoredBookIdx
            currentChapter = restoredChapter
            selectedVerseFilter = savedPos?.selectedVerseFilter ?: 0
            moduleBooks = loadedBooks
            isLoadingModuleBooks = false
        } else {
            isLoadingModuleBooks = false
            moduleBooks = emptyList()
        }
    }

    val currentModuleBook: ModuleBook? = moduleBooks.getOrNull(currentBookIndex)

    // Snap currentChapter to a populated chapter whenever the book changes
    LaunchedEffect(currentModuleBook) {
        val mb = currentModuleBook ?: return@LaunchedEffect
        if (currentChapter !in mb.chapters) {
            currentChapter = mb.chapters.firstOrNull() ?: 1
        }
    }

    // Persist current verse-based position in session once module TOC is loaded
    LaunchedEffect(selectedModule, currentModuleBook, currentChapter, selectedVerseFilter, isLoadingModuleBooks) {
        val mod = selectedModule ?: return@LaunchedEffect
        val mb = currentModuleBook ?: return@LaunchedEffect
        if (!isLoadingModuleBooks && isVerseBasedModule) {
            sessionState?.saveVersePosition(
                moduleInitials = mod.initials,
                bookOsis = mb.bibleBook.osis,
                chapter = currentChapter,
                verseFilter = selectedVerseFilter
            )
        }
    }

    // Quick reference jump
    var quickRefInput by remember { mutableStateOf("") }
    var quickRefError by remember { mutableStateOf<String?>(null) }

    // General Book navigation state (for tree/key-based books)
    var genBookKeys by remember(selectedModule) {
        mutableStateOf<List<Key>>(emptyList())
    }
    var isLoadingGenBookKeys by remember(selectedModule) {
        mutableStateOf(selectedModule != null && !isVerseBasedModule)
    }
    var selectedKeyIndex by remember(selectedModule) { mutableStateOf(0) }
    var genBookSearchQuery by remember { mutableStateOf(sessionState?.furtherStudyGenBookSearchQuery ?: "") }
    LaunchedEffect(genBookSearchQuery) {
        sessionState?.furtherStudyGenBookSearchQuery = genBookSearchQuery
    }

    // Fetch keys when a non-verse-based module (General Book, Dictionary, Devotional, etc.) is selected
    LaunchedEffect(selectedModule) {
        val mod = selectedModule
        if (mod != null && !isVerseBasedModule) {
            isLoadingGenBookKeys = true
            val loadedKeys = withContext(Dispatchers.IO) { manager.getBookKeys(mod) }
            val savedPos = sessionState?.getModulePosition(mod.initials)
            val restoredIdx = when {
                savedPos == null -> 0
                savedPos.selectedKeyName != null -> {
                    loadedKeys.indexOfFirst { it.name == savedPos.selectedKeyName }
                        .takeIf { it >= 0 }
                        ?: savedPos.selectedKeyIndex.coerceIn(0, (loadedKeys.size - 1).coerceAtLeast(0))
                }
                loadedKeys.isNotEmpty() -> savedPos.selectedKeyIndex.coerceIn(0, loadedKeys.lastIndex)
                else -> 0
            }
            genBookKeys = loadedKeys
            selectedKeyIndex = restoredIdx
            isLoadingGenBookKeys = false
        } else {
            isLoadingGenBookKeys = false
            genBookKeys = emptyList()
        }
    }

    // Persist current general book / dictionary section in session once keys are loaded
    LaunchedEffect(selectedModule, selectedKeyIndex, genBookKeys, isLoadingGenBookKeys) {
        val mod = selectedModule ?: return@LaunchedEffect
        if (!isLoadingGenBookKeys && !isVerseBasedModule && genBookKeys.isNotEmpty()) {
            val clamped = selectedKeyIndex.coerceIn(0, genBookKeys.lastIndex)
            sessionState?.saveGenBookPosition(
                moduleInitials = mod.initials,
                keyIndex = clamped,
                keyName = genBookKeys.getOrNull(clamped)?.name
            )
        }
    }

    // Content font size zoom
    var fontSizeSp by remember { mutableStateOf(sessionState?.furtherStudyFontSizeSp ?: 16) }
    LaunchedEffect(fontSizeSp) {
        sessionState?.furtherStudyFontSizeSp = fontSizeSp
    }

    // Copy to clipboard notification
    var copyNotification by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyNotification) {
        if (copyNotification != null) {
            kotlinx.coroutines.delay(2500.milliseconds)
            copyNotification = null
        }
    }

    // Navigation helpers operating on moduleBooks list
    fun stepToPrevChapter() {
        val mb = currentModuleBook ?: return
        val chIdx = mb.chapters.indexOf(currentChapter)
        if (chIdx > 0) {
            currentChapter = mb.chapters[chIdx - 1]
        } else if (currentBookIndex > 0) {
            currentBookIndex -= 1
            currentChapter = moduleBooks.getOrNull(currentBookIndex)?.chapters?.lastOrNull() ?: 1
        }
        selectedVerseFilter = 0
    }

    fun stepToNextChapter() {
        val mb = currentModuleBook ?: return
        val chIdx = mb.chapters.indexOf(currentChapter)
        if (chIdx >= 0 && chIdx < mb.chapters.lastIndex) {
            currentChapter = mb.chapters[chIdx + 1]
        } else if (chIdx < 0 && mb.chapters.any { it > currentChapter }) {
            currentChapter = mb.chapters.first { it > currentChapter }
        } else if (currentBookIndex < moduleBooks.lastIndex) {
            currentBookIndex += 1
            currentChapter = moduleBooks.getOrNull(currentBookIndex)?.chapters?.firstOrNull() ?: 1
        }
        selectedVerseFilter = 0
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header Bar for Further Study View
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Title and Downloader Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Further Study",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "${manager.installedBooks.size} Modules Installed",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Text(
                                text = "View, compare, and study Sword modules (Bibles, Commentaries, Dictionaries, General Books)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Import queue status notification placed to the left of Module Info
                            ImportQueueStatusChip(manager.importService)

                            // Module Info button
                            if (selectedModule != null) {
                                OutlinedButton(
                                    onClick = { showModuleInfoDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("ℹ Module Info", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Category Filter Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModuleCategoryTab.entries.forEach { tab ->
                            val count = when (tab) {
                                ModuleCategoryTab.ALL -> manager.installedBooks.size
                                else -> manager.installedBooks.count { it.bookCategory == tab.category }
                            }
                            FilterChip(
                                selected = categoryTab == tab,
                                onClick = { categoryTab = tab },
                                label = { Text("${tab.displayName} ($count)", fontSize = 12.sp) }
                            )
                        }
                    }

                    // Module Selection & Navigation Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Primary Module Dropdown
                        val filteredBooks = remember(manager.installedBooks.size, manager.installedBooks.toList(), categoryTab) {
                            when (categoryTab) {
                                ModuleCategoryTab.ALL -> manager.installedBooks.toList()
                                else -> manager.installedBooks.filter { it.bookCategory == categoryTab.category }
                            }
                        }

                        var moduleMenuExpanded by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.weight(1.3f)) {
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { moduleMenuExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Module: ${selectedModule?.name ?: "None Selected"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${selectedModule?.initials ?: ""} • ${selectedModule?.bookCategory?.name ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text("▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            DropdownMenu(
                                expanded = moduleMenuExpanded,
                                onDismissRequest = { moduleMenuExpanded = false }
                            ) {
                                if (filteredBooks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No modules found in this category") },
                                        onClick = { moduleMenuExpanded = false }
                                    )
                                } else {
                                    filteredBooks.forEach { book ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = book.name,
                                                            fontWeight = if (book == selectedModule) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Spacer(Modifier.width(10.dp))
                                                    }
                                                    Text(
                                                        text = "${book.bookCategory.name} • ${manager.getBookLocationLabel(book)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedModule = book
                                                moduleMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Parallel Mode Toggle (only for verse-based modules)
                        if (isVerseBasedModule) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Switch(
                                    checked = isParallelMode,
                                    onCheckedChange = { isParallelMode = it }
                                )
                                Text("Parallel Study", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }

                            // Secondary Module Dropdown if Parallel Mode is active
                            if (isParallelMode) {
                                var secondaryMenuExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.weight(1.3f)) {
                                    OutlinedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { secondaryMenuExpanded = true }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Parallel: ${secondaryModule?.name ?: "None"}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = "${secondaryModule?.initials ?: ""} • ${secondaryModule?.bookCategory?.name ?: ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text("▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = secondaryMenuExpanded,
                                        onDismissRequest = { secondaryMenuExpanded = false }
                                    ) {
                                        manager.installedBooks.forEach { book ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = book.name,
                                                            fontWeight = if (book == secondaryModule) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                    }
                                                },
                                                onClick = {
                                                    secondaryModule = book
                                                    secondaryMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Notes Mode Toggle — available for all module types
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Switch(
                                checked = isNotesMode,
                                onCheckedChange = { isNotesMode = it }
                            )
                            Text("📝 Notes", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Sub-Navigation Bar: Passage Navigation or General Book TOC
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isVerseBasedModule) {
                    // Verse Navigation Bar — driven entirely by the module's own TOC
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Book & Chapter Steppers & Quick Jump
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val canStepPrev = currentBookIndex > 0 ||
                                (currentModuleBook?.chapters?.indexOf(currentChapter) ?: 0) > 0
                            val canStepNext = currentBookIndex < moduleBooks.lastIndex ||
                                (currentModuleBook?.let { mb ->
                                    val idx = mb.chapters.indexOf(currentChapter)
                                    (idx >= 0 && idx < mb.chapters.lastIndex) || currentChapter < (mb.chapters.lastOrNull() ?: mb.chapterCount)
                                } ?: false)
                            // Previous Chapter Button
                            OutlinedButton(
                                onClick = { stepToPrevChapter() },
                                enabled = canStepPrev,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("◀ Prev", fontSize = 12.sp)
                            }

                            // Book Selector — lists only the books the module actually has
                            if (moduleBooks.isNotEmpty()) {
                                var bookMenuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(
                                        onClick = { bookMenuExpanded = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            "${currentModuleBook?.displayName ?: "—"} ▼",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = bookMenuExpanded,
                                        onDismissRequest = { bookMenuExpanded = false }
                                    ) {
                                        moduleBooks.forEachIndexed { idx, mb ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = mb.displayName,
                                                            fontWeight = if (idx == currentBookIndex) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Spacer(Modifier.width(16.dp))
                                                        Text(
                                                            text = "${mb.chapters.size} ch",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    currentBookIndex = idx
                                                    currentChapter = mb.chapters.firstOrNull() ?: 1
                                                    selectedVerseFilter = 0
                                                    bookMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Chapter Stepper
                            if (currentModuleBook != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { stepToPrevChapter() },
                                        enabled = canStepPrev,
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Text("-", fontWeight = FontWeight.Bold)
                                    }

                                    var chapterMenuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { chapterMenuExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("Ch $currentChapter", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }

                                        DropdownMenu(
                                            expanded = chapterMenuExpanded,
                                            onDismissRequest = { chapterMenuExpanded = false }
                                        ) {
                                            currentModuleBook.chapters.forEach { ch ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            "Chapter $ch",
                                                            fontWeight = if (ch == currentChapter) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        currentChapter = ch
                                                        selectedVerseFilter = 0
                                                        chapterMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { stepToNextChapter() },
                                        enabled = canStepNext,
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Text("+", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Next Chapter Button
                            OutlinedButton(
                                onClick = { stepToNextChapter() },
                                enabled = canStepNext,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Next ▶", fontSize = 12.sp)
                            }
                        }

                        // Right: Quick Reference Search Box (e.g. Rom 8:28, John 3:16)
                        val navigateToQuickRef = {
                            if (quickRefInput.isNotBlank()) {
                                val result = parseQuickReference(quickRefInput.trim(), moduleBooks)
                                if (result != null) {
                                    currentBookIndex = result.first
                                    currentChapter = result.second
                                    selectedVerseFilter = result.third
                                    quickRefError = null
                                } else {
                                    quickRefError = "Could not parse reference '${quickRefInput.trim()}'"
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = quickRefInput,
                                onValueChange = {
                                    quickRefInput = it
                                    quickRefError = null
                                },
                                placeholder = {
                                    Text(
                                        text = "Jump to ref: e.g. Rom 8:28, John 3",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = { navigateToQuickRef() },
                                    onDone = { navigateToQuickRef() }
                                ),
                                modifier = Modifier
                                    .width(260.dp)
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.key == ComposeKey.Enter || keyEvent.key == ComposeKey.NumPadEnter) {
                                            if (keyEvent.type == KeyEventType.KeyDown) {
                                                navigateToQuickRef()
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                textStyle = MaterialTheme.typography.bodySmall
                            )

                            Button(
                                onClick = navigateToQuickRef,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Go", fontSize = 12.sp)
                            }

                            // Font size controls
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { if (fontSizeSp > 12) fontSizeSp -= 2 },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { if (fontSizeSp < 32) fontSizeSp += 2 },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // General Book / Dictionary Table of Contents Navigation Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (selectedKeyIndex > 0) {
                                        selectedKeyIndex -= 1
                                    }
                                },
                                enabled = selectedKeyIndex > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("◀ Prev Section", fontSize = 12.sp)
                            }

                            // Section Dropdown Picker
                            var sectionMenuExpanded by remember { mutableStateOf(false) }
                            val currentKeyName = genBookKeys.getOrNull(selectedKeyIndex)?.name ?: "Select Section"

                            Box {
                                OutlinedButton(
                                    onClick = { sectionMenuExpanded = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("$currentKeyName ▼", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                DropdownMenu(
                                    expanded = sectionMenuExpanded,
                                    onDismissRequest = { sectionMenuExpanded = false }
                                ) {
                                    val filteredKeys = remember(genBookKeys, genBookSearchQuery) {
                                        if (genBookSearchQuery.isBlank()) {
                                            genBookKeys
                                        } else {
                                            genBookKeys.filter { it.name.contains(genBookSearchQuery, ignoreCase = true) }
                                        }
                                    }
                                    val selectFirstFilteredSection = {
                                        val firstMatch = filteredKeys.firstOrNull()
                                        if (firstMatch != null) {
                                            val idx = genBookKeys.indexOf(firstMatch)
                                            if (idx >= 0) {
                                                selectedKeyIndex = idx
                                                sectionMenuExpanded = false
                                            }
                                        }
                                    }

                                    // Search in dropdown
                                    OutlinedTextField(
                                        value = genBookSearchQuery,
                                        onValueChange = { genBookSearchQuery = it },
                                        placeholder = { Text("Filter sections...", fontSize = 12.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(
                                            onGo = { selectFirstFilteredSection() },
                                            onDone = { selectFirstFilteredSection() }
                                        ),
                                        modifier = Modifier
                                            .width(280.dp)
                                            .padding(8.dp)
                                            .onPreviewKeyEvent { keyEvent ->
                                                if (keyEvent.key == ComposeKey.Enter || keyEvent.key == ComposeKey.NumPadEnter) {
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        selectFirstFilteredSection()
                                                    }
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                    )

                                    filteredKeys.take(80).forEach { k ->
                                        val idx = genBookKeys.indexOf(k)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = k.name,
                                                    fontWeight = if (idx == selectedKeyIndex) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        val mod = selectedModule
                                                        if (mod != null) {
                                                            coroutineScope.launch {
                                                                val secContent = withContext(Dispatchers.IO) {
                                                                    manager.readKeyContent(mod, k)
                                                                }
                                                                ttsService.queueSection(
                                                                    title = k.name,
                                                                    subtitle = mod.name,
                                                                    content = secContent,
                                                                    player = audioPlayer
                                                                )
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Text("➕", fontSize = 11.sp)
                                                }
                                            },
                                            onClick = {
                                                selectedKeyIndex = idx
                                                sectionMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    if (selectedKeyIndex < genBookKeys.lastIndex) {
                                        selectedKeyIndex += 1
                                    }
                                },
                                enabled = selectedKeyIndex < genBookKeys.lastIndex,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Next Section ▶", fontSize = 12.sp)
                            }

                            // Batch queue next 5 sections button (Requirement 4)
                            if (genBookKeys.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        val mod = selectedModule
                                        if (mod != null) {
                                            coroutineScope.launch {
                                                val nextKeys = genBookKeys.drop(selectedKeyIndex).take(5)
                                                nextKeys.forEach { k ->
                                                    val secContent = withContext(Dispatchers.IO) {
                                                        manager.readKeyContent(mod, k)
                                                    }
                                                    ttsService.queueSection(
                                                        title = k.name,
                                                        subtitle = mod.name,
                                                        content = secContent,
                                                        player = audioPlayer
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("➕ Queue Next 5", fontSize = 12.sp)
                                }
                            }

                            Text(
                                text = "Section ${selectedKeyIndex + 1} of ${genBookKeys.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Font size zoom
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { if (fontSizeSp > 12) fontSizeSp -= 2 },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { if (fontSizeSp < 32) fontSizeSp += 2 },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Quick error notice if quick ref parse failed
            quickRefError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // Notification message when copied
            copyNotification?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // TTS Status notification if synthesizing or downloading
            ttsService.statusMessage?.let { statusMsg ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (ttsService.isSynthesizing || ttsService.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = statusMsg,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (ttsService.isSynthesizing) {
                            TextButton(
                                onClick = { ttsService.stopReading(audioPlayer) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text("Cancel", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Main Reader Content Area
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (selectedModule == null) {
                    // Empty state if no modules installed
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 520.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("📚", fontSize = 48.sp)
                                Text(
                                    text = "No Sword Modules Selected",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Install Bibles, commentaries, dictionaries, or general books to begin further study.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = onOpenDownload) {
                                    Text("Manage Books")
                                }
                            }
                        }
                    }
                } else if (isVerseBasedModule) {
                    // Verse-based Module Reading Surface (Supports Single or Parallel comparison)

                    // Loading state while moduleBooks is still being fetched
                    if (isLoadingModuleBooks) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (moduleBooks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "This module contains no readable chapters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        val primaryPassageState by produceState<ChapterPassage?>(
                            initialValue = currentModuleBook?.let { mb ->
                                manager.getCachedChapter(Reading(mb.displayName, currentChapter), selectedModule)
                            },
                            key1 = selectedModule,
                            key2 = currentModuleBook,
                            key3 = currentChapter
                        ) {
                            value = if (currentModuleBook != null) {
                                withContext(Dispatchers.IO) {
                                    manager.readChapter(currentModuleBook, currentChapter, selectedModule)
                                }
                            } else {
                                ChapterPassage("", 0, emptyList(), "", error = "No book selected.")
                            }
                        }

                        val secondaryPassageState by produceState<ChapterPassage?>(
                            initialValue = if (isParallelMode && secondaryModule != null && currentModuleBook != null) {
                                manager.getCachedChapter(Reading(currentModuleBook.displayName, currentChapter), secondaryModule)
                            } else null,
                            key1 = isParallelMode,
                            key2 = secondaryModule,
                            key3 = Triple(selectedModule, currentModuleBook, currentChapter)
                        ) {
                            value = if (isParallelMode && secondaryModule != null && currentModuleBook != null) {
                                withContext(Dispatchers.IO) {
                                    manager.readChapter(currentModuleBook, currentChapter, secondaryModule)
                                }
                            } else {
                                null
                            }
                        }

                        val primaryPassage = primaryPassageState
                        val secondaryPassage = secondaryPassageState

                        if (primaryPassage == null || (isParallelMode && secondaryModule != null && currentModuleBook != null && secondaryPassage == null)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (isParallelMode && secondaryPassage != null) {
                            // Split / Parallel View: primary + secondary (+ optional notes)
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Left Column: Primary Module
                                val primaryTrackTitle = "${primaryPassage.bookName} ${primaryPassage.chapterNumber} (${selectedModule!!.initials})"
                                val isPrimaryReading = audioPlayer.isTrackActive(primaryTrackTitle)
                                val isPrimarySynthesizing = ttsService.isSynthesizing && ttsService.currentReadingTitle == primaryTrackTitle
                                val isPrimaryQueued = audioPlayer.isTrackQueued(primaryTrackTitle)

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    PassageCardContent(
                                        passage = primaryPassage,
                                        book = selectedModule!!,
                                        sectionKey = sessionState?.verseSectionKey(
                                            pane = "primary",
                                            moduleInitials = selectedModule!!.initials,
                                            bookOsis = currentModuleBook?.bibleBook?.osis ?: primaryPassage.bookName,
                                            chapter = currentChapter,
                                            verseFilter = selectedVerseFilter
                                        ) ?: "verse|primary|${selectedModule!!.initials}|${primaryPassage.bookName}|$currentChapter|$selectedVerseFilter",
                                        sessionState = sessionState,
                                        fontSizeSp = fontSizeSp,
                                        selectedVerseFilter = selectedVerseFilter,
                                        isReading = isPrimaryReading,
                                        isSynthesizing = isPrimarySynthesizing,
                                        isQueued = isPrimaryQueued,
                                        ttsService = ttsService,
                                        audioPlayer = audioPlayer,
                                        onReadPassage = {
                                            ttsService.readPassage(primaryPassage, selectedModule!!, audioPlayer)
                                        },
                                        onQueuePassage = {
                                            ttsService.queuePassage(primaryPassage, selectedModule!!, audioPlayer)
                                        },
                                        onSavePassageAudio = {
                                            val safeName = "${primaryPassage.bookName}_${primaryPassage.chapterNumber}_${selectedModule!!.initials}"
                                            TtsFileUtils.openSaveAudioFileDialog(defaultFilename = safeName) { destFile ->
                                                coroutineScope.launch {
                                                    val speechText = TtsTextCleaner.formatPassageForSpeech(
                                                        primaryPassage.bookName,
                                                        primaryPassage.chapterNumber,
                                                        primaryPassage.fullText
                                                    )
                                                    ttsService.synthesizeToFile(speechText, primaryTrackTitle, destFile)
                                                }
                                            }
                                        },
                                        onReadVerse = { vNum, vText ->
                                            ttsService.readVerse(
                                                reference = "${primaryPassage.bookName} ${primaryPassage.chapterNumber}:$vNum",
                                                verseText = vText,
                                                bookInitials = selectedModule!!.initials,
                                                player = audioPlayer
                                            )
                                        },
                                        onCopy = {
                                            copyTextToClipboard(primaryPassage.fullText)
                                            copyNotification = "Copied ${primaryPassage.bookName} ${primaryPassage.chapterNumber} to clipboard!"
                                        }
                                    )
                                }

                                // Centre Column: Secondary Module
                                val secondaryTrackTitle = "${secondaryPassage.bookName} ${secondaryPassage.chapterNumber} (${secondaryModule!!.initials})"
                                val isSecondaryReading = audioPlayer.isTrackActive(secondaryTrackTitle)
                                val isSecondarySynthesizing = ttsService.isSynthesizing && ttsService.currentReadingTitle == secondaryTrackTitle
                                val isSecondaryQueued = audioPlayer.isTrackQueued(secondaryTrackTitle)

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    PassageCardContent(
                                        passage = secondaryPassage,
                                        book = secondaryModule!!,
                                        sectionKey = sessionState?.verseSectionKey(
                                            pane = "secondary",
                                            moduleInitials = secondaryModule!!.initials,
                                            bookOsis = currentModuleBook?.bibleBook?.osis ?: secondaryPassage.bookName,
                                            chapter = currentChapter,
                                            verseFilter = selectedVerseFilter
                                        ) ?: "verse|secondary|${secondaryModule!!.initials}|${secondaryPassage.bookName}|$currentChapter|$selectedVerseFilter",
                                        sessionState = sessionState,
                                        fontSizeSp = fontSizeSp,
                                        selectedVerseFilter = selectedVerseFilter,
                                        isReading = isSecondaryReading,
                                        isSynthesizing = isSecondarySynthesizing,
                                        isQueued = isSecondaryQueued,
                                        ttsService = ttsService,
                                        audioPlayer = audioPlayer,
                                        onReadPassage = {
                                            ttsService.readPassage(secondaryPassage, secondaryModule!!, audioPlayer)
                                        },
                                        onQueuePassage = {
                                            ttsService.queuePassage(secondaryPassage, secondaryModule!!, audioPlayer)
                                        },
                                        onSavePassageAudio = {
                                            val safeName = "${secondaryPassage.bookName}_${secondaryPassage.chapterNumber}_${secondaryModule!!.initials}"
                                            TtsFileUtils.openSaveAudioFileDialog(defaultFilename = safeName) { destFile ->
                                                coroutineScope.launch {
                                                    val speechText = TtsTextCleaner.formatPassageForSpeech(
                                                        secondaryPassage.bookName,
                                                        secondaryPassage.chapterNumber,
                                                        secondaryPassage.fullText
                                                    )
                                                    ttsService.synthesizeToFile(speechText, secondaryTrackTitle, destFile)
                                                }
                                            }
                                        },
                                        onReadVerse = { vNum, vText ->
                                            ttsService.readVerse(
                                                reference = "${secondaryPassage.bookName} ${secondaryPassage.chapterNumber}:$vNum",
                                                verseText = vText,
                                                bookInitials = secondaryModule!!.initials,
                                                player = audioPlayer
                                            )
                                        },
                                        onCopy = {
                                            copyTextToClipboard(secondaryPassage.fullText)
                                            copyNotification = "Copied ${secondaryPassage.bookName} ${secondaryPassage.chapterNumber}  to clipboard!"
                                        }
                                    )
                                }

                                // Right Column: Notes (if enabled)
                                if (isNotesMode && selectedModule != null) {
                                    NotesPanel(
                                        repository = notesRepository,
                                        moduleKey = selectedModule!!.initials,
                                        moduleDisplayName = selectedModule!!.name,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        } else {
                            // Single Module view (+ optional notes column)
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                val primaryTrackTitle = "${primaryPassage.bookName} ${primaryPassage.chapterNumber} (${selectedModule!!.initials})"
                                val isPrimaryReading = audioPlayer.isTrackActive(primaryTrackTitle)
                                val isPrimarySynthesizing = ttsService.isSynthesizing && ttsService.currentReadingTitle == primaryTrackTitle
                                val isPrimaryQueued = audioPlayer.isTrackQueued(primaryTrackTitle)

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    PassageCardContent(
                                        passage = primaryPassage,
                                        book = selectedModule!!,
                                        sectionKey = sessionState?.verseSectionKey(
                                            pane = "primary",
                                            moduleInitials = selectedModule!!.initials,
                                            bookOsis = currentModuleBook?.bibleBook?.osis ?: primaryPassage.bookName,
                                            chapter = currentChapter,
                                            verseFilter = selectedVerseFilter
                                        ) ?: "verse|primary|${selectedModule!!.initials}|${primaryPassage.bookName}|$currentChapter|$selectedVerseFilter",
                                        sessionState = sessionState,
                                        fontSizeSp = fontSizeSp,
                                        selectedVerseFilter = selectedVerseFilter,
                                        isReading = isPrimaryReading,
                                        isSynthesizing = isPrimarySynthesizing,
                                        isQueued = isPrimaryQueued,
                                        ttsService = ttsService,
                                        audioPlayer = audioPlayer,
                                        onReadPassage = {
                                            ttsService.readPassage(primaryPassage, selectedModule!!, audioPlayer)
                                        },
                                        onQueuePassage = {
                                            ttsService.queuePassage(primaryPassage, selectedModule!!, audioPlayer)
                                        },
                                        onSavePassageAudio = {
                                            val safeName = "${primaryPassage.bookName}_${primaryPassage.chapterNumber}_${selectedModule!!.initials}"
                                            TtsFileUtils.openSaveAudioFileDialog(defaultFilename = safeName) { destFile ->
                                                coroutineScope.launch {
                                                    val speechText = TtsTextCleaner.formatPassageForSpeech(
                                                        primaryPassage.bookName,
                                                        primaryPassage.chapterNumber,
                                                        primaryPassage.fullText
                                                    )
                                                    ttsService.synthesizeToFile(speechText, primaryTrackTitle, destFile)
                                                }
                                            }
                                        },
                                        onReadVerse = { vNum, vText ->
                                            ttsService.readVerse(
                                                reference = "${primaryPassage.bookName} ${primaryPassage.chapterNumber}:$vNum",
                                                verseText = vText,
                                                bookInitials = selectedModule!!.initials,
                                                player = audioPlayer
                                            )
                                        },
                                        onCopy = {
                                            copyTextToClipboard(primaryPassage.fullText)
                                            copyNotification = "Copied ${primaryPassage.bookName} ${primaryPassage.chapterNumber} to clipboard!"
                                        }
                                    )
                                }

                                // Notes column (if enabled)
                                if (isNotesMode && selectedModule != null) {
                                    NotesPanel(
                                        repository = notesRepository,
                                        moduleKey = selectedModule!!.initials,
                                        moduleDisplayName = selectedModule!!.name,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // General Book / Dictionary Reading Surface
                    val activeKey = genBookKeys.getOrNull(selectedKeyIndex)
                    val hasActiveKeyContent = selectedModule != null && activeKey != null
                    val keyContentPairState by produceState<Pair<String, List<String>>?>(
                        initialValue = manager.getCachedKeyContent(selectedModule, activeKey)?.let { cached ->
                            cached to TtsTextCleaner.splitIntoParagraphs(cached)
                        },
                        key1 = selectedModule,
                        key2 = activeKey,
                        key3 = isLoadingGenBookKeys
                    ) {
                        if (isLoadingGenBookKeys) {
                            value = null
                        } else if (selectedModule != null && activeKey != null) {
                            val mod = selectedModule!!
                            val loaded = withContext(Dispatchers.IO) {
                                manager.readKeyContent(mod, activeKey)
                            }
                            val split = withContext(Dispatchers.Default) {
                                TtsTextCleaner.splitIntoParagraphs(loaded)
                            }
                            value = loaded to split
                        } else {
                            value = "No content available for this selection." to emptyList()
                        }
                    }

                    val loadedPair = keyContentPairState
                    if (isLoadingGenBookKeys || loadedPair == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val keyContent = loadedPair.first
                        val paragraphs = loadedPair.second

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp)
                            ) {
                                // Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = activeKey?.name ?: "Section Content",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "${selectedModule?.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val sectionTitle = if (!selectedModule?.name.isNullOrBlank()) "${activeKey?.name ?: "Section"} • ${selectedModule?.name}" else activeKey?.name ?: "Section"
                                        val isSectionReading = audioPlayer.isTrackActive(sectionTitle)
                                        val isSectionSynthesizing = ttsService.isSynthesizing && ttsService.currentReadingTitle == sectionTitle
                                        val isSectionQueued = audioPlayer.isTrackQueued(sectionTitle)

                                        FilledTonalButton(
                                            onClick = {
                                                ttsService.readSection(
                                                    title = activeKey?.name ?: "Section",
                                                    subtitle = selectedModule?.name ?: "",
                                                    content = keyContent,
                                                    player = audioPlayer
                                                )
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            if (isSectionSynthesizing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(13.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Loading...", fontSize = 12.sp)
                                            } else if (isSectionReading) {
                                                Text("⏹ Stop Audio", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            } else {
                                                Text("🔊 Read Section", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }

                                        // Queue Section button (Requirement 4)
                                        OutlinedButton(
                                            onClick = {
                                                ttsService.queueSection(
                                                    title = activeKey?.name ?: "Section",
                                                    subtitle = selectedModule?.name ?: "",
                                                    content = keyContent,
                                                    player = audioPlayer
                                                )
                                            },
                                            enabled = !isSectionQueued,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(if (isSectionQueued) "✓ Queued" else "➕ Queue Section", fontSize = 12.sp)
                                        }

                                        // Save Audio to File button (Requirement 3)
                                        OutlinedButton(
                                            onClick = {
                                                val safeName = sectionTitle.replace(SAFE_EXPORT_FILENAME_REGEX, "_")
                                                TtsFileUtils.openSaveAudioFileDialog(defaultFilename = safeName) { destFile ->
                                                    coroutineScope.launch {
                                                        val speechText = TtsTextCleaner.formatSectionForSpeech(
                                                            title = activeKey?.name ?: "Section",
                                                            subtitle = selectedModule?.name ?: "",
                                                            text = keyContent
                                                        )
                                                        ttsService.synthesizeToFile(speechText, sectionTitle, destFile)
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("💾 Save Audio", fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                copyTextToClipboard(keyContent)
                                                copyNotification = "Copied ${activeKey?.name} to clipboard!"
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("📋 Copy", fontSize = 12.sp)
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                // Paragraph-by-paragraph text content with per-paragraph TTS options
                                val genBookSectionKey = sessionState?.genBookSectionKey(
                                    moduleInitials = selectedModule?.initials ?: "",
                                    keyIndex = selectedKeyIndex,
                                    keyName = activeKey?.name
                                ) ?: "genbook|${selectedModule?.initials}|$selectedKeyIndex|${activeKey?.name.orEmpty()}"
                                if (!hasActiveKeyContent || paragraphs.isEmpty()) {
                                    val textScrollState = rememberTrackedScrollState(
                                        sectionKey = "$genBookSectionKey|text",
                                        initialValue = { sessionState?.getSectionScrollPosition("$genBookSectionKey|text")?.verticalScrollValue ?: 0 },
                                        onSaveValue = { v -> sessionState?.saveSectionVerticalScroll("$genBookSectionKey|text", v) }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(textScrollState)
                                    ) {
                                        Text(
                                            text = keyContent,
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp + 8).sp,
                                            modifier = Modifier.padding(bottom = 32.dp)
                                        )
                                    }
                                } else {
                                    val sectionTitle = activeKey?.name ?: "Section"
                                    val moduleSubtitle = selectedModule?.name ?: ""
                                    val paragraphListState = rememberTrackedLazyListState(
                                        sectionKey = "$genBookSectionKey|paras",
                                        initialPosition = { sessionState?.getSectionScrollPosition("$genBookSectionKey|paras") ?: SessionScrollPosition() },
                                        onSavePosition = { idx, off -> sessionState?.saveSectionLazyScroll("$genBookSectionKey|paras", idx, off) }
                                    )

                                    LazyColumn(
                                        state = paragraphListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 32.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        itemsIndexed(paragraphs) { index, paragraph ->
                                            val trackTitle = ttsService.buildParagraphTrackTitle(
                                                sectionTitle = sectionTitle,
                                                subtitle = moduleSubtitle,
                                                paragraphIndex = index
                                            )
                                            ParagraphTtsRow(
                                                paragraphText = paragraph,
                                                fontSizeSp = fontSizeSp,
                                                trackTitle = trackTitle,
                                                ttsService = ttsService,
                                                audioPlayer = audioPlayer,
                                                onReadParagraph = {
                                                    ttsService.readParagraph(
                                                        sectionTitle = sectionTitle,
                                                        subtitle = moduleSubtitle,
                                                        paragraphIndex = index,
                                                        paragraphText = paragraph,
                                                        player = audioPlayer
                                                    )
                                                },
                                                onQueueParagraph = {
                                                    ttsService.queueParagraph(
                                                        sectionTitle = sectionTitle,
                                                        subtitle = moduleSubtitle,
                                                        paragraphIndex = index,
                                                        paragraphText = paragraph,
                                                        player = audioPlayer
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Notes column for general book / dictionary view
                        if (isNotesMode && selectedModule != null) {
                            NotesPanel(
                                repository = notesRepository,
                                moduleKey = selectedModule!!.initials,
                                moduleDisplayName = selectedModule!!.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    // Module Info Dialog
    if (showModuleInfoDialog && selectedModule != null) {
        ModuleInfoDialog(
            book = selectedModule!!,
            manager = manager,
            onDismiss = { showModuleInfoDialog = false }
        )
    }
}

@Composable
private fun ParagraphTtsRow(
    paragraphText: String,
    fontSizeSp: Int,
    trackTitle: String,
    ttsService: TtsService,
    audioPlayer: AudioBiblePlayer,
    onReadParagraph: () -> Unit,
    onQueueParagraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSynthesizing = ttsService.isSynthesizing && ttsService.currentReadingTitle == trackTitle
    val isActive = audioPlayer.isTrackActive(trackTitle)
    val isActivelyPlaying = audioPlayer.isActivelyPlaying(trackTitle)
    val isQueued = audioPlayer.isTrackQueued(trackTitle)
    val isSpeakable = remember(paragraphText) {
        TtsTextCleaner.cleanForSpeech(paragraphText).any { it.isLetterOrDigit() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (isActive || isSynthesizing) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        text = paragraphText,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp + 8).sp
                    )
                }
            }

            if (isSpeakable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onReadParagraph,
                        modifier = Modifier.size(28.dp)
                    ) {
                        when {
                            isSynthesizing -> CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            isActivelyPlaying -> Text("⏸", fontSize = 12.sp)
                            isActive -> Text("▶", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            else -> Text("🔊", fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = onQueueParagraph,
                        enabled = !isQueued,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = if (isQueued) "✓" else "➕",
                            fontSize = 11.sp,
                            color = if (isQueued) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current.copy(alpha = 0.75f)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PassageCardContent(
    passage: ChapterPassage,
    book: Book,
    sectionKey: String,
    sessionState: SessionReadingState?,
    fontSizeSp: Int,
    selectedVerseFilter: Int,
    isReading: Boolean = false,
    isSynthesizing: Boolean = false,
    isQueued: Boolean = false,
    ttsService: TtsService? = null,
    audioPlayer: AudioBiblePlayer? = null,
    onReadPassage: () -> Unit = {},
    onQueuePassage: (() -> Unit)? = null,
    onSavePassageAudio: (() -> Unit)? = null,
    onReadVerse: (verseNumber: Int, text: String) -> Unit = { _, _ -> },
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${passage.bookName} ${passage.chapterNumber}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${book.name} (${book.initials}) • ${book.bookCategory.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onReadPassage,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (isSynthesizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Loading...", fontSize = 12.sp)
                    } else if (isReading) {
                        Text("⏹ Stop Audio", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("🔊 Read Chapter", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (onQueuePassage != null) {
                    OutlinedButton(
                        onClick = onQueuePassage,
                        enabled = !isQueued,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(if (isQueued) "✓ Queued" else "➕ Queue", fontSize = 12.sp)
                    }
                }

                if (onSavePassageAudio != null) {
                    OutlinedButton(
                        onClick = onSavePassageAudio,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("💾 Save", fontSize = 12.sp)
                    }
                }

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("📋", fontSize = 16.sp)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        if (passage.error != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Notice",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = passage.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (passage.verses.isEmpty()) {
            val fullTextParagraphs = remember(passage.fullText, book.bookCategory) {
                if (book.bookCategory != BookCategory.BIBLE) {
                    TtsTextCleaner.splitIntoParagraphs(passage.fullText)
                } else {
                    emptyList()
                }
            }

            if (book.bookCategory != BookCategory.BIBLE && fullTextParagraphs.isNotEmpty() && ttsService != null && audioPlayer != null) {
                val sectionTitle = "${passage.bookName} ${passage.chapterNumber}"
                val paragraphListState = rememberTrackedLazyListState(
                    sectionKey = "$sectionKey|paras",
                    initialPosition = { sessionState?.getSectionScrollPosition("$sectionKey|paras") ?: SessionScrollPosition() },
                    onSavePosition = { idx, off -> sessionState?.saveSectionLazyScroll("$sectionKey|paras", idx, off) }
                )
                LazyColumn(
                    state = paragraphListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(fullTextParagraphs) { index, paragraph ->
                        val trackTitle = ttsService.buildParagraphTrackTitle(
                            sectionTitle = sectionTitle,
                            subtitle = book.name,
                            paragraphIndex = index
                        )
                        ParagraphTtsRow(
                            paragraphText = paragraph,
                            fontSizeSp = fontSizeSp,
                            trackTitle = trackTitle,
                            ttsService = ttsService,
                            audioPlayer = audioPlayer,
                            onReadParagraph = {
                                ttsService.readParagraph(
                                    sectionTitle = sectionTitle,
                                    subtitle = book.name,
                                    paragraphIndex = index,
                                    paragraphText = paragraph,
                                    player = audioPlayer
                                )
                            },
                            onQueueParagraph = {
                                ttsService.queueParagraph(
                                    sectionTitle = sectionTitle,
                                    subtitle = book.name,
                                    paragraphIndex = index,
                                    paragraphText = paragraph,
                                    player = audioPlayer
                                )
                            }
                        )
                    }
                }
            } else {
                val fallbackScrollState = rememberTrackedScrollState(
                    sectionKey = "$sectionKey|text",
                    initialValue = { sessionState?.getSectionScrollPosition("$sectionKey|text")?.verticalScrollValue ?: 0 },
                    onSaveValue = { v -> sessionState?.saveSectionVerticalScroll("$sectionKey|text", v) }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(fallbackScrollState)
                ) {
                    Text(
                        text = passage.fullText.ifBlank { "No text available for this passage." },
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp + 8).sp,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        } else {
            val listState = rememberTrackedLazyListState(
                sectionKey = "$sectionKey|verses",
                initialPosition = { sessionState?.getSectionScrollPosition("$sectionKey|verses") ?: SessionScrollPosition() },
                onSavePosition = { idx, off -> sessionState?.saveSectionLazyScroll("$sectionKey|verses", idx, off) }
            )
            val displayedVerses = remember(passage.verses, selectedVerseFilter) {
                if (selectedVerseFilter > 0) {
                    passage.verses.filter { it.verseNumber == selectedVerseFilter }
                } else {
                    passage.verses
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedVerses) { verse ->
                    val verseParagraphs = remember(verse.text, book.bookCategory) {
                        if (book.bookCategory != BookCategory.BIBLE) {
                            TtsTextCleaner.splitIntoParagraphs(verse.text)
                        } else {
                            emptyList()
                        }
                    }

                    if (book.bookCategory != BookCategory.BIBLE && verseParagraphs.size > 1 && ttsService != null && audioPlayer != null) {
                        val entryTitle = if (verse.verseNumber > 0) {
                            "${passage.bookName} ${passage.chapterNumber}:${verse.verseNumber}"
                        } else {
                            "${passage.bookName} ${passage.chapterNumber}"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (verse.verseNumber > 0) {
                                Text(
                                    text = "${verse.verseNumber}",
                                    fontSize = (fontSizeSp - 3).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .width(36.dp)
                                        .padding(top = 6.dp)
                                )
                            } else {
                                Spacer(Modifier.width(36.dp))
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                verseParagraphs.forEachIndexed { idx, paragraph ->
                                    val trackTitle = ttsService.buildParagraphTrackTitle(
                                        sectionTitle = entryTitle,
                                        subtitle = book.name,
                                        paragraphIndex = idx
                                    )
                                    ParagraphTtsRow(
                                        paragraphText = paragraph,
                                        fontSizeSp = fontSizeSp,
                                        trackTitle = trackTitle,
                                        ttsService = ttsService,
                                        audioPlayer = audioPlayer,
                                        onReadParagraph = {
                                            ttsService.readParagraph(
                                                sectionTitle = entryTitle,
                                                subtitle = book.name,
                                                paragraphIndex = idx,
                                                paragraphText = paragraph,
                                                player = audioPlayer
                                            )
                                        },
                                        onQueueParagraph = {
                                            ttsService.queueParagraph(
                                                sectionTitle = entryTitle,
                                                subtitle = book.name,
                                                paragraphIndex = idx,
                                                paragraphText = paragraph,
                                                player = audioPlayer
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (verse.verseNumber > 0) {
                                Text(
                                    text = "${verse.verseNumber}",
                                    fontSize = (fontSizeSp - 3).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .width(36.dp)
                                        .padding(top = 2.dp)
                                )
                            } else {
                                Spacer(Modifier.width(36.dp))
                            }

                            Text(
                                text = verse.text,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 8).sp,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { onReadVerse(verse.verseNumber, verse.text) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("🔊", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleInfoDialog(
    book: Book,
    manager: SwordManager,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 600.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Module Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                HorizontalDivider()

                InfoItem(label = "Name", value = book.name)
                InfoItem(label = "Initials / ID", value = book.initials)
                InfoItem(label = "Category", value = book.bookCategory.name)
                InfoItem(label = "Language", value = book.language?.code ?: "Unknown")
                InfoItem(label = "Driver", value = book.driverName ?: "Default")
                InfoItem(label = "Location", value = manager.getBookLocationLabel(book))

                val aboutText = try {
                    (book.bookMetaData as? SwordBookMetaData)?.getProperty("About")
                        ?: book.bookMetaData.getProperty("Description")
                        ?: ""
                } catch (_: Exception) {
                    ""
                }

                if (aboutText.isNotBlank()) {
                    Text(
                        text = "About:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = aboutText.replace("\\par", "\n").replace("\\pard", "").trim(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
    }
}

private fun copyTextToClipboard(text: String) {
    try {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    } catch (_: Exception) {}
}

/**
 * Parses a quick-reference string (e.g. "Rom 8:28", "John 3", "Ps 23:1") against
 * the given list of [ModuleBook]s sourced from the active module's versification.
 *
 * Returns a Triple of (bookIndex, chapter, verse) on success, or null if the
 * reference cannot be matched to a book in the module.
 */
internal fun parseQuickReference(input: String, moduleBooks: List<ModuleBook>): Triple<Int, Int, Int>? {
    val clean = input.trim()
    val match = QUICK_REF_REGEX.find(clean) ?: return null
    val bookStr = match.groupValues[1].trim()
    val chapterStr = match.groupValues.getOrNull(2)?.trim()
    val verseStr = match.groupValues.getOrNull(3)?.trim()

    // Find the best matching book within this module's TOC
    val bookIndex = moduleBooks.indexOfFirst { mb ->
        mb.displayName.replace(" ", "").equals(bookStr.replace(" ", ""), ignoreCase = true) ||
                mb.displayName.startsWith(bookStr, ignoreCase = true) ||
                mb.bibleBook.osis.equals(bookStr, ignoreCase = true) ||
                mb.bibleBook.osis.startsWith(bookStr, ignoreCase = true)
    }
    if (bookIndex < 0) return null

    val mb = moduleBooks[bookIndex]
    val requestedChapter = chapterStr?.toIntOrNull()
    val chapter = when {
        requestedChapter == null -> mb.chapters.firstOrNull() ?: 1
        requestedChapter in mb.chapters -> requestedChapter
        else -> mb.chapters.minByOrNull { kotlin.math.abs(it - requestedChapter) }
            ?: requestedChapter.coerceIn(1, mb.chapterCount)
    }
    val verse = verseStr?.toIntOrNull() ?: 0

    return Triple(bookIndex, chapter, verse)
}

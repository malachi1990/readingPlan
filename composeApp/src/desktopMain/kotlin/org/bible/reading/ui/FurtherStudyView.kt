package org.bible.reading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.bible.reading.jsword.ChapterPassage
import org.bible.reading.jsword.ModuleBook
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.passage.Key
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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
    onOpenDownload: () -> Unit
) {
    // Category filter for module selector
    var categoryTab by remember { mutableStateOf(ModuleCategoryTab.ALL) }

    // Selected primary module
    var selectedModule by remember(manager.installedBooks) {
        mutableStateOf<Book?>(
            manager.selectedBible ?: manager.installedBooks.firstOrNull()
        )
    }

    // Keep selectedModule valid if installedBooks changes
    LaunchedEffect(manager.installedBooks) {
        if (selectedModule == null || !manager.installedBooks.contains(selectedModule)) {
            selectedModule = manager.selectedBible ?: manager.installedBooks.firstOrNull()
        }
    }

    // Module info dialog state
    var showModuleInfoDialog by remember { mutableStateOf(false) }

    // Import file dialog state
    var importingFile by remember { mutableStateOf<File?>(null) }

    // Parallel comparison mode state
    var isParallelMode by remember { mutableStateOf(false) }
    var secondaryModule by remember(manager.installedBooks) {
        mutableStateOf<Book?>(
            manager.installedBooks.firstOrNull { it.bookCategory == BookCategory.COMMENTARY }
                ?: manager.installedBooks.firstOrNull { it != selectedModule }
        )
    }

    // Check if the current module is verse-based
    val isVerseBasedModule = selectedModule?.bookCategory == BookCategory.BIBLE ||
            selectedModule?.bookCategory == BookCategory.COMMENTARY ||
            selectedModule?.bookCategory == BookCategory.DAILY_DEVOTIONS

    // Module TOC: list of books for verse-based modules, sourced from the module's versification.
    // Recomputed whenever the selected module changes.
    var moduleBooks by remember(selectedModule) {
        mutableStateOf<List<ModuleBook>>(emptyList())
    }
    LaunchedEffect(selectedModule) {
        val mod = selectedModule
        moduleBooks = if (mod != null && isVerseBasedModule) {
            manager.getModuleBooks(mod).ifEmpty { emptyList() }
        } else {
            emptyList()
        }
    }

    // Verse-based navigation state: index into moduleBooks + chapter number.
    // Reset to the first book/chapter whenever the module changes.
    var currentBookIndex by remember(selectedModule) { mutableStateOf(0) }
    var currentChapter by remember(selectedModule) { mutableStateOf(1) }
    var selectedVerseFilter by remember { mutableStateOf(0) } // 0 means all verses

    val currentModuleBook: ModuleBook? = moduleBooks.getOrNull(currentBookIndex)

    // Clamp currentChapter to valid range whenever the book changes
    LaunchedEffect(currentModuleBook) {
        val maxCh = currentModuleBook?.chapterCount ?: 1
        if (currentChapter > maxCh) currentChapter = maxCh
        if (currentChapter < 1) currentChapter = 1
    }

    // Quick reference jump
    var quickRefInput by remember { mutableStateOf("") }
    var quickRefError by remember { mutableStateOf<String?>(null) }

    // General Book navigation state (for tree-based books)
    var genBookKeys by remember(selectedModule) {
        mutableStateOf<List<Key>>(emptyList())
    }
    var selectedKeyIndex by remember(selectedModule) { mutableStateOf(0) }
    var genBookSearchQuery by remember { mutableStateOf("") }

    // Fetch keys when a general book or dictionary is selected
    LaunchedEffect(selectedModule) {
        val mod = selectedModule
        if (mod != null && (mod.bookCategory == BookCategory.GENERAL_BOOK || mod.bookCategory == BookCategory.DICTIONARY)) {
            genBookKeys = manager.getBookKeys(mod)
            selectedKeyIndex = 0
        } else {
            genBookKeys = emptyList()
        }
    }

    // Content font size zoom
    var fontSizeSp by remember { mutableStateOf(16) }

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
        if (currentChapter > 1) {
            currentChapter -= 1
        } else if (currentBookIndex > 0) {
            currentBookIndex -= 1
            currentChapter = moduleBooks.getOrNull(currentBookIndex)?.chapterCount ?: 1
        }
        selectedVerseFilter = 0
    }

    fun stepToNextChapter() {
        val maxCh = currentModuleBook?.chapterCount ?: 1
        if (currentChapter < maxCh) {
            currentChapter += 1
        } else if (currentBookIndex < moduleBooks.lastIndex) {
            currentBookIndex += 1
            currentChapter = 1
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

                            // Import external book (EPUB) button
                            OutlinedButton(
                                onClick = {
                                    openBookFileChooser { file ->
                                        importingFile = file
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("📥 Import Book", fontSize = 12.sp)
                            }

                            // Download more modules shortcut
                            Button(
                                onClick = onOpenDownload,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("📚 Downloader", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                        val filteredBooks = remember(manager.installedBooks, categoryTab) {
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
                                                        Text(
                                                            text = book.initials,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
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
                                                        Text("(${book.initials})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                            // Previous Chapter Button
                            OutlinedButton(
                                onClick = { stepToPrevChapter() },
                                enabled = currentBookIndex > 0 || currentChapter > 1,
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
                                                            text = "${mb.chapterCount} ch",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    currentBookIndex = idx
                                                    currentChapter = 1
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
                                        enabled = currentChapter > 1 || currentBookIndex > 0,
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
                                            (1..currentModuleBook.chapterCount).forEach { ch ->
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
                                        enabled = currentChapter < currentModuleBook.chapterCount || currentBookIndex < moduleBooks.lastIndex,
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
                                enabled = currentBookIndex < moduleBooks.lastIndex || currentChapter < (currentModuleBook?.chapterCount ?: 1),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Next ▶", fontSize = 12.sp)
                            }
                        }

                        // Right: Quick Reference Search Box (e.g. Rom 8:28, John 3:16)
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
                                placeholder = { Text("Jump to ref: e.g. Rom 8:28, John 3", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .width(260.dp)
                                    .height(48.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )

                            Button(
                                onClick = {
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
                                },
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
                                    // Search in dropdown
                                    OutlinedTextField(
                                        value = genBookSearchQuery,
                                        onValueChange = { genBookSearchQuery = it },
                                        placeholder = { Text("Filter sections...", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .width(280.dp)
                                            .padding(8.dp)
                                    )

                                    val filteredKeys = remember(genBookKeys, genBookSearchQuery) {
                                        if (genBookSearchQuery.isBlank()) {
                                            genBookKeys
                                        } else {
                                            genBookKeys.filter { it.name.contains(genBookSearchQuery, ignoreCase = true) }
                                        }
                                    }

                                    filteredKeys.take(80).forEach { k ->
                                        val idx = genBookKeys.indexOf(k)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = k.name,
                                                    fontWeight = if (idx == selectedKeyIndex) FontWeight.Bold else FontWeight.Normal
                                                )
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

            // Main Reader Content Area
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
                                Text("Open Downloader")
                            }
                        }
                    }
                }
            } else if (isVerseBasedModule) {
                // Verse-based Module Reading Surface (Supports Single or Parallel comparison)

                // Loading state while moduleBooks is still being fetched
                if (moduleBooks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val primaryPassage = remember(selectedModule, currentModuleBook, currentChapter) {
                        if (currentModuleBook != null) {
                            manager.readChapter(currentModuleBook, currentChapter, selectedModule)
                        } else {
                            ChapterPassage("", 0, emptyList(), "", error = "No book selected.")
                        }
                    }

                    val secondaryPassage = if (isParallelMode && secondaryModule != null && currentModuleBook != null) {
                        remember(secondaryModule, currentModuleBook, currentChapter) {
                            manager.readChapter(currentModuleBook, currentChapter, secondaryModule)
                        }
                    } else null

                    if (isParallelMode && secondaryPassage != null) {
                        // Split / Parallel View: 2 equal columns
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Left Column: Primary Module
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                PassageCardContent(
                                    passage = primaryPassage,
                                    book = selectedModule!!,
                                    fontSizeSp = fontSizeSp,
                                    selectedVerseFilter = selectedVerseFilter,
                                    onCopy = {
                                        copyTextToClipboard(primaryPassage.fullText)
                                        copyNotification = "Copied ${primaryPassage.bookName} ${primaryPassage.chapterNumber} (${selectedModule!!.initials}) to clipboard!"
                                    }
                                )
                            }

                            // Right Column: Secondary Module
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                PassageCardContent(
                                    passage = secondaryPassage,
                                    book = secondaryModule!!,
                                    fontSizeSp = fontSizeSp,
                                    selectedVerseFilter = selectedVerseFilter,
                                    onCopy = {
                                        copyTextToClipboard(secondaryPassage.fullText)
                                        copyNotification = "Copied ${secondaryPassage.bookName} ${secondaryPassage.chapterNumber} (${secondaryModule!!.initials}) to clipboard!"
                                    }
                                )
                            }
                        }
                    } else {
                        // Single Module Full View
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            PassageCardContent(
                                passage = primaryPassage,
                                book = selectedModule!!,
                                fontSizeSp = fontSizeSp,
                                selectedVerseFilter = selectedVerseFilter,
                                onCopy = {
                                    copyTextToClipboard(primaryPassage.fullText)
                                    copyNotification = "Copied ${primaryPassage.bookName} ${primaryPassage.chapterNumber} (${selectedModule!!.initials}) to clipboard!"
                                }
                            )
                        }
                    }
                }
            } else {
                // General Book / Dictionary Reading Surface
                val activeKey = genBookKeys.getOrNull(selectedKeyIndex)
                val keyContent = remember(selectedModule, activeKey) {
                    if (selectedModule != null && activeKey != null) {
                        manager.readKeyContent(selectedModule!!, activeKey)
                    } else {
                        "No content available for this selection."
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
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
                                    text = "${selectedModule?.name} (${selectedModule?.initials})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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

                        // Text content
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = keyContent,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 8).sp,
                                modifier = Modifier.padding(bottom = 32.dp)
                            )
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

        // Import Book Dialog
        if (importingFile != null) {
            ImportBookDialog(
                selectedFile = importingFile!!,
                manager = manager,
                onDismiss = { importingFile = null },
                onImportSuccess = { book ->
                    selectedModule = book
                    categoryTab = ModuleCategoryTab.GENERAL_BOOKS
                }
            )
        }
    }
}

@Composable
private fun PassageCardContent(
    passage: ChapterPassage,
    book: Book,
    fontSizeSp: Int,
    selectedVerseFilter: Int,
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

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp)
            ) {
                Text("📋", fontSize = 16.sp)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = passage.fullText.ifBlank { "No text available for this passage." },
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp + 8).sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        } else {
            val listState = rememberLazyListState()
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
private fun parseQuickReference(input: String, moduleBooks: List<ModuleBook>): Triple<Int, Int, Int>? {
    val clean = input.trim()
    val match = Regex("""^([1-3]?\s*[A-Za-z]+(?:\s+[A-Za-z]+)?)\s*(\d+)?(?::(\d+))?""").find(clean) ?: return null
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
    val chapter = chapterStr?.toIntOrNull()?.coerceIn(1, mb.chapterCount) ?: 1
    val verse = verseStr?.toIntOrNull() ?: 0

    return Triple(bookIndex, chapter, verse)
}

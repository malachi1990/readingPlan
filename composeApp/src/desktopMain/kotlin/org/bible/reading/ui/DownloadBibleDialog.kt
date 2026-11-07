package org.bible.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import java.io.File

enum class DownloadCategoryFilter(val displayName: String, val category: BookCategory?) {
    ALL("All", null),
    BIBLES("Bibles", BookCategory.BIBLE),
    COMMENTARIES("Commentaries", BookCategory.COMMENTARY),
    GENERAL_BOOKS("General Books", BookCategory.GENERAL_BOOK),
    DEVOTIONALS("Devotionals", BookCategory.DAILY_DEVOTIONS)
}

enum class LocationFilter(val displayName: String) {
    ALL("All Locations"),
    APP_DIR("App Data (~/.bibleReadingPlan)"),
    SYSTEM("System Modules (~/.sword)")
}

@Composable
fun DownloadBibleDialog(
    manager: SwordManager,
    onDismissRequest: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0: Browse & Download, 1: Installed Books
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf(DownloadCategoryFilter.BIBLES) }
    var installerMenuExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Bible & Book Downloader",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "CrossWire JSword",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Text(
                            text = "Download Bibles, commentaries, and books. Files are saved directly to ${manager.appDir.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismissRequest) {
                        Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Tab Switcher
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Browse & Download", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Installed Books (${manager.installedBooks.size})", fontWeight = FontWeight.SemiBold) }
                    )
                }

                // Status message banner
                manager.lastOperationMessage?.let { msg ->
                    val bgColor = if (manager.isOperationSuccess) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    }
                    val textColor = if (manager.isOperationSuccess) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { manager.lastOperationMessage = null },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (selectedTab == 0) {
                    // TAB 0: Browse & Download
                    BrowseAndDownloadContent(
                        manager = manager,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        categoryFilter = categoryFilter,
                        onCategoryFilterChange = { categoryFilter = it },
                        installerMenuExpanded = installerMenuExpanded,
                        onInstallerMenuExpandedChange = { installerMenuExpanded = it }
                    )
                } else {
                    // TAB 1: Installed Books
                    InstalledBooksContent(manager = manager)
                }
            }
        }
    }
}

@Composable
private fun BrowseAndDownloadContent(
    manager: SwordManager,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    categoryFilter: DownloadCategoryFilter,
    onCategoryFilterChange: (DownloadCategoryFilter) -> Unit,
    installerMenuExpanded: Boolean,
    onInstallerMenuExpandedChange: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Controls Row: Repository Selection + Refresh Button
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Repository Dropdown
        Box(modifier = Modifier.weight(1f)) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInstallerMenuExpandedChange(true) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Source Repository", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(manager.selectedInstallerName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("▼", fontSize = 12.sp)
                }
            }

            DropdownMenu(
                expanded = installerMenuExpanded,
                onDismissRequest = { onInstallerMenuExpandedChange(false) }
            ) {
                manager.availableInstallers.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (name == manager.selectedInstallerName) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            manager.selectedInstallerName = name
                            onInstallerMenuExpandedChange(false)
                            coroutineScope.launch {
                                manager.reloadBookListForInstaller(name)
                            }
                        }
                    )
                }
            }
        }

        // Fetch / Refresh Button
        Button(
            onClick = {
                coroutineScope.launch {
                    manager.reloadBookListForInstaller(manager.selectedInstallerName)
                }
            },
            enabled = !manager.isReloadingBookList,
            modifier = Modifier.height(52.dp)
        ) {
            if (manager.isReloadingBookList) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading...")
            } else {
                Text("Refresh Repository")
            }
        }
    }

    // Search & Category Filters
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search by title, abbreviation (e.g. KJV, ASV), or language...") },
            singleLine = true,
            modifier = Modifier.weight(1.5f)
        )

        Row(
            modifier = Modifier.weight(1.5f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DownloadCategoryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = categoryFilter == filter,
                    onClick = { onCategoryFilterChange(filter) },
                    label = { Text(filter.displayName, fontSize = 12.sp) }
                )
            }
        }
    }

    // Filter books
    val filteredBooks = remember(manager.availableBooksForInstaller.toList(), searchQuery, categoryFilter) {
        var list: List<Book> = manager.availableBooksForInstaller
        if (categoryFilter.category != null) {
            list = list.filter { it.bookCategory == categoryFilter.category }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter { b ->
                b.name.lowercase().contains(q) ||
                        b.initials.lowercase().contains(q) ||
                        (b.language?.name?.lowercase()?.contains(q) == true)
            }
        }
        list
    }

    // Books List Area
    if (manager.isReloadingBookList && manager.availableBooksForInstaller.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Connecting to ${manager.selectedInstallerName} and loading available books...")
            }
        }
    } else if (manager.availableBooksForInstaller.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "No books loaded from ${manager.selectedInstallerName} yet.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Click 'Refresh Repository' above to download the catalog of available Bibles and books.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        coroutineScope.launch {
                            manager.reloadBookListForInstaller(manager.selectedInstallerName)
                        }
                    }
                ) {
                    Text("Fetch Books Now")
                }
            }
        }
    } else {
        Text(
            text = "Showing ${filteredBooks.size} available books from ${manager.selectedInstallerName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredBooks, key = { it.initials }) { book ->
                val isInstalled = manager.isBookInstalled(book.initials)
                val isDownloading = manager.downloadingBookInitials.contains(book.initials)

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isInstalled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = book.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = book.initials,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Category: ${book.bookCategory.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                book.language?.let { lang ->
                                    Text(
                                        text = "• Language: ${lang.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Action Button
                        if (isDownloading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("Downloading...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (isInstalled) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("✓ Installed", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        manager.downloadAndInstallBook(book, manager.selectedInstallerName)
                                    }
                                }
                            ) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun InstalledBooksContent(manager: SwordManager) {
    val coroutineScope = rememberCoroutineScope()
    var locationFilter by remember { mutableStateOf(LocationFilter.ALL) }
    var importingFile by remember { mutableStateOf<File?>(null) }

    val appBooks = remember(manager.installedBooks.toList()) {
        manager.installedBooks.filter { manager.isAppDirectoryBook(it) }
    }
    val systemBooks = remember(manager.installedBooks.toList()) {
        manager.installedBooks.filter { !manager.isAppDirectoryBook(it) }
    }

    val displayedBooks = when (locationFilter) {
        LocationFilter.ALL -> manager.installedBooks
        LocationFilter.APP_DIR -> appBooks
        LocationFilter.SYSTEM -> systemBooks
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${manager.installedBooks.size} Books Installed (${appBooks.size} in App Directory, ${systemBooks.size} in System)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "App Directory: ${manager.appDir.absolutePath} • System: ~/.sword",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        openBookFileChooser { importingFile = it }
                    }
                ) {
                    Text("📥 Import Book")
                }
                Button(
                    onClick = { manager.refreshInstalledBooks() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Text("Refresh Installed")
                }
            }
        }

        // Location filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Filter Location:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            FilterChip(
                selected = locationFilter == LocationFilter.ALL,
                onClick = { locationFilter = LocationFilter.ALL },
                label = { Text("All (${manager.installedBooks.size})") }
            )
            FilterChip(
                selected = locationFilter == LocationFilter.APP_DIR,
                onClick = { locationFilter = LocationFilter.APP_DIR },
                label = { Text("📁 App Data (${appBooks.size})") }
            )
            FilterChip(
                selected = locationFilter == LocationFilter.SYSTEM,
                onClick = { locationFilter = LocationFilter.SYSTEM },
                label = { Text("💻 System (${systemBooks.size})") }
            )
        }

        if (displayedBooks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (locationFilter == LocationFilter.APP_DIR)
                        "No books currently in ~/.bibleReadingPlan. Switch to 'Browse & Download' to download books to the app directory."
                    else
                        "No books found matching this filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedBooks, key = { it.initials }) { book ->
                    val isSelectedActive = manager.selectedBible?.initials == book.initials
                    val isBible = book.bookCategory == BookCategory.BIBLE
                    val isAppDir = manager.isAppDirectoryBook(book)

                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (isSelectedActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(book.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = book.initials,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    // Location badge
                                    if (isAppDir) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "📁 App Data (.bibleReadingPlan)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = "💻 System Module (.sword)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (isSelectedActive) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "Active Reading Bible",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Category: ${book.bookCategory.name} • Location: ${manager.getBookLocationLabel(book)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isBible && !isSelectedActive) {
                                    OutlinedButton(
                                        onClick = { manager.selectedBible = book }
                                    ) {
                                        Text("Set as Active")
                                    }
                                }

                                if (isAppDir) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                manager.uninstallBook(book)
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Uninstall")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (importingFile != null) {
            ImportBookDialog(
                selectedFile = importingFile!!,
                manager = manager,
                onDismiss = { importingFile = null },
                onImportSuccess = {}
            )
        }
    }
}

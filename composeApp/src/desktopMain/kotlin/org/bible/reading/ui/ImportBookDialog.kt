package org.bible.reading.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.bible.reading.importer.BookImportRegistry
import org.bible.reading.importer.service.ImportItemStatus
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Opens a native OS file chooser dialog to select one or more book files (.epub, .pdf, etc.) for import.
 */
fun openBooksFileChooser(
    title: String = "Select Book(s) to Import (EPUB, PDF)",
    onFilesSelected: (List<File>) -> Unit
) {
    try {
        val supportedExts = BookImportRegistry.getSupportedExtensions()
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            supportedExts.contains(ext)
        }
        dialog.isVisible = true

        val selectedFiles = dialog.files?.filter { it.isFile && it.exists() }
        if (!selectedFiles.isNullOrEmpty()) {
            onFilesSelected(selectedFiles)
        } else {
            val file = dialog.file
            val dir = dialog.directory
            if (file != null && dir != null) {
                val selected = File(dir, file)
                if (selected.exists() && selected.isFile) {
                    onFilesSelected(listOf(selected))
                }
            }
        }
    } catch (e: Exception) {
        println("Error opening file dialog: ${e.message}")
    }
}

/**
 * Opens a native OS file chooser dialog to select a book file (.epub, .pdf, etc.) for import.
 */
fun openBookFileChooser(
    title: String = "Select Book to Import (EPUB, PDF)",
    onFileSelected: (File) -> Unit
) {
    openBooksFileChooser(title = title) { files ->
        files.firstOrNull()?.let(onFileSelected)
    }
}

/**
 * Non-blocking dialog displaying the background book import queue, live progress,
 * and controls to queue additional files, run in background, retry failures, or open imported books.
 */
@Composable
fun ImportQueueDialog(
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {}
) {
    val importService = manager.importService
    val items = importService.queueItems.toList()
    val isProcessing = importService.isProcessing

    val successfulBooks = items.mapNotNull { (it.status as? ImportItemStatus.Success)?.book }
    val failureCount = items.count { it.status is ImportItemStatus.Failed }
    val pendingCount = items.count { it.status is ImportItemStatus.Pending }
    val activeItem = items.firstOrNull { it.status is ImportItemStatus.InProgress }
    val completedCount = successfulBooks.size + failureCount

    var selectedBookToOpen by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(successfulBooks) {
        if (selectedBookToOpen == null || successfulBooks.none { it == selectedBookToOpen }) {
            selectedBookToOpen = successfulBooks.lastOrNull()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 500.dp, max = 640.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val headerText = when {
                            isProcessing -> "📥 Importing Books (${completedCount + 1} of ${items.size})"
                            items.isEmpty() -> "📥 Import Queue"
                            failureCount == 0 -> "✅ Import Complete"
                            successfulBooks.isEmpty() -> "❌ Import Failed"
                            else -> "⚠️ Import Finished with Errors"
                        }
                        val headerColor = when {
                            isProcessing || items.isEmpty() -> MaterialTheme.colorScheme.onSurface
                            failureCount == 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = headerColor
                        )
                        val subtitle = when {
                            isProcessing && activeItem != null ->
                                "Processing ${activeItem.file.name}" + if (pendingCount > 0) " • $pendingCount queued" else ""
                            items.isEmpty() ->
                                "Queue EPUB or PDF files to import into Sword modules in the background."
                            else ->
                                "${successfulBooks.size} of ${items.size} imported" +
                                    if (failureCount > 0) " • $failureCount failed" else ""
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            openBooksFileChooser { files ->
                                importService.enqueueFiles(files)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("+ Queue Books", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Active progress bar when processing
                if (isProcessing && items.isNotEmpty()) {
                    val progress = (completedCount.toFloat() / items.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    val activeStep = (activeItem?.status as? ImportItemStatus.InProgress)?.step
                    if (activeStep != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                text = activeStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Queue Item List
                if (items.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("No books in import queue", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "Click \"+ Queue Books\" to select one or more EPUB/PDF files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (item.status) {
                                    is ImportItemStatus.Success -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    is ImportItemStatus.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                    is ImportItemStatus.InProgress -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                    ImportItemStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (item.status is ImportItemStatus.Success) {
                                            Modifier.clickable { selectedBookToOpen = item.status.book }
                                        } else Modifier
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    when (val st = item.status) {
                                        is ImportItemStatus.Pending -> Text("⏳", fontSize = 16.sp)
                                        is ImportItemStatus.InProgress -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        is ImportItemStatus.Success -> {
                                            if (successfulBooks.size > 1) {
                                                RadioButton(
                                                    selected = selectedBookToOpen == st.book,
                                                    onClick = { selectedBookToOpen = st.book },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                Text("✅", fontSize = 16.sp)
                                            }
                                        }
                                        is ImportItemStatus.Failed -> Text("❌", fontSize = 16.sp)
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when (val st = item.status) {
                                                is ImportItemStatus.Success -> st.book.name
                                                else -> item.file.name
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        when (val st = item.status) {
                                            is ImportItemStatus.Pending -> {
                                                Text(
                                                    text = "Queued",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            is ImportItemStatus.InProgress -> {
                                                Text(
                                                    text = st.step,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            is ImportItemStatus.Success -> {
                                                Text(
                                                    text = "Initials: ${st.book.initials} • ${st.book.bookCategory.name} • ${manager.getBookLocationLabel(st.book)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            is ImportItemStatus.Failed -> {
                                                Text(
                                                    text = st.errorMessage,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (item.status is ImportItemStatus.Success) {
                                            val book = item.status.book
                                            TextButton(
                                                onClick = {
                                                    onImportSuccess(book)
                                                    onDismiss()
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Open", fontSize = 11.sp)
                                            }
                                        } else if (item.status is ImportItemStatus.Failed) {
                                            TextButton(
                                                onClick = { importService.retryItem(item.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Retry", fontSize = 11.sp)
                                            }
                                        }

                                        if (item.status !is ImportItemStatus.InProgress) {
                                            IconButton(
                                                onClick = { importService.removeItem(item.id) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Footer Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (completedCount > 0) {
                        TextButton(
                            onClick = { importService.clearFinished() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Clear Finished", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (isProcessing) {
                        Button(onClick = onDismiss) {
                            Text("Run in Background")
                        }
                    } else {
                        OutlinedButton(onClick = onDismiss) {
                            Text(if (successfulBooks.isNotEmpty()) "Done" else "Close")
                        }
                        if (successfulBooks.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val target = selectedBookToOpen ?: successfulBooks.last()
                                    onImportSuccess(target)
                                    onDismiss()
                                }
                            ) {
                                Text("Open in Study")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Convenience wrapper that enqueues [selectedFiles] into the background queue and shows [ImportQueueDialog].
 */
@Composable
fun ImportBookDialog(
    selectedFiles: List<File>,
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {}
) {
    LaunchedEffect(selectedFiles) {
        if (selectedFiles.isNotEmpty()) {
            manager.enqueueBookImports(selectedFiles)
        }
    }
    ImportQueueDialog(
        manager = manager,
        onDismiss = onDismiss,
        onImportSuccess = onImportSuccess
    )
}

/**
 * Convenience overload for importing a single book file.
 */
@Composable
fun ImportBookDialog(
    selectedFile: File,
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {}
) {
    ImportBookDialog(
        selectedFiles = listOf(selectedFile),
        manager = manager,
        onDismiss = onDismiss,
        onImportSuccess = onImportSuccess
    )
}

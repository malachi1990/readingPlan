package org.bible.reading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.bible.reading.importer.BookImportRegistry
import org.bible.reading.importer.service.BookImportService
import org.bible.reading.importer.service.ImportItemStatus
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

/**
 * Result of inspecting files dropped onto the local import area.
 */
data class DroppedFilesResult(
    val validFiles: List<File>,
    val skippedCount: Int
)

/**
 * Resolves URI strings (from [DragData.FilesList]) into existing local files matching [supportedExtensions].
 */
fun parseDroppedFileUris(
    uris: List<String>,
    supportedExtensions: List<String> = BookImportRegistry.getSupportedExtensions()
): DroppedFilesResult {
    val allowedExts = supportedExtensions.map { it.lowercase() }.toSet()
    val valid = mutableListOf<File>()
    var skipped = 0
    for (raw in uris) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val file = try {
            if (trimmed.startsWith("file:", ignoreCase = true)) {
                File(URI(trimmed))
            } else {
                File(trimmed)
            }
        } catch (_: Exception) {
            null
        }
        if (file != null && file.exists() && file.isFile && file.extension.lowercase() in allowedExts) {
            valid.add(file)
        } else {
            skipped++
        }
    }
    return DroppedFilesResult(validFiles = valid, skippedCount = skipped)
}

/**
 * Resolves `text/uri-list` or newline-separated file path text (from [DragData.Text])
 * into existing local files matching [supportedExtensions].
 */
fun parseDroppedTextPaths(
    text: String,
    supportedExtensions: List<String> = BookImportRegistry.getSupportedExtensions()
): DroppedFilesResult {
    val candidates = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()
    return parseDroppedFileUris(candidates, supportedExtensions)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun extractDroppedBookFiles(event: DragAndDropEvent): DroppedFilesResult {
    return try {
        when (val data = event.dragData()) {
            is DragData.FilesList -> parseDroppedFileUris(data.readFiles())
            is DragData.Text -> parseDroppedTextPaths(data.readText())
            else -> DroppedFilesResult(emptyList(), 0)
        }
    } catch (_: Exception) {
        DroppedFilesResult(emptyList(), 0)
    }
}

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
 * Clickable status pill showing active background imports or completed/failed counts.
 * Opens the local import queue view when clicked.
 */
@Composable
fun ImportQueueStatusChip(
    importService: BookImportService,
    modifier: Modifier = Modifier
) {
    val importQueueItems = importService.queueItems
    if (importQueueItems.isEmpty()) return

    val isImporting = importService.isProcessing
    val successCount = importQueueItems.count { it.status is ImportItemStatus.Success }
    val failedCount = importQueueItems.count { it.status is ImportItemStatus.Failed }
    val doneCount = successCount + failedCount
    val chipContainerColor = when {
        isImporting -> MaterialTheme.colorScheme.tertiaryContainer
        failedCount > 0 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val chipContentColor = when {
        isImporting -> MaterialTheme.colorScheme.onTertiaryContainer
        failedCount > 0 -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = chipContainerColor,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { importService.isQueueDialogOpen = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = chipContentColor
                )
            }
            val label = when {
                isImporting -> "Importing ${doneCount + 1}/${importQueueItems.size}"
                failedCount > 0 -> "⚠️ $successCount/${importQueueItems.size} imported"
                else -> "✅ $successCount imported"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = chipContentColor
            )
        }
    }
}

/**
 * Local book import tab content supporting queueing multiple EPUB/PDF files via
 * drag-and-drop or a multi-select file dialog, persistent loaded references,
 * a live/completed progress bar, and "Run in Background" modal dismissal.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LocalBookImportContent(
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {},
    modifier: Modifier = Modifier
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

    var isDragOver by remember { mutableStateOf(false) }
    var dropNotice by remember { mutableStateOf<String?>(null) }

    val supportedFormatsLabel = remember {
        BookImportRegistry.getSupportedExtensions().joinToString(", ") { it.uppercase() }
    }

    val dropTarget = remember(importService) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDragOver = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragOver = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragOver = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragOver = false
                val result = extractDroppedBookFiles(event)
                if (result.validFiles.isNotEmpty()) {
                    importService.enqueueFiles(result.validFiles)
                }
                dropNotice = when {
                    result.validFiles.isEmpty() && result.skippedCount > 0 ->
                        "No supported files found. Supported formats: $supportedFormatsLabel."
                    result.skippedCount > 0 ->
                        "Queued ${result.validFiles.size} file(s); skipped ${result.skippedCount} unsupported/missing file(s)."
                    else -> null
                }
                return result.validFiles.isNotEmpty()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Drag & Drop Zone + File Dialog Button
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isDragOver) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
            border = BorderStroke(
                width = if (isDragOver) 2.dp else 1.dp,
                color = if (isDragOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { startEvent ->
                        runCatching {
                            val data = startEvent.dragData()
                            data is DragData.FilesList || data is DragData.Text
                        }.getOrDefault(false)
                    },
                    target = dropTarget
                )
                .clickable {
                    openBooksFileChooser { files ->
                        dropNotice = null
                        importService.enqueueFiles(files)
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(if (isDragOver) "📂" else "📥", fontSize = 24.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (isDragOver) {
                                "Drop files to queue import"
                            } else {
                                "Drag & drop books here, or choose files"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isDragOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Supported formats: $supportedFormatsLabel • Queue multiple files at once; imports convert into Sword modules.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        openBooksFileChooser { files ->
                            dropNotice = null
                            importService.enqueueFiles(files)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("+ Choose Files...")
                }
            }
        }

        // Optional warning notice if invalid files were dropped
        dropNotice?.let { notice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { dropNotice = null },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }

        // Progress Bar & Status Summary whenever books are queued or loaded
        if (items.isNotEmpty()) {
            val total = items.size.coerceAtLeast(1)
            val activeInProgress = activeItem?.status as? ImportItemStatus.InProgress
            val activeFraction = activeInProgress?.fraction ?: 0f
            val overallProgress = if (!isProcessing && completedCount == items.size) {
                1f
            } else {
                ((completedCount + activeFraction) / total.toFloat()).coerceIn(0f, 1f)
            }
            val percent = (overallProgress * 100).toInt().coerceIn(0, 100)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusHeading = when {
                        isProcessing && activeItem != null ->
                            "Importing ${completedCount + 1} of ${items.size}: ${activeItem.file.name}" +
                                if (pendingCount > 0) " ($pendingCount queued)" else ""
                        failureCount == 0 ->
                            "✅ ${successfulBooks.size} of ${items.size} loaded & imported"
                        successfulBooks.isEmpty() ->
                            "❌ $failureCount of ${items.size} failed"
                        else ->
                            "⚠️ ${successfulBooks.size} of ${items.size} imported • $failureCount failed"
                    }
                    Text(
                        text = statusHeading,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isProcessing -> MaterialTheme.colorScheme.primary
                            failureCount == 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )

                activeInProgress?.step?.let { activeStep ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                        Text(
                            text = activeStep,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Loaded / Queued Book References List
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No local books queued yet. Drag & drop files above or click \"+ Choose Files...\" to queue books.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

                                // Keep source file reference visible even after loading completes
                                Text(
                                    text = "File: ${item.file.absolutePath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            text = "Module: ${st.book.initials} • ${st.book.bookCategory.name} • ${manager.getBookLocationLabel(st.book)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
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

        // Bottom Footer Bar
        HorizontalDivider()
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

/**
 * Standalone wrapper dialog for the local book import queue.
 */
@Composable
fun ImportQueueDialog(
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 660.dp)
                .heightIn(min = 420.dp, max = 620.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📥 Import from Local",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Text("✕", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LocalBookImportContent(
                    manager = manager,
                    onDismiss = onDismiss,
                    onImportSuccess = onImportSuccess,
                    modifier = Modifier.weight(1f)
                )
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

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
 * Status of an individual file within an import batch.
 */
sealed class ImportItemStatus {
    object Pending : ImportItemStatus()
    data class InProgress(val step: String) : ImportItemStatus()
    data class Success(val book: Book) : ImportItemStatus()
    data class Failed(val errorMessage: String) : ImportItemStatus()
}

data class FileImportItem(
    val file: File,
    val status: ImportItemStatus = ImportItemStatus.Pending
)

/**
 * Modal dialog displaying the progress and result of importing one or more book files.
 */
@Composable
fun ImportBookDialog(
    selectedFiles: List<File>,
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit = {}
) {
    var items by remember(selectedFiles) {
        mutableStateOf(selectedFiles.map { FileImportItem(it) })
    }
    var isProcessing by remember { mutableStateOf(true) }
    var currentIndex by remember { mutableStateOf(0) }
    var currentStep by remember { mutableStateOf("Initializing import...") }
    var selectedBookToOpen by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(selectedFiles) {
        if (selectedFiles.isEmpty()) {
            isProcessing = false
            return@LaunchedEffect
        }
        isProcessing = true
        val currentItems = selectedFiles.map { FileImportItem(it) }.toMutableList()
        items = currentItems.toList()

        for (i in selectedFiles.indices) {
            currentIndex = i
            val file = selectedFiles[i]
            currentStep = "Starting import..."
            currentItems[i] = FileImportItem(file, ImportItemStatus.InProgress(currentStep))
            items = currentItems.toList()

            val result = manager.importBook(file) { step ->
                currentStep = step
                currentItems[i] = FileImportItem(file, ImportItemStatus.InProgress(step))
                items = currentItems.toList()
            }

            result.fold(
                onSuccess = { book ->
                    currentItems[i] = FileImportItem(file, ImportItemStatus.Success(book))
                    items = currentItems.toList()
                    if (selectedBookToOpen == null) {
                        selectedBookToOpen = book
                    }
                },
                onFailure = { err ->
                    currentItems[i] = FileImportItem(file, ImportItemStatus.Failed(err.message ?: "Unknown import error"))
                    items = currentItems.toList()
                }
            )
        }
        isProcessing = false
    }

    val isSingleFile = selectedFiles.size == 1

    if (isSingleFile) {
        val singleItem = items.firstOrNull() ?: FileImportItem(selectedFiles[0])
        val singleFile = selectedFiles[0]

        Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 400.dp, max = 520.dp)
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        isProcessing -> {
                            Text(
                                text = "📥 Importing Book",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = singleFile.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Text(
                                text = currentStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        singleItem.status is ImportItemStatus.Failed -> {
                            Text(
                                text = "❌ Import Failed",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = singleFile.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = singleItem.status.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Close")
                            }
                        }
                        singleItem.status is ImportItemStatus.Success -> {
                            val book = singleItem.status.book
                            Text(
                                text = "✅ Import Complete!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = book.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Initials: ${book.initials}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Category: ${book.bookCategory.name}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Saved to: ${manager.getBookLocationLabel(book)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Done")
                                }
                                Button(
                                    onClick = {
                                        onImportSuccess(book)
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open in Study")
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Multiple Files Batch View
    val successfulBooks = items.mapNotNull { (it.status as? ImportItemStatus.Success)?.book }
    val failureCount = items.count { it.status is ImportItemStatus.Failed }

    Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
        Surface(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 600.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    Text(
                        text = "📥 Importing Books (${currentIndex + 1} of ${selectedFiles.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    val overallProgress = (currentIndex.toFloat() / selectedFiles.size).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )

                    Text(
                        text = "Processing: ${selectedFiles.getOrNull(currentIndex)?.name ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = currentStep,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val headerText = when {
                        failureCount == 0 -> "✅ Import Complete!"
                        successfulBooks.isEmpty() -> "❌ Import Failed"
                        else -> "⚠️ Import Finished with Errors"
                    }
                    val headerColor = when {
                        failureCount == 0 -> MaterialTheme.colorScheme.primary
                        successfulBooks.isEmpty() -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.error
                    }

                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = headerColor
                    )

                    Text(
                        text = "${successfulBooks.size} of ${selectedFiles.size} book(s) imported successfully",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Scrollable Item List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (item.status) {
                                is ImportItemStatus.Success -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                is ImportItemStatus.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                is ImportItemStatus.InProgress -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                ImportItemStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (!isProcessing && item.status is ImportItemStatus.Success) {
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
                                    is ImportItemStatus.InProgress -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    is ImportItemStatus.Success -> {
                                        if (!isProcessing && successfulBooks.size > 1) {
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
                                            Text("Pending...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        is ImportItemStatus.InProgress -> {
                                            Text(st.step, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        is ImportItemStatus.Success -> {
                                            Text(
                                                text = "Initials: ${st.book.initials} • Category: ${st.book.bookCategory.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                                if (!isProcessing && item.status is ImportItemStatus.Success) {
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
                                }
                            }
                        }
                    }
                }

                if (!isProcessing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (successfulBooks.isNotEmpty()) "Done" else "Close")
                        }
                        if (successfulBooks.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val target = selectedBookToOpen ?: successfulBooks.first()
                                    onImportSuccess(target)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
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

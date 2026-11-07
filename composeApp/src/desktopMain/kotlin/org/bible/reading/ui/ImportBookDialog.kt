package org.bible.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.bible.reading.importer.BookImportRegistry
import org.bible.reading.jsword.SwordManager
import org.crosswire.jsword.book.Book
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Opens a native OS file chooser dialog to select a book file (.epub, .pdf, etc.) for import.
 */
fun openBookFileChooser(
    title: String = "Select Book to Import (EPUB, PDF)",
    onFileSelected: (File) -> Unit
) {
    try {
        val supportedExts = BookImportRegistry.getSupportedExtensions()
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            supportedExts.contains(ext)
        }
        dialog.isVisible = true

        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
            val selected = File(dir, file)
            if (selected.exists() && selected.isFile) {
                onFileSelected(selected)
            }
        }
    } catch (e: Exception) {
        println("Error opening file dialog: ${e.message}")
    }
}

/**
 * Modal dialog displaying the progress and result of an import operation.
 */
@Composable
fun ImportBookDialog(
    selectedFile: File,
    manager: SwordManager,
    onDismiss: () -> Unit,
    onImportSuccess: (Book) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(true) }
    var currentStep by remember { mutableStateOf("Initializing import...") }
    var error by remember { mutableStateOf<String?>(null) }
    var importedBook by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(selectedFile) {
        coroutineScope.launch {
            isProcessing = true
            error = null
            val result = manager.importBook(selectedFile) { step ->
                currentStep = step
            }
            isProcessing = false
            result.fold(
                onSuccess = { book ->
                    importedBook = book
                },
                onFailure = { err ->
                    error = err.message ?: "Unknown import error"
                }
            )
        }
    }

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
                if (isProcessing) {
                    Text(
                        text = "📥 Importing Book",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedFile.name,
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
                } else if (error != null) {
                    Text(
                        text = "❌ Import Failed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = selectedFile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error ?: "Unknown error",
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
                } else if (importedBook != null) {
                    val book = importedBook!!
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

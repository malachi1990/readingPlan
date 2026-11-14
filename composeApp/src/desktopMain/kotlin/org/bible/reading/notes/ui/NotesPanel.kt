package org.bible.reading.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bible.reading.notes.NoteExportFormat
import org.bible.reading.notes.NotesRepository
import org.bible.reading.notes.StudyNote
import java.awt.Desktop

/**
 * A full-height notes panel to display alongside the module reader column.
 *
 * Features:
 *  - Create / switch between multiple notes for the current module
 *  - Editable title and multi-line body
 *  - Mark a note as default
 *  - Delete notes
 *  - Export any note as Markdown or plain text
 *
 * @param repository    The shared [NotesRepository] (disk-backed, Compose-observable).
 * @param moduleKey     Initials of the currently-open Sword module (used to key notes).
 * @param moduleDisplayName  Human-readable name used as default note title.
 */
@Composable
fun NotesPanel(
    repository: NotesRepository,
    moduleKey: String,
    moduleDisplayName: String,
    modifier: Modifier = Modifier
) {
    // All notes for the current module, re-derived whenever the repository list changes.
    val moduleNotes by remember(repository.notes, moduleKey) {
        derivedStateOf { repository.notesFor(moduleKey) }
    }

    // Selected note id — reset whenever module changes.
    var selectedNoteId by remember(moduleKey) {
        mutableStateOf(repository.defaultNoteFor(moduleKey)?.id)
    }
    // Sync selectedNoteId after create/delete
    LaunchedEffect(moduleNotes) {
        if (selectedNoteId == null || moduleNotes.none { it.id == selectedNoteId }) {
            selectedNoteId = moduleNotes.firstOrNull { it.isDefault }?.id
                ?: moduleNotes.firstOrNull()?.id
        }
    }

    val selectedNote = moduleNotes.firstOrNull { it.id == selectedNoteId }

    // Local edit buffer — synced from the selected note
    var editTitle by remember(selectedNote?.id) { mutableStateOf(selectedNote?.title ?: "") }
    var editBody by remember(selectedNote?.id) { mutableStateOf(selectedNote?.body ?: "") }

    // Export dialog
    var showExportDialog by remember { mutableStateOf(false) }

    // Autosave: flush edits to repository whenever local buffer changes (debounce via LaunchedEffect key)
    val noteIdForSave = selectedNote?.id
    LaunchedEffect(editTitle, editBody) {
        if (noteIdForSave != null) {
            repository.updateNote(noteIdForSave, editTitle, editBody)
        }
    }

    if (showExportDialog && selectedNote != null) {
        NoteExportDialog(
            note = selectedNote,
            repository = repository,
            onDismiss = { showExportDialog = false }
        )
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Panel header ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📝", fontSize = 16.sp)
                    Text(
                        text = "Study Notes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (moduleNotes.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "${moduleNotes.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedNote != null) {
                        // Export button
                        OutlinedButton(
                            onClick = { showExportDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Export", fontSize = 11.sp)
                        }
                    }

                    // New note button
                    FilledTonalButton(
                        onClick = {
                            val newNote = repository.createNote(moduleKey, moduleDisplayName)
                            selectedNoteId = newNote.id
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("+ New", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            HorizontalDivider()

            // ── Note tabs / list (if multiple notes) ─────────────────────────
            if (moduleNotes.size > 1) {
                NoteTabsRow(
                    notes = moduleNotes,
                    selectedNoteId = selectedNoteId,
                    onSelectNote = { selectedNoteId = it },
                    onSetDefault = { repository.setDefault(it) },
                    onDeleteNote = { id ->
                        repository.deleteNote(id)
                        // selectedNoteId will be synced via LaunchedEffect above
                    }
                )
            }

            if (moduleNotes.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📄", fontSize = 36.sp)
                        Text(
                            text = "No notes yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Click \"+ New\" to create a note for $moduleDisplayName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val newNote = repository.createNote(moduleKey, moduleDisplayName)
                                selectedNoteId = newNote.id
                            }
                        ) {
                            Text("Create First Note")
                        }
                    }
                }
            } else if (selectedNote != null) {
                // ── Title field ───────────────────────────────────────────────
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("Note Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                // ── Default badge & single-note delete ────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Default toggle (shown for multi-note scenarios)
                    if (moduleNotes.size > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = selectedNote.isDefault,
                                onCheckedChange = { checked ->
                                    if (checked) repository.setDefault(selectedNote.id)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (selectedNote.isDefault) "Default note ★" else "Set as default",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selectedNote.isDefault)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Single note — show a subtle "default" badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "★ Default",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Delete button (only for single note; multi-note deletes are in the tab row)
                    if (moduleNotes.size == 1) {
                        TextButton(
                            onClick = { repository.deleteNote(selectedNote.id) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "🗑 Delete",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // ── Body text area ────────────────────────────────────────────
                OutlinedTextField(
                    value = editBody,
                    onValueChange = { editBody = it },
                    label = { Text("Note body (plain text or Markdown)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    maxLines = Int.MAX_VALUE
                )

                // ── Footer: last-updated timestamp ────────────────────────────
                Text(
                    text = "Last saved: ${selectedNote.updatedAt.take(16).replace("T", " at ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Note tabs row (displayed when a module has ≥2 notes)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NoteTabsRow(
    notes: List<StudyNote>,
    selectedNoteId: String?,
    onSelectNote: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onDeleteNote: (String) -> Unit
) {
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    if (confirmDeleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete Note?") },
            text = {
                val title = notes.firstOrNull { it.id == confirmDeleteId }?.title ?: "this note"
                Text("Are you sure you want to delete \"$title\"? This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteId?.let { onDeleteNote(it) }
                        confirmDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Notes for this module:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        notes.forEach { note ->
            val isSelected = note.id == selectedNoteId
            NoteTabItem(
                note = note,
                isSelected = isSelected,
                onSelect = { onSelectNote(note.id) },
                onSetDefault = { onSetDefault(note.id) },
                onDelete = { confirmDeleteId = note.id }
            )
        }
    }
}

@Composable
private fun NoteTabItem(
    note: StudyNote,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val bgColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (note.isDefault) {
                Text("★", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = note.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!note.isDefault) {
                TextButton(
                    onClick = onSetDefault,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Set default", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text("✕", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Export dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NoteExportDialog(
    note: StudyNote,
    repository: NotesRepository,
    onDismiss: () -> Unit
) {
    var exportDir by remember { mutableStateOf("~/downloads") }
    var exportFormat by remember { mutableStateOf(NoteExportFormat.MARKDOWN) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(true) }
    var lastFile by remember { mutableStateOf<java.io.File?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Export Note",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Export \"${note.title}\" to a file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = exportDir,
                    onValueChange = { exportDir = it },
                    label = { Text("Directory (default: ~/downloads)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Format chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Format:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    NoteExportFormat.entries.forEach { format ->
                        FilterChip(
                            selected = exportFormat == format,
                            onClick = { exportFormat = format },
                            label = { Text(format.displayName, fontSize = 12.sp) }
                        )
                    }
                }

                // Status feedback
                statusMessage?.let { msg ->
                    val bgColor = if (isSuccess)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    val textColor = if (isSuccess)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSuccess && lastFile != null) {
                            TextButton(
                                onClick = {
                                    try {
                                        if (Desktop.isDesktopSupported()) {
                                            Desktop.getDesktop().open(lastFile)
                                        }
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Open", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val file = repository.exportNote(note, exportDir, exportFormat)
                        lastFile = file
                        isSuccess = true
                        statusMessage = "Saved to ${file.absolutePath}"
                    } catch (e: Exception) {
                        isSuccess = false
                        statusMessage = "Export failed: ${e.message}"
                        lastFile = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Export", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

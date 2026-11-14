package org.bible.reading.notes

import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.time.LocalDateTime

/**
 * Observable, disk-backed repository for [StudyNote]s.
 *
 * Notes are stored as individual JSON-like text files under:
 *   <appDir>/notes/<moduleKey>/<noteId>.note
 *
 * The format is a simple property file to avoid adding a JSON library dependency:
 *   id=<uuid>
 *   moduleKey=<initials>
 *   title=<title (first line only, pipe-encoded)>
 *   isDefault=<true|false>
 *   createdAt=<iso>
 *   updatedAt=<iso>
 *   ---BODY---
 *   <rest of file is the body>
 */
class NotesRepository(private val appDir: File) {

    private val notesDir = File(appDir, "notes").apply { mkdirs() }

    /** All notes in memory, observable via Compose state. */
    val notes = mutableStateListOf<StudyNote>()

    init {
        loadAll()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all notes associated with [moduleKey]. */
    fun notesFor(moduleKey: String): List<StudyNote> =
        notes.filter { it.moduleKey == moduleKey }

    /** Returns the default note for [moduleKey], or the first note if none is marked default. */
    fun defaultNoteFor(moduleKey: String): StudyNote? {
        val moduleNotes = notesFor(moduleKey)
        return moduleNotes.firstOrNull { it.isDefault } ?: moduleNotes.firstOrNull()
    }

    /** Creates a new note for [moduleKey] with [defaultTitle] and saves it. */
    fun createNote(moduleKey: String, defaultTitle: String): StudyNote {
        val existing = notesFor(moduleKey)
        val note = StudyNote(
            moduleKey = moduleKey,
            title = defaultTitle,
            body = "",
            isDefault = existing.isEmpty()   // first note for a module is auto-default
        )
        notes.add(note)
        persist(note)
        return note
    }

    /** Updates a note by id and saves it. Returns the updated note. */
    fun updateNote(id: String, title: String, body: String): StudyNote? {
        val idx = notes.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = notes[idx].withTitle(title).withBody(body)
        notes[idx] = updated
        persist(updated)
        return updated
    }

    /**
     * Marks [noteId] as default for its module and unmarks all siblings.
     */
    fun setDefault(noteId: String) {
        val target = notes.firstOrNull { it.id == noteId } ?: return
        val updatedList = notes.map { n ->
            when {
                n.id == noteId -> n.withDefault(true)
                n.moduleKey == target.moduleKey -> n.withDefault(false)
                else -> n
            }
        }
        notes.clear()
        notes.addAll(updatedList)
        updatedList.filter { it.moduleKey == target.moduleKey }.forEach { persist(it) }
    }

    /** Deletes a note by id. */
    fun deleteNote(id: String) {
        val note = notes.firstOrNull { it.id == id } ?: return
        notes.removeAll { it.id == id }
        noteFile(note).delete()
        // If deleted note was the default and there are siblings, make first sibling default
        val siblings = notesFor(note.moduleKey)
        if (note.isDefault && siblings.isNotEmpty()) {
            setDefault(siblings.first().id)
        }
    }

    /** Exports a note to a file. Returns the written [File]. */
    fun exportNote(note: StudyNote, directory: String, format: NoteExportFormat): File {
        val dir = resolveDir(directory)
        val ext = format.extension
        val safeName = note.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").trim()
        val file = File(dir, "$safeName.$ext")
        file.writeText(
            when (format) {
                NoteExportFormat.MARKDOWN -> note.toMarkdown()
                NoteExportFormat.PLAIN_TEXT -> note.toPlainText()
            }
        )
        return file
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private fun moduleDir(moduleKey: String): File =
        File(notesDir, sanitize(moduleKey)).apply { mkdirs() }

    private fun noteFile(note: StudyNote): File =
        File(moduleDir(note.moduleKey), "${note.id}.note")

    private fun persist(note: StudyNote) {
        val f = noteFile(note)
        f.parentFile?.mkdirs()
        val encodedTitle = note.title.replace("\n", "\\n").replace("|", "\\|")
        f.writeText(buildString {
            appendLine("id=${note.id}")
            appendLine("moduleKey=${note.moduleKey}")
            appendLine("title=$encodedTitle")
            appendLine("isDefault=${note.isDefault}")
            appendLine("createdAt=${note.createdAt}")
            appendLine("updatedAt=${note.updatedAt}")
            appendLine("---BODY---")
            append(note.body)
        })
    }

    private fun loadAll() {
        notes.clear()
        if (!notesDir.exists()) return
        notesDir.listFiles()?.forEach { moduleFolder ->
            if (moduleFolder.isDirectory) {
                moduleFolder.listFiles { f -> f.extension == "note" }?.forEach { noteFile ->
                    parseNote(noteFile)?.let { notes.add(it) }
                }
            }
        }
        // Sort by createdAt for stable ordering
        val sorted = notes.sortedBy { it.createdAt }
        notes.clear()
        notes.addAll(sorted)
    }

    private fun parseNote(file: File): StudyNote? {
        return try {
            val text = file.readText()
            val separatorIndex = text.indexOf("---BODY---")
            val header = if (separatorIndex >= 0) text.substring(0, separatorIndex) else text
            val body = if (separatorIndex >= 0) text.substring(separatorIndex + "---BODY---".length).trimStart('\n') else ""

            val props = header.lines().mapNotNull { line ->
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0) line.substring(0, eqIdx) to line.substring(eqIdx + 1) else null
            }.toMap()

            val id = props["id"] ?: return null
            val moduleKey = props["moduleKey"] ?: return null
            val title = props["title"]?.replace("\\n", "\n")?.replace("\\|", "|") ?: "Untitled"
            val isDefault = props["isDefault"]?.toBooleanStrictOrNull() ?: false
            val createdAt = props["createdAt"] ?: LocalDateTime.now().toString()
            val updatedAt = props["updatedAt"] ?: createdAt

            StudyNote(id, moduleKey, title, body, isDefault, createdAt, updatedAt)
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

    private fun resolveDir(path: String): File {
        val expanded = if (path.startsWith("~")) {
            System.getProperty("user.home") + path.drop(1)
        } else path
        return File(expanded).also { it.mkdirs() }
    }
}

enum class NoteExportFormat(val extension: String, val displayName: String) {
    MARKDOWN("md", "Markdown (.md)"),
    PLAIN_TEXT("txt", "Plain Text (.txt)")
}

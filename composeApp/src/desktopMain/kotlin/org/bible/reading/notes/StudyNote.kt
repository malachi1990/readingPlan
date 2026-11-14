package org.bible.reading.notes

import java.time.LocalDateTime
import java.util.UUID

/**
 * A single study note associated with a module (identified by its initials).
 *
 * @param id           Stable UUID, used to identify the note across renames.
 * @param moduleKey    The initials of the Sword module this note belongs to.
 * @param title        User-editable title; defaults to the module name on creation.
 * @param body         The note body text (plain text / light markdown).
 * @param isDefault    Whether this is the note shown by default for this module.
 * @param createdAt    ISO-8601 string timestamp of creation.
 * @param updatedAt    ISO-8601 string timestamp of last edit.
 */
data class StudyNote(
    val id: String = UUID.randomUUID().toString(),
    val moduleKey: String,
    val title: String,
    val body: String = "",
    val isDefault: Boolean = false,
    val createdAt: String = LocalDateTime.now().toString(),
    val updatedAt: String = LocalDateTime.now().toString()
) {
    /** Export this note as Markdown. */
    fun toMarkdown(): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("> Module: $moduleKey")
        appendLine("> Created: $createdAt")
        appendLine()
        appendLine(body)
    }

    /** Export this note as plain text. */
    fun toPlainText(): String = buildString {
        appendLine("=".repeat(60))
        appendLine("Title: $title")
        appendLine("Module: $moduleKey")
        appendLine("Created: $createdAt")
        appendLine("=".repeat(60))
        appendLine()
        appendLine(body)
    }

    fun withTitle(newTitle: String) =
        copy(title = newTitle, updatedAt = LocalDateTime.now().toString())

    fun withBody(newBody: String) =
        copy(body = newBody, updatedAt = LocalDateTime.now().toString())

    fun withDefault(default: Boolean) =
        copy(isDefault = default, updatedAt = LocalDateTime.now().toString())
}

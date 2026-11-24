package org.bible.reading.tts.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native file dialog utility for TTS audio file saving and file selection.
 */
object TtsFileUtils {

    /**
     * Normalizes a suggested or user-entered filename so exported TTS audio defaults to `.mp3`.
     */
    fun normalizeSaveAudioFilename(filename: String): String {
        val trimmed = filename.trim().ifBlank { "tts_audio" }
        return if (trimmed.endsWith(".mp3", ignoreCase = true)) {
            trimmed
        } else {
            val withoutWav = if (trimmed.endsWith(".wav", ignoreCase = true)) {
                trimmed.dropLast(4)
            } else {
                trimmed
            }
            "$withoutWav.mp3"
        }
    }

    /**
     * Opens a native SAVE file dialog to save synthesized audio as a 128kbps MP3 file.
     */
    fun openSaveAudioFileDialog(
        defaultFilename: String,
        onFileSelected: (File) -> Unit
    ) {
        try {
            val dialog = FileDialog(null as Frame?, "Save Audio As (MP3)", FileDialog.SAVE)
            dialog.file = normalizeSaveAudioFilename(defaultFilename)
            dialog.isVisible = true

            val file = dialog.file
            val dir = dialog.directory
            if (file != null && dir != null) {
                val finalName = normalizeSaveAudioFilename(file)
                onFileSelected(File(dir, finalName))
            }
        } catch (e: Exception) {
            println("Error opening save audio dialog: ${e.message}")
        }
    }

    /**
     * Opens a native LOAD file dialog to choose a file (python executable, onnx model, or voice bin).
     */
    fun openFileChooser(
        title: String,
        allowedExtensions: List<String>? = null,
        onFileSelected: (File) -> Unit
    ) {
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            if (!allowedExtensions.isNullOrEmpty()) {
                dialog.setFilenameFilter { _, name ->
                    val ext = name.substringAfterLast('.', "").lowercase()
                    allowedExtensions.contains(ext)
                }
            }
            dialog.isVisible = true

            val file = dialog.file
            val dir = dialog.directory
            if (file != null && dir != null) {
                val selected = File(dir, file)
                if (selected.exists()) {
                    onFileSelected(selected)
                }
            }
        } catch (e: Exception) {
            println("Error opening file dialog: ${e.message}")
        }
    }
}

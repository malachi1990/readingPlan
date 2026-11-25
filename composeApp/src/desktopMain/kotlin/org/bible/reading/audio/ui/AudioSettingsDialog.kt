package org.bible.reading.audio.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.window.Dialog
import org.bible.reading.audio.service.AudioBibleService
import org.bible.reading.audio.strategy.AudioFilenameStrategyRegistry
import java.io.File
import javax.swing.JFileChooser

fun openAudioDirectoryChooser(
    title: String = "Select Audio Bible Directory",
    initialDir: File? = null,
    onDirectorySelected: (File) -> Unit
) {
    try {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            if (initialDir != null && initialDir.exists()) {
                currentDirectory = initialDir
            }
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
            onDirectorySelected(chooser.selectedFile)
        }
    } catch (e: Exception) {
        println("Error opening directory chooser: ${e.message}")
    }
}

data class AudioBibleSettingsDraft(
    val directoryPath: String,
    val selectedStrategyId: String,
    val customRegex: String,
    val customBookGroup: String,
    val customChapterGroup: String
) {
    companion object {
        fun fromService(audioService: AudioBibleService): AudioBibleSettingsDraft =
            AudioBibleSettingsDraft(
                directoryPath = audioService.preferences.audioDirectory,
                selectedStrategyId = audioService.preferences.selectedStrategyId,
                customRegex = audioService.preferences.customRegex,
                customBookGroup = audioService.preferences.customBookGroup.toString(),
                customChapterGroup = audioService.preferences.customChapterGroup.toString()
            )
    }

    fun hasChangesFrom(audioService: AudioBibleService): Boolean {
        val prefs = audioService.preferences
        return directoryPath.trim() != prefs.audioDirectory ||
            selectedStrategyId != prefs.selectedStrategyId ||
            customRegex != prefs.customRegex ||
            (customBookGroup.toIntOrNull() ?: 1) != prefs.customBookGroup ||
            (customChapterGroup.toIntOrNull() ?: 2) != prefs.customChapterGroup
    }

    fun applyTo(audioService: AudioBibleService) {
        val updatedPrefs = audioService.preferences.copy(
            audioDirectory = directoryPath.trim(),
            selectedStrategyId = selectedStrategyId,
            customRegex = customRegex,
            customBookGroup = customBookGroup.toIntOrNull() ?: 1,
            customChapterGroup = customChapterGroup.toIntOrNull() ?: 2
        )
        audioService.applyPreferences(updatedPrefs)
    }
}

@Composable
fun AudioBibleSettingsContent(
    audioService: AudioBibleService,
    draft: AudioBibleSettingsDraft,
    onDraftChange: (AudioBibleSettingsDraft) -> Unit,
    onAfterSaveAndScan: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var strategyDropdownExpanded by remember { mutableStateOf(false) }

    val allStrategies = remember { AudioFilenameStrategyRegistry.getAllStrategies() }
    val currentStrategy = remember(draft.selectedStrategyId) {
        AudioFilenameStrategyRegistry.getStrategy(draft.selectedStrategyId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Directory Selection
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Audio Bible Folder",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft.directoryPath,
                    onValueChange = { onDraftChange(draft.copy(directoryPath = it)) },
                    placeholder = { Text("e.g. /home/user/Music/AudioBible") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        val initial = if (draft.directoryPath.isNotBlank()) File(draft.directoryPath) else null
                        openAudioDirectoryChooser(initialDir = initial) { dir ->
                            onDraftChange(draft.copy(directoryPath = dir.absolutePath))
                        }
                    }
                ) {
                    Text("Browse...")
                }
            }
        }

        // Strategy Selection
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Filename Matching Strategy",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { strategyDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(currentStrategy.displayName, fontSize = 13.sp)
                        Text("▼", fontSize = 11.sp)
                    }
                }

                DropdownMenu(
                    expanded = strategyDropdownExpanded,
                    onDismissRequest = { strategyDropdownExpanded = false }
                ) {
                    allStrategies.forEach { strategy ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(strategy.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(strategy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                onDraftChange(draft.copy(selectedStrategyId = strategy.id))
                                strategyDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            Text(
                text = currentStrategy.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // If Custom Regex Strategy is selected
        if (draft.selectedStrategyId == "custom_regex") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Custom Regular Expression Configuration", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(
                    value = draft.customRegex,
                    onValueChange = { onDraftChange(draft.copy(customRegex = it)) },
                    label = { Text("Regex Pattern") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.customBookGroup,
                        onValueChange = { onDraftChange(draft.copy(customBookGroup = it)) },
                        label = { Text("Book Group #") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = draft.customChapterGroup,
                        onValueChange = { onDraftChange(draft.copy(customChapterGroup = it)) },
                        label = { Text("Chapter Group #") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }

        // Scan Status Card
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (audioService.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Scanning folder...", style = MaterialTheme.typography.bodySmall)
                } else if (audioService.totalChaptersMatched > 0) {
                    Text("📊", fontSize = 18.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ready: ${audioService.totalChaptersMatched} chapters matched (${audioService.totalBooksMatched} books)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${audioService.totalAudioFilesFound} total audio files detected in folder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (draft.directoryPath.isNotBlank()) {
                    Text("ℹ️", fontSize = 18.sp)
                    Text(
                        text = "Directory configured. Click Save & Scan to verify file matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text("💡", fontSize = 18.sp)
                    Text(
                        text = "Select your audio Bible directory above to enable chapter listening.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    draft.applyTo(audioService)
                    onAfterSaveAndScan()
                }
            ) {
                Text("Save & Scan")
            }
        }
    }
}

@Composable
fun AudioSettingsDialog(
    audioService: AudioBibleService,
    onDismiss: () -> Unit
) {
    var draft by remember(audioService) { mutableStateOf(AudioBibleSettingsDraft.fromService(audioService)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 640.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🎧 Audio Bible Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Specify where your audio Bible files are stored and how filenames are formatted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                AudioBibleSettingsContent(
                    audioService = audioService,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onAfterSaveAndScan = onDismiss,
                    modifier = Modifier.heightIn(max = 460.dp)
                )
            }
        }
    }
}

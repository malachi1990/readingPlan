package org.bible.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bible.reading.dto.DailyReading
import org.bible.reading.dto.Reading
import org.bible.reading.jsword.ChapterPassage
import org.bible.reading.jsword.SwordManager
import org.bible.reading.audio.service.AudioBibleService
import org.bible.reading.audio.ui.AudioPlayerBar
import java.time.format.DateTimeFormatter

@Composable
fun DailyReadingView(
    dailyReading: DailyReading,
    manager: SwordManager,
    audioService: AudioBibleService? = null,
    onBack: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenAudioSettings: (() -> Unit)? = null,
    onPreviousDay: (() -> Unit)? = null,
    onNextDay: (() -> Unit)? = null
) {
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
    var selectedReadingIndex by remember(dailyReading) { mutableStateOf(-1) } // -1 means "All Chapters"
    var bibleMenuExpanded by remember { mutableStateOf(false) }

    // Recompute passages whenever dailyReading, selectedBible, or selectedReadingIndex changes
    val chaptersToDisplay: List<Reading> = remember(dailyReading, selectedReadingIndex) {
        if (selectedReadingIndex in dailyReading.reading.indices) {
            listOf(dailyReading.reading[selectedReadingIndex])
        } else {
            dailyReading.reading
        }
    }

    val passages: List<ChapterPassage> = remember(chaptersToDisplay, manager.selectedBible) {
        chaptersToDisplay.map { reading ->
            manager.readChapter(reading, manager.selectedBible)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Back button & Day title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("← Back to Plan", fontWeight = FontWeight.SemiBold)
                        }

                        Column {
                            Text(
                                text = dailyReading.date.format(dateFormatter),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val summary = dailyReading.reading.joinToString(", ") { "${it.book} ${it.chapterNumber}" }
                            Text(
                                text = "Assigned Readings: $summary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Right: Day Navigation & Bible Selector & Download Shortcut
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Previous / Next Day Buttons
                        if (onPreviousDay != null) {
                            OutlinedButton(
                                onClick = onPreviousDay,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("◀ Prev Day", fontSize = 12.sp)
                            }
                        }

                        if (onNextDay != null) {
                            OutlinedButton(
                                onClick = onNextDay,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Next Day ▶", fontSize = 12.sp)
                            }
                        }

                        // Active Bible Dropdown
                        Box {
                            OutlinedButton(
                                onClick = { bibleMenuExpanded = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                val currentInitials = manager.selectedBible?.initials ?: "None"
                                Text("Bible: $currentInitials ▼", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            DropdownMenu(
                                expanded = bibleMenuExpanded,
                                onDismissRequest = { bibleMenuExpanded = false }
                            ) {
                                val bibles = manager.installedBibles
                                if (bibles.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No Bibles installed") },
                                        onClick = { bibleMenuExpanded = false }
                                    )
                                } else {
                                    bibles.forEach { bible ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(bible.name, fontWeight = if (bible == manager.selectedBible) FontWeight.Bold else FontWeight.Normal)
                                                    Spacer(Modifier.width(10.dp))
                                                    Text("(${bible.initials})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            onClick = {
                                                manager.selectedBible = bible
                                                bibleMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Download Bible button
                        Button(
                            onClick = onOpenDownload,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("📚 Download Bibles", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Sub-header: Chapter Selector Chips
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedReadingIndex == -1,
                            onClick = { selectedReadingIndex = -1 },
                            label = { Text("All Chapters (${dailyReading.reading.size})") }
                        )

                        dailyReading.reading.forEachIndexed { index, reading ->
                            FilterChip(
                                selected = selectedReadingIndex == index,
                                onClick = { selectedReadingIndex = index },
                                label = { Text("${reading.book} ${reading.chapterNumber}") }
                            )
                        }
                    }

                    if (audioService != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (audioService.hasAudioForDailyReading(dailyReading)) {
                                Button(
                                    onClick = {
                                        if (selectedReadingIndex in dailyReading.reading.indices) {
                                            audioService.playReading(dailyReading.reading[selectedReadingIndex])
                                        } else {
                                            audioService.playDailyReading(dailyReading)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("🎧 Listen to Day", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = { onOpenAudioSettings?.invoke() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("🎧 Audio Settings", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Main Reader Area
            if (manager.selectedBible == null && manager.installedBibles.isEmpty()) {
                // Empty state when no Bible is installed
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.widthIn(max = 500.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("📖", fontSize = 48.sp)
                            Text(
                                text = "No Bible Installed",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "To read Scripture passages, please download a Bible translation (such as KJV, ASV, or NETfree) using the JSword downloader.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onOpenDownload,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download Bible Now")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(passages) { passage ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Chapter Title Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${passage.bookName} ${passage.chapterNumber}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (audioService != null) {
                                            val currentChapterReading = Reading(passage.bookName, passage.chapterNumber)
                                            if (audioService.hasAudioForReading(currentChapterReading)) {
                                                FilledTonalButton(
                                                    onClick = { audioService.playReading(currentChapterReading) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("🎧 Listen", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }

                                        manager.selectedBible?.let { bible ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "${bible.name} (${bible.initials})",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider()

                                if (passage.error != null) {
                                    // Error display
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Could not display passage",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = passage.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Button(
                                                onClick = onOpenDownload,
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Download or Change Translation")
                                            }
                                        }
                                    }
                                } else if (passage.verses.isEmpty()) {
                                    // Fallback text
                                    Text(
                                        text = passage.fullText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = 28.sp
                                    )
                                } else {
                                    // Render verses
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        passage.verses.forEach { verse ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                if (verse.verseNumber > 0) {
                                                    Text(
                                                        text = "${verse.verseNumber}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier
                                                            .width(34.dp)
                                                            .padding(top = 2.dp)
                                                    )
                                                } else {
                                                    Spacer(Modifier.width(34.dp))
                                                }
                                                Text(
                                                    text = verse.text,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    lineHeight = 26.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }


        }
    }
}

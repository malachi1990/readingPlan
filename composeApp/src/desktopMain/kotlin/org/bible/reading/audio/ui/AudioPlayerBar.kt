package org.bible.reading.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.player.MAX_PLAYBACK_SPEED
import org.bible.reading.audio.player.MIN_PLAYBACK_SPEED
import org.bible.reading.tts.ui.TtsFileUtils
import java.util.Locale
import kotlin.math.abs

@Composable
fun AudioPlayerBar(
    player: AudioBiblePlayer,
    modifier: Modifier = Modifier,
    isSynthesizing: Boolean = false,
    synthesizingTitle: String? = null,
    onCancelSynthesis: (() -> Unit)? = null
) {
    val track = player.currentTrack
    if (track == null && !isSynthesizing) return

    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        if (track == null && isSynthesizing) {
            // Synthesis / loading state before audio stream starts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Synthesizing Speech...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!synthesizingTitle.isNullOrBlank()) {
                            Text(
                                text = synthesizingTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (onCancelSynthesis != null) {
                    OutlinedButton(
                        onClick = onCancelSynthesis,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                }
            }
        } else if (track != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Top Row: Track info, playlist index, and error/status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text("🎧", fontSize = 16.sp)
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        if (player.playlist.size > 1) {
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.clickable { showPlaylistMenu = true }
                                ) {
                                    Text(
                                        text = "📋 ${player.currentTrackIndex + 1} of ${player.playlist.size} ▼",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showPlaylistMenu,
                                    onDismissRequest = { showPlaylistMenu = false }
                                ) {
                                    Text(
                                        text = "Playlist Queue (${player.playlist.size} tracks)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    HorizontalDivider()
                                    player.playlist.forEachIndexed { idx, t ->
                                        val isCurrent = idx == player.currentTrackIndex
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(if (isCurrent) "▶" else "•", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                                    Text(
                                                        text = t.title,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = { player.removeTrack(idx) },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Text("✕", fontSize = 10.sp)
                                                }
                                            },
                                            onClick = {
                                                if (idx != player.currentTrackIndex) {
                                                    player.playTracks(player.playlist.toList(), idx)
                                                }
                                                showPlaylistMenu = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Clear Queue", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            player.clearPlaylist()
                                            showPlaylistMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        val subtitleText = if (track.subtitle.isNotBlank()) track.subtitle else "(${track.file.name})"
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (player.errorMessage != null) {
                            Text(
                                text = player.errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }

                        // Save audio file button (Requirement 3)
                        OutlinedButton(
                            onClick = {
                                val safeName = track.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                                TtsFileUtils.openSaveAudioFileDialog(defaultFilename = safeName) { destFile ->
                                    try {
                                        track.file.copyTo(destFile, overwrite = true)
                                    } catch (e: Exception) {
                                        println("Failed to save audio: ${e.message}")
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("💾 Save Audio", fontSize = 11.sp)
                        }

                        // Dismiss button
                        IconButton(
                            onClick = { player.stop() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Middle Row: Slider & Timers with smooth interactive scrubbing
                var isDragging by remember { mutableStateOf(false) }
                var dragFraction by remember { mutableStateOf(0f) }

                LaunchedEffect(track) {
                    isDragging = false
                    dragFraction = 0f
                }

                val duration = if (player.durationMs > 0) player.durationMs else 1L
                val playbackFraction = (player.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val sliderFraction = if (isDragging) dragFraction else playbackFraction

                val displayPositionMs = if (isDragging) {
                    (dragFraction * duration).toLong().coerceIn(0L, duration)
                } else {
                    player.currentPositionMs
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = formatDuration(displayPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(42.dp)
                    )

                    Slider(
                        value = sliderFraction,
                        onValueChange = { frac ->
                            isDragging = true
                            dragFraction = frac
                        },
                        onValueChangeFinished = {
                            val targetMs = (dragFraction * duration).toLong().coerceIn(0L, duration)
                            player.seekTo(targetMs)
                            isDragging = false
                        },
                        enabled = player.durationMs > 0,
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = if (player.durationMs > 0) formatDuration(player.durationMs) else "--:--",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(42.dp)
                    )
                }

                // Bottom Row: Playback Controls, Playback Speed (0.25x..2.00x in 5% steps), & Volume
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Playback Controls with skip buttons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = { player.previousTrack() },
                            enabled = player.playlist.size > 1 || player.currentPositionMs > 3000L,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("⏮", fontSize = 14.sp)
                        }

                        FilledTonalIconButton(
                            onClick = { player.seekBackward(10_000L) },
                            enabled = player.currentTrack != null,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("⏪10", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        FilledIconButton(
                            onClick = { player.togglePlayPause() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text(if (player.isPlaying && !player.isPaused) "⏸" else "▶", fontSize = 16.sp)
                        }

                        FilledTonalIconButton(
                            onClick = { player.seekForward(10_000L) },
                            enabled = player.currentTrack != null,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("10⏩", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        FilledTonalIconButton(
                            onClick = { player.stop() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("⏹", fontSize = 14.sp)
                        }

                        FilledTonalIconButton(
                            onClick = { player.nextTrack() },
                            enabled = player.currentTrackIndex < player.playlist.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("⏭", fontSize = 14.sp)
                        }
                    }

                    // Playback Speed Stepper + Popover & Volume Slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PlaybackSpeedControl(
                            speed = player.playbackSpeed,
                            expanded = showSpeedMenu,
                            onExpandedChange = { showSpeedMenu = it },
                            onDecrease = { player.decreasePlaybackSpeed() },
                            onIncrease = { player.increasePlaybackSpeed() },
                            onSpeedSelect = { player.setPlaybackSpeed(it) },
                            onReset = { player.resetPlaybackSpeed() }
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.widthIn(min = 130.dp, max = 180.dp)
                        ) {
                            Text(if (player.volume <= 0.001f) "🔇" else if (player.volume < 0.5f) "🔉" else "🔊", fontSize = 14.sp)
                            Slider(
                                value = player.volume,
                                onValueChange = { player.setVolumeLevel(it) },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackSpeedControl(
    speed: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSpeedSelect: (Float) -> Unit,
    onReset: () -> Unit
) {
    val isCustomSpeed = abs(speed - 1.0f) >= 0.01f
    val speedLabel = formatSpeed(speed)

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FilledTonalIconButton(
                onClick = onDecrease,
                enabled = speed > MIN_PLAYBACK_SPEED + 0.005f,
                modifier = Modifier.size(28.dp)
            ) {
                Text("−", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isCustomSpeed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExpandedChange(true) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = speedLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCustomSpeed) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            FilledTonalIconButton(
                onClick = onIncrease,
                enabled = speed < MAX_PLAYBACK_SPEED - 0.005f,
                modifier = Modifier.size(28.dp)
            ) {
                Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Playback Speed: $speedLabel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = onReset,
                        enabled = isCustomSpeed,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("Reset 1.00x", fontSize = 11.sp)
                    }
                }

                // 0.25x to 2.00x in 0.05x (5%) steps -> 35 intervals => 34 intermediate steps
                Slider(
                    value = speed,
                    onValueChange = { onSpeedSelect(it) },
                    valueRange = MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED,
                    steps = 34,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.25x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("1.00x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2.00x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDecrease,
                        enabled = speed > MIN_PLAYBACK_SPEED + 0.005f,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f).height(28.dp)
                    ) {
                        Text("−5%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onIncrease,
                        enabled = speed < MAX_PLAYBACK_SPEED - 0.005f,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f).height(28.dp)
                    ) {
                        Text("+5%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider()

                val presets = listOf(0.25f, 0.50f, 0.75f, 1.00f, 1.25f, 1.50f, 2.00f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presets.forEach { preset ->
                        val selected = abs(speed - preset) < 0.01f
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onSpeedSelect(preset)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = formatSpeed(preset),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    return String.format(Locale.US, "%.2fx", speed)
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

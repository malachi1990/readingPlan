package org.bible.reading.audio.ui

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

@Composable
fun AudioPlayerBar(
    player: AudioBiblePlayer,
    modifier: Modifier = Modifier
) {
    val track = player.currentTrack ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🎧", fontSize = 16.sp)
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (player.playlist.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "${player.currentTrackIndex + 1} of ${player.playlist.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "(${track.file.name})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

                    // Dismiss button
                    IconButton(
                        onClick = { player.stop() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Middle Row: Slider & Timers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = formatDuration(player.currentPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(42.dp)
                )

                val duration = if (player.durationMs > 0) player.durationMs else 1L
                val progress = (player.currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

                Slider(
                    value = progress,
                    onValueChange = { frac ->
                        val targetMs = (frac * duration).toLong()
                        player.seekTo(targetMs)
                    },
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = if (player.durationMs > 0) formatDuration(player.durationMs) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(42.dp)
                )
            }

            // Bottom Row: Playback Controls & Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playback Controls
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

                    FilledIconButton(
                        onClick = { player.togglePlayPause() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Text(if (player.isPlaying && !player.isPaused) "⏸" else "▶", fontSize = 16.sp)
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

                // Volume slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.widthIn(max = 200.dp)
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

package org.bible.reading.tts.ui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bible.reading.audio.player.AudioBiblePlayer
import org.bible.reading.audio.service.AudioBibleService
import org.bible.reading.audio.ui.AudioBibleSettingsContent
import org.bible.reading.audio.ui.AudioBibleSettingsDraft
import org.bible.reading.tts.TtsEngineType
import org.bible.reading.tts.TtsService
import org.bible.reading.tts.TtsVoice
import org.bible.reading.tts.TtsVoiceCatalog
import org.bible.reading.ui.theme.AppThemeMode
import org.bible.reading.ui.theme.AppearancePreferences
import org.bible.reading.ui.theme.AppearanceSettingsContent
import org.bible.reading.ui.theme.CustomThemeColors
import org.bible.reading.ui.theme.FontCatalog

@Composable
fun TtsSettingsDialog(
    ttsService: TtsService,
    player: AudioBiblePlayer,
    audioService: AudioBibleService? = null,
    appearancePreferences: AppearancePreferences = AppearancePreferences(),
    onThemeModeChange: ((AppThemeMode) -> Unit)? = null,
    onFontSelectionChange: ((String) -> Unit)? = null,
    onCustomThemeColorsChange: ((CustomThemeColors, Boolean) -> Unit)? = null,
    onResetAppearance: (() -> Unit)? = null,
    initialTab: Int = 0,
    onDismiss: () -> Unit
) {
    val dialogScope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val hasAppearanceTab = onThemeModeChange != null && onFontSelectionChange != null
    val audioTabIndex = if (audioService != null) 1 else -1
    val appearanceTabIndex = when {
        !hasAppearanceTab -> -1
        audioService != null -> 2
        else -> 1
    }
    val maxTab = when {
        hasAppearanceTab -> appearanceTabIndex
        audioService != null -> 1
        else -> 0
    }
    var activeTab by remember { mutableStateOf(initialTab.coerceIn(0, maxTab)) }
    LaunchedEffect(initialTab, maxTab) {
        activeTab = initialTab.coerceIn(0, maxTab)
    }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var audioDraft by remember(audioService) {
        mutableStateOf(audioService?.let { AudioBibleSettingsDraft.fromService(it) })
    }

    val applyPendingAudioChanges = {
        val svc = audioService
        val draft = audioDraft
        if (svc != null && draft != null && draft.hasChangesFrom(svc)) {
            draft.applyTo(svc)
        }
    }

    LaunchedEffect(Unit) {
        cacheBytes = ttsService.getCacheSizeBytesAsync()
    }

    Dialog(
        onDismissRequest = { currentOnDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 660.dp, max = 780.dp)
                .heightIn(min = 550.dp, max = 780.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚙", fontSize = 24.sp)
                        Column {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Configure appearance, fonts, AI narration voices, and audio Bible files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = { currentOnDismiss() }) {
                        Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tabs: Text to Speech / Audio Bible / Appearance
                TabRow(
                    selectedTabIndex = activeTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Text to Speech", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    )
                    if (audioService != null) {
                        Tab(
                            selected = activeTab == audioTabIndex,
                            onClick = { activeTab = audioTabIndex },
                            text = { Text("Audio Bible", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        )
                    }
                    if (hasAppearanceTab) {
                        Tab(
                            selected = activeTab == appearanceTabIndex,
                            onClick = { activeTab = appearanceTabIndex },
                            text = { Text("Appearance", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tab Content
                Box(modifier = Modifier.weight(1f).clipToBounds()) {
                    when (activeTab) {
                        0 -> TextToSpeechTabContent(
                            ttsService = ttsService,
                            player = player,
                            cacheSizeBytes = cacheBytes,
                            onClearCache = {
                                dialogScope.launch {
                                    ttsService.clearCacheAsync()
                                    cacheBytes = ttsService.getCacheSizeBytesAsync()
                                }
                            }
                        )
                        1 -> if (audioService != null) {
                            val currentDraft = audioDraft ?: AudioBibleSettingsDraft.fromService(audioService)
                            AudioBibleSettingsContent(
                                audioService = audioService,
                                draft = currentDraft,
                                onDraftChange = { audioDraft = it }
                            )
                        } else if (hasAppearanceTab) {
                            AppearanceSettingsContent(
                                preferences = appearancePreferences,
                                onThemeModeChange = onThemeModeChange!!,
                                onFontSelectionChange = onFontSelectionChange!!,
                                onCustomThemeColorsChange = onCustomThemeColorsChange ?: { _, _ -> },
                                onResetToDefaults = { onResetAppearance?.invoke() }
                            )
                        }
                        else -> if (hasAppearanceTab) {
                            AppearanceSettingsContent(
                                preferences = appearancePreferences,
                                onThemeModeChange = onThemeModeChange!!,
                                onFontSelectionChange = onFontSelectionChange!!,
                                onCustomThemeColorsChange = onCustomThemeColorsChange ?: { _, _ -> },
                                onResetToDefaults = { onResetAppearance?.invoke() }
                            )
                        }
                    }
                }

                // Global Status Message if present
                ttsService.statusMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (ttsService.isDownloading || ttsService.isSynthesizing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Active settings quick label
                    val activeVoice = ttsService.getActiveVoice()
                    val audioSummary = if (audioService != null && audioService.totalChaptersMatched > 0) {
                        " • Audio Bible: ${audioService.totalChaptersMatched} ch"
                    } else {
                        ""
                    }
                    val appearanceSummary = if (hasAppearanceTab) {
                        " • ${appearancePreferences.themeMode.displayName} (${FontCatalog.displayNameForSelection(appearancePreferences.fontSelection)})"
                    } else {
                        ""
                    }
                    Text(
                        text = "Active: ${activeVoice.name} (${activeVoice.engineType.displayName})$audioSummary$appearanceSummary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    Button(
                        onClick = {
                            try {
                                applyPendingAudioChanges()
                            } finally {
                                currentOnDismiss()
                            }
                        }
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun TextToSpeechTabContent(
    ttsService: TtsService,
    player: AudioBiblePlayer,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit
) {
    val tabScope = rememberCoroutineScope()
    val prefs = ttsService.preferences
    val currentEngine = prefs.selectedEngine
    val voices = remember(currentEngine) { TtsVoiceCatalog.getVoicesForEngine(currentEngine) }
    val listState = rememberLazyListState()

    var readinessRevision by remember { mutableIntStateOf(0) }
    var piperInstalled by remember { mutableStateOf<Boolean?>(null) }
    var kokoroAvailable by remember { mutableStateOf<Boolean?>(null) }
    var voiceReadiness by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(
        currentEngine,
        prefs.kokoroPythonPath,
        prefs.kokoroBinaryPath,
        prefs.kokoroVoicesPath,
        ttsService.isDownloading,
        readinessRevision
    ) {
        withContext(Dispatchers.IO) {
            when (currentEngine) {
                TtsEngineType.PIPER -> {
                    val binReady = ttsService.piperManager.isBinaryInstalled()
                    val map = voices.associate { voice ->
                        voice.id to (binReady && ttsService.piperManager.isVoiceDownloaded(voice))
                    }
                    piperInstalled = binReady
                    voiceReadiness = map
                }
                TtsEngineType.KOKORO -> {
                    val avail = ttsService.kokoroManager.isAvailable(
                        prefs.kokoroPythonPath,
                        prefs.kokoroBinaryPath,
                        prefs.kokoroVoicesPath
                    )
                    kokoroAvailable = avail
                    voiceReadiness = voices.associate { it.id to avail }
                }
                TtsEngineType.SYSTEM -> {
                    voiceReadiness = voices.associate { it.id to true }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Engine selector row
            item {
                Text(
                    text = "TTS Engine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TtsEngineType.entries.forEach { engine ->
                        val isSelected = engine == currentEngine
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) ButtonDefaults.outlinedButtonBorder(true) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { ttsService.setEngine(engine) }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = engine.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when (engine) {
                                        TtsEngineType.PIPER -> "Neural • Offline • Zero config"
                                        TtsEngineType.KOKORO -> "82M Param • Highly natural"
                                        TtsEngineType.SYSTEM -> "Built-in OS speech"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            if (currentEngine == TtsEngineType.KOKORO) {
                item {
                    KokoroConfigurationCard(ttsService)
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Voice List Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Voices",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentEngine == TtsEngineType.PIPER) {
                        val installed = piperInstalled
                        Text(
                            text = when (installed) {
                                null -> "Engine: Checking..."
                                true -> "Engine: Installed ✓"
                                false -> "Engine: Auto-downloadable"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (installed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (currentEngine == TtsEngineType.KOKORO) {
                        val available = kokoroAvailable
                        Text(
                            text = when (available) {
                                null -> "Kokoro: Checking..."
                                true -> "Kokoro: Ready ✓"
                                false -> "Kokoro: Path configuration needed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (available) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            items(voices) { voice ->
                val isReady = voiceReadiness[voice.id] ?: (currentEngine == TtsEngineType.SYSTEM)
                VoiceItemCard(
                    voice = voice,
                    isSelected = voice.id == prefs.selectedVoiceId,
                    isReady = isReady,
                    isDownloading = ttsService.isDownloading && ttsService.downloadingVoiceId == voice.id,
                    downloadProgress = ttsService.downloadProgress,
                    onSelect = { ttsService.setVoice(voice.id) },
                    onDownload = {
                        ttsService.downloadVoiceAsync(voice) {
                            readinessRevision++
                        }
                    },
                    onDelete = {
                        if (voice.engineType == TtsEngineType.PIPER) {
                            tabScope.launch {
                                withContext(Dispatchers.IO) {
                                    ttsService.piperManager.deleteVoice(voice)
                                }
                                readinessRevision++
                            }
                        }
                    },
                    onTest = {
                        ttsService.testVoice(voice, player)
                    }
                )
            }

            // Speech Rate & Cache Settings (below TTS engine specific settings)
            item {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Speech Rate & Cache",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                PlaybackAndCacheSettingsSection(
                    ttsService = ttsService,
                    cacheSizeBytes = cacheSizeBytes,
                    onClearCache = onClearCache
                )
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp),
            style = defaultScrollbarStyle().copy(
                thickness = 8.dp,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
            )
        )
    }
}

private data class KokoroResolvedPathsUi(
    val pythonPath: String,
    val modelName: String,
    val modelExists: Boolean,
    val voicesName: String,
    val voicesExist: Boolean
)

@Composable
private fun KokoroConfigurationCard(ttsService: TtsService) {
    val prefs = ttsService.preferences
    val kokoroMgr = ttsService.kokoroManager

    val resolvedPaths by produceState<KokoroResolvedPathsUi?>(
        initialValue = null,
        key1 = prefs.kokoroPythonPath,
        key2 = prefs.kokoroBinaryPath,
        key3 = prefs.kokoroVoicesPath
    ) {
        value = withContext(Dispatchers.IO) {
            val py = kokoroMgr.resolvePythonPath(prefs.kokoroPythonPath)
            val model = kokoroMgr.resolveModelFile(prefs.kokoroBinaryPath)
            val voices = kokoroMgr.resolveVoicesFile(prefs.kokoroVoicesPath)
            KokoroResolvedPathsUi(
                pythonPath = py,
                modelName = model.name,
                modelExists = model.exists(),
                voicesName = voices.name,
                voicesExist = voices.exists()
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kokoro Settings & CUDA Acceleration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                TextButton(
                    onClick = {
                        ttsService.setKokoroPythonPath("")
                        ttsService.setKokoroBinaryPath("")
                        ttsService.setKokoroVoicesPath("")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Reset Paths", fontSize = 11.sp)
                }
            }

            // CUDA Acceleration Toggle (Requirement 2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CUDA Acceleration (GPU)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Enable NVIDIA CUDA execution provider for faster speech synthesis. Toggle off for the CPU non-CUDA version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = prefs.kokoroUseCuda,
                    onCheckedChange = { ttsService.setKokoroUseCuda(it) }
                )
            }

            HorizontalDivider()

            // Python Path (Requirement 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Python Installation / Executable",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Using: ${resolvedPaths?.pythonPath ?: "Resolving..."}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = prefs.kokoroPythonPath,
                        onValueChange = { ttsService.setKokoroPythonPath(it) },
                        placeholder = { Text("Auto-detect (e.g. ~/.local/share/pipx/venvs/kokoro-onnx/bin/python3)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            TtsFileUtils.openFileChooser("Select Python Executable") { file ->
                                ttsService.setKokoroPythonPath(file.absolutePath)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Browse...", fontSize = 12.sp)
                    }
                }
            }

            // Kokoro Model / Binary Path (Requirement 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kokoro Binary / Model File (.onnx)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val modelExists = resolvedPaths?.modelExists == true
                    val modelLabel = resolvedPaths?.let { "${it.modelName} (${if (it.modelExists) "✓ Found" else "✗ Missing"})" } ?: "Checking..."
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (modelExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = prefs.kokoroBinaryPath,
                        onValueChange = { ttsService.setKokoroBinaryPath(it) },
                        placeholder = { Text("Default: kokoro.onnx") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            TtsFileUtils.openFileChooser(
                                title = "Select Kokoro Model File (.onnx)",
                                allowedExtensions = listOf("onnx", "bin")
                            ) { file ->
                                ttsService.setKokoroBinaryPath(file.absolutePath)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Browse...", fontSize = 12.sp)
                    }
                }
            }

            // Kokoro Voices Path (Requirement 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voices File (.bin)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val voicesExist = resolvedPaths?.voicesExist == true
                    val voicesLabel = resolvedPaths?.let { "${it.voicesName} (${if (it.voicesExist) "✓ Found" else "✗ Missing"})" } ?: "Checking..."
                    Text(
                        text = voicesLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (voicesExist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = prefs.kokoroVoicesPath,
                        onValueChange = { ttsService.setKokoroVoicesPath(it) },
                        placeholder = { Text("Default: voices-v1.0.bin") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            TtsFileUtils.openFileChooser(
                                title = "Select Voices File (.bin)",
                                allowedExtensions = listOf("bin", "json")
                            ) { file ->
                                ttsService.setKokoroVoicesPath(file.absolutePath)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Browse...", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceItemCard(
    voice: TtsVoice,
    isSelected: Boolean,
    isReady: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = onSelect
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = voice.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (isReady) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "READY",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = voice.description,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action buttons on right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isDownloading) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(70.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                fontSize = 10.sp
                            )
                        }
                    } else if (!isReady && voice.engineType == TtsEngineType.PIPER) {
                        OutlinedButton(
                            onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("📥 ${voice.sizeMb.toInt()}MB", fontSize = 11.sp)
                        }
                    } else if (isReady) {
                        OutlinedButton(
                            onClick = onTest,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("▶ Test", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackAndCacheSettingsSection(
    ttsService: TtsService,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit
) {
    val prefs = ttsService.preferences

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Speech Rate Slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Speech Rate / Speed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.2f", prefs.speechRate)}x",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = prefs.speechRate,
                    onValueChange = { ttsService.setSpeechRate(it) },
                    valueRange = 0.75f..1.5f,
                    steps = 6
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.75x (Slower)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("1.0x (Normal)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("1.5x (Faster)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Sentence Silence Slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sentence Pause Duration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.1f", prefs.sentenceSilence)}s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = prefs.sentenceSilence,
                    onValueChange = { ttsService.setSentenceSilence(it) },
                    valueRange = 0.0f..0.5f,
                    steps = 4
                )
                Text(
                    text = "Controls natural pause timing between consecutive sentences when reading chapters.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Audio Cache Management
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Audio Cache",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Synthesized chapters and verses are cached to disk so repeated listens start instantly with zero latency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cacheMb = cacheSizeBytes / (1024.0 * 1024.0)
                    Text(
                        text = "Current Cache Size: ${String.format(java.util.Locale.US, "%.1f", cacheMb)} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedButton(onClick = onClearCache) {
                        Text("🗑 Clear Cache", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

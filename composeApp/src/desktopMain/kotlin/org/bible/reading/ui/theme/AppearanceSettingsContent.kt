package org.bible.reading.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsContent(
    preferences: AppearancePreferences,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onFontSelectionChange: (String) -> Unit,
    onCustomThemeColorsChange: (CustomThemeColors, Boolean) -> Unit = { _, _ -> },
    onResetToDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    var systemFonts by remember { mutableStateOf<List<String>?>(null) }
    var fontSearchQuery by remember { mutableStateOf("") }
    var activeSlot by remember { mutableStateOf(CustomThemeColorSlot.BACKGROUND) }

    LaunchedEffect(Unit) {
        systemFonts = withContext(Dispatchers.IO) {
            FontCatalog.getInstalledSystemFontFamilies()
        }
    }

    val filteredSystemFonts = remember(systemFonts, fontSearchQuery) {
        val all = systemFonts.orEmpty()
        val q = fontSearchQuery.trim()
        if (q.isEmpty()) all else all.filter { it.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Theme Selection
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Color Theme",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onResetToDefaults,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Reset Defaults", fontSize = 11.sp)
                }
            }

            // 2x2 grid for the 4 themes (Default, Dark, High Contrast, Custom)
            val themeRows = remember { AppThemeMode.entries.chunked(2) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                themeRows.forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowModes.forEach { mode ->
                            ThemeOptionCard(
                                mode = mode,
                                customColors = preferences.customColors,
                                isSelected = preferences.themeMode == mode,
                                onSelect = { onThemeModeChange(mode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Custom RGB Theme Editor (shown when Custom theme is selected)
        if (preferences.themeMode == AppThemeMode.CUSTOM) {
            CustomThemeRgbEditor(
                customColors = preferences.customColors,
                activeSlot = activeSlot,
                onSelectSlot = { activeSlot = it },
                onColorsChange = onCustomThemeColorsChange
            )
        }

        HorizontalDivider()

        // Section 2: Font Selection
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Font Family",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Active: ${FontCatalog.displayNameForSelection(preferences.fontSelection)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Built-in / Recommended Font Options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BuiltInFontCard(
                    title = "Ubuntu",
                    subtitle = "Included v${FontCatalog.EMBEDDED_UBUNTU_VERSION} as the default font",
                    badgeText = "Default",
                    isSelected = preferences.fontSelection == FontCatalog.EMBEDDED_UBUNTU_ID,
                    onClick = { onFontSelectionChange(FontCatalog.EMBEDDED_UBUNTU_ID) },
                    modifier = Modifier.weight(1f)
                )
                BuiltInFontCard(
                    title = "System Default",
                    subtitle = "Operating system default UI font",
                    badgeText = null,
                    isSelected = preferences.fontSelection == FontCatalog.SYSTEM_DEFAULT_ID,
                    onClick = { onFontSelectionChange(FontCatalog.SYSTEM_DEFAULT_ID) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Installed System Fonts Browser
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Installed System Fonts",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val countLabel = when (val all = systemFonts) {
                            null -> "Scanning..."
                            else -> "${filteredSystemFonts.size} of ${all.size}"
                        }
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = fontSearchQuery,
                        onValueChange = { fontSearchQuery = it },
                        placeholder = { Text("Filter installed fonts (e.g. Georgia, Inter, DejaVu)...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (fontSearchQuery.isNotEmpty()) {
                                TextButton(onClick = { fontSearchQuery = "" }) {
                                    Text("Clear", fontSize = 11.sp)
                                }
                            }
                        }
                    )

                    val listState = rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        when {
                            systemFonts == null -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                }
                            }
                            filteredSystemFonts.isEmpty() -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No matching system fonts found.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            else -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(end = 12.dp)
                                ) {
                                    items(filteredSystemFonts, key = { it }) { familyName ->
                                        val fontId = FontCatalog.systemFontId(familyName)
                                        val isSelected = preferences.fontSelection == fontId
                                        val itemFamily = remember(familyName) {
                                            FontCatalog.buildSystemFontFamily(familyName)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                                    else MaterialTheme.colorScheme.surface
                                                )
                                                .clickable { onFontSelectionChange(fontId) }
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                                    onClick = { onFontSelectionChange(fontId) },
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = familyName,
                                                    fontFamily = itemFamily,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1
                                                )
                                            }
                                            Text(
                                                text = "Aa Bb",
                                                fontFamily = itemFamily,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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
                    }
                }
            }
        }

        // Section 3: Live Preview
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Preview — ${preferences.themeMode.displayName} • ${FontCatalog.displayNameForSelection(preferences.fontSelection)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Genesis 1:1 — In the beginning God created the heaven and the earth.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = "Bold heading & Italic emphasis for daily reading and study notes.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CustomThemeRgbEditor(
    customColors: CustomThemeColors,
    activeSlot: CustomThemeColorSlot,
    onSelectSlot: (CustomThemeColorSlot) -> Unit,
    onColorsChange: (CustomThemeColors, Boolean) -> Unit
) {
    val activeArgb = customColors.getArgb(activeSlot)
    val red = CustomThemeColors.red(activeArgb)
    val green = CustomThemeColors.green(activeArgb)
    val blue = CustomThemeColors.blue(activeArgb)

    var hexInput by remember(activeSlot, activeArgb) {
        mutableStateOf(CustomThemeColors.toHex(activeArgb))
    }

    val latestColors by rememberUpdatedState(customColors)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header & Quick Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Custom RGB Palette",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PresetSeedChip("Light") { onColorsChange(CustomThemeColors.fromDefaultLight(), true) }
                    PresetSeedChip("Dark") { onColorsChange(CustomThemeColors.fromDark(), true) }
                    PresetSeedChip("Hi-Contrast") { onColorsChange(CustomThemeColors.fromHighContrast(), true) }
                    PresetSeedChip("Sepia") { onColorsChange(CustomThemeColors.warmSepia(), true) }
                }
            }

            // UI Component Slot Selector Grid (3 columns)
            val slotRows = remember { CustomThemeColorSlot.entries.chunked(3) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                slotRows.forEach { rowSlots ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowSlots.forEach { slot ->
                            val isSlotSelected = slot == activeSlot
                            val slotArgb = customColors.getArgb(slot)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectSlot(slot) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSlotSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                border = if (isSlotSelected) {
                                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SwatchDot(Color(slotArgb), MaterialTheme.colorScheme.outline)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = slot.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSlotSelected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = CustomThemeColors.toHex(slotArgb),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        repeat(3 - rowSlots.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            HorizontalDivider()

            // Active Component RGB Sliders & Hex Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(activeArgb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    Column {
                        Text(
                            text = activeSlot.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeSlot.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        hexInput = raw
                        val parsed = CustomThemeColors.parseHexOrNull(raw)
                        if (parsed != null) {
                            onColorsChange(customColors.withArgb(activeSlot, parsed), true)
                        }
                    },
                    label = { Text("Hex", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.width(120.dp)
                )
            }

            RgbChannelSlider(
                label = "R",
                value = red,
                trackColor = Color(0xFFE53935),
                onValueChange = { newR ->
                    onColorsChange(customColors.withRgb(activeSlot, newR, green, blue), false)
                },
                onValueChangeFinished = {
                    onColorsChange(latestColors, true)
                }
            )
            RgbChannelSlider(
                label = "G",
                value = green,
                trackColor = Color(0xFF43A047),
                onValueChange = { newG ->
                    onColorsChange(customColors.withRgb(activeSlot, red, newG, blue), false)
                },
                onValueChangeFinished = {
                    onColorsChange(latestColors, true)
                }
            )
            RgbChannelSlider(
                label = "B",
                value = blue,
                trackColor = Color(0xFF1E88E5),
                onValueChange = { newB ->
                    onColorsChange(customColors.withRgb(activeSlot, red, green, newB), false)
                },
                onValueChangeFinished = {
                    onColorsChange(latestColors, true)
                }
            )

            // Accessibility Contrast Indicator
            val textContrast = contrastRatio(Color(customColors.textArgb), Color(customColors.backgroundArgb))
            val btnContrast = contrastRatio(Color(customColors.buttonTextArgb), Color(customColors.buttonArgb))
            val lowContrast = textContrast < 4.5f || btnContrast < 4.5f
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Contrast • Text/Bg: %.1f:1 • Button: %.1f:1",
                        textContrast,
                        btnContrast
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lowContrast) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lowContrast) {
                    Text(
                        text = "⚠ Low readability (< 4.5:1)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetSeedChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RgbChannelSlider(
    label: String,
    value: Int,
    trackColor: Color,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = trackColor,
            modifier = Modifier.width(16.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor
            ),
            modifier = Modifier.weight(1f).height(24.dp)
        )
        Text(
            text = value.toString().padStart(3, ' '),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(32.dp)
        )
    }
}

private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

@Composable
private fun ThemeOptionCard(
    mode: AppThemeMode,
    customColors: CustomThemeColors,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewScheme = remember(mode, customColors) { colorSchemeForTheme(mode, customColors) }
    val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = border
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwatchDot(previewScheme.background, previewScheme.outline)
                    SwatchDot(previewScheme.primary, previewScheme.outline)
                    SwatchDot(previewScheme.onBackground, previewScheme.outline)
                }
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = mode.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = mode.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun SwatchDot(color: Color, borderColor: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, borderColor.copy(alpha = 0.6f), CircleShape)
    )
}

@Composable
private fun BuiltInFontCard(
    title: String,
    subtitle: String,
    badgeText: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = border
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (badgeText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

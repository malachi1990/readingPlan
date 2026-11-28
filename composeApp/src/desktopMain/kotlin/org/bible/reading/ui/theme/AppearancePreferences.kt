package org.bible.reading.ui.theme

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

enum class AppThemeMode(
    val id: String,
    val displayName: String,
    val description: String
) {
    DEFAULT(
        id = "default",
        displayName = "Default (Light)",
        description = "Standard light Material 3 theme"
    ),
    DARK(
        id = "dark",
        displayName = "Dark Mode",
        description = "Low-glare dark color scheme for comfortable reading"
    ),
    HIGH_CONTRAST(
        id = "high_contrast",
        displayName = "High Contrast",
        description = "Maximum foreground/background contrast for accessibility"
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom",
        description = "Customize RGB colors for each major UI component"
    );

    companion object {
        fun fromId(id: String?): AppThemeMode {
            return entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT
        }
    }
}

enum class CustomThemeColorSlot(
    val key: String,
    val displayName: String,
    val description: String
) {
    BACKGROUND("custom.background", "Background", "Main window and reading screen background"),
    SURFACE("custom.surface", "Cards & Surfaces", "Cards, dialogs, headers, and sidebars"),
    TEXT("custom.text", "Primary Text", "Body text, Scripture passages, and headings"),
    SECONDARY_TEXT("custom.secondaryText", "Secondary Text", "Subtitles, captions, and muted labels"),
    BUTTON("custom.button", "Buttons & Accent", "Filled buttons, active tabs, sliders, and links"),
    BUTTON_TEXT("custom.buttonText", "Button Text", "Text and icons inside filled buttons"),
    ACCENT_CONTAINER("custom.accentContainer", "Highlights & Chips", "Active badges, chips, and tonal buttons"),
    OUTLINE("custom.outline", "Borders & Dividers", "Outlined buttons, input borders, and dividers"),
    ERROR("custom.error", "Errors & Alerts", "Error banners and destructive action alerts")
}

data class CustomThemeColors(
    val backgroundArgb: Int = 0xFFFEF7FF.toInt(),
    val surfaceArgb: Int = 0xFFFFFFFF.toInt(),
    val textArgb: Int = 0xFF1D1B20.toInt(),
    val secondaryTextArgb: Int = 0xFF49454F.toInt(),
    val buttonArgb: Int = 0xFF6750A4.toInt(),
    val buttonTextArgb: Int = 0xFFFFFFFF.toInt(),
    val accentContainerArgb: Int = 0xFFEADDFF.toInt(),
    val outlineArgb: Int = 0xFF79747E.toInt(),
    val errorArgb: Int = 0xFFB3261E.toInt()
) {
    fun getArgb(slot: CustomThemeColorSlot): Int = when (slot) {
        CustomThemeColorSlot.BACKGROUND -> backgroundArgb
        CustomThemeColorSlot.SURFACE -> surfaceArgb
        CustomThemeColorSlot.TEXT -> textArgb
        CustomThemeColorSlot.SECONDARY_TEXT -> secondaryTextArgb
        CustomThemeColorSlot.BUTTON -> buttonArgb
        CustomThemeColorSlot.BUTTON_TEXT -> buttonTextArgb
        CustomThemeColorSlot.ACCENT_CONTAINER -> accentContainerArgb
        CustomThemeColorSlot.OUTLINE -> outlineArgb
        CustomThemeColorSlot.ERROR -> errorArgb
    }

    fun withArgb(slot: CustomThemeColorSlot, argb: Int): CustomThemeColors {
        val opaque = (argb and 0x00FFFFFF) or 0xFF000000.toInt()
        return when (slot) {
            CustomThemeColorSlot.BACKGROUND -> copy(backgroundArgb = opaque)
            CustomThemeColorSlot.SURFACE -> copy(surfaceArgb = opaque)
            CustomThemeColorSlot.TEXT -> copy(textArgb = opaque)
            CustomThemeColorSlot.SECONDARY_TEXT -> copy(secondaryTextArgb = opaque)
            CustomThemeColorSlot.BUTTON -> copy(buttonArgb = opaque)
            CustomThemeColorSlot.BUTTON_TEXT -> copy(buttonTextArgb = opaque)
            CustomThemeColorSlot.ACCENT_CONTAINER -> copy(accentContainerArgb = opaque)
            CustomThemeColorSlot.OUTLINE -> copy(outlineArgb = opaque)
            CustomThemeColorSlot.ERROR -> copy(errorArgb = opaque)
        }
    }

    fun withRgb(slot: CustomThemeColorSlot, r: Int, g: Int, b: Int): CustomThemeColors {
        return withArgb(slot, packRgb(r, g, b))
    }

    companion object {
        fun packRgb(r: Int, g: Int, b: Int): Int {
            val cr = r.coerceIn(0, 255)
            val cg = g.coerceIn(0, 255)
            val cb = b.coerceIn(0, 255)
            return (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
        }

        fun red(argb: Int): Int = (argb shr 16) and 0xFF
        fun green(argb: Int): Int = (argb shr 8) and 0xFF
        fun blue(argb: Int): Int = argb and 0xFF

        fun toHex(argb: Int): String = String.format("#%06X", argb and 0x00FFFFFF)

        fun parseHexOrNull(raw: String?): Int? {
            val cleaned = raw?.trim()?.removePrefix("#")?.uppercase().orEmpty()
            if (cleaned.length != 6) return null
            val rgb = cleaned.toIntOrNull(16) ?: return null
            return (0xFF shl 24) or (rgb and 0x00FFFFFF)
        }

        fun fromDefaultLight(): CustomThemeColors = CustomThemeColors()

        fun fromDark(): CustomThemeColors = CustomThemeColors(
            backgroundArgb = 0xFF111318.toInt(),
            surfaceArgb = 0xFF191C22.toInt(),
            textArgb = 0xFFE2E2E9.toInt(),
            secondaryTextArgb = 0xFFC3C7CF.toInt(),
            buttonArgb = 0xFF9ECAFF.toInt(),
            buttonTextArgb = 0xFF003258.toInt(),
            accentContainerArgb = 0xFF00497D.toInt(),
            outlineArgb = 0xFF8D9199.toInt(),
            errorArgb = 0xFFFFB4AB.toInt()
        )

        fun fromHighContrast(): CustomThemeColors = CustomThemeColors(
            backgroundArgb = 0xFF000000.toInt(),
            surfaceArgb = 0xFF0A0A0A.toInt(),
            textArgb = 0xFFFFFFFF.toInt(),
            secondaryTextArgb = 0xFFF2F2F2.toInt(),
            buttonArgb = 0xFF66E3FF.toInt(),
            buttonTextArgb = 0xFF000000.toInt(),
            accentContainerArgb = 0xFF004B61.toInt(),
            outlineArgb = 0xFFFFFFFF.toInt(),
            errorArgb = 0xFFFF6B6B.toInt()
        )

        fun warmSepia(): CustomThemeColors = CustomThemeColors(
            backgroundArgb = 0xFFF5ECD8.toInt(),
            surfaceArgb = 0xFFFAF3E4.toInt(),
            textArgb = 0xFF33261A.toInt(),
            secondaryTextArgb = 0xFF665341.toInt(),
            buttonArgb = 0xFF7D4E24.toInt(),
            buttonTextArgb = 0xFFFFFFFF.toInt(),
            accentContainerArgb = 0xFFE8D5B7.toInt(),
            outlineArgb = 0xFF9E876E.toInt(),
            errorArgb = 0xFFB3261E.toInt()
        )
    }
}

data class AppearancePreferences(
    val themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    val fontSelection: String = FontCatalog.EMBEDDED_UBUNTU_ID,
    val customColors: CustomThemeColors = CustomThemeColors()
)

/**
 * Persists and loads theme and font preferences in ~/.bibleReadingPlan/appearance_preferences.properties.
 */
class AppearancePreferencesManager(private val appDir: File) {

    private val prefsFile = File(appDir, "appearance_preferences.properties")

    fun loadPreferences(): AppearancePreferences {
        if (!prefsFile.exists() || !prefsFile.isFile) {
            return AppearancePreferences()
        }

        val props = Properties()
        try {
            FileInputStream(prefsFile).use { props.load(it) }
        } catch (_: Exception) {
            return AppearancePreferences()
        }

        val themeMode = AppThemeMode.fromId(props.getProperty("themeMode"))
        val rawFont = props.getProperty("fontSelection", FontCatalog.EMBEDDED_UBUNTU_ID)?.trim().orEmpty()
        val fontSelection = FontCatalog.normalizeSelection(rawFont)

        var customColors = CustomThemeColors()
        for (slot in CustomThemeColorSlot.entries) {
            val parsed = CustomThemeColors.parseHexOrNull(props.getProperty(slot.key))
            if (parsed != null) {
                customColors = customColors.withArgb(slot, parsed)
            }
        }

        return AppearancePreferences(
            themeMode = themeMode,
            fontSelection = fontSelection,
            customColors = customColors
        )
    }

    fun savePreferences(prefs: AppearancePreferences) {
        val props = Properties().apply {
            setProperty("themeMode", prefs.themeMode.id)
            setProperty("fontSelection", FontCatalog.normalizeSelection(prefs.fontSelection))
            for (slot in CustomThemeColorSlot.entries) {
                setProperty(slot.key, CustomThemeColors.toHex(prefs.customColors.getArgb(slot)))
            }
        }

        try {
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            val targetPath = prefsFile.toPath()
            if (Files.isSymbolicLink(targetPath)) {
                return
            }
            val tempFile = File(appDir, "appearance_preferences.properties.tmp")
            FileOutputStream(tempFile).use { out ->
                props.store(out, "Bible Reading Plan Appearance Settings")
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            println("Failed to save appearance_preferences.properties: ${e.message}")
        }
    }
}

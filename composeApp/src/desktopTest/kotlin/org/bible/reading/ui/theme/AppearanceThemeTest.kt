package org.bible.reading.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.runBlocking
import org.bible.reading.ui.PlanState
import org.jetbrains.compose.resources.getFontResourceBytes
import readingplan.composeapp.generated.resources.Res
import readingplan.composeapp.generated.resources.ubuntu_sans_bold
import readingplan.composeapp.generated.resources.ubuntu_sans_bold_italic
import readingplan.composeapp.generated.resources.ubuntu_sans_italic
import readingplan.composeapp.generated.resources.ubuntu_sans_light
import readingplan.composeapp.generated.resources.ubuntu_sans_light_italic
import readingplan.composeapp.generated.resources.ubuntu_sans_medium
import readingplan.composeapp.generated.resources.ubuntu_sans_medium_italic
import readingplan.composeapp.generated.resources.ubuntu_sans_regular
import readingplan.composeapp.generated.resources.ubuntu_sans_semibold
import readingplan.composeapp.generated.resources.ubuntu_sans_semibold_italic
import java.io.File
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class AppearanceThemeTest {

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = fg.luminance().toDouble()
        val l2 = bg.luminance().toDouble()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun testThemeColorSchemesAndAccessibilityContrast() {
        // Default theme matches standard Material 3 light color scheme
        val defaultScheme = colorSchemeForTheme(AppThemeMode.DEFAULT)
        val expectedLight = lightColorScheme()
        assertEquals(expectedLight.primary, defaultScheme.primary)
        assertEquals(expectedLight.background, defaultScheme.background)
        assertEquals(expectedLight.surface, defaultScheme.surface)
        assertEquals(expectedLight.onBackground, defaultScheme.onBackground)

        // Dark theme has dark background and readable foreground
        val dark = colorSchemeForTheme(AppThemeMode.DARK)
        assertTrue(dark.background.luminance() < 0.1f)
        assertTrue(dark.onBackground.luminance() > 0.7f)
        assertTrue(contrastRatio(dark.onBackground, dark.background) >= 7.0)
        assertTrue(contrastRatio(dark.onSurface, dark.surface) >= 7.0)

        // High contrast theme satisfies WCAG AAA (>= 7:1) across key pairs
        val hc = colorSchemeForTheme(AppThemeMode.HIGH_CONTRAST)
        assertEquals(Color.Black, hc.background)
        assertEquals(Color.White, hc.onBackground)
        assertTrue(contrastRatio(hc.onBackground, hc.background) >= 20.0)
        assertTrue(contrastRatio(hc.onSurface, hc.surface) >= 15.0)
        assertTrue(contrastRatio(hc.onPrimary, hc.primary) >= 10.0)
        assertTrue(contrastRatio(hc.onSecondary, hc.secondary) >= 10.0)
        assertTrue(contrastRatio(hc.onError, hc.error) >= 7.0)
        assertTrue(contrastRatio(hc.outline, hc.background) >= 15.0)
    }

    @Test
    fun testAppearancePreferencesPersistence() {
        val tempDir = Files.createTempDirectory("appearance_pref_test").toFile()
        try {
            val manager = AppearancePreferencesManager(tempDir)
            val initial = manager.loadPreferences()
            assertEquals(AppThemeMode.DEFAULT, initial.themeMode)
            assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, initial.fontSelection)

            val updated = AppearancePreferences(
                themeMode = AppThemeMode.HIGH_CONTRAST,
                fontSelection = FontCatalog.systemFontId("Serif")
            )
            manager.savePreferences(updated)

            val reloaded = manager.loadPreferences()
            assertEquals(AppThemeMode.HIGH_CONTRAST, reloaded.themeMode)
            assertEquals(FontCatalog.systemFontId("Serif"), reloaded.fontSelection)

            // Invalid theme mode falls back safely to DEFAULT
            File(tempDir, "appearance_preferences.properties").writeText("themeMode=nonexistent\nfontSelection=\n")
            val fallback = manager.loadPreferences()
            assertEquals(AppThemeMode.DEFAULT, fallback.themeMode)
            assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, fallback.fontSelection)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSystemFontDiscoveryAndCatalog() {
        val systemFonts = FontCatalog.getInstalledSystemFontFamilies()
        assertTrue(systemFonts.isNotEmpty(), "Expected at least one system font installed on host")

        val firstFont = systemFonts.first()
        val fontId = FontCatalog.systemFontId(firstFont)
        assertEquals(firstFont, FontCatalog.parseSystemFontName(fontId))
        assertEquals(firstFont, FontCatalog.displayNameForSelection(fontId))

        val family = FontCatalog.buildSystemFontFamily(firstFont)
        assertNotNull(family)

        val typo = typographyWithFontFamily(family)
        assertEquals(family, typo.bodyLarge.fontFamily)
        assertEquals(family, typo.titleLarge.fontFamily)
        assertEquals(family, typo.labelSmall.fontFamily)

        assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, FontCatalog.normalizeSelection(null))
        assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, FontCatalog.normalizeSelection("   "))
        assertEquals(FontCatalog.SYSTEM_DEFAULT_ID, FontCatalog.normalizeSelection(FontCatalog.SYSTEM_DEFAULT_ID))
    }

    @Test
    fun testEmbeddedUbuntuSansFontResourcesExist() = runBlocking {
        val resources = listOf(
            Res.font.ubuntu_sans_light,
            Res.font.ubuntu_sans_light_italic,
            Res.font.ubuntu_sans_regular,
            Res.font.ubuntu_sans_italic,
            Res.font.ubuntu_sans_medium,
            Res.font.ubuntu_sans_medium_italic,
            Res.font.ubuntu_sans_semibold,
            Res.font.ubuntu_sans_semibold_italic,
            Res.font.ubuntu_sans_bold,
            Res.font.ubuntu_sans_bold_italic
        )
        assertEquals(10, resources.size)
        val env = org.jetbrains.compose.resources.getSystemResourceEnvironment()
        for (fontRes in resources) {
            val bytes = getFontResourceBytes(env, fontRes)
            assertTrue(bytes.size > 100_000, "Expected non-empty TTF font bytes for $fontRes")
        }
    }

    @Test
    fun testPlanStateAppearanceIntegration() {
        val tempDir = Files.createTempDirectory("plan_state_appearance_test").toFile()
        try {
            val state = PlanState(planStorageDir = tempDir)
            assertEquals(AppThemeMode.DEFAULT, state.appearancePreferences.themeMode)
            assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, state.appearancePreferences.fontSelection)

            state.setThemeMode(AppThemeMode.DARK)
            state.setFontSelection(FontCatalog.SYSTEM_DEFAULT_ID)
            assertEquals(AppThemeMode.DARK, state.appearancePreferences.themeMode)
            assertEquals(FontCatalog.SYSTEM_DEFAULT_ID, state.appearancePreferences.fontSelection)

            // Reload into a fresh PlanState sharing the same directory
            val reloadedState = PlanState(planStorageDir = tempDir)
            assertEquals(AppThemeMode.DARK, reloadedState.appearancePreferences.themeMode)
            assertEquals(FontCatalog.SYSTEM_DEFAULT_ID, reloadedState.appearancePreferences.fontSelection)

            // Verify unified Settings modal routing
            assertFalse(reloadedState.showTtsSettingsDialog)
            reloadedState.openSettingsDialog(2)
            assertTrue(reloadedState.showTtsSettingsDialog)
            assertEquals(2, reloadedState.audioTtsSettingsInitialTab)

            // Reset restores defaults
            reloadedState.resetAppearancePreferences()
            assertEquals(AppThemeMode.DEFAULT, reloadedState.appearancePreferences.themeMode)
            assertEquals(FontCatalog.EMBEDDED_UBUNTU_ID, reloadedState.appearancePreferences.fontSelection)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCustomThemeRgbColorsAndScheme() {
        var colors = CustomThemeColors()
            .withRgb(CustomThemeColorSlot.BACKGROUND, 18, 24, 32)
            .withRgb(CustomThemeColorSlot.SURFACE, 28, 36, 48)
            .withRgb(CustomThemeColorSlot.TEXT, 240, 245, 250)
            .withRgb(CustomThemeColorSlot.SECONDARY_TEXT, 180, 190, 205)
            .withRgb(CustomThemeColorSlot.BUTTON, 50, 140, 220)
            .withRgb(CustomThemeColorSlot.BUTTON_TEXT, 255, 255, 255)
            .withRgb(CustomThemeColorSlot.ACCENT_CONTAINER, 32, 85, 135)
            .withRgb(CustomThemeColorSlot.OUTLINE, 110, 130, 155)
            .withRgb(CustomThemeColorSlot.ERROR, 225, 70, 70)

        assertEquals("#121820", CustomThemeColors.toHex(colors.backgroundArgb))
        assertEquals(18, CustomThemeColors.red(colors.backgroundArgb))
        assertEquals(24, CustomThemeColors.green(colors.backgroundArgb))
        assertEquals(32, CustomThemeColors.blue(colors.backgroundArgb))

        assertEquals(0xFF328CDC.toInt(), CustomThemeColors.parseHexOrNull("#328CDC"))
        assertEquals(0xFF328CDC.toInt(), CustomThemeColors.parseHexOrNull("328cdc"))
        assertNull(CustomThemeColors.parseHexOrNull("invalid"))
        assertNull(CustomThemeColors.parseHexOrNull("#12345"))

        val scheme = colorSchemeForTheme(AppThemeMode.CUSTOM, colors)
        assertEquals(Color(0xFF121820), scheme.background)
        assertEquals(Color(0xFF1C2430), scheme.surface)
        assertEquals(Color(0xFFF0F5FA), scheme.onBackground)
        assertEquals(Color(0xFFF0F5FA), scheme.onSurface)
        assertEquals(Color(0xFFB4BECD), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF328CDC), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFF205587), scheme.secondaryContainer)
        assertEquals(Color(0xFF6E829B), scheme.outline)
        assertEquals(Color(0xFFE14646), scheme.error)
    }

    @Test
    fun testCustomThemePersistenceAndPlanState() {
        val tempDir = Files.createTempDirectory("custom_theme_pref_test").toFile()
        try {
            val state = PlanState(planStorageDir = tempDir)
            val custom = CustomThemeColors.warmSepia()
                .withRgb(CustomThemeColorSlot.BUTTON, 120, 65, 25)
                .withRgb(CustomThemeColorSlot.BACKGROUND, 248, 240, 224)

            state.setThemeMode(AppThemeMode.CUSTOM)
            state.setCustomThemeColors(custom)

            val reloadedState = PlanState(planStorageDir = tempDir)
            assertEquals(AppThemeMode.CUSTOM, reloadedState.appearancePreferences.themeMode)
            assertEquals("#F8F0E0", CustomThemeColors.toHex(reloadedState.appearancePreferences.customColors.backgroundArgb))
            assertEquals("#784119", CustomThemeColors.toHex(reloadedState.appearancePreferences.customColors.buttonArgb))
            assertEquals("#33261A", CustomThemeColors.toHex(reloadedState.appearancePreferences.customColors.textArgb))

            // Corrupt single hex entry should fall back only that slot without crashing
            val prefsFile = File(tempDir, "appearance_preferences.properties")
            prefsFile.writeText(
                "themeMode=custom\n" +
                    "custom.background=NOT_HEX\n" +
                    "custom.button=#112233\n"
            )
            val manager = AppearancePreferencesManager(tempDir)
            val loaded = manager.loadPreferences()
            assertEquals(AppThemeMode.CUSTOM, loaded.themeMode)
            assertEquals(CustomThemeColors().backgroundArgb, loaded.customColors.backgroundArgb)
            assertEquals(0xFF112233.toInt(), loaded.customColors.buttonArgb)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

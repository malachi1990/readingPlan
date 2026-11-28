package org.bible.reading.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import org.jetbrains.compose.resources.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle as SkFontStyle
import org.jetbrains.skia.FontWidth
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
import java.awt.GraphicsEnvironment
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

val DefaultColorScheme: ColorScheme = lightColorScheme()

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF191C22),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

val HighContrastColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF66E3FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF004B61),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFFFEA00),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3D3700),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF9EFF),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF4F004F),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFFC4C4C4),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF7A0000),
    onErrorContainer = Color(0xFFFFFFFF)
)

private fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun bestForeground(bg: Color, preferred: Color, fallback: Color): Color {
    if (contrastRatio(preferred, bg) >= 4.5f) return preferred
    if (contrastRatio(fallback, bg) >= 4.5f) return fallback
    val whiteRatio = contrastRatio(Color.White, bg)
    val blackRatio = contrastRatio(Color.Black, bg)
    return if (whiteRatio >= blackRatio) Color.White else Color.Black
}

fun customColorScheme(custom: CustomThemeColors): ColorScheme {
    val background = Color(custom.backgroundArgb)
    val surface = Color(custom.surfaceArgb)
    val text = Color(custom.textArgb)
    val secondaryText = Color(custom.secondaryTextArgb)
    val button = Color(custom.buttonArgb)
    val buttonText = Color(custom.buttonTextArgb)
    val accentContainer = Color(custom.accentContainerArgb)
    val outline = Color(custom.outlineArgb)
    val error = Color(custom.errorArgb)

    val onAccentContainer = bestForeground(accentContainer, text, background)
    val surfaceVariant = lerp(surface, text, 0.08f)
    val tertiaryContainer = lerp(accentContainer, surface, 0.30f)
    val errorContainer = lerp(error, surface, 0.72f)
    val isDarkBg = background.luminance() < 0.45f

    val base = if (isDarkBg) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = button,
        onPrimary = buttonText,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = lerp(button, accentContainer, 0.30f),
        onSecondary = buttonText,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = lerp(button, secondaryText, 0.25f),
        onTertiary = buttonText,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = bestForeground(tertiaryContainer, text, background),
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = secondaryText,
        surfaceContainerLowest = lerp(surface, background, 0.5f),
        surfaceContainerLow = surface,
        surfaceContainer = lerp(surface, text, 0.03f),
        surfaceContainerHigh = lerp(surface, text, 0.06f),
        surfaceContainerHighest = lerp(surface, text, 0.10f),
        outline = outline,
        outlineVariant = lerp(outline, surface, 0.5f),
        error = error,
        onError = bestForeground(error, Color.White, Color.Black),
        errorContainer = errorContainer,
        onErrorContainer = bestForeground(errorContainer, text, background)
    )
}

fun colorSchemeForTheme(
    mode: AppThemeMode,
    customColors: CustomThemeColors = CustomThemeColors()
): ColorScheme = when (mode) {
    AppThemeMode.DEFAULT -> DefaultColorScheme
    AppThemeMode.DARK -> DarkColorScheme
    AppThemeMode.HIGH_CONTRAST -> HighContrastColorScheme
    AppThemeMode.CUSTOM -> customColorScheme(customColors)
}

object FontCatalog {
    const val EMBEDDED_UBUNTU_ID = "embedded:ubuntu"
    const val EMBEDDED_UBUNTU_NAME = "Ubuntu (Embedded)"
    const val EMBEDDED_UBUNTU_VERSION = "1.006"
    const val SYSTEM_DEFAULT_ID = "system:default"
    const val SYSTEM_DEFAULT_NAME = "System Default"
    const val SYSTEM_FONT_PREFIX = "system:"

    private val systemFontFamilyCache = ConcurrentHashMap<String, FontFamily>()

    fun systemFontId(familyName: String): String = "$SYSTEM_FONT_PREFIX${familyName.trim()}"

    fun parseSystemFontName(selection: String): String? {
        if (selection == EMBEDDED_UBUNTU_ID || selection == SYSTEM_DEFAULT_ID) return null
        if (!selection.startsWith(SYSTEM_FONT_PREFIX)) return null
        return selection.removePrefix(SYSTEM_FONT_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    fun normalizeSelection(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == EMBEDDED_UBUNTU_ID) return EMBEDDED_UBUNTU_ID
        if (trimmed == SYSTEM_DEFAULT_ID) return SYSTEM_DEFAULT_ID
        if (trimmed.startsWith(SYSTEM_FONT_PREFIX)) {
            val name = trimmed.removePrefix(SYSTEM_FONT_PREFIX).trim()
            return if (name.isNotEmpty() && name.length <= 200) systemFontId(name) else EMBEDDED_UBUNTU_ID
        }
        return if (trimmed.length <= 200) systemFontId(trimmed) else EMBEDDED_UBUNTU_ID
    }

    fun displayNameForSelection(selection: String): String {
        return when (val normalized = normalizeSelection(selection)) {
            EMBEDDED_UBUNTU_ID -> EMBEDDED_UBUNTU_NAME
            SYSTEM_DEFAULT_ID -> SYSTEM_DEFAULT_NAME
            else -> parseSystemFontName(normalized) ?: EMBEDDED_UBUNTU_NAME
        }
    }

    fun getInstalledSystemFontFamilies(): List<String> {
        val skiaFamilies = try {
            val mgr = FontMgr.default
            val count = mgr.familiesCount
            (0 until count)
                .mapNotNull { idx -> mgr.getFamilyName(idx).trim().takeIf { it.isNotEmpty() } }
                .distinct()
        } catch (_: Throwable) {
            emptyList()
        }

        val families = if (skiaFamilies.isNotEmpty()) {
            skiaFamilies
        } else {
            try {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }
                    .distinct()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        return families.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun isSystemFontAvailable(familyName: String): Boolean {
        return try {
            val style = SkFontStyle(400, FontWidth.NORMAL, FontSlant.UPRIGHT)
            val typeface = FontMgr.default.matchFamilyStyle(familyName, style)
            if (typeface != null) {
                typeface.close()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @OptIn(ExperimentalTextApi::class)
    fun buildSystemFontFamily(familyName: String): FontFamily {
        return systemFontFamilyCache.computeIfAbsent(familyName) { name ->
            FontFamily(
                listOf(
                    SystemFont(name, FontWeight.Light, FontStyle.Normal),
                    SystemFont(name, FontWeight.Light, FontStyle.Italic),
                    SystemFont(name, FontWeight.Normal, FontStyle.Normal),
                    SystemFont(name, FontWeight.Normal, FontStyle.Italic),
                    SystemFont(name, FontWeight.Medium, FontStyle.Normal),
                    SystemFont(name, FontWeight.Medium, FontStyle.Italic),
                    SystemFont(name, FontWeight.SemiBold, FontStyle.Normal),
                    SystemFont(name, FontWeight.SemiBold, FontStyle.Italic),
                    SystemFont(name, FontWeight.Bold, FontStyle.Normal),
                    SystemFont(name, FontWeight.Bold, FontStyle.Italic)
                )
            )
        }
    }
}

@Composable
fun rememberUbuntuSansFontFamily(): FontFamily {
    val light = Font(Res.font.ubuntu_sans_light, FontWeight.Light, FontStyle.Normal)
    val lightItalic = Font(Res.font.ubuntu_sans_light_italic, FontWeight.Light, FontStyle.Italic)
    val regular = Font(Res.font.ubuntu_sans_regular, FontWeight.Normal, FontStyle.Normal)
    val italic = Font(Res.font.ubuntu_sans_italic, FontWeight.Normal, FontStyle.Italic)
    val medium = Font(Res.font.ubuntu_sans_medium, FontWeight.Medium, FontStyle.Normal)
    val mediumItalic = Font(Res.font.ubuntu_sans_medium_italic, FontWeight.Medium, FontStyle.Italic)
    val semiBold = Font(Res.font.ubuntu_sans_semibold, FontWeight.SemiBold, FontStyle.Normal)
    val semiBoldItalic = Font(Res.font.ubuntu_sans_semibold_italic, FontWeight.SemiBold, FontStyle.Italic)
    val bold = Font(Res.font.ubuntu_sans_bold, FontWeight.Bold, FontStyle.Normal)
    val boldItalic = Font(Res.font.ubuntu_sans_bold_italic, FontWeight.Bold, FontStyle.Italic)

    return remember(
        light, lightItalic,
        regular, italic,
        medium, mediumItalic,
        semiBold, semiBoldItalic,
        bold, boldItalic
    ) {
        FontFamily(
            light, lightItalic,
            regular, italic,
            medium, mediumItalic,
            semiBold, semiBoldItalic,
            bold, boldItalic
        )
    }
}

@Composable
fun rememberSelectedFontFamily(fontSelection: String): FontFamily {
    val ubuntuSansFamily = rememberUbuntuSansFontFamily()
    return remember(fontSelection, ubuntuSansFamily) {
        when (val normalized = FontCatalog.normalizeSelection(fontSelection)) {
            FontCatalog.EMBEDDED_UBUNTU_ID -> ubuntuSansFamily
            FontCatalog.SYSTEM_DEFAULT_ID -> FontFamily.Default
            else -> {
                val systemName = FontCatalog.parseSystemFontName(normalized)
                if (systemName != null && FontCatalog.isSystemFontAvailable(systemName)) {
                    FontCatalog.buildSystemFontFamily(systemName)
                } else {
                    ubuntuSansFamily
                }
            }
        }
    }
}

fun typographyWithFontFamily(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily)
    )
}

@Composable
fun BibleReadingPlanTheme(
    themeMode: AppThemeMode,
    fontSelection: String,
    customColors: CustomThemeColors = CustomThemeColors(),
    content: @Composable () -> Unit
) {
    val colorScheme = colorSchemeForTheme(themeMode, customColors)
    val fontFamily = rememberSelectedFontFamily(fontSelection)
    val typography = remember(fontFamily) { typographyWithFontFamily(fontFamily) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

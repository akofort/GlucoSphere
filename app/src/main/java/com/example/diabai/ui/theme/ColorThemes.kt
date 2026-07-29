package com.example.diabai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.example.diabai.data.AppColorTheme

/** One fixed light/dark [ColorScheme] pair per [AppColorTheme] -- see that enum's doc comment for
 * why these replace Android 12+ dynamic color entirely instead of layering on top of it. Each
 * palette only sets primary/secondary/tertiary (Material3 derives every other role -- containers,
 * "on" colors, surface tints -- from those three), except [AppColorTheme.HIGH_CONTRAST_DARK],
 * which also pins background/surface to near-black and their "on" colors to near-white for real
 * OLED/AMOLED contrast rather than Material3's usual softer dark-theme surface tint. */
private data class ThemePalette(val light: ColorScheme, val dark: ColorScheme)

private val MedicalBlue = ThemePalette(
    light = lightColorScheme(primary = Color(0xFF1565C0), secondary = Color(0xFF0277BD), tertiary = Color(0xFF00838F)),
    dark = darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFF81D4FA), tertiary = Color(0xFF80CBC4)),
)

private val EmeraldGreen = ThemePalette(
    light = lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF388E3C), tertiary = Color(0xFF00695C)),
    dark = darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7), tertiary = Color(0xFF80CBC4)),
)

private val SunsetOrange = ThemePalette(
    light = lightColorScheme(primary = Color(0xFFE65100), secondary = Color(0xFFEF6C00), tertiary = Color(0xFFBF360C)),
    dark = darkColorScheme(primary = Color(0xFFFFB74D), secondary = Color(0xFFFFCC80), tertiary = Color(0xFFFF8A65)),
)

private val CyberPurple = ThemePalette(
    light = lightColorScheme(primary = Color(0xFF6A1B9A), secondary = Color(0xFF8E24AA), tertiary = Color(0xFFAD1457)),
    dark = darkColorScheme(primary = Color(0xFFCE93D8), secondary = Color(0xFFE1BEE7), tertiary = Color(0xFFF48FB1)),
)

private val OceanTeal = ThemePalette(
    light = lightColorScheme(primary = Color(0xFF00695C), secondary = Color(0xFF00838F), tertiary = Color(0xFF0277BD)),
    dark = darkColorScheme(primary = Color(0xFF4DB6AC), secondary = Color(0xFF80DEEA), tertiary = Color(0xFF81D4FA)),
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF69F0AE),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFFFD740),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFE0E0E0),
    outline = Color(0xFF8A8A8A),
)

/** [darkTheme] is ignored for [AppColorTheme.HIGH_CONTRAST_DARK] -- it's always dark by design
 * (see its doc comment), not just "this palette's dark variant". */
fun colorSchemeFor(theme: AppColorTheme, darkTheme: Boolean): ColorScheme = when (theme) {
    AppColorTheme.MEDICAL_BLUE -> if (darkTheme) MedicalBlue.dark else MedicalBlue.light
    AppColorTheme.EMERALD_GREEN -> if (darkTheme) EmeraldGreen.dark else EmeraldGreen.light
    AppColorTheme.SUNSET_ORANGE -> if (darkTheme) SunsetOrange.dark else SunsetOrange.light
    AppColorTheme.CYBER_PURPLE -> if (darkTheme) CyberPurple.dark else CyberPurple.light
    AppColorTheme.OCEAN_TEAL -> if (darkTheme) OceanTeal.dark else OceanTeal.light
    AppColorTheme.HIGH_CONTRAST_DARK -> HighContrastDark
}

/** The swatch color shown for [theme] in the "Erscheinungsbild" picker (see
 * SettingsOverviewScreen) -- always that theme's own primary, light variant for the 5 normal
 * palettes (matches how they'll actually look in the app's default light mode), the (already
 * dark-only) scheme's primary for [AppColorTheme.HIGH_CONTRAST_DARK]. */
fun swatchColorFor(theme: AppColorTheme): Color = colorSchemeFor(theme, darkTheme = theme == AppColorTheme.HIGH_CONTRAST_DARK).primary

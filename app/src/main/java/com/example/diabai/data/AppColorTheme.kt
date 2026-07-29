package com.example.diabai.data

import kotlinx.serialization.Serializable

/**
 * "Einstellungen -> Erscheinungsbild": one of 6 fixed Material3 color palettes the whole app is
 * themed with (see [com.example.diabai.ui.theme.GlucoSphereTheme]) -- persisted the same
 * enum-by-name way as [UserRole]/[AppLanguage]/[LlmProviderType] (see
 * [SettingsRepository.saveColorTheme]). [MEDICAL_BLUE] is the default, matching the app's
 * original (unthemed) blue-leaning Material baseline so existing installs don't visually jump on
 * first launch after this setting was introduced.
 *
 * Deliberately replaces Android 12+'s dynamic (wallpaper-derived) color entirely rather than
 * layering on top of it: a user-picked palette should look the same on every device regardless of
 * wallpaper, and mixing "sometimes dynamic, sometimes one of these 6" would make the picker's
 * effect unpredictable.
 */
@Serializable
enum class AppColorTheme(val label: String) {
    MEDICAL_BLUE("Medizinisches Blau"),
    EMERALD_GREEN("Emerald Green"),
    SUNSET_ORANGE("Sunset Orange"),
    CYBER_PURPLE("Cyber Purple"),
    OCEAN_TEAL("Ocean Teal"),
    /** Forces dark mode regardless of [com.example.diabai.ui.theme.GlucoSphereTheme]'s `darkTheme`
     * param -- near-black (`#000000`) surfaces so OLED/AMOLED panels can turn those pixels fully
     * off, plus higher-contrast text/border colors than the other 5 palettes' own dark variants. */
    HIGH_CONTRAST_DARK("High Contrast / AMOLED Dark"),
}

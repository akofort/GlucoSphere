package com.example.diabai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.diabai.data.AppColorTheme

/** Colors come entirely from [colorSchemeFor] now -- see [AppColorTheme]'s doc comment for why
 * Android 12+ dynamic (wallpaper-derived) color was deliberately dropped in favor of 6 fixed,
 * user-picked palettes. */
@Composable
fun GlucoSphereTheme(
    colorTheme: AppColorTheme = AppColorTheme.MEDICAL_BLUE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(colorTheme, darkTheme),
        typography = Typography,
        content = content
    )
}

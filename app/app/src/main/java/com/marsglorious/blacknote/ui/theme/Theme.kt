package com.marsglorious.blacknote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BlackNoteScheme = darkColorScheme(
    primary       = MdColors.Accent,
    onPrimary     = MdColors.Background,
    background    = MdColors.Background,
    onBackground  = MdColors.OnSurface,
    surface       = MdColors.Surface,
    onSurface     = MdColors.OnSurface,
    surfaceVariant = MdColors.SurfaceHi,
    onSurfaceVariant = MdColors.OnSurfaceDim,
    outline       = MdColors.Divider,
)

@Composable
fun BlackNoteTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(ctx).copy(background = MdColors.Background, surface = MdColors.Surface)
        else -> BlackNoteScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = MdColors.Background.toArgb()
            window.navigationBarColor = MdColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = scheme, typography = BlackNoteTypography, content = content)
}

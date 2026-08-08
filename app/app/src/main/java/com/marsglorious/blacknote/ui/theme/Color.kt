package com.marsglorious.blacknote.ui.theme

import androidx.compose.ui.graphics.Color

object MdColors {
    val Background     = Color(0xFF0F0F0F)
    val Surface        = Color(0xFF181818)
    val SurfaceHi      = Color(0xFF222222)
    val SurfaceHi2     = Color(0xFF2A2A2A)
    val OnSurface      = Color(0xFFECECEC)
    val OnSurfaceDim   = Color(0xFFA0A0A0)
    val OnSurfaceFaint = Color(0xFF6B6B6B)
    val Accent         = Color(0xFF8AB4F8)
    val Divider        = Color(0x14FFFFFF)
    val LabelChipBg    = Color(0xFF2D3950)
    val LabelChipFg    = Color(0xFFB0C8F0)
    // Folder rows use a distinct deep-blue tone so they read as a different kind of
    // entry from the grey note cards. Expanded state is a touch brighter so the user
    // sees which folders are currently revealing their children.
    val FolderBg         = Color(0xFF1A2740)
    val FolderBgExpanded = Color(0xFF233454)
    val FolderFg         = Color(0xFFCFE0FF)
    // Destructive confirmations (delete forever, empty trash).
    val DangerBg = Color(0xFF4A2226)
    val DangerFg = Color(0xFFF2B8BB)

    // Each folder is assigned one of these accent colours, used as a left edge-stripe on the
    // folder row and on every note that lives inside it — so membership reads by colour rather
    // than by deep indentation. Muted but distinct against the near-black background.
    val FolderPalette = listOf(
        Color(0xFF8AB4F8), // blue
        Color(0xFF81C995), // green
        Color(0xFFF7A76C), // orange
        Color(0xFFC58AF9), // purple
        Color(0xFFF28B94), // rose
        Color(0xFF78D9EC), // cyan
        Color(0xFFF6C445), // amber
        Color(0xFF9DA7F2), // indigo
        Color(0xFF6FD0B6), // teal
        Color(0xFFE68FD0), // magenta
    )

    /** Stable colour for a folder, derived from its path so it never shifts between sessions. */
    fun folderColor(path: String): Color =
        FolderPalette[(path.hashCode() and 0x7FFFFFFF) % FolderPalette.size]
}

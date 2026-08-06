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
}

package com.marsglorious.blacknote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BlackNoteTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = MdColors.OnSurface),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = MdColors.OnSurface),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, color = MdColors.OnSurface, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, color = MdColors.OnSurfaceDim, lineHeight = 19.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, color = MdColors.OnSurfaceDim),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, color = MdColors.LabelChipFg),
)

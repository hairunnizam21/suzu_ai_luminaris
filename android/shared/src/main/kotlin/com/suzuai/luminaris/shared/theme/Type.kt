package com.suzuai.luminaris.shared.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif

val LuminarisTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
)

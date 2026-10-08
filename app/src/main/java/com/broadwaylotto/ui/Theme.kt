package com.broadwaylotto.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.broadwaylotto.model.Site

// Marquee palette: midnight ink, warm gold bulbs, curtain crimson.
val Gold = Color(0xFFE6B450)
val Crimson = Color(0xFFC2344D)
val Ink = Color(0xFF13101A)
val Panel = Color(0xFF1E1A27)
val PanelHigh = Color(0xFF2B2638)
val Cream = Color(0xFFF3ECDD)
val Muted = Color(0xFFA89FB8)
val Good = Color(0xFF5FC28A)
val Warn = Color(0xFFF0A04B)
val Bad = Color(0xFFFF6B6B)

fun Site.accent(): Color = when (this) {
    Site.BROADWAY_DIRECT -> Color(0xFFE07A3F)
    Site.TODAYTIX -> Color(0xFF3FB8AF)
    Site.LUCKY_SEAT -> Color(0xFF9B7BE0)
}

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = PanelHigh,
    onPrimaryContainer = Gold,
    secondary = Crimson,
    onSecondary = Cream,
    background = Ink,
    onBackground = Cream,
    surface = Panel,
    onSurface = Cream,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    outline = Color(0xFF4A4358),
    outlineVariant = Color(0xFF332D40),
    error = Bad,
)

private val base = Typography()
private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LottoTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)

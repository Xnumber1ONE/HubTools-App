package com.gamestats.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0A0C12)        // near-black with a blue tint
val CardBg = Color(0xFF141824)
val CardHi = Color(0xFF1E2434)
val Outline = Color(0xFF262D42)
val Track = Color(0xFF232A3E)
val Accent = Color(0xFF7B61FF)    // electric violet
val Cyan = Color(0xFF2DD4F0)
val Gold = Color(0xFFFFC857)      // trophies
val Muted = Color(0xFF8A93A8)
val TextMain = Color(0xFFF1F4FA)

@Composable
fun GameStatsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Cyan,
            tertiary = Gold,
            background = Bg,
            onBackground = TextMain,
            surface = CardBg,
            onSurface = TextMain,
            surfaceVariant = CardHi,
            onSurfaceVariant = Muted,
            outline = Outline,
            outlineVariant = Outline,
            primaryContainer = Accent,
            secondaryContainer = Color(0xFF2A2563),
            onSecondaryContainer = TextMain,
            error = Color(0xFFFF6B6B),
        ),
        content = content,
    )
}

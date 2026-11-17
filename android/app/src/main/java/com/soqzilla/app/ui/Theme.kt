package com.soqzilla.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soqzilla.app.R

/** Tokens: warm paper, near-black ink, one signal orange. Light and dark follow the phone. */
data class Palette(
    val paper: Color, val panel: Color, val ink: Color, val mute: Color,
    val signal: Color, val onSignal: Color, val warn: Color, val hot: Color, val dark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFE9E6DF), panel = Color(0xFFDFDBD2), ink = Color(0xFF0F0F0E), mute = Color(0xFF5C5850),
    signal = Color(0xFFFF4A1C), onSignal = Color(0xFF0F0F0E), warn = Color(0xFF8A5300), hot = Color(0xFFB3261E), dark = false,
)
val DarkPalette = Palette(
    paper = Color(0xFF0F0F0E), panel = Color(0xFF181816), ink = Color(0xFFE9E6DF), mute = Color(0xFF98938A),
    signal = Color(0xFFFF4A1C), onSignal = Color(0xFF0F0F0E), warn = Color(0xFFFFC857), hot = Color(0xFFFF6B5E), dark = true,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// The names the screens use, read from the current palette
val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.paper
val PanelColor: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.panel
val Fg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Rule: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Mute: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.mute
val Signal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.signal
val OnSignal: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onSignal
val Warn: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warn
val Hot: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.hot

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

@OptIn(ExperimentalTextApi::class)
private fun jetbrains(weight: Int) = Font(
    R.font.jetbrains_mono, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Archivo for text; the display cut is heavy and fully wide (125%). */
val Sans = FontFamily(archivo(400, 100f), archivo(600, 100f), archivo(700, 100f))
val Display = FontFamily(archivo(800, 125f))
val Mono = FontFamily(jetbrains(400), jetbrains(600))

private fun scheme(p: Palette) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.signal, onPrimary = p.onSignal, secondary = p.signal,
    background = p.paper, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
    surfaceVariant = p.panel, onSurfaceVariant = p.mute, surfaceContainer = p.paper, surfaceContainerHigh = p.panel,
    outline = p.ink, outlineVariant = p.mute, error = p.hot,
)

private val type = Typography().let { t ->
    Typography(
        bodyLarge = t.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = t.bodyMedium.copy(fontFamily = Sans),
        bodySmall = t.bodySmall.copy(fontFamily = Sans),
        titleLarge = t.titleLarge.copy(fontFamily = Display),
        titleMedium = t.titleMedium.copy(fontFamily = Sans),
        labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 12.sp, letterSpacing = 1.sp),
        labelMedium = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp),
        labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.8.sp),
    )
}

// Square corners everywhere
private val square = RoundedCornerShape(0.dp)
private val shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)

/** [mode]: "system" follows the phone, or "light" / "dark". */
@Composable
fun SoqzillaTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p), typography = type, shapes = shapes, content = content)
    }
}

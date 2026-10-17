package com.nascon.league.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Matchday palette. Turf is only the fallback: the signed-in tenant's colour replaces it.
object Pitch {
    val Chalk = Color(0xFFF6F8F5)      // background, cool pitch-line white
    val Ink = Color(0xFF16212B)        // text
    val Muted = Color(0xFF55626D)      // secondary text (4.5:1 on Chalk)
    val Line = Color(0xFFDCE2DA)       // hairlines
    val Wash = Color(0xFFEAEFE8)       // quiet fills (chips, inputs)
    val Turf = Color(0xFF1E6B4B)       // default brand colour
    val Floodlight = Color(0xFFFFD23F) // the "live" marker only
    val Whistle = Color(0xFFC62F2F)    // errors and offline
    val Win = Color(0xFF1E7A46)        // clinched / applied
}

/** Android's built-in condensed sans: scoreboard numerals and headings, no font download needed. */
val Condensed = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold),
)

/** Tabular figures so score and table columns line up. */
const val TABULAR = "tnum"

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 64.sp, fontFeatureSettings = TABULAR),
    headlineMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

fun parseHexColor(hex: String?): Color? = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrNull()

/** Readable text colour on top of any tenant colour. */
fun contentOn(color: Color): Color = if (color.luminance() > 0.45f) Pitch.Ink else Color.White

@Composable
fun LeagueTheme(brand: Color = Pitch.Turf, content: @Composable () -> Unit) {
    val onBrand = contentOn(brand)
    val scheme = lightColorScheme(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = brand.copy(alpha = 0.12f).compositeOver(Pitch.Chalk),
        onPrimaryContainer = Pitch.Ink,
        secondary = Pitch.Ink,
        onSecondary = Color.White,
        secondaryContainer = Pitch.Wash,
        onSecondaryContainer = Pitch.Ink,
        background = Pitch.Chalk,
        onBackground = Pitch.Ink,
        surface = Pitch.Chalk,
        onSurface = Pitch.Ink,
        surfaceVariant = Pitch.Wash,
        onSurfaceVariant = Pitch.Muted,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Pitch.Wash,
        surfaceContainerLow = Color.White,
        outline = Pitch.Muted,
        outlineVariant = Pitch.Line,
        error = Pitch.Whistle,
        onError = Color.White,
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

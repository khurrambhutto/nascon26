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

// ArenaOne palette (from mobile/design mockup): warm paper, deep teal ink, terracotta, mint and peach.
// Primary is the signed-in tenant's colour; Turf is only the fallback before login.
object Pitch {
    val Chalk = Color(0xFFFFF7EE)      // paper background
    val Card = Color(0xFFFFFFFF)       // raised cards and inputs
    val Ink = Color(0xFF293D3A)        // text, dark surfaces
    val Muted = Color(0xFF6F675F)      // secondary text (4.6:1 on paper)
    val Line = Color(0xFFEADED2)       // hairlines and borders
    val Edge = Color(0xFFDECFC0)       // darker bottom edge of chunky pills/cards
    val Wash = Color(0xFFF7EBDF)       // quiet fills
    val Turf = Color(0xFFB84432)       // terracotta fallback brand
    val Mint = Color(0xFFBCE8D7)       // positive tiles, club banner
    val MintSoft = Color(0xFFE0EEE6)   // soft mint fill (highlight rows)
    val MintDeep = Color(0xFF326B59)   // text/icons on mint, "online"
    val Peach = Color(0xFFF8DDCC)      // product art, number badges
    val Floodlight = Color(0xFFEE644C) // the "live" dot
    val Whistle = Color(0xFFC0392B)    // errors and offline
    val Win = Color(0xFF2F7A5C)        // clinched / applied
}

/** A slightly darker shade of a colour, used for the chunky bottom edge of buttons and cards. */
fun Color.edge(): Color = Color(red * 0.78f, green * 0.78f, blue * 0.78f, alpha)

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
        surfaceContainer = Pitch.Card,
        surfaceContainerHigh = Pitch.Card,
        surfaceContainerHighest = Pitch.Wash,
        surfaceContainerLow = Pitch.Card,
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

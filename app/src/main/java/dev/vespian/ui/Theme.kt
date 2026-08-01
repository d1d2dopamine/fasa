package dev.vespian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette. Deliberately no violet anywhere.
// Night sky blues for surfaces, teal for the primary signal,
// amber for the warm accent that marks "act now" moments.

val Teal = Color(0xFF4FD1C5)
val TealDim = Color(0xFF2B8A80)
val Amber = Color(0xFFFFB74D)
val AmberDim = Color(0xFF8A6224)
val Coral = Color(0xFFFF7B72)
val Ink = Color(0xFF0A1018)
val Slate = Color(0xFF141E2C)
val SlateHi = Color(0xFF1D2A3C)
val Mist = Color(0xFFE4EAF2)
val MistDim = Color(0xFF97A6BA)

private val NightScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00312C),
    primaryContainer = Color(0xFF12433E),
    onPrimaryContainer = Color(0xFFA7EFE7),
    secondary = Amber,
    onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF4A3410),
    onSecondaryContainer = Color(0xFFFFDEB0),
    tertiary = Color(0xFF7FB2E5),
    onTertiary = Color(0xFF00305A),
    background = Ink,
    onBackground = Mist,
    surface = Slate,
    onSurface = Mist,
    surfaceVariant = SlateHi,
    onSurfaceVariant = MistDim,
    outline = Color(0xFF3A4C64),
    outlineVariant = Color(0xFF26344A),
    error = Coral,
    onError = Color(0xFF4A0A06),
)

private val DayScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8EDE6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEB0),
    onSecondaryContainer = Color(0xFF2C1A00),
    tertiary = Color(0xFF2A5F94),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF10161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10161F),
    surfaceVariant = Color(0xFFE5EAF2),
    onSurfaceVariant = Color(0xFF4A5769),
    outline = Color(0xFF9AA7B8),
    error = Color(0xFFB3261E),
)

private val VespianTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 56.sp,
        letterSpacing = (-1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun VespianTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // No dynamic colour on purpose. Material You derives every role from one
    // wallpaper hue, so primary, secondary and tertiary collapse into
    // neighbouring tones. The day ring separates three arcs by colour alone,
    // and a monochrome palette makes it unreadable.
    MaterialTheme(
        colorScheme = if (dark) NightScheme else DayScheme,
        typography = VespianTypography,
        content = content,
    )
}

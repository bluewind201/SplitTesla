package com.splittesla.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.splittesla.app.R

/** Night cockpit palette: near-black surfaces, the app icon's lime as the one accent. */
object Neon {
    val bg = Color(0xFF07080A)
    val surface = Color(0xFF111317)
    val surfaceHi = Color(0xFF1A1D23)
    val stroke = Color(0xFF262A32)
    val strokeHi = Color(0xFF3A404B)

    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFF8B93A1)
    val faint = Color(0xFF4A505B)

    val lime = Color(0xFFC6FF3D)
    val cyan = Color(0xFF35E0FF)
    val red = Color(0xFFFF3B3B)
    val amber = Color(0xFFFFB320)
    val green = Color(0xFF3DFF8A)
    val blue = Color(0xFF4D7CFF)
}

/** Display face for numbers and English labels; Korean text falls back to the system font. */
val Orbitron = FontFamily(
    Font(R.font.orbitron_semibold, FontWeight.SemiBold),
    Font(R.font.orbitron_heavy, FontWeight.ExtraBold),
)

/** Narrow technical face for small captions. */
val Rajdhani = FontFamily(
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

private val colors = darkColorScheme(
    primary = Neon.lime,
    onPrimary = Color.Black,
    secondary = Neon.cyan,
    onSecondary = Color.Black,
    background = Neon.bg,
    onBackground = Neon.text,
    surface = Neon.surface,
    onSurface = Neon.text,
    surfaceVariant = Neon.surfaceHi,
    onSurfaceVariant = Neon.muted,
    surfaceContainer = Neon.surface,
    surfaceContainerHigh = Neon.surfaceHi,
    surfaceContainerHighest = Neon.surfaceHi,
    secondaryContainer = Neon.surfaceHi,
    onSecondaryContainer = Neon.text,
    outline = Neon.strokeHi,
    outlineVariant = Neon.stroke,
    error = Neon.red,
    onError = Color.White,
)

private val base = Typography()

private val typography = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.sp),
)

@Composable
fun SplitTheme(content: @Composable () -> Unit) {
    // Plain Text outside a Surface takes LocalContentColor, which defaults to black;
    // set it to the light text colour so nothing renders black on the dark background.
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides Neon.text, content = content)
    }
}

/** Small uppercase caption above a value, e.g. "DRIVE MODE". */
val Caption = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = Neon.muted)

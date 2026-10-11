package dev.ahmed.spotter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.spotter.engine.model.EngineSettings

/**
 * Spotter design system. A deep-purple identity (the launcher icon colour) with a coloured top bar, dense list rows and
 * state colours that carry meaning: orange = needs attention, grey = finished, green = success.
 */
@androidx.compose.runtime.Immutable
data class SpotterColors(
    val topBar: Color,
    val onTopBar: Color,
    val topBarMuted: Color,
    val rowError: Color,
    val onRowError: Color,
    val rowDone: Color,
    val rowSelected: Color,
    val success: Color,
    val warning: Color,
    val bottomBar: Color,
    val addressPill: Color,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5E35B1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFFF0EBF7),
    surfaceContainerHigh = Color(0xFFEAE4F1),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = Color(0xFF4DB6AC),
    background = Color(0xFF14121A),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF14121A),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
)

private val LightSpotter = SpotterColors(
    topBar = Color(0xFF4527A0),
    onTopBar = Color.White,
    topBarMuted = Color(0xFFD1C4E9),
    rowError = Color(0xFFFFCC80),
    onRowError = Color(0xFF4E2600),
    rowDone = Color(0xFFEDE9F3),
    rowSelected = Color(0xFFD9CCF5),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFE65100),
    bottomBar = Color(0xFFF0EBF7),
    addressPill = Color(0xFF5E3DB0),
)

private val DarkSpotter = SpotterColors(
    topBar = Color(0xFF2B2250),
    onTopBar = Color.White,
    topBarMuted = Color(0xFFB9ABE0),
    rowError = Color(0xFF5B3A12),
    onRowError = Color(0xFFFFDDB2),
    rowDone = Color(0xFF1E1C25),
    rowSelected = Color(0xFF3B3160),
    success = Color(0xFF81C784),
    warning = Color(0xFFFFB74D),
    bottomBar = Color(0xFF1B1922),
    addressPill = Color(0xFF3B3068),
)

private val SpotterTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val SpotterShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

val LocalSpotterColors = staticCompositionLocalOf { LightSpotter }

object SpotterTheme {
    val colors: SpotterColors
        @Composable @ReadOnlyComposable get() = LocalSpotterColors.current
}

fun resolveDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
    EngineSettings.THEME_LIGHT -> false
    EngineSettings.THEME_DARK -> true
    else -> systemDark
}

@Composable
fun SpotterTheme(theme: String, content: @Composable () -> Unit) {
    val dark = resolveDark(theme, isSystemInDarkTheme())
    val scheme: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalSpotterColors provides if (dark) DarkSpotter else LightSpotter) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SpotterTypography,
            shapes = SpotterShapes,
            content = content,
        )
    }
}

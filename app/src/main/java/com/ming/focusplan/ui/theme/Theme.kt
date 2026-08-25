package com.ming.focusplan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF176F65),
    onPrimary = Color(0xFFF7FFFC),
    primaryContainer = Color(0xFFCBEDE6),
    onPrimaryContainer = Color(0xFF123B35),
    secondary = Color(0xFF536B68),
    onSecondary = Color(0xFFF7FFFC),
    secondaryContainer = Color(0xFFDDE9E7),
    onSecondaryContainer = Color(0xFF263B38),
    tertiary = Color(0xFF6E657D),
    tertiaryContainer = Color(0xFFE9E2F1),
    onTertiaryContainer = Color(0xFF342D40),
    background = Color(0xFFF2F6F5),
    onBackground = Color(0xFF202827),
    surface = Color(0xFFFAFCFB),
    onSurface = Color(0xFF202827),
    surfaceVariant = Color(0xFFE3EBE9),
    onSurfaceVariant = Color(0xFF4A5956),
    outline = Color(0xFF71817E),
    outlineVariant = Color(0xFFC3CECB),
    error = Color(0xFF9F4039),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF3F0806)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF70D6C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF145148),
    onPrimaryContainer = Color(0xFFB5F2E6),
    secondary = Color(0xFFB7CDC8),
    onSecondary = Color(0xFF233431),
    secondaryContainer = Color(0xFF354B47),
    onSecondaryContainer = Color(0xFFD3E9E4),
    tertiary = Color(0xFFD1C5DF),
    tertiaryContainer = Color(0xFF4C4359),
    onTertiaryContainer = Color(0xFFEDE3F8),
    background = Color(0xFF101716),
    onBackground = Color(0xFFDCE5E2),
    surface = Color(0xFF151E1C),
    onSurface = Color(0xFFDCE5E2),
    surfaceVariant = Color(0xFF354340),
    onSurfaceVariant = Color(0xFFBAC9C5),
    outline = Color(0xFF859692),
    outlineVariant = Color(0xFF3C4B48),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF7D2A25),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val FocusPlanTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 31.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

private val FocusPlanShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
)

@Composable
fun FocusPlanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = FocusPlanTypography,
        shapes = FocusPlanShapes,
        content = content
    )
}

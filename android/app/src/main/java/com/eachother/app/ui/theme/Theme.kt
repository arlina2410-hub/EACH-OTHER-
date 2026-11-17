package com.eachother.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5F5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFECE8),
    onPrimaryContainer = Color(0xFF0A2E2B),
    secondary = Color(0xFF3E5C76),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8F2),
    onSecondaryContainer = Color(0xFF132636),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFE7ECEA),
    onSurfaceVariant = Color(0xFF424C4A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D5CE),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF0E4D48),
    onPrimaryContainer = Color(0xFFCFECE8),
    secondary = Color(0xFFA9C7E3),
    onSecondary = Color(0xFF0E2B42),
    background = Color(0xFF101514),
    onBackground = Color(0xFFE1E6E4),
    surface = Color(0xFF171D1C),
    onSurface = Color(0xFFE1E6E4),
    surfaceVariant = Color(0xFF28312F),
    onSurfaceVariant = Color(0xFFBFC9C6),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

private val EachOtherTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun EachOtherTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EachOtherTypography,
        content = content
    )
}

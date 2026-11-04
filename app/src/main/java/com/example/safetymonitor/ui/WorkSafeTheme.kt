package com.example.safetymonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val WorkSafeBrandAccent = Color(0xFFFFC700)

private val LightColors = lightColorScheme(
    primary = Color(0xFFAA8E35),
    onPrimary = Color.White,
    secondary = WorkSafeBrandAccent,
    onSecondary = Color(0xFF191918),
    tertiary = Color(0xFF4D855E),
    onTertiary = Color.White,
    background = Color(0xFFF5F4F0),
    onBackground = Color(0xFF191918),
    surface = Color.White,
    onSurface = Color(0xFF191918),
    surfaceVariant = Color(0xFFE6E4DE),
    onSurfaceVariant = Color(0xFF6E6C64),
    outline = Color(0xFFB9BAC3),
    error = Color(0xFF9F4842),
    onError = Color.White,
    errorContainer = Color(0xFFF1D7CF),
    onErrorContainer = Color(0xFF241A18)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB89C4A),
    onPrimary = Color(0xFF141412),
    secondary = Color(0xFF242421),
    onSecondary = Color(0xFFE8E6DB),
    tertiary = Color(0xFF639C73),
    onTertiary = Color(0xFF131312),
    background = Color(0xFF131312),
    onBackground = Color(0xFFE8E6DB),
    surface = Color(0xFF242421),
    onSurface = Color(0xFFE8E6DB),
    surfaceVariant = Color(0xFF30302B),
    onSurfaceVariant = Color(0xFFB5B2A7),
    outline = Color(0xFF74746B),
    error = Color(0xFFB86663),
    onError = Color(0xFF131312),
    errorContainer = Color(0xFF4E2826),
    onErrorContainer = Color(0xFFE8E6DB)
)

private val WorkSafeTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
)

@Composable
fun WorkSafeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WorkSafeTypography,
        content = content
    )
}

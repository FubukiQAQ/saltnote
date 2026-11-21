package com.saltnote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Exact Salt Halo 3-color semantic accents from MVP Section 3.3
val SaltHaloAmber = Color(0xFFF5C242)   // 琥珀黄 · 概念扩充
val SaltHaloGlacier = Color(0xFF4EA8DE) // 冰川蓝 · 提炼闪卡
val SaltHaloMineral = Color(0xFF8D99AE) // 矿石灰 · OCR 笔迹清洗

val SaltPrivacyAmber = Color(0xFFB45309)

private val SaltLightColorScheme = lightColorScheme(
    primary = Color(0xFF1A657A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2E8F5),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF4D6169),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E6EF),
    onSecondaryContainer = Color(0xFF091E25),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DAFF),
    onTertiaryContainer = Color(0xFF251432),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE0E5E8),
    onSurfaceVariant = Color(0xFF42484C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3EE),
    surfaceContainer = Color(0xFFEEEDE7),
    surfaceContainerHigh = Color(0xFFE8E7E0),
    surfaceContainerHighest = Color(0xFFE2E1DA),
    outline = Color(0xFF72787C),
    outlineVariant = Color(0xFFC3C8CC),
)

private val SaltDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8CCEE3),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFC2E8F5),
    secondary = Color(0xFFB4CAD3),
    onSecondary = Color(0xFF1F333B),
    secondaryContainer = Color(0xFF354A52),
    onSecondaryContainer = Color(0xFFD0E6EF),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF3DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF41484C),
    onSurfaceVariant = Color(0xFFC1C7CC),
    surfaceContainerLowest = Color(0xFF0C0F11),
    surfaceContainerLow = Color(0xFF191C1E),
    surfaceContainer = Color(0xFF1D2023),
    surfaceContainerHigh = Color(0xFF282B2E),
    surfaceContainerHighest = Color(0xFF323639),
    outline = Color(0xFF8B9296),
    outlineVariant = Color(0xFF41484C),
)

private val SaltTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val SaltShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SaltNoteTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SaltDarkColorScheme else SaltLightColorScheme,
        typography = SaltTypography,
        shapes = SaltShapes,
        content = content,
    )
}

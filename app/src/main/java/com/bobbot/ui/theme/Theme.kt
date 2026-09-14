package com.bobbot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = BobColors.Accent,
    onPrimary = BobColors.Bg,
    primaryContainer = BobColors.AccentSoft,
    onPrimaryContainer = BobColors.Text,
    secondary = BobColors.Mint,
    onSecondary = BobColors.Bg,
    secondaryContainer = BobColors.MintSoft,
    onSecondaryContainer = BobColors.Text,
    tertiary = BobColors.Amber,
    onTertiary = BobColors.Bg,
    tertiaryContainer = BobColors.AmberSoft,
    onTertiaryContainer = BobColors.Text,
    error = BobColors.Rose,
    onError = BobColors.Bg,
    errorContainer = BobColors.RoseSoft,
    onErrorContainer = BobColors.Text,
    background = BobColors.Bg,
    onBackground = BobColors.Text,
    surface = BobColors.Bg,
    onSurface = BobColors.Text,
    surfaceVariant = BobColors.SurfaceRaised,
    onSurfaceVariant = BobColors.TextMuted,
    surfaceContainerLowest = BobColors.Bg,
    surfaceContainerLow = BobColors.Surface,
    surfaceContainer = BobColors.SurfaceRaised,
    surfaceContainerHigh = BobColors.SurfaceHigh,
    surfaceContainerHighest = BobColors.SurfaceHigh,
    outline = BobColors.Outline,
    outlineVariant = BobColors.OutlineSoft,
    inverseSurface = BobColors.Text,
    inverseOnSurface = BobColors.Bg,
    scrim = BobColors.Bg,
)

private val BobTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val BobShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BobBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = BobTypography,
        shapes = BobShapes,
        content = content,
    )
}

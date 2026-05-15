package com.trirrin.xiaoshuo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = ForestTeal,
    onPrimary = SurfaceWhite,
    primaryContainer = ForestTealLight,
    onPrimaryContainer = ForestTealDark,

    secondary = WarmUmber,
    onSecondary = SurfaceWhite,
    secondaryContainer = WarmUmberLight,
    onSecondaryContainer = WarmUmberDark,

    tertiary = SteelBlue,
    onTertiary = SurfaceWhite,
    tertiaryContainer = SteelBlueLight,
    onTertiaryContainer = SteelBlueDark,

    error = ErrorRed,
    onError = SurfaceWhite,
    errorContainer = ErrorRedLight,
    onErrorContainer = Color(0xFF410002),

    background = PaperWhite,
    onBackground = NearBlack,

    surface = SurfaceWhite,
    onSurface = NearBlack,
    surfaceVariant = SageTint,
    onSurfaceVariant = MutedText,

    outline = SoftBorder,
    outlineVariant = Color(0xFFE2E5E0),

    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = Color(0xFFF5F6F3),
    surfaceContainer = Color(0xFFF0F2EE),
    surfaceContainerHigh = Color(0xFFE8EBE8),
    surfaceContainerHighest = Color(0xFFE2E5E0),
)

private val XiaoShuoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun XiaoShuoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = XiaoShuoTypography,
        shapes = XiaoShuoShapes,
        content = content,
    )
}
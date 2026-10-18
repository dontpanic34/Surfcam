package com.surfcam.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Palette sombre : lisible en plein soleil, et économe en batterie sur écran OLED. */
object SurfColors {
    val Night = Color(0xFF0B1620)
    val Deep = Color(0xFF12232F)
    val Card = Color(0xFF1A3140)
    val Aqua = Color(0xFF2BD9C4)
    val OnAqua = Color(0xFF00201C)
    val Foam = Color(0xFFE9F3F5)
    val Muted = Color(0xFF8FA6B2)
    val Ok = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFC857)
    val Alert = Color(0xFFFF6B6B)
    val Info = Color(0xFF6FB7FF)
}

private val scheme = darkColorScheme(
    primary = SurfColors.Aqua,
    onPrimary = SurfColors.OnAqua,
    secondary = SurfColors.Info,
    background = SurfColors.Night,
    onBackground = SurfColors.Foam,
    surface = SurfColors.Deep,
    onSurface = SurfColors.Foam,
    surfaceVariant = SurfColors.Card,
    onSurfaceVariant = SurfColors.Muted,
    error = SurfColors.Alert,
    onError = Color(0xFF2B0000),
    outline = Color(0xFF3C5666),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun SurfCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

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
    val Night = Color(0xFF0A1219)
    val Deep = Color(0xFF121D27)
    val Card = Color(0xFF1A2936)
    val Line = Color(0xFF26394A)
    val Aqua = Color(0xFF3CC8B4)
    val OnAqua = Color(0xFF04211D)
    val Foam = Color(0xFFEEF4F6)
    val Muted = Color(0xFF8A9EAB)
    val Ok = Color(0xFF46D39A)
    val Warn = Color(0xFFFFC24D)
    val Alert = Color(0xFFFF5A52)
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
    outline = SurfColors.Line,
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

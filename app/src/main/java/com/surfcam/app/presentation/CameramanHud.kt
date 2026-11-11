package com.surfcam.app.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.engine.EngineState
import com.surfcam.app.presentation.theme.SurfColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Affichage de tournage façon caméraman, par-dessus l'image : horizon artificiel (niveau du téléphone), état du pilote,
 * objectif réellement utilisé (le ×5 doit être optique) et voyant d'enregistrement. Peu de texte, lisible au soleil.
 */
@Composable
fun CameramanHud(state: EngineState, modifier: Modifier = Modifier) {
    val roll by rememberHorizonAngle()
    Box(modifier) {
        // Horizon artificiel : une ligne qui reste horizontale dans le monde ; verte quand le téléphone est de niveau.
        roll?.let { angle ->
            val color = when {
                abs(angle) <= 0.5f -> SurfColors.Ok
                abs(angle) <= 1.5f -> SurfColors.Warn
                else -> SurfColors.Alert
            }
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val half = size.width * 0.32f
                val a = Math.toRadians(-angle.toDouble())
                val dx = (cos(a) * half).toFloat()
                val dy = (sin(a) * half).toFloat()
                drawLine(color.copy(alpha = 0.85f), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f)))
                drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x - 24f, c.y), Offset(c.x + 24f, c.y), strokeWidth = 2f)
            }
            HudChip(
                "Niveau %+.1f°".format(angle).replace('.', ','),
                color,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            )
        }
        // État du pilote, en haut à gauche.
        val pilot = state.followNote.substringBefore(" · ").ifBlank { null }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pilot != null) {
                val color = when {
                    state.clipActive -> SurfColors.Alert
                    pilot.startsWith("Suivi") -> SurfColors.Ok
                    pilot.startsWith("Recherche") || pilot.startsWith("Accrochage") -> SurfColors.Info
                    pilot.startsWith("Cible perdue") -> SurfColors.Warn
                    else -> SurfColors.Foam
                }
                HudChip(pilot, color)
            }
        }
        // Objectif et enregistrement, en haut à droite.
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.clipActive) {
                Box(Modifier.size(10.dp).background(SurfColors.Alert, CircleShape))
                Spacer(Modifier.width(6.dp))
                HudChip("REC", SurfColors.Alert)
                Spacer(Modifier.width(8.dp))
            }
            // Pendant le tournage, l'image vient du téléobjectif : ×5 par rapport à l'objectif principal.
            val shownZoom = if (state.lens == com.surfcam.app.data.camera.Lens.TELE) state.zoom * 5f else state.zoom
            val zoomText = "×%.1f".format(shownZoom).replace(".0", "").replace('.', ',')
            val optical = state.opticalZoom
            HudChip(
                if (optical) "$zoomText optique" else "$zoomText numérique",
                if (optical) SurfColors.Ok else if (shownZoom >= 4.9f) SurfColors.Alert else SurfColors.Muted,
            )
        }
    }
}

@Composable
private fun HudChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

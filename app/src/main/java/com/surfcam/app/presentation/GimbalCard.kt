package com.surfcam.app.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.surfcam.app.data.gimbal.GimbalHub
import com.surfcam.app.data.gimbal.HorizonLeveler
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Le gimbal depuis l'accueil : connexion, état, horizon. Une fois choisi, l'appli s'y reconnecte toute seule et il suit
 * le surfeur pendant la session sans autre réglage.
 */
@Composable
fun GimbalCard() {
    val context = LocalContext.current
    val link = remember { GimbalHub.link(context) }
    val state by link.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("gimbal", Context.MODE_PRIVATE) }
    val leveler = remember { HorizonLeveler(link, prefs) }
    val scope = rememberCoroutineScope()
    val horizon by rememberHorizonAngle()
    var message by remember { mutableStateOf<String?>(null) }
    var leveling by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var styleName by remember { mutableStateOf(prefs.getString("followStyle", null) ?: "NORMAL") }
    var zone by remember { mutableStateOf(prefs.getFloat("zoneDeg", 45f)) }

    fun permitted() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun connectOrScan() {
        val saved = prefs.getString("address", null)
        if (saved != null) {
            link.connect(saved)
        } else {
            picking = true
            link.startScan()
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) connectOrScan() else message = "Autorise le Bluetooth (appareils à proximité) dans les réglages Android."
    }

    // Gimbal déjà choisi : on s'y reconnecte tout seul à l'ouverture de l'accueil.
    LaunchedEffect(Unit) {
        val saved = prefs.getString("address", null)
        if (saved != null && permitted() && !state.connected && !state.connecting) link.connect(saved)
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false; link.stopScan() },
            title = { Text("Choisis ton gimbal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (state.scanning) "Recherche… allume le gimbal et approche-le du téléphone." else "Recherche terminée.",
                        fontSize = 13.sp,
                    )
                    if (state.devices.isEmpty()) Text("Aucun appareil trouvé pour l'instant.", fontSize = 13.sp)
                    state.devices.take(6).forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    prefs.edit().putString("address", d.address).apply()
                                    link.connect(d.address)
                                    picking = false
                                }.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(d.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (d.likelyGimbal) Text("probable", color = SurfColors.Ok, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { link.startScan() }) { Text("Relancer") } },
            dismissButton = { TextButton(onClick = { picking = false; link.stopScan() }) { Text("Fermer") } },
        )
    }

    Section("Gimbal") {
        Text(
            when {
                state.connected -> "Connecté · batterie du gimbal " + (state.battery?.let { "$it %" + if (state.charging) " (en charge)" else "" } ?: "…")
                state.connecting -> "Connexion…"
                prefs.getString("address", null) != null -> "Non connecté (allume le gimbal)"
                else -> "Aucun gimbal choisi"
            },
            color = if (state.connected) SurfColors.Ok else SurfColors.Foam,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!state.connected) {
            Button(
                onClick = {
                    message = null
                    if (permitted()) connectOrScan() else permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                },
                enabled = !state.connecting,
            ) { Text(if (prefs.getString("address", null) != null) "Reconnecter" else "Connecter mon gimbal") }
            Text("Une fois connecté, il suit le surfeur tout seul pendant la session.", color = SurfColors.Muted, fontSize = 12.sp)
        } else {
            horizon?.let {
                val off = abs(it)
                Text(
                    "Inclinaison : %.1f°".format(it).replace('.', ',') + if (off < 0.5f) " ✓ droit" else "",
                    color = if (off < 0.5f) SurfColors.Ok else if (off < 2f) SurfColors.Warn else SurfColors.Alert,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        leveling = true
                        message = "Redressement…"
                        scope.launch {
                            message = leveler.run { horizon }
                            leveling = false
                        }
                    },
                    enabled = !leveling,
                ) { Text(if (leveling) "…" else "Redresser") }
                OutlinedButton(onClick = { leveler.nudge(-0.5f) }, enabled = !leveling) { Text("−0,5°") }
                OutlinedButton(onClick = { leveler.nudge(0.5f) }, enabled = !leveling) { Text("+0,5°") }
            }
            Text("Place le téléphone en paysage dans le gimbal, puis touche « Redresser » (ou règle à la main).", color = SurfColors.Muted, fontSize = 12.sp)
        }
        Text("Vitesse du suivi", color = SurfColors.Foam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (st in com.surfcam.app.domain.gimbal.FollowController.FollowStyle.entries) {
                androidx.compose.material3.FilterChip(
                    selected = styleName == st.name,
                    onClick = { styleName = st.name; prefs.edit().putString("followStyle", st.name).apply() },
                    label = { Text(st.label) },
                )
            }
        }
        Text("Zone de surf (de chaque côté du point de départ)", color = SurfColors.Foam, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((deg, label) in listOf(25f to "Étroite ±25°", 45f to "Normale ±45°", 90f to "Large ±90°")) {
                androidx.compose.material3.FilterChip(
                    selected = zone == deg,
                    onClick = { zone = deg; prefs.edit().putFloat("zoneDeg", deg).apply() },
                    label = { Text(label) },
                )
            }
        }
        Text("Sans surfeur pendant 8 s, le gimbal revient face à la zone.", color = SurfColors.Muted, fontSize = 12.sp)
        message?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
    }
}

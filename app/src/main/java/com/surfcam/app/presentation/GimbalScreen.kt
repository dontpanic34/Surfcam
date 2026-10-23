package com.surfcam.app.presentation

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.gimbal.OsmoMobileLink
import com.surfcam.app.presentation.theme.SurfCamTheme
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Écran de test d'un gimbal DJI Osmo Mobile (OM2, OM3, OM4) posé sur le trépied avec le téléphone dessus : recherche,
 * connexion, pivotement à gauche et à droite, et mesure de la vitesse réelle avec le gyroscope du téléphone. Sert à
 * vérifier que le gimbal obéit et à calibrer les unités avant tout suivi automatique.
 */
@Composable
fun GimbalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = remember { OsmoMobileLink(context) }
    val state by link.state.collectAsState()
    var speed by remember { mutableFloatStateOf(600f) }
    var measured by remember { mutableFloatStateOf(0f) }
    var report by remember { mutableStateOf<String?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    var invert by remember { mutableStateOf(false) }
    var permissionRefused by remember { mutableStateOf(false) }
    val horizon by rememberHorizonAngle()
    var leveling by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("gimbal", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) { invert = prefs.getBoolean("invert", false) }

    // Vitesse de rotation réelle du téléphone (le gimbal le fait tourner) : norme du gyroscope, lissée.
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val rate = Math.toDegrees(sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())).toFloat()
                measured += 0.25f * (rate - measured)
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (gyro != null) manager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager.unregisterListener(listener)
            link.disconnect()
        }
    }
    BackHandler {
        link.disconnect()
        onBack()
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) link.startScan() else permissionRefused = true
    }

    fun holdRotation(direction: Int): Job = scope.launch {
        while (isActive) {
            link.rotate(direction * speed.toInt())
            delay(150)
        }
    }

    /**
     * Redresse l'horizon avec le roulis du gimbal : on mesure l'inclinaison du téléphone (capteur de gravité), on ordonne
     * un petit pivotement, on remesure, et on recommence. Si l'écart grandit, le sens était faux : on l'inverse (et on s'en
     * souvient). S'arrête sous 0,4° ou après six essais.
     */
    fun autoLevel() {
        if (leveling) return
        leveling = true
        report = "Redressement de l'horizon…"
        scope.launch {
            var sign = prefs.getInt("rollSign", 1)
            var before = horizon
            if (before == null) {
                report = "Le téléphone n'a pas de capteur d'inclinaison utilisable."
                leveling = false
                return@launch
            }
            val start = before
            repeat(6) {
                val e = before ?: return@repeat
                if (kotlin.math.abs(e) < 0.4f) return@repeat
                val step = (-e * 0.8f).coerceIn(-5f, 5f) * sign
                link.rollBy(step, 1.0f)
                delay(2_500)
                val after = horizon ?: e
                if (kotlin.math.abs(after) > kotlin.math.abs(e) - 0.2f) {
                    // Pas d'amélioration : mauvais sens.
                    sign = -sign
                    prefs.edit().putInt("rollSign", sign).apply()
                }
                before = after
            }
            val end = horizon ?: 0f
            report = "Horizon : %.1f° avant, %.1f° après.".format(start, end) +
                if (kotlin.math.abs(end) > 1f) " Le gimbal n'a pas suivi l'ordre de roulis : utilise le réglage de l'appli DJI Mimo." else " C'est droit."
            leveling = false
        }
    }

    fun calibrate() {
        if (calibrating) return
        calibrating = true
        report = "Calibrage en cours : le gimbal va tourner quelques secondes. Garde la zone libre."
        scope.launch {
            val speeds = listOf(300, 600, 1000, 1500)
            val results = mutableListOf<Pair<Int, Float>>()
            for (v in speeds) {
                var sum = 0f
                var n = 0
                val sendJob = scope.launch {
                    while (isActive) {
                        link.rotate(v)
                        delay(150)
                    }
                }
                delay(1200) // montée en vitesse
                repeat(10) {
                    sum += measured
                    n++
                    delay(100)
                }
                sendJob.cancel()
                link.stop()
                results += v to (if (n > 0) sum / n else 0f)
                delay(1500)
            }
            val gain = results.sumOf { (it.first * it.second).toDouble() } / results.sumOf { (it.first * it.first).toDouble() }
            prefs.edit().putFloat("deg_per_sec_per_unit", gain.toFloat()).apply()
            report = "Résultat : " + results.joinToString(" ; ") { "${it.first} → %.1f °/s".format(it.second) } +
                "\nGain : %.4f °/s par unité (enregistré). Si les valeurs sont toutes proches de 0, le gimbal n'a pas tourné.".format(gain)
            calibrating = false
        }
    }

    SurfCamTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        link.disconnect()
                        onBack()
                    }) { Text("‹ Retour") }
                    Text("Tête motorisée · test", color = SurfColors.Aqua, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Pour un DJI Osmo Mobile 2, 3 ou 4 posé sur le trépied, téléphone dessus, éteint sur l'appli DJI. Protocole non officiel : " +
                        "à utiliser à tes risques, sans mise à jour du gimbal.",
                    color = SurfColors.Muted, fontSize = 12.sp,
                )
                Text(state.status, color = if (state.connected) SurfColors.Ok else SurfColors.Foam, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (permissionRefused) Text("Autorise le Bluetooth (appareils à proximité) dans les réglages Android.", color = SurfColors.Warn, fontSize = 12.sp)

                if (!state.connected) {
                    Button(
                        onClick = {
                            permissionRefused = false
                            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                        },
                        enabled = !state.connecting,
                    ) { Text(if (state.scanning) "Recherche… (relancer)" else "Rechercher le gimbal") }
                    LazyColumn(Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.devices, key = { it.address }) { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        prefs.edit().putString("address", d.address).apply()
                                        link.connect(d.address)
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(d.name, color = SurfColors.Foam, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (d.likelyGimbal) Text("probable", color = SurfColors.Ok, fontSize = 11.sp)
                                Text("${d.rssi} dBm", color = SurfColors.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    horizon?.let { Text("Inclinaison du téléphone : %.1f°".format(it).replace('.', ','), color = SurfColors.Aqua, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Text("Vitesse demandée : ${speed.toInt()} (unités du protocole)", color = SurfColors.Foam, fontSize = 13.sp)
                    Slider(
                        value = speed, onValueChange = { speed = it }, valueRange = 100f..2000f,
                        colors = SliderDefaults.colors(thumbColor = SurfColors.Aqua, activeTrackColor = SurfColors.Aqua),
                    )
                    Text("Vitesse réelle mesurée (gyroscope) : %.1f °/s".format(measured), color = SurfColors.Aqua, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        HoldButton("◀ Gauche (maintenir)", Modifier.weight(1f)) { holdRotation(-1) }
                        HoldButton("Droite (maintenir) ▶", Modifier.weight(1f)) { holdRotation(1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { link.stop() },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfColors.Alert, contentColor = Color.White),
                        ) { Text("STOP") }
                        OutlinedButton(onClick = { autoLevel() }, enabled = !leveling && !calibrating) { Text(if (leveling) "Redressement…" else "Redresser l'horizon") }
                        OutlinedButton(onClick = { calibrate() }, enabled = !calibrating && !leveling) { Text(if (calibrating) "Calibrage…" else "Calibrer la vitesse") }
                        OutlinedButton(onClick = { link.disconnect() }) { Text("Déconnecter") }
                    }
                    Text(
                        "Vérifie que « Droite » fait bien tourner vers la droite. Sinon, inverse le sens : le suivi automatique s'en servira.",
                        color = SurfColors.Muted, fontSize = 12.sp,
                    )
                    OutlinedButton(onClick = {
                        invert = !invert
                        prefs.edit().putBoolean("invert", invert).apply()
                    }) { Text(if (invert) "Sens inversé : oui (toucher pour changer)" else "Sens inversé : non (toucher pour changer)", fontSize = 12.sp) }
                    Text("Mouvement d'angle (sans calibrage) : le gimbal doit tourner de l'angle indiqué en 1,5 s.", color = SurfColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { link.rotateBy(-30f * if (invert) -1 else 1, 1.5f) }) { Text("◀ 30°") }
                        OutlinedButton(onClick = { link.rotateBy(30f * if (invert) -1 else 1, 1.5f) }) { Text("30° ▶") }
                        OutlinedButton(onClick = { link.rotateBy(-90f * if (invert) -1 else 1, 3f) }) { Text("◀ 90°") }
                        OutlinedButton(onClick = { link.rotateBy(90f * if (invert) -1 else 1, 3f) }) { Text("90° ▶") }
                    }
                    state.battery?.let { Text("Batterie du gimbal : $it %" + if (state.charging) " (en charge)" else "", color = SurfColors.Foam, fontSize = 12.sp) }
                    Text("Messages reçus du gimbal : ${state.received}", color = SurfColors.Muted, fontSize = 11.sp)
                    prefs.getFloat("deg_per_sec_per_unit", 0f).takeIf { it > 0f }?.let {
                        Text("Dernier calibrage enregistré : %.4f °/s par unité".format(it), color = SurfColors.Muted, fontSize = 11.sp)
                    }
                }
                if (state.journal.isNotEmpty()) {
                    Text("Journal (à me montrer si ça ne marche pas) :", color = SurfColors.Muted, fontSize = 11.sp)
                    Text(state.journal.joinToString("\n"), color = SurfColors.Muted, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                report?.let { Text(it, color = SurfColors.Warn, fontSize = 12.sp) }
            }
        }
    }
}

/** Bouton qui commande tant qu'il est maintenu, et arrête dès qu'on le relâche. */
@Composable
private fun HoldButton(text: String, modifier: Modifier, start: () -> Job) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(SurfColors.Aqua)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = start()
                    try {
                        tryAwaitRelease()
                    } finally {
                        job.cancel()
                    }
                })
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = SurfColors.OnAqua, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

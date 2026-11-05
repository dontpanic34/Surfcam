package com.surfcam.app.presentation

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.data.storage.StorageDiag
import com.surfcam.app.presentation.theme.SurfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostic de la carte SD : état en direct (supports, appareils USB, alimentation), test d'écriture soutenue de 30 secondes
 * pendant lequel on peut brancher ou débrancher le chargeur, et journal horodaté à partager.
 */
@Composable
fun StorageDiagDialog(volumeName: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    val live by produceState("…") {
        while (true) {
            value = withContext(Dispatchers.IO) { StorageDiag.snapshot(context) } + "\n\n" + withContext(Dispatchers.IO) { StorageDiag.tail(14) }
            delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Diagnostic de la carte SD") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Lance le test, puis pendant les 30 secondes débranche le chargeur du hub vers la 10e seconde et rebranche-le vers la 20e. Le journal note ce qui arrive à la carte, seconde par seconde.",
                    fontSize = 12.sp, color = SurfColors.Muted,
                )
                Button(
                    onClick = {
                        val v = volumeName ?: return@Button
                        running = true
                        verdict = null
                        scope.launch {
                            verdict = withContext(Dispatchers.IO) { StorageDiag.sustainedTest(context, v, 30, 12) { progress = it } }
                            running = false
                        }
                    },
                    enabled = !running && volumeName != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (running) "Test en cours…" else if (volumeName == null) "Aucune carte détectée" else "Lancer le test de 30 s") }
                if (running && progress.isNotEmpty()) Text(progress, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                verdict?.let { Text(it, fontSize = 13.sp, color = SurfColors.Warn) }
                Text(live, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, color = SurfColors.Foam)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Diagnostic carte SD SurfCam")
                            putExtra(Intent.EXTRA_TEXT, StorageDiag.snapshot(context) + "\n\n" + StorageDiag.fullText())
                        }
                        context.startActivity(Intent.createChooser(send, "Partager le diagnostic").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Partager le diagnostic", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !running) { Text("Fermer") } },
    )
}

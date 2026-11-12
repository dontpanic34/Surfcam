package com.surfcam.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surfcam.app.presentation.theme.SurfColors

/** Une couleur stable par prénom, pour reconnaître d'un coup d'œil qui est sur chaque vague. */
fun surferColor(name: String): Color {
    val palette = listOf(0xFF46D39A, 0xFF6FB7FF, 0xFFFFC24D, 0xFFFF8A65, 0xFFC792EA, 0xFF4DD0E1, 0xFFF06292, 0xFFAED581)
    return Color(palette[(name.lowercase().hashCode() and 0x7fffffff) % palette.size])
}

/**
 * Rangée de prénoms de surfeurs, défilable, avec « + Ajouter » au bout. [selected] est entouré ; un toucher appelle [onSelect]
 * (le même prénom une seconde fois appelle [onSelect] avec null). [onAdd] reçoit un nouveau prénom saisi.
 */
@Composable
fun SurferPicker(names: List<String>, selected: String?, onSelect: (String?) -> Unit, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (n in names) {
            val on = n == selected
            val c = surferColor(n)
            Text(
                n, color = if (on) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c else Color(0x99000000))
                    .border(2.dp, c, RoundedCornerShape(50)).clickable { onSelect(if (on) null else n) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Text(
            "+ Ajouter", color = SurfColors.Aqua, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, SurfColors.Line, RoundedCornerShape(50))
                .clickable { typed = ""; adding = true }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Nouveau surfeur") },
            text = { OutlinedTextField(value = typed, onValueChange = { typed = it.take(24) }, singleLine = true, label = { Text("Prénom") }) },
            confirmButton = { TextButton(onClick = { if (typed.isNotBlank()) onAdd(typed.trim()); adding = false }, enabled = typed.isNotBlank()) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } },
        )
    }
}

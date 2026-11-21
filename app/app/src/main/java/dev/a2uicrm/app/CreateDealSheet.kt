package dev.a2uicrm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * New deal form (Analytics «New deal»): title, amount, stage, owner, contact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDealSheet(
    members: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (title: String, amountMicros: Long, stage: String, ownerId: String, contactName: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("Lead") }
    var owner by remember { mutableStateOf(members.firstOrNull()?.first ?: "wm-1") }
    var error by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New deal", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; error = null },
                label = { Text("Title") },
                placeholder = { Text("e.g. Acme — rollout") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it; error = null },
                label = { Text("Amount, $") },
                placeholder = { Text("12000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
            )
            Text("Stage", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = stage == "Lead", onClick = { stage = "Lead" }, label = { Text("Lead") })
                FilterChip(selected = stage == "Qualified", onClick = { stage = "Qualified" }, label = { Text("Qualified") })
                FilterChip(selected = stage == "Proposal", onClick = { stage = "Proposal" }, label = { Text("Proposal") })
            }
            Text("Owner", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { (id, name) ->
                    FilterChip(
                        selected = owner == id,
                        onClick = { owner = id },
                        label = { Text(name.substringBefore(" ")) },
                    )
                }
            }
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                label = { Text("Contact (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val t = title.trim()
                    val amountMicros = amount.trim().replace(",", ".").toDoubleOrNull()
                    if (t.isBlank()) {
                        error = "Enter a title"
                        return@Button
                    }
                    if (amountMicros == null || amountMicros <= 0) {
                        error = "Enter the amount as a number, e.g. 12000"
                        return@Button
                    }
                    onSave(t, (amountMicros * 1_000_000).toLong(), stage, owner, contact.trim().ifBlank { null })
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create") }
        }
    }
}

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.agentic.action.CrmActionDispatcher

/**
 * Fill-in form for a new task (5.4c): the A2UI CTA / «Новая задача» no longer silently
 * creates a task with inferred defaults — the user fills title, due day and contact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskSheet(
    initialTitle: String,
    initialContact: String,
    onDismiss: () -> Unit,
    onSave: (title: String, dueAtEpochMs: Long, contactName: String?) -> Unit,
) {
    var title by remember { mutableStateOf(if (initialTitle == "New task") "" else initialTitle) }
    var contact by remember { mutableStateOf(initialContact) }
    var dateText by remember { mutableStateOf(fmtDate(System.currentTimeMillis() + 24L * 60 * 60 * 1000)) }
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
            Text("New task", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; error = null },
                label = { Text("Title") },
                placeholder = { Text("e.g. Call Jane") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
            )
            Text("Date (DD.MM.YYYY)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it; error = null },
                label = { Text("Date") },
                placeholder = { Text("15.11.2026") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = false,
                    onClick = { dateText = fmtDate(System.currentTimeMillis()) },
                    label = { Text("Today") },
                )
                FilterChip(
                    selected = false,
                    onClick = { dateText = fmtDate(System.currentTimeMillis() + 24L * 60 * 60 * 1000) },
                    label = { Text("Tomorrow") },
                )
                FilterChip(
                    selected = false,
                    onClick = { dateText = fmtDate(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000) },
                    label = { Text("In a week") },
                )
            }
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                label = { Text("Contact (optional)") },
                placeholder = { Text("Jane Cooper") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val t = title.trim()
                    if (t.isBlank()) {
                        error = "Enter a task title"
                        return@Button
                    }
                    val due = parseDateText(dateText)
                    if (due == null) {
                        error = "Date as DD.MM.YYYY, e.g. 15.11.2026"
                        return@Button
                    }
                    onSave(t, due, contact.trim().ifBlank { null })
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create") }
        }
    }
}

/** "15.11.2026" -> epoch ms at 09:00 local, or null. */
private fun parseDateText(s: String): Long? {
    val m = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(s.trim()) ?: return null
    val day = m.groupValues[1].toIntOrNull() ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val year = m.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(year, month - 1, day, 9, 0, 0)
    if (cal.get(java.util.Calendar.DAY_OF_MONTH) != day) return null
    return cal.timeInMillis
}

/** epoch -> "15.11.2026". */
private fun fmtDate(epochMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = epochMs
    return "%02d.%02d.%d".format(
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.YEAR),
    )
}

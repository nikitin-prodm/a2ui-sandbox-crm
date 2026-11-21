package dev.a2uicrm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.core.domain.Task
import dev.a2uicrm.core.domain.TeamStats

/**
 * STANDARD native screens: instant navigation, no spinners. The A2UI agent lives behind
 * the FAB; an augment request builds on the TWIN of these screens (CompositionBuilder).
 */

@Composable
fun TodayScreen(
    groups: dev.a2uicrm.core.domain.TaskGroups,
    onToggle: (Task, Boolean) -> Unit,
    onOpen: (Task) -> Unit,
    onCreate: () -> Unit = {},
    title: String = "My day",
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        val sections = groups.asSections
        if (sections.isEmpty()) {
            item { EmptyRow("No tasks — everything is under control") }
        } else {
            sections.forEach { (title, list) ->
                item {
                    if (title == "Overdue") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Overdue: ${list.size} — need attention",
                                modifier = Modifier.padding(10.dp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    SectionLabel(title, list.size, error = title == "Overdue")
                }
                items(list, key = { it.id }) { task -> TaskRow(task, onToggle, onOpen) }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
        // A2UI-style primary CTA: full-width filled button at the bottom of the screen.
        item {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  New task")
            }
        }
    }
}

@Composable
fun TaskRow(task: Task, onToggle: (Task, Boolean) -> Unit, onOpen: (Task) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(task) }) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.completed, onCheckedChange = { onToggle(task, it) })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = androidx.compose.ui.text.TextStyle(
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    ),
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    task.dueAtEpochMs?.let {
                        Text(
                            text = formatDueShort(it),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    task.contactName?.let {
                        Text(
                            text = "· $it",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TeamScreen(stats: List<TeamStats>, title: String = "Team") {
    // Deals are hidden by default — detail is added by the AGENT on request
    // ("show open deals under each employee" -> augment of this view's twin).
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { ScreenTitle(title) }
        item { SectionLabel("Leaderboard: pipeline", stats.size) }
        // Rank = position by pipeline (1/2/3) — the SAME number the A2UI twin's
        // LeaderboardRow shows, so the agent view and this view are indistinguishable.
        // (Before: wonCount — always 0 in the fake, meaningless to the user.)
        val ranked = stats.sortedByDescending { it.pipelineValueMicros }
        itemsIndexed(ranked, key = { _, s -> s.memberId }) { index, s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(10.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.memberName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "${s.openTasks} open tasks · ${s.overdueTasks} overdue",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatK(s.pipelineValueMicros),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun AnalyticsScreen(
    stages: List<dev.a2uicrm.core.domain.PipelineStage>,
    onCreateDeal: () -> Unit = {},
    title: String = "Analytics",
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { ScreenTitle(title) }
        if (stages.isNotEmpty()) {
            item {
                Text(
                    "Total in funnel: ${formatK(stages.sumOf { it.valueMicros })}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        item { SectionLabel("Funnel", stages.size) }
        items(stages, key = { it.stageId }) { stage ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Distinct icon PER STAGE (bug fix: all stages used to share TaskAlt).
                // The same token mapping drives the A2UI twin (analyticsTwin), so the
                // agent-built screen keeps these icons when the screen is augmented.
                Icon(
                    imageVector = stageIconVector(stage.stageId, stage.label),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stage.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    val conv = stage.conversionFromPrevious?.let { " · conv ${"%.0f".format(it * 100)}%" } ?: ""
                    Text(
                        "deals: ${stage.count}$conv",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatK(stage.valueMicros), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        item {
            Button(onClick = onCreateDeal, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  New deal")
            }
        }
    }
}

/** epoch millis -> "Nov 9" (compact; no java.time — minSdk 24). */
internal fun formatDueShort(epochMs: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "%s %d".format(month[cal.get(java.util.Calendar.MONTH)], cal.get(java.util.Calendar.DAY_OF_MONTH))
}

/**
 * Funnel-stage icons (native side). Each stage gets its OWN meaningful icon;
 * the token strings match the A2UI renderer vocabulary (A2uiRendererRegistry.ICONS)
 * so the native screen and the agent twin resolve the SAME icon per stage.
 */
internal fun stageIconToken(stageId: String, label: String): String = when (stageId) {
    "st-lead", "Lead" -> "person"          // new lead = a contact person
    "st-qualified", "Qualified" -> "checkCircle" // vetted/qualified
    "st-proposal", "Proposal" -> "description"   // proposal document
    "st-won", "Won" -> "storefront"         // closed deal (shop front)
    else -> when {
        label.contains("won", ignoreCase = true) -> "storefront"
        label.contains("qualif", ignoreCase = true) -> "checkCircle"
        label.contains("propos", ignoreCase = true) -> "description"
        label.contains("lead", ignoreCase = true) -> "person"
        else -> "work"
    }
}

/** The native-side twin of the token mapping (same choices the A2UI renderer makes). */
internal fun stageIconVector(stageId: String, label: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (stageIconToken(stageId, label)) {
        "person" -> Icons.Filled.Person
        "checkCircle" -> Icons.Filled.CheckCircle
        "description" -> Icons.Filled.Description
        "storefront" -> Icons.Filled.Storefront
        else -> Icons.Filled.Work
    }

/** micros -> "$41.2k" (compact display). Locale.US on purpose: the A2UI twins bake
 *  amounts with a "." decimal separator (formatMicros in CompositionBuilder), and
 *  "%.1f" alone is locale-dependent — on a ru-RU device native showed "$29,5k"
 *  while the twin showed "$29.5k" (live-found parity gap, 18.11). */
private fun formatK(micros: Long): String =
    if (micros >= 1_000_000_000) "$${String.format(java.util.Locale.US, "%.1f", micros / 1_000_000_000.0)}k" else "$${micros / 1_000_000}"

@Composable
private fun ScreenTitle(title: String) {
    Text(
        text = title,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionLabel(label: String, count: Int, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Surface(
            color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Native task detail: tap a task card (checkbox still toggles in place). */
@Composable
fun TaskDetailScreen(
    task: Task,
    onToggle: (Task, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val overdue = !task.completed && task.dueAtEpochMs != null &&
        task.dueAtEpochMs!! < startOfDayUtc()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Task", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(task.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Surface(
                color = when {
                    task.completed -> MaterialTheme.colorScheme.primaryContainer
                    overdue -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = when {
                        task.completed -> "Completed"
                        overdue -> "Overdue"
                        else -> "Active"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            DetailRow("Contact", task.contactName ?: "—")
            DetailRow("Due", formatDueFull(task.dueAtEpochMs))
            DetailRow("ID", task.id)
            task.body?.let { DetailRow("Description", it) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = task.completed,
                        onCheckedChange = { checked -> onToggle(task, checked) },
                    )
                    Text(
                        text = if (task.completed) "Completed — unmark" else "Mark as completed",
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** epoch millis -> "October 9, 2025, 08:53" (UTC; no java.time — minSdk 24). */
private fun formatDueFull(epochMs: Long?): String {
    if (epochMs == null) return "—"
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    val month = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    return "%s %d %d, %02d:%02d".format(
        month[cal.get(java.util.Calendar.MONTH)],
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

private fun startOfDayUtc(now: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

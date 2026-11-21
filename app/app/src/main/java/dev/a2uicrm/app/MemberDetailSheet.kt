package dev.a2uicrm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.TeamStats

/**
 * 5.4g: "open a team member" (LeaderboardRow / EntityCard tap -> openEntity(contact)).
 * Before this the action was dead (navigate fallback). The sheet answers the user's
 * question ("open on a team member — tasks or contracts?") with BOTH: stats + deals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailSheet(
    stats: TeamStats,
    deals: List<Deal>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(stats.memberName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Team member - pipeline " + formatMicros(stats.pipelineValueMicros),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemberStatChip(Icons.Filled.CheckCircle, "Done 7d", stats.doneTasks7d.toString(), MaterialTheme.colorScheme.primary)
                    MemberStatChip(Icons.Filled.Error, "Overdue", stats.overdueTasks.toString(), MaterialTheme.colorScheme.error)
                    MemberStatChip(Icons.Filled.CalendarToday, "Open", stats.openTasks.toString(), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemberStatChip(Icons.Filled.AttachMoney, "Won", stats.wonCount.toString(), MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Text(
                    "Deals owned (${deals.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (deals.isEmpty()) {
                item {
                    Text(
                        "No open deals for this member.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(deals) { d ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(d.title, fontWeight = FontWeight.Medium)
                            Text(
                                "Stage: ${d.stage} - ${formatMicros(d.amountMicros)} - ${d.contactName ?: "-"}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun MemberStatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMicros(micros: Long): String {
    val dollars = micros / 1_000_000
    return if (dollars >= 1000) "$${dollars / 1000}.${(dollars % 1000) / 100}k" else "\$$dollars"
}

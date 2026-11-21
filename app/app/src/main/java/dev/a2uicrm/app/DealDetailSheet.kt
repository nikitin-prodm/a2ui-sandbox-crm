package dev.a2uicrm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Storefront
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

/** 5.4g: EntityCard(openEntity opportunity) tap - deal detail (was a navigate stub). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealDetailSheet(deal: Deal, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                Icon(imageVector = Icons.Filled.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(deal.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Opportunity - stage ${deal.stage}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.AttachMoney, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("$${deal.amountMicros / 1_000_000}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Text(
                        "Contact: ${deal.contactName ?: "-"} · ownerId: ${deal.ownerId}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

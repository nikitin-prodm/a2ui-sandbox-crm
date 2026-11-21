package dev.a2uicrm.agentic.render

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interactive CRM components (5.2): EntityCard, ActionItem, PrimaryActionButton —
 * all dispatch whitelisted actions through A2uiRenderContext (defaultActions and
 * `<this X>` placeholders per catalog.json rendererSemantics).
 */

@Composable
fun EntityCardComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val entityType = props["entityType"] as? String ?: "task"
    // Style-kit icon token (5.4g): overrides the entityType default — the agent can
    // set/change icons on any card.
    val icon = A2uiRendererRegistry.iconVector(props["icon"] as? String) ?: when (entityType) {
        "contact" -> Icons.AutoMirrored.Filled.Send
        "company" -> Icons.Filled.Storefront
        "opportunity" -> Icons.Filled.Storefront
        else -> Icons.Filled.TaskAlt
    }
    val onClick: () -> Unit = {
        // rendererSemantics default: openEntity with entityType/entityId from own props.
        ctx.dispatchAction(
            node,
            defaultEvent = mapOf(
                "name" to "openEntity",
                "context" to mapOf("entityType" to entityType, "entityId" to (props["entityId"] ?: "")),
            ),
        )
    }
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = entityType,
                modifier = Modifier.padding(10.dp).size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = A2uiRendererRegistry.resolveString(props["title"], ctx),
                fontWeight = FontWeight.SemiBold,
            )
            props["subtitle"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            props["footer"]?.let { footer ->
                Text(
                    text = (footer as? List<*>)?.joinToString(" · ") { A2uiRendererRegistry.resolveString(it, ctx) } ?: "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            props["badge"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            props["amount"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    // Native parity: cards are the DEFAULT (My day / Team rows are gray rounded cards);
    // the agent view reads as the SAME page. A flat row is an explicit opt-in
    // (style="plain") for compact sub-lists.
    when (props["style"] as? String) {
        "tonal" -> Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        "outline" -> androidx.compose.material3.OutlinedCard(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        "filled" -> Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        "plain" -> Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        else -> Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}


@Composable
fun ActionItemComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val completed = props["completed"] as? Boolean ?: false
    val priority = props["priority"] as? String ?: "normal"
    val titleColor = when {
        completed -> MaterialTheme.colorScheme.onSurfaceVariant
        priority == "high" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val rowContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (props["checkboxShape"] as? String == "round") {
            // 5.4i editable knob: round checkboxes (Material3 Checkbox has no shape param
            // in this version — a compact custom round toggle, same interaction contract).
            RoundToggle(
                checked = completed,
                onChecked = { checked ->
                    ctx.dispatchAction(
                        node,
                        defaultEvent = mapOf(
                            "name" to "completeTask",
                            "context" to mapOf("taskId" to (props["taskId"] ?: ""), "completed" to checked),
                        ),
                    )
                },
            )
        } else {
            Checkbox(
                checked = completed,
                onCheckedChange = { checked ->
                    // rendererSemantics default: completeTask with taskId from own props.
                    ctx.dispatchAction(
                        node,
                        defaultEvent = mapOf(
                            "name" to "completeTask",
                            "context" to mapOf("taskId" to (props["taskId"] ?: ""), "completed" to checked),
                        ),
                    )
                },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = A2uiRendererRegistry.resolveString(props["title"], ctx),
                color = titleColor,
                fontSize = 14.sp,
                style = if (completed) TextStyle(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                ) else TextStyle(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                props["dueAt"]?.let {
                    Text(
                        text = formatDueShort(A2uiRendererRegistry.resolveString(it, ctx)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                props["contactName"]?.let {
                    Text(
                        text = "· ${A2uiRendererRegistry.resolveString(it, ctx)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (priority == "high" && !completed) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = CircleShape) {
                Text(
                    text = "!",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
    // Native parity: the task row is a gray rounded CARD — exactly like TodayScreen
    // TaskRow (Card → Row with horizontal=8/vertical=4 padding, same checkbox + meta
    // line). A flat row is an explicit opt-in (style="plain") for compact sub-lists.
    when (props["style"] as? String) {
        "plain" -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                ctx.dispatchAction(
                    node,
                    defaultEvent = mapOf(
                        "name" to "openEntity",
                        "context" to mapOf("entityType" to "task", "entityId" to (props["taskId"] ?: "")),
                    ),
                )
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = rowContent,
        )
        else -> Card(
            modifier = Modifier.fillMaxWidth().clickable {
                // Tap on the row (not the checkbox) opens the native Detail screen (5.4).
                ctx.dispatchAction(
                    node,
                    defaultEvent = mapOf(
                        "name" to "openEntity",
                        "context" to mapOf("entityType" to "task", "entityId" to (props["taskId"] ?: "")),
                    ),
                )
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = rowContent,
            )
        }
    }
}

/** 5.4i: round variant of the task toggle (checkboxShape="round"). */
@Composable
private fun RoundToggle(checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (checked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .size(24.dp)
            .clickable { onChecked(!checked) },
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (checked) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** "2026-11-14T09:00:00Z" -> "Nov 14" (native TaskRow parity: DATE ONLY, no time; no java.time — minSdk 24). */
private fun formatDueShort(iso: String): String {
    val epoch = dev.a2uicrm.agentic.action.CrmActionDispatcher.parseIsoToEpoch(iso) ?: return iso
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epoch
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "%s %d".format(
        month[cal.get(java.util.Calendar.MONTH)],
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

@Composable
fun PrimaryActionButtonComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val label = A2uiRendererRegistry.resolveString(props["label"], ctx)
    val variant = props["variant"] as? String ?: "primary"
    val icon = when (props["icon"] as? String) {
        "add" -> Icons.Filled.Add
        "check" -> Icons.Filled.Check
        "refresh" -> Icons.Filled.Refresh
        else -> null
    }

    val onClick = {
        // action is required per the catalog; the validator guarantees its presence.
        ctx.dispatchAction(node)
    }

    when (variant) {
        "borderless" -> TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            icon?.let { Icon(imageVector = it, contentDescription = null) }
            Text(label)
        }
        "default" -> FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            icon?.let { Icon(imageVector = it, contentDescription = null) }
            Text(label)
        }
        else -> Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            icon?.let { Icon(imageVector = it, contentDescription = null) }
            Text(label)
        }
    }
}


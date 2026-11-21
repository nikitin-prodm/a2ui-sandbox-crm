package dev.a2uicrm.agentic.render

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** CRM app_extension components (catalog.json -> components): the 5 budgeted in 5.1. */

@Composable
fun MetricCardComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val labelRow: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            A2uiRendererRegistry.iconVector(props["icon"] as? String)?.let {
                Icon(imageVector = it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = A2uiRendererRegistry.resolveString(props["label"], ctx),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val valueRow: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = A2uiRendererRegistry.resolveString(props["value"], ctx),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            val trend = props["trend"] as? String
            if (trend != null) {
                val icon = when (trend) {
                    "up" -> Icons.AutoMirrored.Filled.TrendingUp
                    "down" -> Icons.AutoMirrored.Filled.TrendingDown
                    else -> Icons.AutoMirrored.Filled.TrendingFlat
                }
                val color = when (trend) {
                    "up" -> MaterialTheme.colorScheme.primary
                    "down" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(imageVector = icon, contentDescription = trend, tint = color)
                props["trendLabel"]?.let {
                    Text(
                        text = A2uiRendererRegistry.resolveString(it, ctx),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    // 5.4i: the DEFAULT is the NATIVE flat look (a plain metric line like the native
    // "Total in funnel" row) — the agent view must feel like the SAME page. A card/
    // background is an explicit opt-in via style: tonal|filled|outline.
    when (props["style"] as? String) {
        "tonal" -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) { labelRow(); valueRow() }
        }
        "outline" -> androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) { labelRow(); valueRow() }
        }
        "filled" -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) { labelRow(); valueRow() }
        }
        else -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            labelRow()
            valueRow()
        }
    }
}

@Composable
fun SectionHeaderComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Style-kit icon token (5.4g) before the title.
        A2uiRendererRegistry.iconVector(props["icon"] as? String)?.let {
            Icon(imageVector = it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = A2uiRendererRegistry.resolveString(props["title"], ctx),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        // Optional subtitle (e.g. the stage total in the deals-by-stage section).
        props["subtitle"]?.let {
            Text(
                text = A2uiRendererRegistry.resolveString(it, ctx),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val count = A2uiRendererRegistry.resolveInt(props["count"], ctx)
        if (count != null && count > 0) {
            val tone = props["countTone"] as? String
            val container = when (tone) {
                "warning" -> MaterialTheme.colorScheme.tertiaryContainer
                "error" -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            Surface(color = container, shape = RoundedCornerShape(10.dp)) {
                Text(
                    text = count.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun AlertBannerComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val severity = props["severity"] as? String ?: "info"
    val (container, icon) = when (severity) {
        "warning" -> MaterialTheme.colorScheme.tertiaryContainer to Icons.Filled.Warning
        "error" -> MaterialTheme.colorScheme.errorContainer to Icons.Filled.Error
        else -> MaterialTheme.colorScheme.secondaryContainer to Icons.Filled.Email
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = severity)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = A2uiRendererRegistry.resolveString(props["title"], ctx),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                props["message"]?.let {
                    Text(
                        text = A2uiRendererRegistry.resolveString(it, ctx),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            props["actionLabel"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun LeaderboardRowComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    // 5.4h: the whole row opens the member detail (EntityCard-style default semantics —
    // a bare LeaderboardRow used to be a dead tap).
    val rowContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        val rank = A2uiRendererRegistry.resolveInt(props["rank"], ctx) ?: 0
        val token = A2uiRendererRegistry.iconVector(props["icon"] as? String)
        if (token != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    imageVector = token,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        } else {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = rank.toString(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = A2uiRendererRegistry.resolveString(props["memberName"], ctx),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            props["metricLabel"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = A2uiRendererRegistry.resolveString(props["metricValue"], ctx),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            props["delta"]?.let {
                Text(
                    text = A2uiRendererRegistry.resolveString(it, ctx),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    // Native parity: the leaderboard row is a gray rounded CARD — exactly like the
    // native TeamScreen member row (Card → Row, padding 12, rank badge on
    // primaryContainer). Flat row is an explicit opt-in (style="plain").
    when (props["style"] as? String) {
        "plain" -> Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    ctx.dispatchAction(
                        node,
                        defaultEvent = mapOf(
                            "name" to "openEntity",
                            "context" to mapOf("entityType" to "contact", "entityId" to (props["memberId"] ?: "")),
                        ),
                    )
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = rowContent,
        )
        else -> Card(
            modifier = Modifier.fillMaxWidth().clickable {
                ctx.dispatchAction(
                    node,
                    defaultEvent = mapOf(
                        "name" to "openEntity",
                        "context" to mapOf("entityType" to "contact", "entityId" to (props["memberId"] ?: "")),
                    ),
                )
            },
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = rowContent,
            )
        }
    }
}

@Composable
fun EmptyStateComposable(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
    val props = node.properties
    val icon = A2uiRendererRegistry.iconVector(props["icon"] as? String)
        ?: when (props["icon"] as? String) {
            "contact" -> Icons.Filled.Email
            "deal", "team", "chart" -> Icons.Filled.Leaderboard
            else -> Icons.Filled.CheckCircle
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(
                MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = A2uiRendererRegistry.resolveString(props["title"], ctx),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = A2uiRendererRegistry.resolveString(props["message"], ctx),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Loading placeholder (shimmer arrives in 5.5 polish). */
@Composable
fun LoadingSurface(color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp)
            .background(color, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("...", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

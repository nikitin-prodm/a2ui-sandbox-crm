package dev.a2uicrm.agentic.render

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renderer registry: "component" (the 0.9.1 discriminator, basic-catalog l.1332) ->
 * @Composable. Native types (8 budgeted, §3.1) + CRM app_extensions live side by side;
 * the catalog is the source of truth for what can appear here.
 */
object A2uiRendererRegistry {

    /**
     * The sandbox icon vocabulary (5.4g): token -> vector, all from material-icons-extended
     * (already compiled into the APK — zero size cost). The SAME token list is the `icon`
     * enum in catalog.json, so the LLM prompt and the validator share it automatically.
     */
    val ICONS: Map<String, ImageVector> = mapOf(
        "add" to Icons.Filled.Add,
        "check" to Icons.Filled.Check,
        "checkCircle" to Icons.Filled.CheckCircle,
        "close" to Icons.Filled.Close,
        "calendarToday" to Icons.Filled.CalendarToday,
        "error" to Icons.Filled.Error,
        "warning" to Icons.Filled.Warning,
        "flag" to Icons.Filled.Flag,
        "filterAlt" to Icons.Filled.FilterAlt,
        "description" to Icons.Filled.Description,
        "emojiEvents" to Icons.Filled.EmojiEvents,
        "work" to Icons.Filled.Work,
        "person" to Icons.Filled.Person,
        "group" to Icons.Filled.Groups,
        "paid" to Icons.Filled.Paid,
        "attachMoney" to Icons.Filled.AttachMoney,
        "trendingUp" to Icons.AutoMirrored.Filled.TrendingUp,
        "trendingDown" to Icons.AutoMirrored.Filled.TrendingDown,
        "schedule" to Icons.Filled.Schedule,
        "event" to Icons.Filled.Event,
        "phone" to Icons.Filled.Phone,
        "star" to Icons.Filled.Star,
        "rocket" to Icons.Filled.RocketLaunch,
        "storefront" to Icons.Filled.Storefront,
        "business" to Icons.Filled.Business,
        "insights" to Icons.Filled.Insights,
        "sort" to Icons.Filled.Sort,
        "swapVert" to Icons.Filled.SwapVert,
        "email" to Icons.Filled.Email,
        "leaderboard" to Icons.Filled.Leaderboard,
        "task" to Icons.Filled.CheckCircle,
        "refresh" to Icons.Filled.Refresh,
    )

    /** Token -> vector; null when the token is unknown (caller picks a fallback). */
    fun iconVector(name: String?): ImageVector? = name?.let { ICONS[it] }

    @Composable
    fun render(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String> = emptySet()) {
        // Cycle / depth guard: an LLM can emit children that form a loop — that must
        // never recurse to StackOverflow (the "какие есть компоненты" crash).
        if (node.id in visited || visited.size > 48) return
        val next = visited + node.id
        when (node.type) {
            "Column" -> RenderColumn(node, ctx, next)
            "Row" -> RenderRow(node, ctx, next)
            "Card" -> RenderCard(node, ctx, next)
            "Text" -> RenderText(node, ctx)
            "Divider" -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            "Button" -> RenderButton(node, ctx, next)
            "Icon" -> RenderIcon(node, ctx)
            "List" -> RenderList(node, ctx, next) // lazy scrolling (5.2)
            "MetricCard" -> MetricCardComposable(node, ctx)
            "SectionHeader" -> SectionHeaderComposable(node, ctx)
            "AlertBanner" -> AlertBannerComposable(node, ctx)
            "LeaderboardRow" -> LeaderboardRowComposable(node, ctx)
            "EmptyState" -> EmptyStateComposable(node, ctx)
            "EntityCard" -> EntityCardComposable(node, ctx)
            "ActionItem" -> ActionItemComposable(node, ctx)
            "PrimaryActionButton" -> PrimaryActionButtonComposable(node, ctx)
            "PipelineStrip" -> PipelineStripComposable(node, ctx)
            "TrendChart" -> TrendChartComposable(node, ctx)
            else -> UnknownComponentPlaceholder(node.type)
        }
    }

    fun childrenIds(node: A2uiComponentPayload): List<String> =
        (node.properties["children"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    fun childId(node: A2uiComponentPayload): String? =
        node.properties["child"] as? String

    /** Literal rendering with DataBinding resolution through the render context. */
    fun resolveString(value: Any?, ctx: A2uiRenderContext? = null): String = when {
        value == null -> ""
        ctx != null && value is Map<*, *> && value.containsKey("path") ->
            ctx.resolve(value)?.let { resolveString(it) } ?: "…" // unresolved binding (5.2: refresh lag)
        value is String -> value
        value is Number -> value.toString()
        value is Map<*, *> -> "—"
        else -> value.toString()
    }

    fun resolveInt(value: Any?, ctx: A2uiRenderContext? = null): Int? = when {
        ctx != null && value is Map<*, *> && value.containsKey("path") ->
            (ctx.resolve(value) as? Number)?.toInt()
        value is Number -> value.toInt()
        value is String -> value.toIntOrNull()
        else -> null
    }

    /**
     * STYLE KIT (5.4f): a tiny closed token set the agent may use on ANY component.
     * Tokens map ONLY to MaterialTheme color roles — whatever the LLM picks, the result
     * stays inside the app's unified design system (no raw hex, no invented colors).
     */
    @Composable
    fun toneColor(tone: Any?): androidx.compose.ui.graphics.Color = when (tone as? String) {
        "primary" -> MaterialTheme.colorScheme.primary
        "secondary" -> MaterialTheme.colorScheme.secondary
        "tertiary" -> MaterialTheme.colorScheme.tertiary
        "success" -> MaterialTheme.colorScheme.tertiary
        "error" -> MaterialTheme.colorScheme.error
        "neutral", "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
        "onPrimary" -> MaterialTheme.colorScheme.onPrimary
        "onSecondary" -> MaterialTheme.colorScheme.onSecondary
        else -> androidx.compose.ui.graphics.Color.Unspecified
    }

    /** 5.4h editable template knob: gap tight|normal|loose (children spacing). */
    private fun gapDp(node: A2uiComponentPayload): androidx.compose.ui.unit.Dp = when (node.properties["gap"] as? String) {
        "tight" -> 4.dp
        "loose" -> 16.dp
        else -> 8.dp
    }

    @Composable
    private fun RenderColumn(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String>) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gapDp(node)),
        ) {
            childrenIds(node).forEach { childId ->
                ctx.byId[childId]?.let { render(it, ctx, visited) }
            }
        }
    }

    @Composable
    private fun RenderRow(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String>) {
        val children = childrenIds(node).mapNotNull { ctx.byId[it] }
        // Two fillMaxWidth MetricCards in a bare Row: the second collapses to 0 width
        // and wraps vertically — that was the "empty space" between banner and sections.
        // 3+ metrics in a Row is an LLM layout mistake: stack them vertically instead.
        val allMetrics = children.isNotEmpty() && children.all { it.type == "MetricCard" }
        if (allMetrics && children.size > 2) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(gapDp(node)),
            ) {
                children.forEach { child -> render(child, ctx, visited) }
            }
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gapDp(node)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allMetrics) {
                children.forEach { child ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        render(child, ctx, visited)
                    }
                }
            } else {
                children.forEach { child ->
                    // 5.5 "flex": a child with flex=true takes the remaining row width
                    // (native-parity rows: icon + flexible column + trailing value).
                    if (child.properties["flex"] == true) {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                            render(child, ctx, visited)
                        }
                    } else {
                        render(child, ctx, visited)
                    }
                }
            }
        }
    }

    /**
     * List = a plain sequence. NOT LazyColumn: the A2UI screen is already vertically
     * scrollable, and a LazyColumn inside an infinite-height scrollable parent CRASHES
     * (IllegalStateException) — that was the boot crash loop (crash.log 16.11).
     */
    @Composable
    private fun RenderList(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String>) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            childrenIds(node).forEach { childId ->
                ctx.byId[childId]?.let { render(it, ctx, visited) }
            }
        }
    }

    @Composable
    private fun RenderCard(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String>) {
        // 5.4h editable template knobs: style filled (default) | tonal | outline,
        // padding compact|normal|comfortable, tone -> a themed container color.
        // All Material3 roles — the agent cannot leave the app's design system.
        val pad = when (node.properties["padding"] as? String) {
            "compact" -> 8.dp
            "comfortable" -> 20.dp
            else -> 12.dp
        }
        val toneContainer = when (node.properties["tone"] as? String) {
            "primary" -> MaterialTheme.colorScheme.primaryContainer
            "secondary" -> MaterialTheme.colorScheme.secondaryContainer
            "tertiary", "success" -> MaterialTheme.colorScheme.tertiaryContainer
            "error" -> MaterialTheme.colorScheme.errorContainer
            else -> null
        }
        when (node.properties["style"] as? String) {
            "outline" -> androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(pad)) {
                    childId(node)?.let { id -> ctx.byId[id]?.let { child -> render(child, ctx, visited) } }
                }
            }
            "tonal", "filled" -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = toneContainer ?: MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(pad)) {
                    childId(node)?.let { id -> ctx.byId[id]?.let { child -> render(child, ctx, visited) } }
                }
            }
            else -> Card(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(pad)) {
                    childId(node)?.let { id -> ctx.byId[id]?.let { child -> render(child, ctx, visited) } }
                }
            }
        }
    }

    @Composable
    private fun RenderText(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
        val variant = node.properties["variant"] as? String
        val tone = toneColor(node.properties["tone"]).takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
        Text(
            text = resolveString(node.properties["text"], ctx),
            fontSize = when (variant) {
                "h1" -> 28.sp
                "h2" -> 24.sp
                "h3" -> 20.sp
                "h4" -> 18.sp
                "h5" -> 16.sp
                "label" -> 13.sp   // native parity: section row label (13sp Medium)
                "meta" -> 11.sp    // native parity: meta/caption line (11sp muted)
                "caption" -> 12.sp
                else -> 14.sp
            },
            fontWeight = when (variant) {
                "h1", "h2", "h3", "h4", "h5" -> FontWeight.Bold
                "label" -> FontWeight.Medium
                "value" -> FontWeight.Bold
                else -> FontWeight.Normal
            },
            color = when (variant) {
                "meta", "caption" -> tone ?: MaterialTheme.colorScheme.onSurfaceVariant
                else -> tone ?: androidx.compose.ui.graphics.Color.Unspecified
            },
        )
    }

    @Composable
    private fun RenderButton(node: A2uiComponentPayload, ctx: A2uiRenderContext, visited: Set<String>) {
        Button(onClick = { ctx.dispatchAction(node) }) {
            childId(node)?.let { id -> ctx.byId[id]?.let { child -> render(child, ctx, visited) } }
        }
    }

    @Composable
    private fun RenderIcon(node: A2uiComponentPayload, ctx: A2uiRenderContext) {
        val name = resolveString(node.properties["name"], ctx)
        val icon = iconVector(name) ?: Icons.Filled.Error
        val tone = toneColor(node.properties["tone"]).takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
        // 5.4h editable template knob: iconSize small|medium|large (default medium/24dp).
        val size = when (node.properties["iconSize"] as? String) {
            "small" -> 16.dp
            "large" -> 32.dp
            else -> 24.dp
        }
        Icon(
            imageVector = icon,
            contentDescription = name,
            tint = tone ?: androidx.compose.material3.LocalContentColor.current,
            modifier = Modifier.size(size),
        )
    }

    @Composable
    fun UnknownComponentPlaceholder(type: String) {
        Text(
            text = "⚠ Unknown component: $type",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

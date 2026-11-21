package dev.a2uicrm.agentic.agent

import dev.a2uicrm.agentic.action.CrmActionDispatcher
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.Task
import dev.a2uicrm.core.domain.TeamStats
import java.util.TimeZone

/** Built screen: components message + optional updateDataModel message. */
data class Composition(
    val componentsJson: String,
    val dataModelJson: String? = null,
)

/**
 * Builds A2UI 0.9.1 JSON compositions from CRM repository data (analysis.md §4.1).
 *
 * Conventions (kept honest for the demo):
 * - metric values use DataBinding `{"path": ...}` resolved via updateDataModel (the engine
 *   data flow), tasks/members are literals the agent filled from repository data;
 * - every screen follows the Home template: metrics row + sections + empty branch + CTA.
 */
object CompositionBuilder {

    private fun esc(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun envelope(surfaceId: String, components: String) =
        """{"version":"v0.9.1","updateComponents":{"surfaceId":"$surfaceId","components":[$components]}}"""

    private fun dataModel(surfaceId: String, path: String, value: String) =
        """{"version":"v0.9.1","updateDataModel":{"surfaceId":"$surfaceId","path":"$path","value":$value}}"""

    private fun toJsonArray(list: List<String>): String =
        list.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }

    private fun decline(n: Int): String = when {
        n == 1 -> "$n task"
        else -> "$n tasks"
    }

    private fun formatMicros(micros: Long): String {
        val dollars = micros / 1_000_000
        return if (dollars >= 1000) "$${dollars / 1000}.${(dollars % 1000) / 100}k" else "\$$dollars"
    }

    private fun weekday(iso: String): String {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        CrmActionDispatcher.parseIsoToEpoch(iso)?.let { cal.timeInMillis = it }
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "Monday"
            java.util.Calendar.TUESDAY -> "Tuesday"
            java.util.Calendar.WEDNESDAY -> "Wednesday"
            java.util.Calendar.THURSDAY -> "Thursday"
            java.util.Calendar.FRIDAY -> "Friday"
            java.util.Calendar.SATURDAY -> "Saturday"
            else -> "Sunday"
        }
    }

    /** Manager home: overdue/today/tomorrow + calendar extras + metrics + CTA (§4.1). */
    fun managerHome(
        surfaceId: String,
        overdue: List<Task>,
        today: List<Task>,
        tomorrow: List<Task>,
        extra: List<Pair<String, List<Task>>> = emptyList(),
        doneToday: Int = 5,
    ): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()

        // Alert banner if overdue exists (agent decision, noted in inference).
        if (overdue.isNotEmpty()) {
            parts += """{"id":"al1","component":"AlertBanner","severity":"warning","title":"${esc(decline(overdue.size))}","message":"${esc("${decline(overdue.size)} need attention")}","actionLabel":"View"}"""
            children += "al1"
        }

        // Metrics row with DataBinding.
        parts += """{"id":"m1","component":"MetricCard","label":"Overdue","value":{"path":"/stats/overdueCount"},"trend":"up","trendLabel":"${esc("+${(overdue.size - 1).coerceAtLeast(0)} per day")}"}"""
        parts += """{"id":"m2","component":"MetricCard","label":"Done today","value":{"path":"/stats/doneToday"},"trend":"flat","trendLabel":"on track"}"""
        parts += """{"id":"mrow","component":"Row","children":["m1","m2"]}"""
        children += "mrow"

        // Sections with tasks (ActionItem literals filled from repository data).
        // section() returns the header id AND the item ids — they must all be in the tree.
        children += section(parts, "Overdue", overdue, "error", "ov")
        children += section(parts, "Today", today, null, "td")
        if (tomorrow.isEmpty()) {
            parts += """{"id":"em1","component":"EmptyState","title":"Nothing for tomorrow","message":"All under control.","icon":"task"}"""
            children += "em1"
        } else {
            children += section(parts, "Tomorrow", tomorrow, null, "tm")
        }

        // Calendar extras (this week / next week / months / years) — only non-empty.
        extra.forEachIndexed { i, (title, list) ->
            if (list.isNotEmpty()) {
                children += section(parts, title, list, null, "ex$i")
            }
        }

        // Primary CTA (primary action of the manager role).
        parts += """{"id":"cta1","component":"PrimaryActionButton","label":"New task","icon":"add","action":{"event":{"name":"createTask","context":{"title":"New task","dueAt":"${CrmActionDispatcher.epochToIso(CrmActionDispatcher.defaultTomorrow9am())}"}}}}"""
        children += "cta1"

        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        val data = dataModel(surfaceId, "/stats", """{"overdueCount":${overdue.size},"doneToday":$doneToday}""")
        return Composition(envelope(surfaceId, components), data)
    }

    /**
     * Adds SectionHeader + ActionItems for [tasks]; returns ALL ids to attach to the parent.
     *
     * Two traps fixed here:
     * 1. ActionItems used to be emitted into [parts] but never added to root children —
     *    the tree showed headers only (empty middle after a rebuild).
     * 2. Header id was `"sh_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "")`:
     *    Cyrillic titles collapsed to the same `"sh_"` (three «Завтра N» headers).
     */
    private fun section(
        parts: MutableList<String>,
        title: String,
        tasks: List<Task>,
        tone: String?,
        itemPrefix: String,
    ): List<String> {
        // Unique ASCII ids: Cyrillic titles used to collapse to the same "sh_".
        // StringBuilder (not raw-string quote tricks) so ActionItems actually land in the tree.
        val headerId = "sh_$itemPrefix"
        val ids = mutableListOf(headerId)
        val header = StringBuilder()
        header.append('{').append('"').append("id").append('"').append(':').append('"').append(headerId)
        header.append('"').append(',').append('"').append("component").append('"').append(':').append('"').append("SectionHeader")
        header.append('"').append(',').append('"').append("title").append('"').append(':').append('"').append(esc(title))
        header.append('"').append(',').append('"').append("count").append('"').append(':').append(tasks.size)
        if (tone == "error") header.append(',').append('"').append("countTone").append('"').append(':').append('"').append("error").append('"')
        header.append('}')
        parts += header.toString()
        tasks.forEachIndexed { i, task ->
            val itemId = "${itemPrefix}$i"
            // Native parity: priority stays "normal" — the native TaskRow does not
            // recolor overdue titles; the error tone lives on the SECTION header only.
            val priority = "normal"
            val dueIso = task.dueAtEpochMs?.let { CrmActionDispatcher.epochToIso(it) } ?: ""
            val item = StringBuilder()
            item.append('{').append('"').append("id").append('"').append(':').append('"').append(itemId)
            item.append('"').append(',').append('"').append("component").append('"').append(':').append('"').append("ActionItem")
            item.append('"').append(',').append('"').append("taskId").append('"').append(':').append('"').append(esc(task.id))
            item.append('"').append(',').append('"').append("title").append('"').append(':').append('"').append(esc(task.title))
            item.append('"').append(',').append('"').append("dueAt").append('"').append(':').append('"').append(dueIso)
            item.append('"').append(',').append('"').append("completed").append('"').append(':').append(task.completed)
            item.append(',').append('"').append("priority").append('"').append(':').append('"').append(priority).append('"')
            task.contactName?.let {
                item.append(',').append('"').append("contactName").append('"').append(':').append('"').append(esc(it)).append('"')
            }
            item.append('}')
            parts += item.toString()
            ids += itemId
        }
        // Native parity: a thin Divider line after every section — exactly where
        // TodayScreen draws its HorizontalDivider (bug fix: agent sections had none).
        parts += """{"id":"dv_$itemPrefix","component":"Divider"}"""
        ids += "dv_$itemPrefix"
        return ids
    }

    /** Head home: team leaderboard + pipeline metrics + per-member deals + attention banner (§4.1). */
    fun headHome(surfaceId: String, stats: List<TeamStats>, deals: List<Deal> = emptyList()): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()

        val pipelineValue = stats.sumOf { it.pipelineValueMicros }
        val won = stats.sumOf { it.wonCount }

        parts += """{"id":"m1","component":"MetricCard","label":"Team pipeline","value":{"path":"/stats/teamPipelineValue"},"trend":"up","trendLabel":"+8% this week"}"""
        parts += """{"id":"m2","component":"MetricCard","label":"Wins this quarter","value":{"path":"/stats/teamWon"},"trend":"flat","trendLabel":"stable"}"""
        parts += """{"id":"mrow","component":"Row","children":["m1","m2"]}"""
        children += "mrow"

        children += "sh1"
        parts += """{"id":"sh1","component":"SectionHeader","title":"Leaderboard","count":${stats.size}}"""
        stats.sortedByDescending { it.pipelineValueMicros }.forEachIndexed { i, s ->
            children += "lb$i"
            parts += """{"id":"lb$i","component":"LeaderboardRow","rank":${i + 1},"memberId":"${esc(s.memberId)}","memberName":"${esc(s.memberName)}","metricLabel":"${s.openTasks} open tasks · ${s.overdueTasks} overdue","metricValue":"${esc(formatMicros(s.pipelineValueMicros))}"}"""
            val memberDeals = deals.filter { it.ownerId == s.memberId }
            if (memberDeals.isNotEmpty()) {
                children += "dl$i"
                val body = memberDeals.joinToString(" · ") {
                    "${esc(it.title)} (${esc(formatMicros(it.amountMicros))}, ${esc(it.stage)})"
                }
                parts += """{"id":"dl$i","component":"Text","text":"Open deals: $body","variant":"caption"}"""
            }
        }

        val withOverdue = stats.count { it.overdueTasks > 0 }
        if (withOverdue > 0) {
            children += "al1"
            parts += """{"id":"al1","component":"AlertBanner","severity":"warning","title":"${withOverdue} member(s) with overdue tasks","message":"Load distribution needs a look","actionLabel":"Review"}"""
        }

        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        val data = dataModel(
            surfaceId, "/stats",
            """{"teamPipelineValue":"${esc(formatMicros(pipelineValue))}","teamWon":$won}""",
        )
        return Composition(envelope(surfaceId, components), data)
    }

    /** Create-task draft screen (user asked to add a task; agent prepared a CTA). */
    fun createTaskDraft(surfaceId: String, title: String, dueAtIso: String): Composition {
        val components = """
            {"id":"root","component":"Column","children":["ec1","cta1"]},
            {"id":"ec1","component":"EntityCard","entityType":"task","entityId":"draft","title":"${esc(title)}","subtitle":"Draft — not saved yet","badge":"На ${weekday(dueAtIso)}"},
            {"id":"cta1","component":"PrimaryActionButton","label":"Create task","icon":"check","action":{"event":{"name":"createTask","context":{"title":"${esc(title)}","dueAt":"$dueAtIso"}}}}
        """.trimIndent()
        return Composition(envelope(surfaceId, components))
    }

    /**
     * Refusal screen (analysis.md §5.2): a SAFE explanation + a catalog-supported
     * alternative — the agent says what it CANNOT do and offers what it CAN.
     * Never renders the requested unsupported component.
     */
    fun refusalScreen(
        surfaceId: String,
        requested: String,
        reason: String,
        alternativeLabel: String,
        alternativeAction: Map<String, Any?>,
    ): Composition {
        val components = """
            {"id":"root","component":"Column","children":["rs1","cta1"]},
            {"id":"rs1","component":"EmptyState","title":"«${esc(requested)}» — outside the catalog","message":"${esc(reason)}","icon":"task"},
            {"id":"cta1","component":"PrimaryActionButton","label":"${esc(alternativeLabel)}","icon":"refresh","action":{"event":${eventJson(alternativeAction)}}}
        """.trimIndent()
        return Composition(envelope(surfaceId, components))
    }

    private fun eventJson(event: Map<String, Any?>): String {
        val name = event["name"]
        val context = event["context"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val sb = StringBuilder()
        sb.append('{').append('"').append("name").append('"').append(':').append('"').append(name).append('"')
        sb.append(',').append('"').append("context").append('"').append(':').append('{')
        context.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(esc(k.toString())).append('"').append(':').append('"').append(esc(v.toString())).append('"')
        }
        sb.append('}').append('}')
        return sb.toString()
    }

    /** Analytics screen (5.3): pipeline funnel + weekly trend, head role (§4.1). */
    fun analyticsScreen(
        surfaceId: String,
        stages: List<dev.a2uicrm.core.domain.PipelineStage>,
        trend: List<dev.a2uicrm.core.domain.TrendPoint>,
    ): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()

        parts += """{"id":"m1","component":"MetricCard","label":"Pipeline (3+ stages)","value":{"path":"/pipeline/totalValue"},"trend":"up","trendLabel":"+12% this week"}"""
        parts += """{"id":"m2","component":"MetricCard","label":"Lead→win conversion","value":{"path":"/pipeline/conversion"},"trend":"up","trendLabel":"+4 pts"}"""
        parts += """{"id":"mrow","component":"Row","children":["m1","m2"]}"""
        children += "mrow"

        children += "sh1"
        parts += """{"id":"sh1","component":"SectionHeader","title":"Funnel","count":${stages.size}}"""
        val stagesJson = stages.joinToString(",") { s ->
            val sb = StringBuilder()
            sb.append("{\"name\":\"").append(esc(s.label)).append("\",\"count\":").append(s.count)
            sb.append(",\"amount\":\"").append(esc(formatMicros(s.valueMicros))).append("\"")
            if (s.stageId == "st-prop") sb.append(",\"highlight\":true")
            sb.append("}")
            sb.toString()
        }
        parts += """{"id":"pipe1","component":"PipelineStrip","stages":[$stagesJson],"selectedStage":"${esc(stages.getOrNull(2)?.label ?: "")}"}"""
        children += "pipe1"

        children += "sh2"
        parts += """{"id":"sh2","component":"SectionHeader","title":"Pipeline this week","count":${trend.size}}"""
        val pointsJson = trend.joinToString(",") { it.value.toString() }
        val labelsJson = trend.joinToString(",") { "\"" + esc(it.label) + "\"" }
        parts += """{"id":"tr1","component":"TrendChart","kind":"bar","points":[$pointsJson],"labels":[$labelsJson],"unit":"k$","caption":"Open deals value by day"}"""
        children += "tr1"

        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        val totalValue = formatMicros(stages.sumOf { it.valueMicros })
        val conversion = if (stages.first().count > 0) {
            "%.0f%%".format(100.0 * stages.last().count / stages.first().count)
        } else "0%"
        val data = dataModel(
            surfaceId, "/pipeline",
            """{"totalValue":"${esc(totalValue)}","conversion":"$conversion"}""",
        )
        return Composition(envelope(surfaceId, components), data)
    }

    /** Generic task sections (выполненные / все задачи / любой набор групп). */
    fun tasksScreen(surfaceId: String, sections: List<Pair<String, List<Task>>>): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()
        val nonEmpty = sections.filter { it.second.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            parts += """{"id":"em1","component":"EmptyState","title":"No tasks","message":"Nothing here yet.","icon":"task"}"""
            children += "em1"
        } else {
            nonEmpty.forEachIndexed { i, (title, list) ->
                children += section(
                    parts,
                    title,
                    list,
                    if (title == "Overdue") "error" else null,
                    "s$i",
                )
            }
        }
        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        return Composition(envelope(surfaceId, components))
    }

    /** «Team» screen twin of the NATIVE Команда view: header + leaderboard rows. */
    fun teamScreen(surfaceId: String, stats: List<TeamStats>): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()
        children += "sh1"
        // Native parity: the SAME section label the native TeamScreen shows
        // ("Leaderboard: pipeline") — the twin must render identically.
        parts += """{"id":"sh1","component":"SectionHeader","title":"Leaderboard: pipeline","count":${stats.size}}"""
        stats.sortedByDescending { it.pipelineValueMicros }.forEachIndexed { i, s ->
            children += "lb$i"
            // Native parity: the SAME subtitle the native TeamScreen shows under the
            // name ("N open tasks · M overdue"), no invented delta percentages.
            parts += """{"id":"lb$i","component":"LeaderboardRow","rank":${i + 1},"memberId":"${esc(s.memberId)}","memberName":"${esc(s.memberName)}","metricLabel":"${s.openTasks} open tasks · ${s.overdueTasks} overdue","metricValue":"${esc(formatMicros(s.pipelineValueMicros))}"}"""
        }
        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        return Composition(envelope(surfaceId, components))
    }

    /**
     * «Analytics» twin of the NATIVE Аналитика view — VISUAL PARITY IS THE POINT:
     * the user asks to change this screen and must see ONLY what they asked for,
     * so the twin the agent edits must render EXACTLY like the native screen:
     * - small caption "Total in funnel: $X" (NOT a big MetricCard with a trend arrow);
     * - SectionHeader "Funnel";
     * - ONE full-width atom Row per stage (distinct icon + label + "deals: N · conv X%"
     *   + bold value) — VERTICAL, like the native rows, not a horizontal strip;
     * - the "New deal" CTA at the bottom.
     * Stage rows carry a "stage" property so deterministic merges can insert deal
     * EntityCards right AFTER a stage row ("show deals under each funnel stage").
     */
    fun analyticsTwin(surfaceId: String, stages: List<dev.a2uicrm.core.domain.PipelineStage>): Composition {
        val parts = mutableListOf<String>()
        val children = mutableListOf<String>()
        val total = formatMicros(stages.sumOf { it.valueMicros })

        children += "tt1"
        parts += """{"id":"tt1","component":"Text","text":"Total in funnel: ${esc(total)}","variant":"caption","tone":"neutral"}"""

        children += "sh1"
        parts += """{"id":"sh1","component":"SectionHeader","title":"Funnel","count":${stages.size}}"""

        stages.forEachIndexed { i, s ->
            val iconToken = stageIconTokenFor(s.stageId, s.label)
            val conv = s.conversionFromPrevious?.let { " · conv ${"%.0f".format(it * 100)}%" } ?: ""
            // ONLY the stage Row goes into root children: its internals (icon, label
            // column, value) are referenced by the Row's own "children". Listing them
            // at root as well made the renderer draw every stage row TWICE — a proper
            // row plus a loose "Lead / deals: N / $X" cluster below the deals (the
            // "extra blocks" bug; found live on the emulator 18.11, block 5.5).
            children += "stg$i"
            parts += """{"id":"stg$i","component":"Row","gap":"tight","stage":"${esc(s.label)}","children":["sic$i","scl$i","sval$i"]}"""
            parts += """{"id":"sic$i","component":"Icon","name":"$iconToken","tone":"primary"}"""
            parts += """{"id":"scl$i","component":"Column","gap":"tight","flex":true,"children":["slb$i","smt$i"]}"""
            parts += """{"id":"slb$i","component":"Text","text":"${esc(s.label)}","variant":"label"}"""
            parts += """{"id":"smt$i","component":"Text","text":"deals: ${s.count}$conv","variant":"meta"}"""
            parts += """{"id":"sval$i","component":"Text","text":"${esc(formatMicros(s.valueMicros))}","variant":"value"}"""
        }

        children += "cta1"
        parts += """{"id":"cta1","component":"PrimaryActionButton","label":"New deal","icon":"add","action":{"event":{"name":"createTask","context":{}}}}"""

        val components = buildString {
            append("""{"id":"root","component":"Column","children":${toJsonArray(children)}},""")
            parts.forEachIndexed { i, p -> append(p); if (i < parts.size - 1) append(",") }
        }
        return Composition(envelope(surfaceId, components))
    }

    /** Stage icon token — the SAME mapping the native AnalyticsScreen uses (bug fix:
     *  all stages used to share one icon; the twin must keep the per-stage icons). */
    fun stageIconTokenFor(stageId: String, label: String): String = when (stageId) {
        "st-lead" -> "person"
        "st-qualified" -> "checkCircle"
        "st-proposal" -> "description"
        "st-won" -> "storefront"
        else -> when {
            label.contains("won", ignoreCase = true) -> "storefront"
            label.contains("qualif", ignoreCase = true) -> "checkCircle"
            label.contains("propos", ignoreCase = true) -> "description"
            label.contains("lead", ignoreCase = true) -> "person"
            else -> "work"
        }
    }

    /** Minimal always-valid composition (self-correction fallback). */
    fun safeFallback(surfaceId: String): String =
        envelope(
            surfaceId,
            """{"id":"root","component":"Column","children":["m1"]},""" +
                """{"id":"m1","component":"MetricCard","label":"Overdue","value":"0","trend":"flat"}""",
        )
}

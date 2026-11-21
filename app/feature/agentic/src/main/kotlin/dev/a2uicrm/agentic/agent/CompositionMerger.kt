package dev.a2uicrm.agentic.agent

import dev.a2uicrm.agentic.json.JsonValue
import dev.a2uicrm.agentic.json.stringify
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.Task

/**
 * Deterministic edits on an EXISTING 0.9.1 composition — the «augment, don't rebuild»
 * contract: «добавь сюда выполненные» must APPEND to the current screen, never replace.
 * Parse (own JsonDom) → mutate → stringify. Nothing throws: malformed input → null,
 * the caller falls back to a full rebuild.
 */
object CompositionMerger {

    private fun envelopeParts(json: String): Triple<JsonValue.JsonObject, JsonValue.JsonObject, List<JsonValue>>? {
        return try {
            val root = JsonValue.parse(json) as? JsonValue.JsonObject ?: return null
            val msg = root["updateComponents"] as? JsonValue.JsonObject ?: return null
            val components = msg["components"] as? JsonValue.JsonArray ?: return null
            Triple(root, msg, components.items)
        } catch (e: Exception) {
            null
        }
    }

    private fun rebuild(root: JsonValue.JsonObject, msg: JsonValue.JsonObject, items: List<JsonValue>): String {
        val newMsg = JsonValue.JsonObject(msg.entries + ("components" to JsonValue.JsonArray(items)))
        val newRoot = JsonValue.JsonObject(root.entries + ("updateComponents" to newMsg))
        return newRoot.stringify()
    }

    private fun List<JsonValue>.objs(): List<JsonValue.JsonObject> = mapNotNull { it as? JsonValue.JsonObject }

    private fun s(v: String) = JsonValue.JsonString(v)
    private fun b(v: Boolean) = JsonValue.JsonBool(v)
    private fun n(v: Int) = JsonValue.JsonNumber(v.toDouble())
    private fun arr(items: List<JsonValue>) = JsonValue.JsonArray(items)

    private fun childrenOf(rootObj: JsonValue.JsonObject): List<String> =
        (rootObj["children"] as? JsonValue.JsonArray)?.items
            ?.mapNotNull { (it as? JsonValue.JsonString)?.value }
            .orEmpty()

    /**
     * Appends [newComponents]: new objects go into the components array, their ids are
     * inserted into root children BEFORE the trailing CTA (PrimaryActionButton).
     * Returns null on id collision / bad shape.
     */
    fun appendComponents(componentsJson: String, newComponents: List<JsonValue.JsonObject>): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val existingIds = objs.mapNotNull { it.string("id") }.toHashSet()
        val newIds = newComponents.mapNotNull { it.string("id") }
        if (newIds.size != newComponents.size || newIds.any { it in existingIds }) return null

        val rootObj = objs.firstOrNull { it.string("id") == "root" } ?: return null
        val oldChildren = childrenOf(rootObj)

        val ctaId = objs.lastOrNull { it.string("component") == "PrimaryActionButton" }?.string("id")
        val insertAt = ctaId?.let { oldChildren.indexOf(it).takeIf { i -> i >= 0 } } ?: oldChildren.size
        val children = oldChildren.subList(0, insertAt) + newIds + oldChildren.subList(insertAt, oldChildren.size)

        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(children.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it } + newComponents
        return rebuild(root, msg, newItems)
    }

    /**
     * Toggles `completed` on the ActionItem with [taskId] AND MOVES the node between
     * sections: completing puts it under the «Completed» SectionHeader; un-completing
     * returns it to [homeSection] (its calendar bucket — «Overdue», «Today», …) and
     * CREATES that section (in calendar order) when it is not on the screen yet.
     * Section counts follow the move (counted from the tree, not trusted from the
     * header prop); the Completed section's EmptyState placeholder disappears when
     * it gains an item and is restored when it empties again.
     * The meta line (deadline + contact) SURVIVES the toggle: an LLM-built node may
     * carry an empty dueAt — it is filled from [task] on every flip (18.11 feedback:
     * the deadline under the title must not disappear on completing).
     * Falls back to the pure prop flip (old behavior) when the target section is
     * absent (and cannot be created) or the item is not a direct root child.
     */
    fun patchCompleted(
        componentsJson: String,
        taskId: String,
        completed: Boolean,
        homeSection: String? = null,
        task: Task? = null,
    ): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val itemObj = objs.firstOrNull { it.string("component") == "ActionItem" && it.string("taskId") == taskId }
            ?: return null
        val itemId = itemObj.string("id") ?: return null

        /** Flip + keep the meta line: fill a missing dueAt/contactName from [task]. */
        fun flip(o: JsonValue.JsonObject): JsonValue.JsonObject {
            val entries = LinkedHashMap<String, JsonValue>(o.entries)
            entries["completed"] = b(completed)
            task?.dueAtEpochMs?.let { d ->
                if (o.string("dueAt").isNullOrBlank()) {
                    entries["dueAt"] = s(dev.a2uicrm.agentic.action.CrmActionDispatcher.epochToIso(d))
                }
            }
            task?.contactName?.let { c ->
                if (o.string("contactName").isNullOrBlank()) entries["contactName"] = s(c)
            }
            return JsonValue.JsonObject(entries)
        }

        fun flipOnly(): String? {
            val newItems = items.map { item ->
                val o = item as? JsonValue.JsonObject ?: return@map item
                if (o.string("id") == itemId) flip(o) else o
            }
            return rebuild(root, msg, newItems)
        }

        val targetTitle = if (completed) "Completed" else homeSection
        if (targetTitle.isNullOrBlank()) return flipOnly()
        var targetHeaderId = objs
            .filter { it.string("component") == "SectionHeader" && it.string("title") == targetTitle }
            .mapNotNull { it.string("id") }
            .firstOrNull()

        val children = childrenOf(rootObj).toMutableList()
        var itemIdx = children.indexOf(itemId)
        val extraComponents = mutableListOf<JsonValue.JsonObject>()

        // 18.11: un-completing must return the task to its calendar bucket EVEN WHEN
        // that section is not on the screen — create it (header + divider) in calendar
        // order and let the common move path do the rest.
        if (targetHeaderId == null && !completed && homeSection != null && itemIdx >= 0) {
            val headerId = "sh_hm$taskId"
            val dividerId = "dv_hm$taskId"
            val existingIds = objs.mapNotNull { it.string("id") }.toHashSet()
            if (headerId in existingIds || dividerId in existingIds) return flipOnly()
            children.addAll(calendarInsertIndex(children, byId, homeSection), listOf(headerId, dividerId))
            val headerEntries = linkedMapOf<String, JsonValue>(
                "id" to s(headerId),
                "component" to s("SectionHeader"),
                "title" to s(homeSection),
                "count" to n(0),
            )
            // Native parity: the Overdue header carries the error tone (red count).
            if (homeSection == "Overdue") headerEntries["countTone"] = s("error")
            extraComponents += JsonValue.JsonObject(headerEntries)
            extraComponents += JsonValue.JsonObject(
                linkedMapOf(
                    "id" to s(dividerId),
                    "component" to s("Divider"),
                ),
            )
            targetHeaderId = headerId
            itemIdx = children.indexOf(itemId) // shifted by the insertion
        }

        val targetHeader = targetHeaderId ?: return flipOnly()
        if (itemIdx < 0 || children.indexOf(targetHeader) < 0) return flipOnly()
        val owningHeaderIdx = lastHeaderIndexBefore(children, itemIdx, byId)
        if (owningHeaderIdx == children.indexOf(targetHeader)) return flipOnly() // already in the target section

        // Section walk map — includes the freshly created components.
        val map = byId + extraComponents.associateBy { it.string("id") ?: "" }

        // Section boundary (exclusive): items end at a Divider / EmptyState / the next
        // SectionHeader / the CTA — the same trailing rule as insertActionItem.
        fun sectionEnd(ch: List<String>, hIdx: Int, m: Map<String, JsonValue.JsonObject>): Int {
            var i = hIdx + 1
            while (i < ch.size) {
                val t = m[ch[i]]?.string("component")
                if (t == "SectionHeader" || t == "PrimaryActionButton" || t == "Divider" || t == "EmptyState") break
                i++
            }
            return i
        }

        fun actionCount(ch: List<String>, hIdx: Int, m: Map<String, JsonValue.JsonObject>): Int {
            var i = hIdx + 1
            var c = 0
            while (i < ch.size) {
                val t = m[ch[i]]?.string("component")
                if (t == "SectionHeader" || t == "PrimaryActionButton" || t == "Divider" || t == "EmptyState") break
                if (t == "ActionItem") c++
                i++
            }
            return c
        }

        // 1) take the item out of its current section. Indices at or before the owning
        //    header are unaffected (the item always sits AFTER its header), so the id
        //    is captured HERE — later insertions may shift the owning header's index.
        children.removeAt(itemIdx)
        val oldHeaderId = if (owningHeaderIdx >= 0) children[owningHeaderIdx] else null

        // 2) put it into the target section, after its items, before its Divider /
        //    EmptyState / the next header. The Completed placeholder goes away the
        //    moment the section has real content.
        val headerIdx = children.indexOf(targetHeader)
        val end = sectionEnd(children, headerIdx, map)
        if (end < children.size && map[children[end]]?.string("component") == "EmptyState") {
            children.removeAt(end)
        }
        children.add(end, itemId)

        // 3) if the item just left an EMPTYING Completed section, restore the
        //    "No completed tasks" placeholder (reuse an orphan node when possible).
        if (oldHeaderId != null && map[oldHeaderId]?.string("title") == "Completed" &&
            actionCount(children, children.indexOf(oldHeaderId), map) == 0
        ) {
            val referenced = children.toHashSet()
            val orphan = objs.firstOrNull {
                it.string("component") == "EmptyState" &&
                    (it.string("title") ?: "").contains("completed", ignoreCase = true) &&
                    (it.string("id") ?: "") !in referenced
            }
            val esId = orphan?.string("id")
                ?: ("es_completed".takeIf { it !in byId.keys })
            if (esId != null) {
                children.add(children.indexOf(oldHeaderId) + 1, esId)
                if (orphan == null) {
                    extraComponents += JsonValue.JsonObject(
                        linkedMapOf(
                            "id" to s(esId),
                            "component" to s("EmptyState"),
                            "title" to s("No completed tasks"),
                            "message" to s("You have not completed any tasks yet."),
                            "icon" to s("checkCircle"),
                        ),
                    )
                }
            }
        }

        // The walk map for everything below — includes every component added so far.
        val mapNow = byId + extraComponents.associateBy { it.string("id") ?: "" }

        // 3b) an emptied HOME (calendar) section is REMOVED from the screen — an empty
        //     «Overdue (0)» header must not hang there (18.11 feedback). «Completed»
        //     keeps its placeholder instead (step 3). The dropped nodes also leave the
        //     components array, so a later re-creation (un-check) cannot collide on ids.
        val removedIds = mutableSetOf<String>()
        if (oldHeaderId != null) {
            val oldTitle = mapNow[oldHeaderId]?.string("title")
            val oldIdx = children.indexOf(oldHeaderId)
            if (oldIdx >= 0 && oldTitle != null &&
                sectionRank(oldTitle) < calendarSectionOrder.size &&
                actionCount(children, oldIdx, mapNow) == 0
            ) {
                var cutEnd = sectionEnd(children, oldIdx, mapNow)
                // Absorb the section's own trailing divider / placeholder (at most a
                // placeholder followed by one divider).
                while (cutEnd < children.size) {
                    val t = mapNow[children[cutEnd]]?.string("component")
                    if (t == "Divider" || t == "EmptyState") {
                        removedIds += children[cutEnd]
                        cutEnd++
                        if (t == "Divider") break
                    } else break
                }
                removedIds += oldHeaderId
                children.subList(oldIdx, cutEnd).clear()
            }
        }

        // 4) counts follow the move (re-looked-up by id — indices shifted above).
        val newCountOld = oldHeaderId
            ?.takeIf { it !in removedIds }
            ?.let { actionCount(children, children.indexOf(it), mapNow) }
        val newCountNew = actionCount(children, children.indexOf(targetHeader), mapNow)

        // A CREATED section header lives in extraComponents (not in the original items
        // array) — its count is patched here, same as the mapped headers below.
        val patchedExtra = extraComponents.map { o ->
            if (o.string("id") == targetHeader) JsonValue.JsonObject(o.entries + ("count" to n(newCountNew))) else o
        }

        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(children.map { s(it) })))
        val mapped = items
            .filter { (it as? JsonValue.JsonObject)?.string("id") !in removedIds }
            .map { item ->
                val o = item as? JsonValue.JsonObject ?: return@map item
                val id = o.string("id")
                when {
                    id == itemId -> flip(o)
                    id == oldHeaderId && newCountOld != null -> JsonValue.JsonObject(o.entries + ("count" to n(newCountOld)))
                    id == targetHeaderId -> JsonValue.JsonObject(o.entries + ("count" to n(newCountNew)))
                    else -> o
                }
            }
        val finalItems = (mapped + patchedExtra).map {
            if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it
        }
        return rebuild(root, msg, finalItems)
    }

    /** The index of the last SectionHeader that precedes [idx] in root children. */
    private fun lastHeaderIndexBefore(children: List<String>, idx: Int, byId: Map<String, JsonValue.JsonObject>): Int {
        for (i in idx - 1 downTo 0) {
            val o = byId[children[i]] ?: continue
            if (o.string("component") == "SectionHeader") return i
        }
        return -1
    }

    /** Calendar bucket order (groupByCalendar.asSections); «Completed» ranks after it. */
    private val calendarSectionOrder = listOf(
        "Overdue", "Today", "Tomorrow", "This week", "Next week",
        "This month", "Next month", "This year", "Next year", "Later",
    )

    private fun sectionRank(title: String?): Int = when (title) {
        "Completed" -> calendarSectionOrder.size
        null -> Int.MAX_VALUE
        else -> calendarSectionOrder.indexOf(title).let { if (it >= 0) it else calendarSectionOrder.size + 1 }
    }

    /**
     * Where a NEW calendar section [title] starts: right before the first existing
     * section ranked after it (calendar order, «Completed» last), or before the
     * trailing CTA, or at the very end.
     */
    private fun calendarInsertIndex(children: List<String>, byId: Map<String, JsonValue.JsonObject>, title: String): Int {
        val rank = sectionRank(title)
        for (i in children.indices) {
            val o = byId[children[i]] ?: continue
            if (o.string("component") == "SectionHeader" && sectionRank(o.string("title")) > rank) return i
        }
        val ctaIdx = children.indexOfLast { byId[it]?.string("component") == "PrimaryActionButton" }
        return if (ctaIdx >= 0) ctaIdx else children.size
    }

    /**
     * Inserts an ActionItem into the section titled [sectionTitle] (after its trailing
     * items). Null if the section is missing (caller appends a whole section instead).
     */
    fun insertActionItem(componentsJson: String, sectionTitle: String, task: Task, itemId: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val children = childrenOf(rootObj)

        val headerId = objs
            .filter { it.string("component") == "SectionHeader" && it.string("title") == sectionTitle }
            .mapNotNull { it.string("id") }
            .firstOrNull() ?: return null
        val headerIdx = children.indexOf(headerId)
        if (headerIdx < 0) return null

        var insertAt = headerIdx + 1
        while (insertAt < children.size) {
            val type = byId[children[insertAt]]?.string("component")
            // 5.5: a Divider ENDS a section (the trailing rule) — insert before it,
            // so a new task stays visually inside its section.
            if (type == "SectionHeader" || type == "PrimaryActionButton" || type == "EmptyState" || type == "Divider") break
            insertAt++
        }
        val itemObj = actionItemObj(itemId, task, error = false)
        val newChildren = children.subList(0, insertAt) + listOf(itemId) + children.subList(insertAt, children.size)
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it } + itemObj
        return rebuild(root, msg, newItems)
    }

    // ---------- fragments (component builders) ----------

    /** SectionHeader + ActionItems for [tasks] — DOM twin of CompositionBuilder.section(). */
    fun sectionComponents(title: String, tasks: List<Task>, prefix: String, error: Boolean = false): List<JsonValue.JsonObject> {
        val headerEntries = linkedMapOf<String, JsonValue>(
            "id" to s("sh_$prefix"),
            "component" to s("SectionHeader"),
            "title" to s(title),
            "count" to n(tasks.size),
        )
        if (error) headerEntries["countTone"] = s("error")
        val header = JsonValue.JsonObject(headerEntries)
        val items = tasks.mapIndexed { i, task -> actionItemObj("${prefix}$i", task, error) }
        // 5.5 native parity: a Divider line BEFORE an appended section — the native
        // screens separate sections with a thin rule; agent additions must too.
        val divider = JsonValue.JsonObject(
            linkedMapOf(
                "id" to s("dv_$prefix"),
                "component" to s("Divider"),
            ),
        )
        return listOf(divider, header) + items
    }

    private fun actionItemObj(id: String, task: Task, error: Boolean): JsonValue.JsonObject {
        val entries = linkedMapOf<String, JsonValue>(
            "id" to s(id),
            "component" to s("ActionItem"),
            "taskId" to s(task.id),
            "title" to s(task.title),
            "dueAt" to s(task.dueAtEpochMs?.let { dev.a2uicrm.agentic.action.CrmActionDispatcher.epochToIso(it) } ?: ""),
            "completed" to b(task.completed),
            // 5.5 native parity: priority stays "normal" — the native TaskRow does not
            // recolor overdue titles; the error tone lives on the SECTION header only.
            "priority" to s("normal"),
        )
        task.contactName?.let { entries["contactName"] = s(it) }
        return JsonValue.JsonObject(entries)
    }

    /** EntityCards for open deals («добавь сделки» augment). */
    fun dealCardComponents(deals: List<Deal>, prefix: String): List<JsonValue.JsonObject> {
        return deals.mapIndexed { i, d ->
            JsonValue.JsonObject(
                linkedMapOf(
                    "id" to s("${prefix}$i"),
                    "component" to s("EntityCard"),
                    "entityType" to s("opportunity"),
                    "entityId" to s(d.id),
                    "title" to s(d.title),
                    "subtitle" to s("Stage: ${d.stage}"),
                    "amount" to s(formatMicros(d.amountMicros)),
                    "amountMicros" to JsonValue.JsonNumber(d.amountMicros.toDouble()),
                    "badge" to s(d.stage),
                    "footer" to arr(listOfNotNull(d.contactName?.let { s(it) })),
                ),
            )
        }
    }

    /** «Открытые сделки» section: header + EntityCards (for the augment merge). */
    fun dealSectionComponents(deals: List<Deal>, prefix: String): List<JsonValue.JsonObject> {
        if (deals.isEmpty()) return emptyList()
        val header = JsonValue.JsonObject(
            linkedMapOf(
                "id" to s("sh_$prefix"),
                "component" to s("SectionHeader"),
                "title" to s("Open deals"),
                "count" to n(deals.size),
            ),
        )
        // 5.5 native parity: divider before an appended section (thin rule).
        val divider = JsonValue.JsonObject(
            linkedMapOf(
                "id" to s("dv_$prefix"),
                "component" to s("Divider"),
            ),
        )
        return listOf(divider, header) + dealCardComponents(deals, "${prefix}d")
    }

    private fun formatMicros(micros: Long): String {
        val dollars = micros / 1_000_000
        return if (dollars >= 1000) "$${dollars / 1000}.${(dollars % 1000) / 100}k" else "\$$dollars"
    }


    // ---------- 5.4g sandbox operations: query / move / sort / stage-group / sanitize ----------

    /** Does the screen already contain a SectionHeader with this title? */
    fun hasSection(componentsJson: String, title: String): Boolean {
        val parts = envelopeParts(componentsJson) ?: return false
        return parts.third.objs().any {
            it.string("component") == "SectionHeader" && it.string("title") == title
        }
    }

    /**
     * Deals grouped BY FUNNEL STAGE (the funnel IS the ranking): one section per stage
     * in [stageOrder] order, deals inside sorted by amount desc. Section titles are the
     * stage labels; the section gets a stage icon. EntityCards keep the openEntity action.
     */
    fun dealStageSectionComponents(
        deals: List<Deal>,
        stageOrder: List<String>,
        prefix: String,
    ): List<JsonValue.JsonObject> {
        if (deals.isEmpty()) return emptyList()
        val stageIcon = mapOf(
            "Lead" to "flag", "Qualified" to "filterAlt", "Proposal" to "description",
            "Won" to "emojiEvents", "Lost" to "close",
        )
        val out = mutableListOf<JsonValue.JsonObject>()
        val known = stageOrder.filter { stage -> deals.any { it.stage == stage } }
        val rest = deals.filter { it.stage !in stageOrder }
        (known + listOfNotNull(if (rest.isEmpty()) null else "Other")).forEachIndexed { si, stage ->
            val list = if (stage == "Other") rest else deals.filter { it.stage == stage }
            if (list.isEmpty()) return@forEachIndexed
            val headerEntries = linkedMapOf<String, JsonValue>(
                "id" to s("sh_${prefix}$si"),
                "component" to s("SectionHeader"),
                "title" to s("$stage deals"),
                "count" to n(list.size),
                "icon" to s(stageIcon[stage] ?: "work"),
            )
            val total = list.sumOf { it.amountMicros }
            headerEntries["subtitle"] = s(formatMicros(total))
            out += JsonValue.JsonObject(headerEntries)
            list.sortedByDescending { it.amountMicros }.forEachIndexed { i, d ->
                val card = dealCardComponents(listOf(d), "${prefix}${si}_").first()
                val withId = JsonValue.JsonObject(
                    card.entries + ("id" to s("${prefix}${si}_$i")),
                )
                val action = JsonValue.JsonObject(
                    linkedMapOf(
                        "event" to JsonValue.JsonObject(
                            linkedMapOf(
                                "name" to s("openEntity"),
                                "context" to JsonValue.JsonObject(
                                    linkedMapOf("entityType" to s("opportunity"), "entityId" to s(d.id)),
                                ),
                            ),
                        ),
                    ),
                )
                out += JsonValue.JsonObject(withId.entries + ("action" to action))
            }
        }
        return out
    }

    /**
     * 5.5 «show deals under each funnel stage»: inserts deal EntityCards DIRECTLY AFTER
     * every stage row (a root-level Row carrying a "stage" property — the analytics
     * twin emits these). The funnel stays in place; the deals appear UNDER their stage.
     * Returns null when the screen has no stage rows (caller falls back to a section
     * append) or when nothing matched.
     */
    fun insertDealCardsAfterStages(componentsJson: String, deals: List<Deal>, prefix: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val children = childrenOf(rootObj).toMutableList()

        // Stage rows: root children that are Rows with a "stage" property.
        val stageRows = children.filter { id ->
            val o = byId[id]
            o?.string("component") == "Row" && !o.string("stage").isNullOrBlank()
        }
        if (stageRows.isEmpty()) return null

        val existingIds = objs.mapNotNull { it.string("id") }.toHashSet()
        val newComponents = mutableListOf<JsonValue.JsonObject>()
        var inserted = 0
        // Walk children and insert deal cards right after each matching stage row.
        val newChildren = mutableListOf<String>()
        for (id in children) {
            newChildren += id
            val o = byId[id] ?: continue
            val stage = o.string("stage") ?: continue
            val stageDeals = deals.filter { it.stage == stage }.sortedByDescending { it.amountMicros }
            stageDeals.forEachIndexed { i, d ->
                val cardId = "${prefix}_${id}_$i"
                if (cardId in existingIds) return@forEachIndexed
                val card = dealCardComponents(listOf(d), "${prefix}_${id}_$i").first()
                newComponents += JsonValue.JsonObject(card.entries + ("id" to s(cardId)))
                newChildren += cardId
                inserted++
            }
        }
        if (inserted == 0) return null
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it } + newComponents
        return rebuild(root, msg, newItems)
    }

    /**
     * 5.5 «add chart» augment fragment: a TrendChart built from REAL repository trend
     * points (never invented numbers). Inserted into the current screen as-is.
     */
    fun trendChartComponents(
        points: List<Pair<String, Double>>,
        prefix: String,
        caption: String,
        unit: String? = null,
    ): List<JsonValue.JsonObject> {
        if (points.size < 3) return emptyList()
        val entries = linkedMapOf<String, JsonValue>(
            "id" to s("tr_$prefix"),
            "component" to s("TrendChart"),
            "kind" to s("bar"),
            "points" to arr(points.map { JsonValue.JsonNumber(it.second) }),
            "labels" to arr(points.map { s(it.first) }),
            "showValues" to b(true),
            "caption" to s(caption),
        )
        unit?.let { entries["unit"] = s(it) }
        return listOf(JsonValue.JsonObject(entries))
    }

    /**
     * 5.5 STRUCTURAL preservation (the add-request guard, lighter than
     * [validatePreservation]): an ADD must keep every existing component id and the
     * old children order as a subsequence — insertions are the point, replacements
     * are NOT. Property edits (counts, texts) are allowed: they are legitimate
     * consequences of an addition. Non-empty list = the screen was restructured.
     */
    fun validateStructure(oldJson: String, newJson: String): List<String> {
        val oldParts = envelopeParts(oldJson) ?: return listOf("old screen unparsable")
        val newParts = envelopeParts(newJson) ?: return listOf("new screen unparsable")
        val oldObjs = oldParts.third.objs()
        val newIds = newParts.third.objs().mapNotNull { it.string("id") }.toHashSet()

        val errors = mutableListOf<String>()
        val removed = oldObjs.mapNotNull { it.string("id") }.filter { it.isNotEmpty() && it !in newIds }
        if (removed.isNotEmpty()) {
            errors += "rebuilt the screen: removed components ${removed.joinToString(",").take(80)}"
        }

        val oldChildren = oldObjs.firstOrNull { it.string("id") == "root" }?.let { childrenOf(it) }.orEmpty()
        val newChildren = newParts.third.objs().firstOrNull { it.string("id") == "root" }?.let { childrenOf(it) }.orEmpty()
        var i = 0
        for (child in newChildren) {
            if (i < oldChildren.size && child == oldChildren[i]) i++
        }
        if (i < oldChildren.size) {
            errors += "screen order broken: missing ${oldChildren.drop(i).joinToString(",").take(80)}"
        }
        return errors
    }

    /**
     * Moves [id] within root children: "top" | "bottom" | "up" | "down" (one slot).
     * Only root-level blocks move (sections/cards); returns null if the id is unknown.
     */
    fun moveComponent(componentsJson: String, id: String, position: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val rootObj = objs.firstOrNull { it.string("id") == "root" } ?: return null
        val children = childrenOf(rootObj).toMutableList()
        val idx = children.indexOf(id)
        if (idx < 0) return null
        children.removeAt(idx)
        when (position) {
            "top" -> children.add(0, id)
            "bottom" -> children.add(id)
            "up" -> children.add((idx - 1).coerceAtLeast(0), id)
            "down" -> children.add((idx + 1).coerceAtMost(children.size), id)
            else -> return null
        }
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(children.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it }
        return rebuild(root, msg, newItems)
    }

    /**
     * Sorts the items of section [sectionTitle] by [by]: "amount" (EntityCard deals),
     * "dueAt" (ActionItem tasks), "priority" (high first), "title" (alphabetical).
     * Deterministic ranking — the agent exposes it, the engine executes it.
     */
    fun sortSection(componentsJson: String, sectionTitle: String, by: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val children = childrenOf(rootObj).toMutableList()

        val headerId = objs
            .filter { it.string("component") == "SectionHeader" && it.string("title") == sectionTitle }
            .mapNotNull { it.string("id") }
            .firstOrNull() ?: return null
        val hIdx = children.indexOf(headerId)
        if (hIdx < 0) return null
        var end = hIdx + 1
        while (end < children.size) {
            val type = byId[children[end]]?.string("component")
            // 5.5: a Divider ENDS a section (the trailing rule) — never sorted away.
            if (type == "SectionHeader" || type == "PrimaryActionButton" || type == "EmptyState" || type == "Divider") break
            end++
        }
        val sectionItems = children.subList(hIdx + 1, end)
        if (sectionItems.size < 2) return null

        val priorityRank = mapOf("high" to 0, "normal" to 1, "low" to 2)
        val sorted = sectionItems.sortedWith { a, bbf ->
            val oa = byId[a] ?: return@sortedWith 0
            val ob = byId[bbf] ?: return@sortedWith 0
            when (by) {
                "amount" -> compareValues(
                    -((oa.entries["amountMicros"] as? JsonValue.JsonNumber)?.value ?: 0.0),
                    -((ob.entries["amountMicros"] as? JsonValue.JsonNumber)?.value ?: 0.0),
                )
                "dueAt" -> compareValues(
                    (oa.string("dueAt") ?: "").ifEmpty { "9999" },
                    (ob.string("dueAt") ?: "").ifEmpty { "9999" },
                )
                "priority" -> compareValues(
                    priorityRank[oa.string("priority")] ?: 1,
                    priorityRank[ob.string("priority")] ?: 1,
                )
                "title" -> compareValues(oa.string("title") ?: "", ob.string("title") ?: "")
                else -> 0
            }
        }
        val newChildren = children.subList(0, hIdx + 1) + sorted + children.subList(end, children.size)
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it }
        return rebuild(root, msg, newItems)
    }

    /**
     * validatePreservation with [allowReorder] = true skips ONLY the children-order check
     * (the content/property contract still holds). Used when the user explicitly asked
     * to move/reorder/rank blocks.
     */
    fun validatePreservation(oldJson: String, newJson: String, allowReorder: Boolean): List<String> {
        val errors = validatePreservation(oldJson, newJson)
        if (!allowReorder) return errors
        return errors.filterNot { it.startsWith("screen order broken") }
    }


    /** All SectionHeader titles on the screen, in order. */
    fun sectionTitles(componentsJson: String): List<String> {
        val parts = envelopeParts(componentsJson) ?: return emptyList()
        return parts.third.objs()
            .filter { it.string("component") == "SectionHeader" }
            .mapNotNull { it.string("title") }
    }

    /**
     * Moves the WHOLE section block (header + its items, up to the next section/CTA)
     * within root children: top | bottom | up | down (one slot = one sibling block).
     * Block partition: a new block starts at every SectionHeader / CTA / EmptyState
     * (leading metrics/banner cards form their own blocks).
     */
    fun moveSection(componentsJson: String, sectionTitle: String, position: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val children = childrenOf(rootObj)

        val headerId = objs
            .filter { it.string("component") == "SectionHeader" && it.string("title") == sectionTitle }
            .mapNotNull { it.string("id") }
            .firstOrNull() ?: return null

        fun isBlockStart(id: String): Boolean {
            val type = byId[id]?.string("component")
            return type == "SectionHeader" || type == "PrimaryActionButton" || type == "EmptyState"
        }

        val blocks = mutableListOf<MutableList<String>>()
        for (id in children) {
            if (blocks.isEmpty() || isBlockStart(id)) blocks.add(mutableListOf())
            blocks.last().add(id)
        }
        val slot = blocks.indexOfFirst { it.contains(headerId) }
        if (slot < 0) return null
        val targetSlot = when (position) {
            "top" -> 0
            "bottom" -> blocks.size - 1
            "up" -> (slot - 1).coerceAtLeast(0)
            "down" -> (slot + 1).coerceAtMost(blocks.size - 1)
            else -> return null
        }
        val block = blocks.removeAt(slot)
        blocks.add(targetSlot.coerceAtMost(blocks.size), block)
        val newChildren = blocks.flatten()

        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it }
        return rebuild(root, msg, newItems)
    }

    /**
     * LLM sanitizer (5.4g/5.4h): drops components referencing ids that DO NOT exist in the
     * repository (invented "opp-acme" / "Manager One"). The engine, not hope, enforces
     * the DATA RULE. Returns null when nothing was dropped (the common, healthy case).
     *
     * 5.4h REPAIR: a model that sets EntityCard.entityId to the deal TITLE ("Acme —
     * rollout") instead of the id gets the id REWRITTEN via [dealIdByTitle] instead of
     * the card being dropped — the edit intent survives.
     */
    fun sanitizeEntityRefs(
        componentsJson: String,
        validTaskIds: Set<String>,
        validDealIds: Set<String>,
        validMemberIds: Set<String>,
        dealIdByTitle: Map<String, String> = emptyMap(),
    ): Pair<String, List<String>>? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val rootObj = objs.firstOrNull { it.string("id") == "root" } ?: return null

        val repairs = mutableListOf<String>()
        fun repairEntityId(o: JsonValue.JsonObject): JsonValue.JsonObject? {
            if (o.string("component") != "EntityCard") return o
            val eid = o.string("entityId") ?: return o
            if (o.string("entityType") != "opportunity" || eid in validDealIds) return o
            val realId = dealIdByTitle[eid] ?: dealIdByTitle.entries.firstOrNull { it.key.startsWith(eid) }?.value
                ?: return o
            repairs += "repaired EntityCard entityId '$eid' -> '$realId'"
            return JsonValue.JsonObject(o.entries + ("entityId" to s(realId)))
        }
        val repaired = objs.mapNotNull { repairEntityId(it) }
        if (repairs.isNotEmpty()) {
            val newItems = items.map {
                val o = it as? JsonValue.JsonObject ?: return@map it
                repaired.firstOrNull { r -> r === o } ?: o
            }
            val fixed = rebuild(root, msg, newItems)
            val second = sanitizeEntityRefs(fixed, validTaskIds, validDealIds, validMemberIds)
            return (second?.first ?: fixed) to (repairs + (second?.second ?: emptyList()))
        }

        data class Dropped(val obj: JsonValue.JsonObject, val id: String, val reason: String)
        val dropped = mutableListOf<Dropped>()
        for (o in objs) {
            val id = o.string("id") ?: continue
            if (id == "root") continue
            val reason: String? = when (o.string("component")) {
                "ActionItem" -> o.string("taskId")?.takeIf { it.isNotBlank() && it !in validTaskIds }
                    ?.let { "ActionItem taskId=$it is not in the repository" }
                "LeaderboardRow" -> o.string("memberId")?.takeIf { it.isNotBlank() && it !in validMemberIds }
                    ?.let { "LeaderboardRow memberId=$it is not a team member" }
                "EntityCard" -> {
                    val eid = o.string("entityId")
                    when {
                        eid.isNullOrBlank() -> null
                        o.string("entityType") == "task" && eid !in validTaskIds -> "EntityCard task id=$eid is not in the repository"
                        o.string("entityType") == "opportunity" && eid !in validDealIds -> "EntityCard deal id=$eid is not in the repository"
                        else -> null
                    }
                }
                else -> null
            }
            if (reason != null) dropped += Dropped(o, id, reason)
        }
        if (dropped.isEmpty()) return null

        val droppedIds = dropped.map { it.id }.toHashSet()
        val oldChildren = childrenOf(rootObj)
        val newChildren = oldChildren.filter { it !in droppedIds }
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val droppedObjs = dropped.map { it.obj }.toHashSet()
        val newItems = items.map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it }
            .filter { (it as? JsonValue.JsonObject) !in droppedObjs }
        return rebuild(root, msg, newItems) to dropped.map { it.reason }
    }


    /**
     * Removes the WHOLE section block (header + items). The "delete/hide a block"
     * operation the sandbox was missing (5.4h): "удали блок выполненные".
     */
    fun removeSection(componentsJson: String, sectionTitle: String): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val objs = items.objs()
        val byId = objs.associateBy { it.string("id") ?: "" }
        val rootObj = byId["root"] ?: return null
        val children = childrenOf(rootObj)

        val headerId = objs
            .filter { it.string("component") == "SectionHeader" && it.string("title") == sectionTitle }
            .mapNotNull { it.string("id") }
            .firstOrNull() ?: return null

        fun isBlockStart(id: String): Boolean {
            val type = byId[id]?.string("component")
            return type == "SectionHeader" || type == "PrimaryActionButton" || type == "EmptyState"
        }

        val blocks = mutableListOf<MutableList<String>>()
        for (id in children) {
            if (blocks.isEmpty() || isBlockStart(id)) blocks.add(mutableListOf())
            blocks.last().add(id)
        }
        val block = blocks.firstOrNull { it.contains(headerId) } ?: return null
        val removedIds = block.toHashSet()

        val newChildren = children.filter { it !in removedIds }
        val newRootObj = JsonValue.JsonObject(rootObj.entries + ("children" to arr(newChildren.map { s(it) })))
        val newItems = items
            .map { if ((it as? JsonValue.JsonObject)?.string("id") == "root") newRootObj else it }
            .filter { (it as? JsonValue.JsonObject)?.string("id") !in removedIds }
        return rebuild(root, msg, newItems)
    }

    /**
     * Structural DIFF between two compositions (5.4h transparency): what the agent
     * changed — added/removed components and changed properties. Shown in the chat so
     * the user SEES the edit instead of trusting it blindly (undo if wrong).
     */
    fun diffSummary(oldJson: String, newJson: String): List<String> {
        fun objs(json: String): Map<String, JsonValue.JsonObject>? {
            val parts = envelopeParts(json) ?: return null
            return parts.third.objs().filter { it.string("id")?.isNotEmpty() == true }
                .associateBy { it.string("id")!! }
        }
        val old = objs(oldJson) ?: return emptyList()
        val new = objs(newJson) ?: return emptyList()
        val out = mutableListOf<String>()
        fun label(o: JsonValue.JsonObject): String {
            val type = o.string("component") ?: "?"
            val title = o.string("title") ?: o.string("label") ?: o.string("memberName") ?: o.string("name") ?: ""
            return if (title.isNotBlank()) "$type \"$title\"" else type
        }
        for ((id, o) in new) {
            if (id !in old) out += "+ $id (${label(o)})"
        }
        for ((id, o) in old) {
            val n = new[id]
            if (n == null) {
                out += "- $id (${label(o)})"
            } else {
                for ((k, oldV) in o.entries) {
                    val newV = n.entries[k]
                    if (newV != null && newV != oldV) out += "~ $id.$k"
                }
                // 5.4i: NEW props the agent added to an EXISTING component — these must be
                // visible too (a phantom edit is exactly a new prop no renderer reads).
                for (k in n.entries.keys) {
                    if (k == "id" || k == "component") continue
                    if (id == "root" && k == "children") continue
                    if (k !in o.entries) out += "+ $id.$k"
                }
            }
        }
        return out.take(12)
    }

    // ---------- preservation guard (LLM augment contract) ----------

    /**
     * STRONG preservation: the LLM edit must keep EVERYTHING the user had:
     * - all old component ids still present;
     * - for each old id, the new component keeps every old property with the SAME value
     *   (adding new properties is allowed; changing/removing is NOT — this catches
     *   renamed section titles and dropped taskId props that broke interactions);
     * - old root children order preserved as a subsequence.
     * Empty list = OK.
     */
    fun validatePreservation(oldJson: String, newJson: String): List<String> {
        val oldParts = envelopeParts(oldJson) ?: return listOf("old screen unparsable")
        val newParts = envelopeParts(newJson) ?: return listOf("new screen unparsable")
        val oldObjs = oldParts.third.objs()
        val newById = newParts.third.objs().filter { it.string("id")?.isNotEmpty() == true }
            .associateBy { it.string("id")!! }

        val errors = mutableListOf<String>()
        for (old in oldObjs) {
            val id = old.string("id") ?: continue
            if (id.isEmpty()) continue
            val new = newById[id]
            if (new == null) {
                errors += "removed component: $id"
                continue
            }
            for ((key, oldValue) in old.entries) {
                // root.children is checked separately (as a subsequence) — appending
                // new ids into root.children is the POINT of an augment.
                if (old.string("component") == "Column" && key == "children" && old.string("id") == "root") continue
                val newValue = new.entries[key]
                when {
                    newValue == null -> errors += "dropped property $id.$key"
                    newValue != oldValue -> errors += "changed property $id.$key"
                }
            }
        }

        val oldChildren = oldObjs.firstOrNull { it.string("id") == "root" }?.let { childrenOf(it) }.orEmpty()
        val newChildren = newParts.third.objs().firstOrNull { it.string("id") == "root" }?.let { childrenOf(it) }.orEmpty()
        var i = 0
        for (child in newChildren) {
            if (i < oldChildren.size && child == oldChildren[i]) i++
        }
        if (i < oldChildren.size) {
            errors += "screen order broken: missing ${oldChildren.drop(i).joinToString(", ").take(80)}"
        }
        return errors
    }

    /**
     * «добавь дедлайны в задачи»: fill missing/empty dueAt on ActionItems from the
     * repository tasks (taskId match). Returns null when nothing needed patching.
     */
    fun ensureDueDates(componentsJson: String, tasks: List<Task>): String? {
        val (root, msg, items) = envelopeParts(componentsJson) ?: return null
        val dueById = tasks.associate { it.id to it.dueAtEpochMs }
        var changed = false
        val newItems = items.map { item ->
            val o = item as? JsonValue.JsonObject ?: return@map item
            if (o.string("component") != "ActionItem") return@map o
            val taskId = o.string("taskId") ?: return@map o
            val due = dueById[taskId] ?: return@map o
            val current = o.string("dueAt")
            if (!current.isNullOrBlank()) return@map o
            changed = true
            JsonValue.JsonObject(
                o.entries + ("dueAt" to s(dev.a2uicrm.agentic.action.CrmActionDispatcher.epochToIso(due))),
            )
        }
        if (!changed) return null
        return rebuild(root, msg, newItems)
    }
}

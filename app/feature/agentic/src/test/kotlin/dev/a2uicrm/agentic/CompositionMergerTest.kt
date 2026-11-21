package dev.a2uicrm.agentic

import dev.a2uicrm.agentic.agent.CompositionMerger
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «augment, don't rebuild» contract (5.4d): appends/patches on an existing
 * composition must preserve every old component and the screen order.
 */
class CompositionMergerTest {

    private val base = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["m1","sh_ov","ov0","cta1"]},
        {"id":"m1","component":"MetricCard","label":"Overdue","value":{"path":"/stats/overdueCount"},"trend":"up"},
        {"id":"sh_ov","component":"SectionHeader","title":"Overdue","count":1,"countTone":"error"},
        {"id":"ov0","component":"ActionItem","taskId":"tt11","title":"Send proposal","dueAt":"2026-11-13T17:00:00Z","completed":false,"priority":"high","contactName":"John Doe"},
        {"id":"cta1","component":"PrimaryActionButton","label":"New task","icon":"add"}
        ]}}
    """.trimIndent()

    private val task = Task("tt99", "Call the client", dueAtEpochMs = 1_760_000_000_000, contactName = "Jane")

    /** My day twin with a Completed section (flat root children — the 18.11 shape). */
    private val myday = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["sh_ov","ov0","ov1","dv_ov","sh_done","es_done"]},
        {"id":"sh_ov","component":"SectionHeader","title":"Overdue","count":2,"countTone":"error"},
        {"id":"ov0","component":"ActionItem","taskId":"tt11","title":"Send proposal","dueAt":"2026-11-13T17:00:00Z","completed":false},
        {"id":"ov1","component":"ActionItem","taskId":"tt12","title":"Call Jane","dueAt":"2026-11-14T12:00:00Z","completed":false},
        {"id":"dv_ov","component":"Divider"},
        {"id":"sh_done","component":"SectionHeader","title":"Completed","count":0},
        {"id":"es_done","component":"EmptyState","title":"No completed tasks","message":"You have not completed any tasks yet.","icon":"checkCircle"}
        ]}}
    """.trimIndent()

    /** One Overdue task + an empty Completed section: completing empties the home section. */
    private val oneTask = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["sh_ov","ov0","dv_ov","sh_done","es_done"]},
        {"id":"sh_ov","component":"SectionHeader","title":"Overdue","count":1,"countTone":"error"},
        {"id":"ov0","component":"ActionItem","taskId":"tt11","title":"Send proposal","dueAt":"2026-11-13T17:00:00Z","completed":false},
        {"id":"dv_ov","component":"Divider"},
        {"id":"sh_done","component":"SectionHeader","title":"Completed","count":0},
        {"id":"es_done","component":"EmptyState","title":"No completed tasks","message":"You have not completed any tasks yet.","icon":"checkCircle"}
        ]}}
    """.trimIndent()

    /** LLM-style screen: only a Completed section, the item has NO dueAt/contactName. */
    private val doneOnly = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["sh_done","dn0"]},
        {"id":"sh_done","component":"SectionHeader","title":"Completed","count":1},
        {"id":"dn0","component":"ActionItem","taskId":"tt11","title":"Send proposal","completed":true}
        ]}}
    """.trimIndent()

    /** Today + Completed: the created home section must land BETWEEN them in order. */
    private val todayAndDone = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["sh_today","td0","dv_today","sh_done","dn0"]},
        {"id":"sh_today","component":"SectionHeader","title":"Today","count":1},
        {"id":"td0","component":"ActionItem","taskId":"tt12","title":"Call Jane","dueAt":"2026-11-14T12:00:00Z","completed":false},
        {"id":"dv_today","component":"Divider"},
        {"id":"sh_done","component":"SectionHeader","title":"Completed","count":1},
        {"id":"dn0","component":"ActionItem","taskId":"tt11","title":"Send proposal","dueAt":"2025-10-09T08:53:20Z","completed":true}
        ]}}
    """.trimIndent()

    @Test
    fun `appendComponents - inserts before CTA and preserves everything`() {
        val new = CompositionMerger.sectionComponents("Completed", listOf(task.copy(completed = true)), "au0")
        val merged = CompositionMerger.appendComponents(base, new)
        assertNotNull(merged)
        val violations = CompositionMerger.validatePreservation(base, merged!!)
        assertTrue("old content must survive: $violations", violations.isEmpty())
        // new ids present, before CTA
        assertTrue(merged.contains("\"sh_au0\""))
        assertTrue(merged.contains("\"au00\""))
        val rootChildren = Regex("\"children\":\\[(.*?)]").find(merged)!!.groupValues[1]
        val ctaIdx = rootChildren.indexOf("\"cta1\"")
        val newIdx = rootChildren.indexOf("\"sh_au0\"")
        assertTrue("new section must precede the CTA", newIdx in 0 until ctaIdx)
    }

    @Test
    fun `appendComponents - id collision rejected`() {
        val dup = CompositionMerger.sectionComponents("Completed", listOf(task), "ov")
        // section header id sh_ov already used
        assertNull(CompositionMerger.appendComponents(base, dup))
    }

    @Test
    fun `patchCompleted - flips the flag, layout untouched`() {
        val patched = CompositionMerger.patchCompleted(base, "tt11", true)
        assertNotNull(patched)
        assertTrue(patched!!.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":true"))
        // unknown task -> null
        assertNull(CompositionMerger.patchCompleted(base, "tt77", true))
    }

    @Test
    fun `patchCompleted - completing moves the task into the Completed section`() {
        val patched = CompositionMerger.patchCompleted(myday, "tt11", true)
        assertNotNull(patched)
        val children = Regex("\"children\":\\[(.*?)]").find(patched!!)!!.groupValues[1]
        // tt11 (ov0) moved under the Completed header, after the remaining Overdue item:
        assertTrue(children.contains("\"sh_ov\",\"ov1\",\"dv_ov\",\"sh_done\",\"ov0\""))
        // The Completed EmptyState placeholder is gone (real content now):
        assertFalse(children.contains("es_done"))
        // Checkbox flipped:
        assertTrue(patched.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":true"))
        // Counts follow the move:
        assertTrue(patched.contains("\"title\":\"Overdue\",\"count\":1"))
        assertTrue(patched.contains("\"title\":\"Completed\",\"count\":1"))
    }

    @Test
    fun `patchCompleted - un-completing returns the task to its home section`() {
        val done = CompositionMerger.patchCompleted(myday, "tt11", true)!!
        val restored = CompositionMerger.patchCompleted(done, "tt11", false, homeSection = "Overdue")
        assertNotNull(restored)
        val children = Regex("\"children\":\\[(.*?)]").find(restored!!)!!.groupValues[1]
        // Back under Overdue — at the end of its items, before its divider:
        assertTrue(children.contains("\"sh_ov\",\"ov1\",\"ov0\",\"dv_ov\""))
        // Completed is empty again -> the placeholder returns:
        assertTrue(children.contains("\"sh_done\",\"es_done\""))
        assertTrue(restored.contains("\"title\":\"Overdue\",\"count\":2"))
        assertTrue(restored.contains("\"title\":\"Completed\",\"count\":0"))
        assertTrue(restored.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":false"))
        // The divider is NOT a section header — it must never pick up a count:
        assertFalse(restored.contains("\"component\":\"Divider\",\"count\""))
    }

    @Test
    fun `patchCompleted - un-completing creates the missing home section and fills the deadline`() {
        // LLM-style node: NO dueAt/contactName — the repo task provides the meta line.
        val task = Task("tt11", "Send proposal", dueAtEpochMs = 1_760_000_000_000, contactName = "John Doe")
        val restored = CompositionMerger.patchCompleted(doneOnly, "tt11", false, homeSection = "Overdue", task = task)
        assertNotNull(restored)
        val children = Regex("\"children\":\\[(.*?)]").find(restored!!)!!.groupValues[1]
        // The missing Overdue section is CREATED (header + divider) before Completed,
        // the Completed placeholder appears for the emptied section:
        assertEquals("\"sh_hmtt11\",\"dn0\",\"dv_hmtt11\",\"sh_done\",\"es_completed\"", children)
        // The task is back, unchecked, WITH its meta line (deadline + contact filled):
        assertTrue(restored.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"completed\":false,\"dueAt\":\"2025-10-09T08:53:20Z\",\"contactName\":\"John Doe\""))
        assertTrue(restored.contains("\"title\":\"Overdue\",\"count\":1"))
        assertTrue(restored.contains("\"title\":\"Completed\",\"count\":0"))
    }

    @Test
    fun `patchCompleted - a created home section lands in calendar order`() {
        // Un-complete into "Tomorrow": the new section must land AFTER Today, BEFORE Completed.
        val restored = CompositionMerger.patchCompleted(todayAndDone, "tt11", false, homeSection = "Tomorrow")
        assertNotNull(restored)
        val children = Regex("\"children\":\\[(.*?)]").find(restored!!)!!.groupValues[1]
        assertTrue(children.contains("\"dv_today\",\"sh_hmtt11\",\"dn0\",\"dv_hmtt11\",\"sh_done\""))
        // "Tomorrow" gets its count; Completed empties and shows the placeholder:
        assertTrue(restored.contains("\"title\":\"Tomorrow\",\"count\":1"))
        assertTrue(restored.contains("\"title\":\"Completed\",\"count\":0"))
        assertTrue(children.contains("\"sh_done\",\"es_completed\""))
    }

    @Test
    fun `patchCompleted - an emptied home section is removed and restored`() {
        // Completing the only Overdue task empties the section -> it must DISAPPEAR
        // (header + divider, nodes dropped from the tree — no «Overdue (0)» hanging).
        val done = CompositionMerger.patchCompleted(oneTask, "tt11", true)
        assertNotNull(done)
        val children = Regex("\"children\":\\[(.*?)]").find(done!!)!!.groupValues[1]
        assertEquals("\"sh_done\",\"ov0\"", children)
        assertFalse(done!!.contains("\"title\":\"Overdue\""))
        assertTrue(done.contains("\"title\":\"Completed\",\"count\":1"))
        // Un-completing brings the home section BACK (re-created in calendar order):
        val restored = CompositionMerger.patchCompleted(done, "tt11", false, homeSection = "Overdue")
        assertNotNull(restored)
        val children2 = Regex("\"children\":\\[(.*?)]").find(restored!!)!!.groupValues[1]
        assertEquals("\"sh_hmtt11\",\"ov0\",\"dv_hmtt11\",\"sh_done\",\"es_done\"", children2)
        // Native parity: the recreated Overdue header carries the error tone.
        assertTrue(restored.contains("\"title\":\"Overdue\",\"count\":1,\"countTone\":\"error\""))
        assertTrue(restored.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":false"))
    }

    @Test
    fun `patchCompleted - no Completed section on screen keeps the flip-only fallback`() {
        // base has no Completed section: completing must not invent one, the item stays.
        val patched = CompositionMerger.patchCompleted(base, "tt11", true, homeSection = "Overdue")
        assertNotNull(patched)
        val children = Regex("\"children\":\\[(.*?)]").find(patched!!)!!.groupValues[1]
        assertEquals("\"m1\",\"sh_ov\",\"ov0\",\"cta1\"", children)
        assertTrue(patched.contains("\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":true"))
    }

    @Test
    fun `insertActionItem - lands inside the matching section`() {
        val merged = CompositionMerger.insertActionItem(base, "Overdue", task, "nw1")
        assertNotNull(merged)
        val rootChildren = Regex("\"children\":\\[(.*?)]").find(merged!!)!!.groupValues[1]
        val ov0 = rootChildren.indexOf("\"ov0\"")
        val nw1 = rootChildren.indexOf("\"nw1\"")
        val cta1 = rootChildren.indexOf("\"cta1\"")
        assertTrue("new item after the section's items", nw1 > ov0 && nw1 < cta1)
    }

    @Test
    fun `validatePreservation - detects removal and reorder`() {
        // simulate an LLM edit that dropped a component
        val dropped = base.replace(",{\"id\":\"ov0\",\"component\":\"ActionItem\",\"taskId\":\"tt11\",\"title\":\"Send proposal\",\"dueAt\":\"2026-11-13T17:00:00Z\",\"completed\":false,\"priority\":\"high\",\"contactName\":\"John Doe\"}", "")
            .replace("\"sh_ov\",\"ov0\",\"cta1\"", "\"sh_ov\",\"cta1\"")
        val violations = CompositionMerger.validatePreservation(base, dropped)
        assertTrue(violations.isNotEmpty())
        // identity edit -> no violations
        assertTrue(CompositionMerger.validatePreservation(base, base).isEmpty())
    }

    @Test
    fun `dealSectionComponents - header plus cards`() {
        val deals = listOf(Deal("d1", "Acme — rollout", 12_000_000_000, "Lead", "wm-1", "John Doe"))
        val comps = CompositionMerger.dealSectionComponents(deals, "ag0")
        // 5.5: a Divider precedes the appended section (native parity).
        assertEquals(3, comps.size)
        assertEquals("dv_ag0", comps[0].string("id"))
        assertEquals("Divider", comps[0].string("component"))
        assertEquals("sh_ag0", comps[1].string("id"))
        assertEquals("ag0d0", comps[2].string("id"))
        val merged = CompositionMerger.appendComponents(base, comps)
        assertNotNull(merged)
        assertTrue(merged!!.contains("Acme"))
    }

    private fun dev.a2uicrm.agentic.json.JsonValue.JsonObject.string(key: String): String? =
        (this[key] as? dev.a2uicrm.agentic.json.JsonValue.JsonString)?.value


    // ---------- 5.4g sandbox ops ----------

    @Test
    fun `hasSection - finds titles, misses absent`() {
        assertTrue(CompositionMerger.hasSection(base, "Overdue"))
        assertFalse(CompositionMerger.hasSection(base, "Completed"))
    }

    @Test
    fun `moveSection - reorder keeps every component and property`() {
        val moved = CompositionMerger.moveSection(base, "Overdue", "bottom")
        assertNotNull(moved)
        // Strict preservation (no reorder allowed) must flag the order change…
        assertTrue(CompositionMerger.validatePreservation(base, moved!!).any { it.startsWith("screen order broken") })
        // …and the reorder-mode guard must accept exactly that.
        assertTrue(CompositionMerger.validatePreservation(base, moved, allowReorder = true).isEmpty())
        val children = Regex("\"children\":\\[(.*?)]").find(moved)!!.groupValues[1]
        assertTrue("the whole section block must be last now", children.trim().endsWith("\"ov0\""))
    }

    @Test
    fun `sortSection - by dueAt ranks tasks, nothing lost`() {
        val extra2 = CompositionMerger.sectionComponents("Today", listOf(task, task.copy(id = "tt98", dueAtEpochMs = 1_790_000_000_000)), "zz")
        val merged = CompositionMerger.appendComponents(base, extra2)!!
        // the new section sh_zz holds zz0 (tt99, 1760...) and zz1 (tt98, 1790...)
        val sorted = CompositionMerger.sortSection(merged, "Today", "dueAt")
        assertNotNull(sorted)
        // In-section sorting reorders root children -> the REORDER-mode guard applies;
        // every component and property must still survive.
        assertTrue(CompositionMerger.validatePreservation(merged, sorted!!, allowReorder = true).isEmpty())
        val children = Regex("\"children\":\\[(.*?)]").find(sorted)!!.groupValues[1]
        // earliest due (zz0, 2025) must come FIRST — ascending by dueAt:
        assertTrue("sorted by dueAt ascending", children.indexOf("\"zz0\"") < children.indexOf("\"zz1\""))
    }

    @Test
    fun `sanitizeEntityRefs - invented ids dropped, real ones kept`() {
        val invented = """
            {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
            {"id":"root","component":"Column","children":["ov0","fake1","lb1"]},
            {"id":"ov0","component":"ActionItem","taskId":"tt11","title":"Real","dueAt":"","completed":false,"priority":"normal"},
            {"id":"fake1","component":"ActionItem","taskId":"tt77","title":"Invented","dueAt":"","completed":false,"priority":"normal"},
            {"id":"lb1","component":"LeaderboardRow","rank":1,"memberId":"wm-9","memberName":"Manager One","metricValue":"1"}
            ]}}
        """.trimIndent()
        val out = CompositionMerger.sanitizeEntityRefs(invented, setOf("tt11"), setOf("d1"), setOf("wm-1"))
        assertNotNull(out)
        val (json, notes) = out!!
        assertTrue("invented ActionItem must be gone", !json.contains("\"taskId\":\"tt77\""))
        assertTrue("invented LeaderboardRow must be gone", !json.contains("Manager One"))
        assertTrue("real ActionItem must stay", json.contains("\"taskId\":\"tt11\""))
        assertTrue("dropped ids must leave root.children", !Regex("\"children\":\\[.*fake1").containsMatchIn(json))
        assertTrue(notes.size == 2)
        // Healthy screen -> null (nothing to sanitize).
        assertNull(CompositionMerger.sanitizeEntityRefs(base, setOf("tt11"), setOf("d1"), setOf("wm-1")))
    }

    @Test
    fun `removeSection - drops the whole block, keeps the rest`() {
        val removed = CompositionMerger.removeSection(base, "Overdue")
        assertNotNull(removed)
        val ids = Regex("\"id\":\"([^\"]+)\"").findAll(removed!!).map { it.groupValues[1] }.toList()
        assertTrue("header and items must be gone", "sh_ov" !in ids && "ov0" !in ids)
        assertTrue("the CTA must stay", "cta1" in ids)
        assertTrue("the metric must stay", "m1" in ids)
        assertTrue("root children must not reference removed ids", !removed.contains("\"sh_ov\"") || !Regex("\"children\":\\[.*ov0").containsMatchIn(removed))
    }

    @Test
    fun `diffSummary - added removed changed components are reported`() {
        val newJson = CompositionMerger.insertActionItem(base, "Overdue", task, "nw1") ?: error("insert failed")
        val patched = CompositionMerger.patchCompleted(newJson, "tt11", true) ?: error("patch failed")
        val diff = CompositionMerger.diffSummary(base, patched)
        assertTrue("addition must be reported: $diff", diff.any { it.startsWith("+ nw1") })
        assertTrue("property change must be reported: $diff", diff.any { it.contains("ov0.completed") })
    }

    @Test
    fun `diffSummary - NEW props on existing components are visible (anti-phantom)`() {
        // The "round checkboxes" bug: the model adds a knob nobody reads — the diff MUST
        // show the new prop, so the chat can flag a phantom edit.
        val phantom = base.replace(
            "\"completed\":false,\"priority\":\"high\"",
            "\"completed\":false,\"priority\":\"high\",\"checkboxShape\":\"round\"",
        )
        val diff = CompositionMerger.diffSummary(base, phantom)
        assertTrue("new prop must be reported: $diff", diff.any { it.contains("+ ov0.checkboxShape") })
    }

    @Test
    fun `FakeCrmConnector - funnel and team stats are REAL deal aggregates`() = kotlinx.coroutines.runBlocking {
        val fake = dev.a2uicrm.core.data.FakeCrmConnector()
        val deals = fake.getDeals()
        val stages = fake.getPipelineStages()
        // 7 fixture deals -> stage counts/sums match the actual list:
        assertEquals(deals.count { it.stage == "Lead" }, stages.first { it.label == "Lead" }.count)
        assertEquals(
            deals.filter { it.stage == "Lead" }.sumOf { it.amountMicros },
            stages.first { it.label == "Lead" }.valueMicros,
        )
        assertEquals(deals.count { it.stage == "Won" }, stages.first { it.label == "Won" }.count)
        // Team stats: pipeline = the owner's open-deal sum, won = stage Won count.
        val stats = fake.getTeamStats()
        val jane = stats.first { it.memberId == "wm-1" }
        assertEquals(deals.filter { it.ownerId == "wm-1" }.sumOf { it.amountMicros }, jane.pipelineValueMicros)
        assertEquals(deals.count { it.ownerId == "wm-1" && it.stage == "Won" }, jane.wonCount)
        // A CREATED deal moves the numbers immediately.
        val janePipelineBefore = jane.pipelineValueMicros
        fake.createDeal("New pilot", 5_000_000_000, "Lead", "wm-1", null)
        val stagesAfter = fake.getPipelineStages()
        assertEquals(4, stagesAfter.first { it.label == "Lead" }.count)
        assertEquals(deals.size + 1, stagesAfter.sumOf { it.count })
        val janeAfter = fake.getTeamStats().first { it.memberId == "wm-1" }
        assertEquals(janePipelineBefore + 5_000_000_000, janeAfter.pipelineValueMicros)
    }

    @Test
    fun `dealStageSectionComponents - funnel order, amount ranking, stage icons`() {
        val deals = listOf(
            Deal("d5", "Stark", 22_000_000_000, "Qualified", "wm-3"),
            Deal("d1", "Acme", 12_000_000_000, "Lead", "wm-1"),
            Deal("d7", "Oscorp", 9_500_000_000, "Lead", "wm-1"),
            Deal("d3", "Initech", 15_000_000_000, "Proposal", "wm-2"),
            Deal("d9", "Unknown", 1_000_000_000, "Weird", "wm-1"),
        )
        val comps = CompositionMerger.dealStageSectionComponents(deals, listOf("Lead", "Qualified", "Proposal", "Won"), "st")
        val titles = comps.filter { (it.string("component")) == "SectionHeader" }.mapNotNull { it.string("title") }
        // Sections in FUNNEL order, unknown stage falls to the end:
        assertEquals(listOf("Lead deals", "Qualified deals", "Proposal deals", "Other deals"), titles)
        // Stage icons on the headers:
        val leadHeader = comps.first { it.string("title") == "Lead deals" }
        assertEquals("flag", leadHeader.string("icon"))
        // Inside a stage: ranked by amount desc (Acme 12k before Oscorp 9.5k):
        val leadCards = comps.filter { it.string("component") == "EntityCard" }
        val leadIds = leadCards.mapNotNull { it.string("entityId") }.take(2)
        assertEquals(listOf("d1", "d7"), leadIds)
        // Every card carries a working openEntity action:
        assertTrue(leadCards.all { (it.entries["action"] as? dev.a2uicrm.agentic.json.JsonValue.JsonObject) != null })
    }

    // ---------- 5.5: add/show contract, deals under stages, structural guard ----------

    /** The analytics twin shape: stage Rows with a "stage" property (visual twin parity). */
    private val twin = """
        {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
        {"id":"root","component":"Column","children":["tt1","sh1","stg0","stg1","cta1"]},
        {"id":"tt1","component":"Text","text":"Total in funnel: $50k","variant":"caption"},
        {"id":"sh1","component":"SectionHeader","title":"Funnel","count":2},
        {"id":"stg0","component":"Row","gap":"tight","stage":"Lead","children":["sic0","scl0","sval0"]},
        {"id":"sic0","component":"Icon","name":"person","tone":"primary"},
        {"id":"scl0","component":"Column","gap":"tight","flex":true,"children":["slb0","smt0"]},
        {"id":"slb0","component":"Text","text":"Lead","variant":"label"},
        {"id":"smt0","component":"Text","text":"deals: 2","variant":"meta"},
        {"id":"sval0","component":"Text","text":"$21.5k","variant":"value"},
        {"id":"stg1","component":"Row","gap":"tight","stage":"Proposal","children":["sic1","scl1","sval1"]},
        {"id":"sic1","component":"Icon","name":"description","tone":"primary"},
        {"id":"scl1","component":"Column","gap":"tight","flex":true,"children":["slb1","smt1"]},
        {"id":"slb1","component":"Text","text":"Proposal","variant":"label"},
        {"id":"smt1","component":"Text","text":"deals: 1","variant":"meta"},
        {"id":"sval1","component":"Text","text":"$15k","variant":"value"},
        {"id":"cta1","component":"PrimaryActionButton","label":"New deal","icon":"add"}
        ]}}
    """.trimIndent()

    @Test
    fun `insertDealCardsAfterStages - deals land right under their stage row`() {
        val deals = listOf(
            Deal("d1", "Acme", 12_000_000_000, "Lead", "wm-1"),
            Deal("d7", "Oscorp", 9_500_000_000, "Lead", "wm-1"),
            Deal("d3", "Initech", 15_000_000_000, "Proposal", "wm-2"),
            Deal("d2", "Globex", 8_000_000_000, "Qualified", "wm-1"), // no Qualified row -> skipped
        )
        val merged = CompositionMerger.insertDealCardsAfterStages(twin, deals, "au1")
        assertNotNull(merged)
        val rootChildren = Regex("\"children\":\\[(.*?)]").find(merged!!)!!.groupValues[1]
        // Deal cards are inserted DIRECTLY after their stage row, ranked by amount:
        val leadCardIds = Regex("\"(au1_stg0_\\d)\"").findAll(rootChildren).map { it.groupValues[1] }.toList()
        assertEquals(listOf("au1_stg0_0", "au1_stg0_1"), leadCardIds)
        assertTrue(rootChildren.contains("\"stg0\",\"au1_stg0_0\""))
        // Proposal deal under stg1:
        assertTrue(rootChildren.contains("\"stg1\",\"au1_stg1_0\""))
        // Qualified has no stage row on this screen -> no orphan cards:
        assertFalse(merged.contains("Globex"))
        // The funnel itself is untouched (twin subtree is a subsequence):
        assertTrue(CompositionMerger.validateStructure(twin, merged).isEmpty())
    }

    @Test
    fun `insertDealCardsAfterStages - no stage rows returns null`() {
        assertNull(CompositionMerger.insertDealCardsAfterStages(base, listOf(Deal("d1", "X", 1, "Lead", "wm-1")), "p"))
    }

    @Test
    fun `validateStructure - rebuild detected, pure addition passes`() {
        // A pure addition (inserted chart) keeps the old tree -> OK:
        val chart = CompositionMerger.trendChartComponents(
            listOf("Mon" to 61.0, "Tue" to 68.5, "Wed" to 64.0), "au9", "Open deals value by day",
        )
        val augmented = CompositionMerger.appendComponents(base, chart)!!
        assertTrue(CompositionMerger.validateStructure(base, augmented).isEmpty())

        // A REBUILD (old components gone) is flagged — this is what "add" must not do:
        val rebuilt = """
            {"version":"v0.9.1","updateComponents":{"surfaceId":"home-mgr","components":[
            {"id":"root","component":"Column","children":["m1"]},
            {"id":"m1","component":"MetricCard","label":"Total","value":"$5"}
            ]}}
        """.trimIndent()
        val violations = CompositionMerger.validateStructure(base, rebuilt)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.first().contains("rebuilt"))
    }

    @Test
    fun `trendChartComponents - real points, labels, values on by default`() {
        val comps = CompositionMerger.trendChartComponents(
            listOf("Mon" to 61.0, "Tue" to 68.5, "Wed" to 64.0), "tr", "Open deals value by day", unit = "k$",
        )
        assertEquals(1, comps.size)
        val chart = comps[0]
        assertEquals("TrendChart", chart.string("component"))
        assertEquals("bar", chart.string("kind"))
        assertEquals(3, (chart.entries["points"] as? dev.a2uicrm.agentic.json.JsonValue.JsonArray)?.items?.size)
        // Too few points -> no chart (the catalog requires >= 3):
        assertTrue(CompositionMerger.trendChartComponents(listOf("Mon" to 1.0), "t2", "x").isEmpty())
    }

    @Test
    fun `analyticsTwin - visual twin of the native Analytics screen`() {
        val stages = listOf(
            dev.a2uicrm.core.domain.PipelineStage("st-lead", "Lead", 3, 31_500_000_000, null),
            dev.a2uicrm.core.domain.PipelineStage("st-proposal", "Proposal", 2, 19_000_000_000, 2.0 / 3.0),
            dev.a2uicrm.core.domain.PipelineStage("st-won", "Won", 0, 0, 0.0),
        )
        val twinComp = dev.a2uicrm.agentic.agent.CompositionBuilder.analyticsTwin("home-mgr", stages)
        val json = twinComp.componentsJson
        // NO MetricCard (the "Total in funnel $76.5k ->" bug), NO horizontal PipelineStrip:
        assertFalse(json.contains("MetricCard"))
        assertFalse(json.contains("PipelineStrip"))
        // Caption total + Funnel header + vertical stage rows + New deal CTA:
        assertTrue(json.contains("Total in funnel: $50.5k"))
        assertTrue(json.contains("\"title\":\"Funnel\""))
        assertTrue(json.contains("\"label\":\"New deal\""))
        // Distinct per-stage icon tokens (bug: all stages shared one icon):
        assertTrue(json.contains("\"name\":\"person\""))
        assertTrue(json.contains("\"name\":\"description\""))
        assertTrue(json.contains("\"name\":\"storefront\""))
        // Stage rows carry the "stage" property for the under-stage inserts:
        assertTrue(json.contains("\"stage\":\"Lead\""))
        // "deals: N · conv X%" meta lines like the native screen:
        assertTrue(json.contains("deals: 2 · conv 67%"))
        // Root children list ONLY top-level blocks: stage-row internals (sic/scl/
        // slb/smt/sval) are referenced by the Row's own "children" — a flat root
        // listing made the renderer draw every stage row TWICE (live bug 18.11).
        val rootChildren = Regex("\"children\":\\[(.*?)]").find(json)!!.groupValues[1]
        assertEquals("\"tt1\",\"sh1\",\"stg0\",\"stg1\",\"stg2\",\"cta1\"", rootChildren)
    }

    @Test
    fun `tasksScreen twin - divider after every section and normal priority`() {
        val comp = dev.a2uicrm.agentic.agent.CompositionBuilder.tasksScreen(
            "home-mgr",
            listOf("Overdue" to listOf(Task("t1", "A", dueAtEpochMs = 1L)), "Today" to listOf(Task("t2", "B", dueAtEpochMs = 2L))),
        )
        val json = comp.componentsJson
        // 5.5 native parity: thin Divider lines between sections (TodayScreen shape):
        assertTrue(json.contains("\"component\":\"Divider\""))
        // Priority stays normal — the native TaskRow does not recolor overdue titles:
        assertFalse(json.contains("\"priority\":\"high\""))
    }
}

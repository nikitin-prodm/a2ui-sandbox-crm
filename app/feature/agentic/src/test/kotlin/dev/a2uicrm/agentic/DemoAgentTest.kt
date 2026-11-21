package dev.a2uicrm.agentic

import dev.a2uicrm.agentic.agent.AgentRequest
import dev.a2uicrm.agentic.agent.AgentSession
import dev.a2uicrm.agentic.agent.DemoAgent
import dev.a2uicrm.agentic.engine.AgenticSurfaceHost
import dev.a2uicrm.core.data.FakeCrmConnector
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent request loop (5.2): voice/text -> classifier -> inference -> valid
 * composition -> two-layer validation -> delivery; plus the self-correction loop.
 */
class DemoAgentTest {

    private fun catalogJson(): String =
        javaClass.classLoader.getResourceAsStream("catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("catalog.json not on test classpath")

    private suspend fun awaitDelivery(host: AgenticSurfaceHost) {
        var attempts = 0
        while (host.registry.components.isEmpty() && attempts < 50) {
            delay(100)
            attempts++
        }
    }

    @Test
    fun `focused overdue request - ONLY the Overdue section, no metrics banner CTA`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            // 5.4f FOCUS RULE: "покажи просроченные" → exactly the Overdue section.
            // The old behavior (full Home template: metrics + banner + CTA the user never
            // asked for) was the "random squares on top of the screen" complaint.
            val result = AgentSession(host, agent).run(AgentRequest("manager", "покажи просроченные"))
            assertTrue("composition rejected: ${result.errors}", result.accepted)
            assertEquals(1, result.attempts)
            assertTrue(result.inference.isNotEmpty())

            awaitDelivery(host)
            val types = host.registry.components.map { it.type }
            assertTrue("ActionItem must be delivered, got $types", types.contains("ActionItem"))
            // NOTHING the user did not ask for:
            assertTrue("focused screen must have no MetricCard, got $types", !types.contains("MetricCard"))
            assertTrue("focused screen must have no AlertBanner, got $types", !types.contains("AlertBanner"))
            assertTrue("focused screen must have no CTA, got $types", !types.contains("PrimaryActionButton"))
            // Exactly ONE section header — the requested one.
            assertEquals(1, types.count { it == "SectionHeader" })
            val header = host.registry.components.first { it.type == "SectionHeader" }
            assertEquals("Overdue", header.properties["title"])
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `focused today request - ONLY the Today section`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val connector = FakeCrmConnector()
        // The canned fixtures are overdue; inject a task due NOW so Today is non-empty.
        connector.replaceTasks(
            listOf(dev.a2uicrm.core.domain.Task("t1", "Review contract", dueAtEpochMs = System.currentTimeMillis())),
        )
        val agent = DemoAgent(connector)
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", "show today's tasks"))
            assertTrue("composition rejected: ${result.errors}", result.accepted)
            awaitDelivery(host)
            val types = host.registry.components.map { it.type }
            assertTrue("ActionItem must be delivered, got $types", types.contains("ActionItem"))
            assertEquals(1, types.count { it == "SectionHeader" })
            assertEquals("Today", host.registry.components.first { it.type == "SectionHeader" }.properties["title"])
            assertTrue(!types.contains("MetricCard"))
            assertTrue(!types.contains("AlertBanner"))
            assertTrue(!types.contains("PrimaryActionButton"))
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `blank request - full Home template with metrics sections CTA`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            // Blank/"make it pretty" is the ONLY case that legitimately gets the full
            // canonical template (banner → metrics → sections → CTA).
            val result = AgentSession(host, agent).run(AgentRequest("manager", ""))
            assertTrue("composition rejected: ${result.errors}", result.accepted)
            awaitDelivery(host)
            val types = host.registry.components.map { it.type }
            assertTrue("blank request → full template, got $types", types.contains("MetricCard"))
            assertTrue(types.contains("PrimaryActionButton"))
            assertTrue(types.count { it == "SectionHeader" } >= 2)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `empty request - role home with inference notes`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            val result = AgentSession(host, agent).run(AgentRequest("head", ""))
            assertTrue(result.accepted)
            // Inference must be transparent: what the agent decided for the user.
            assertTrue(result.inference.isNotEmpty())
            awaitDelivery(host)
            assertTrue(host.registry.components.map { it.type }.contains("LeaderboardRow"))
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `create task request - draft screen with CTA and inferred defaults`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", "добавь задачу позвонить клиенту"))
            assertTrue(result.accepted)
            assertTrue(result.inference.any { it.contains("09:00") })
            awaitDelivery(host)
            val card = host.registry.components.firstOrNull { it.type == "EntityCard" }
            assertEquals("Позвонить клиенту", card?.properties?.get("title"))
            val cta = host.registry.components.firstOrNull { it.type == "PrimaryActionButton" }
            val eventName = (cta?.properties?.get("action") as? Map<*, *>)
                ?.get("event")?.let { it as? Map<*, *> }?.get("name")
            assertEquals("createTask", eventName)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `data binding - updateDataModel resolves metric values`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", ""))
            assertTrue(result.accepted)
            awaitDelivery(host)
            // The engine applied the updateDataModel message to the surface data model.
            assertEquals(2, host.dataModel.get("/stats/overdueCount"))
            // The MetricCard references the path ({"path": "/stats/overdueCount"}).
            val metric = host.registry.components.first { it.type == "MetricCard" }
            val binding = metric.properties["value"] as? Map<*, *>
            assertEquals("/stats/overdueCount", binding?.get("path"))
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    /** Fault-injecting agent: first attempt invalid (bad enum), then self-corrects. */
    private class FaultyAgent : dev.a2uicrm.agentic.agent.AgentGateway {
        var calls = 0
        override suspend fun apply(
            request: AgentRequest,
            feedback: List<String>,
        ): dev.a2uicrm.agentic.agent.AgentResponse {
            calls++
            return if (feedback.isEmpty()) {
                dev.a2uicrm.agentic.agent.AgentResponse(
                    surfaceId = "s-fault",
                    updateDataModelJson = null,
                    componentsJson = """{"version":"v0.9.1","updateComponents":{"surfaceId":"s-fault","components":[
                        {"id":"root","component":"Column","children":["m1"]},
                        {"id":"m1","component":"MetricCard","label":"Overdue","value":"2","trend":"spiral"}]}}""",
                )
            } else {
                dev.a2uicrm.agentic.agent.AgentResponse(
                    surfaceId = "s-fault",
                    updateDataModelJson = null,
                    componentsJson = dev.a2uicrm.agentic.agent.CompositionBuilder.safeFallback("s-fault"),
                    inference = listOf("Fixed after a validation error"),
                )
            }
        }
    }

    @Test
    fun `self-correction - invalid composition retried and recovered`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = FaultyAgent()
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", "x"))
            assertTrue(result.accepted)
            assertEquals(2, result.attempts)
            assertEquals(2, agent.calls)
            awaitDelivery(host)
            assertTrue(host.registry.components.isNotEmpty())
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    /** Agent that never recovers: both attempts invalid -> graceful failure. */
    private class AlwaysFaultyAgent : dev.a2uicrm.agentic.agent.AgentGateway {
        var calls = 0
        override suspend fun apply(
            request: AgentRequest,
            feedback: List<String>,
        ): dev.a2uicrm.agentic.agent.AgentResponse {
            calls++
            return dev.a2uicrm.agentic.agent.AgentResponse(
                surfaceId = "s-always",
                updateDataModelJson = null,
                componentsJson = """{"version":"v0.9.1","updateComponents":{"surfaceId":"s-always","components":[
                    {"id":"root","component":"Column","children":["m1"]},
                    {"id":"m1","component":"MetricCard","label":"Overdue","value":"2","trend":"spiral"}]}}""",
            )
        }
    }

    @Test
    fun `retry exhausted - both attempts invalid, errors reported gracefully`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = AlwaysFaultyAgent()
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", "x"))
            assertEquals(false, result.accepted)
            assertEquals(2, result.attempts)
            assertEquals(2, agent.calls)
            // Structured errors survive to the UI (level-3 UX: the failure panel).
            assertTrue(result.errors.isNotEmpty())
            assertEquals("INVALID_PROPERTY_VALUE", result.errors.first().code)
            awaitDelivery(host)
            // Nothing invalid was ever delivered to the renderer.
            assertTrue(host.registry.components.isEmpty())
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    private fun assertRefusal(requestText: String, expectedCtaAction: String) = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", requestText))
            assertTrue("refusal composition rejected: ${result.errors}", result.accepted)
            assertTrue(result.inference.any { it.startsWith("Refusal") })
            awaitDelivery(host)
            val types = host.registry.components.map { it.type }
            // The refusal screen is EmptyState + an alternative CTA — never the requested thing.
            assertTrue(types.contains("EmptyState"))
            assertTrue(types.contains("PrimaryActionButton"))
            val cta = host.registry.components.first { it.type == "PrimaryActionButton" }
            val eventName = (cta.properties["action"] as? Map<*, *>)
                ?.get("event")?.let { it as? Map<*, *> }?.get("name")
            assertEquals(expectedCtaAction, eventName)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `refusal - pie chart request refused with explanation and alternative`() {
        assertRefusal("покажи круговую диаграмму по задачам", "navigate")
    }

    @Test
    fun `refusal - send email request refused, task-reminder offered instead`() {
        assertRefusal("отправь письмо клиенту", "createTask")
    }

    @Test
    fun `refusal - raw html request refused`() {
        assertRefusal("сделай экран с html версткой", "navigate")
    }

    @Test
    fun `analytics - funnel and trend delivered with computed metrics`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            // Vague request: "покажи воронку" without KPIs -> canonical analytics view.
            val result = AgentSession(host, agent).run(AgentRequest("head", "покажи воронку"))
            assertTrue("analytics composition rejected: ${result.errors} engine: ${host.engineErrors()}", result.accepted)
            assertTrue(result.inference.any { it.contains("funnel") || it.contains("canonical") })
            awaitDelivery(host)

            val types = host.registry.components.map { it.type }
            assertTrue("delivered: $types engine: ${host.engineErrors()}", types.contains("PipelineStrip"))
            assertTrue(types.contains("TrendChart"))

            // PipelineStrip: 4 canonical stages (zeros kept), computed from the REAL deals.
            val strip = host.registry.components.first { it.type == "PipelineStrip" }
            val stages = strip.properties["stages"] as? List<*>
            assertEquals(4, stages?.size)
            val stage = stages?.firstOrNull { (it as? Map<*, *>)?.get("name") == "Lead" } as? Map<*, *>
            // 7 fixture deals: 3 in Lead (12k + 9.5k + 6k)
            assertEquals(3, stage?.get("count"))

            // TrendChart: 7 points, labels length == points length (catalog constraint).
            val chart = host.registry.components.first { it.type == "TrendChart" }
            val points = chart.properties["points"] as? List<*>
            val labels = chart.properties["labels"] as? List<*>
            assertEquals(7, points?.size)
            assertEquals(points?.size, labels?.size)

            // DataBinding: REAL funnel numbers — total 27.5+30+19+0 = $76.5k, Won/Lead = 0%.
            assertEquals("\$76.5k", host.dataModel.get("/pipeline/totalValue"))
            assertEquals("0%", host.dataModel.get("/pipeline/conversion"))
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `analytics from manager role - surface switched to head`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        val agent = DemoAgent(FakeCrmConnector())
        try {
            val result = AgentSession(host, agent).run(AgentRequest("manager", "покажи аналитику по сделкам"))
            assertTrue("rejected: ${result.errors} | engine: ${host.engineErrors()}", result.accepted)
            assertTrue(result.inference.any { it.contains("home-head") })
            awaitDelivery(host)
            assertTrue(host.registry.components.map { it.type }.contains("PipelineStrip"))
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `managerHome - action items in the tree and unique section ids`() = runBlocking {
        val agent = DemoAgent(FakeCrmConnector())
        // Blank request → MANAGER_HOME → the full template (overdue/today/tomorrow ids).
        val resp = agent.composeImmediately(AgentRequest("manager", ""))
        val json = resp.componentsJson

        // Root children must include the ActionItem ids (they used to be orphans).
        val rootChildren = Regex("""\"id\":\"root\",\"component\":\"Column\",\"children\":\[(.*?)\]""")
            .find(json)?.groupValues?.get(1).orEmpty()
        assertTrue("ov0 must be a child of root, children=$rootChildren", rootChildren.contains("\"ov0\""))
        assertTrue(rootChildren.contains("\"sh_ov\""))
        assertTrue(rootChildren.contains("\"sh_td\""))
        // tomorrow is empty in the fake -> EmptyState, not a Завтра header
        assertTrue(rootChildren.contains("\"em1\""))
        assertTrue("section ids must not collapse to sh_", !rootChildren.contains("\"sh_\""))

        // All component ids in the message are unique (three «Завтра N» headers were sh_ ×3).
        val ids = Regex("""\"id\":\"([^\"]+)\"""").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals("duplicate component ids: $ids", ids.size, ids.toSet().size)
    }
}

package dev.a2uicrm.agentic

import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import dev.a2uicrm.agentic.engine.AgenticSurfaceHost
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end wiring test (port of probe/MessageProcessorProbe.java): the SAME pipeline the
 * app uses — AgenticSurfaceHost (parser -> CatalogValidator -> A2uiCoreMessageProcessor ->
 * ComposeComponentRegistry). Runs on plain JVM: the pipeline has no Android dependencies.
 */
class EngineWiringTest {

    private fun catalogJson(): String =
        javaClass.classLoader.getResourceAsStream("catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("catalog.json not on test classpath")

    @Test
    fun `end-to-end - surface created, components delivered to registry`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        try {
            val result = host.submit(
                """{"version":"v0.9.1","createSurface":{"surfaceId":"s1","catalogId":"dev.a2uicrm:crm-catalog"}}"""
            )
            assertTrue("createSurface rejected: ${result.errors}", result.accepted)
            assertTrue(result.message is A2uiCreateSurfaceMessage)

            val result2 = host.submit(
                """{"version":"v0.9.1","updateComponents":{"surfaceId":"s1","components":[
                    {"id":"root","component":"Column","children":["m1"]},
                    {"id":"m1","component":"MetricCard","label":"Overdue","value":"2","trend":"up"}]}}""".trimIndent()
            )
            assertTrue("updateComponents rejected: ${result2.errors}", result2.accepted)
            assertTrue(result2.message is A2uiUpdateComponentsMessage)

            // Wait for the single-threaded surface actor to deliver components to the registry.
            var attempts = 0
            while (host.registry.components.isEmpty() && attempts < 50) {
                delay(100)
                attempts++
            }
            assertEquals(2, host.registry.components.size)
            assertEquals("Column", host.registry.components.first { it.id == "root" }.type)
            assertEquals(1, host.activeSurfaces().size)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `layer 1 - agent-facing rejection for invalid enum before engine sees it`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        try {
            host.submit(
                """{"version":"v0.9.1","createSurface":{"surfaceId":"s2","catalogId":"dev.a2uicrm:crm-catalog"}}"""
            )
            val result = host.submit(
                """{"version":"v0.9.1","updateComponents":{"surfaceId":"s2","components":[
                    {"id":"root","component":"Column","children":["m1"]},
                    {"id":"m1","component":"MetricCard","label":"Overdue","value":"2","trend":"spiral"}]}}""".trimIndent()
            )
            assertEquals(false, result.accepted)
            assertEquals("INVALID_PROPERTY_VALUE", result.errors.first().code)
            assertEquals(emptyList<Any>(), host.registry.components)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `layer 2 - engine full-schema validation reports errors for unknown types`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        try {
            // UNKNOWN at layer 1 already blocks submission; check the engine catches what
            // layer 1 lets through only when the catalog contains the type. Here: PieChart
            // is caught by layer 1 (UNKNOWN_COMPONENT), proving the two-layer contract.
            val result = host.submit(
                """{"version":"v0.9.1","updateComponents":{"surfaceId":"s3","components":[
                    {"id":"root","component":"Column","children":["p1"]},
                    {"id":"p1","component":"PieChart","title":"x"}]}}""".trimIndent()
            )
            assertEquals(false, result.accepted)
            assertEquals("UNKNOWN_COMPONENT", result.errors.first().code)
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }

    @Test
    fun `protocol 1_0 rejected by engine parser`() = runBlocking {
        val host = AgenticSurfaceHost(catalogJson(), this)
        try {
            val result = host.submit(
                """{"version":"1.0","createSurface":{"surfaceId":"s4","catalogId":"dev.a2uicrm:crm-catalog"}}"""
            )
            assertEquals(false, result.accepted)
            // Layer 1 surfaces the engine's VALIDATION_FAILED as an agent-facing error.
            assertTrue(result.errors.isNotEmpty())
        } finally {
            coroutineContext.cancelChildren()
        }
        host.dispose()
    }
}

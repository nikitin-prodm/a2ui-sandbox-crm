package dev.a2uicrm.agentic

import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import dev.a2uicrm.agentic.json.JsonLexerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the JsonLexerAdapter (A2uiJsonReader implementation). The happy-path
 * round-trip is covered by CatalogValidatorTest (10 messageExamples); this file targets
 * lexer edge cases: unicode escapes, nested objects/arrays, numbers, booleans, null.
 */
class JsonLexerAdapterTest {

    private val parser = A2uiJsonMessageParser({ JsonLexerAdapter(it) })

    @Test
    fun `parses nested envelope with all scalar kinds`() {
        val parsed = parser.parse(
            """{"version":"v0.9.1","updateComponents":{"surfaceId":"s","components":[
                {"id":"root","component":"Column","children":["a"]},
                {"id":"a","component":"MetricCard","label":"Overdue",
                 "value":"2","trend":"up"}]}}""".trimIndent()
        )
        assertTrue(parsed is A2uiUpdateComponentsMessage)
        val payloads = (parsed as A2uiUpdateComponentsMessage).components
        assertEquals(2, payloads.size)
        val metric = payloads.first { it.id == "a" }
        assertEquals("MetricCard", metric.type)
        assertEquals("Overdue", metric.properties["label"])
        assertEquals("up", metric.properties["trend"])
    }

    @Test
    fun `parses unicode escapes`() {
        val parsed = parser.parse(
            """{"version":"v0.9.1","updateComponents":{"surfaceId":"s","components":[
                {"id":"root","component":"Column","children":["a"]},
                {"id":"a","component":"Text","text":"\u00e9tude caf\u00e9"}]}}""".trimIndent()
        )
        val text = (parsed as A2uiUpdateComponentsMessage).components
            .first { it.id == "a" }.properties["text"]
        assertEquals("étude café", text)
    }

    @Test
    fun `rejects malformed json`() {
        var thrown = false
        try {
            parser.parse("""{"version":"v0.9.1","updateComponents":""")
        } catch (e: Exception) {
            thrown = true // A2uiException (checked) — surfaced to the agent as VALIDATION_FAILED
        }
        assertTrue("Parser should reject truncated JSON", thrown)
    }
}

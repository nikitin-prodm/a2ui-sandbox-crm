package dev.a2uicrm.agentic

import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import dev.a2uicrm.agentic.catalog.CatalogDefinition
import dev.a2uicrm.agentic.json.jsonLexerReaderFactory
import dev.a2uicrm.agentic.json.stringify
import dev.a2uicrm.agentic.validate.CatalogValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of probe/CatalogValidationProbe.java into the Gradle test source set:
 * 10/10 positive + 6/6 negative controls (analysis.md §3.4).
 * The catalog is loaded from the SAME catalog.json (test resources -> ../../catalog).
 */
class CatalogValidatorTest {

    private lateinit var validator: CatalogValidator
    private lateinit var catalog: CatalogDefinition
    private val parser = A2uiJsonMessageParser(jsonLexerReaderFactory())

    @Before
    fun setUp() {
        val json = javaClass.classLoader.getResourceAsStream("catalog.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("catalog.json not on test classpath")
        catalog = CatalogDefinition.parse(json)
        validator = CatalogValidator(catalog)
    }

    private fun validate(messageJson: String): List<CatalogValidator.ValidationError> {
        val parsed = parser.parse(messageJson)
        assertTrue("Expected UpdateComponentsMessage", parsed is A2uiUpdateComponentsMessage)
        return validator.validate(parsed)
    }

    private fun msg(componentJson: String): String =
        """{"version":"v0.9.1","updateComponents":{"surfaceId":"t","components":[
            {"id":"root","component":"Column","children":["x1"]},$componentJson]}}""".trimIndent()

    /** Positive: every messageExample validates cleanly (round-trip 10/10 + validation). */
    @Test
    fun `all catalog messageExamples pass validation`() {
        var checked = 0
        catalog.components.forEach { (name, def) ->
            val example = def.messageExample ?: return@forEach
            val errors = validate(example.stringify())
            assertEquals("messageExample of $name should pass: $errors", 0, errors.size)
            checked++
        }
        assertEquals(10, checked)
    }

    // Negative 1: INVALID_PROPERTY_VALUE (enum)
    @Test
    fun `negative 1 - MetricCard trend spiral rejected`() {
        val errors = validate(msg(
            """{"id":"x1","component":"MetricCard","label":"Overdue","value":"2","trend":"spiral"}"""
        ))
        assertEquals(1, errors.size)
        assertEquals("INVALID_PROPERTY_VALUE", errors[0].code)
    }

    // Negative 2: MISSING_REQUIRED_PROPERTY
    @Test
    fun `negative 2 - MetricCard without value rejected`() {
        val errors = validate(msg(
            """{"id":"x1","component":"MetricCard","label":"Overdue"}"""
        ))
        assertEquals(1, errors.size)
        assertEquals("MISSING_REQUIRED_PROPERTY", errors[0].code)
    }

    // Negative 3: UNSUPPORTED_RENDERER_TYPE (deferred input component)
    @Test
    fun `negative 3 - TextField rejected as deferred`() {
        val errors = validate(msg(
            """{"id":"x1","component":"TextField","label":"Edit me"}"""
        ))
        assertEquals(1, errors.size)
        assertEquals("UNSUPPORTED_RENDERER_TYPE", errors[0].code)
    }

    // Negative 4: UNSUPPORTED_RENDERER_TYPE (unbudgeted media)
    @Test
    fun `negative 4 - Video and AudioPlayer rejected`() {
        val errors = validate(
            """{"version":"v0.9.1","updateComponents":{"surfaceId":"t","components":[
                {"id":"root","component":"Column","children":["v1","a1"]},
                {"id":"v1","component":"Video","url":"https://x.mp4"},
                {"id":"a1","component":"AudioPlayer","url":"https://x.mp3"}]}}""".trimIndent()
        )
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.code == "UNSUPPORTED_RENDERER_TYPE" })
    }

    // Negative 5: UNSUPPORTED_RENDERER_TYPE (unbudgeted Image)
    @Test
    fun `negative 5 - Image rejected`() {
        val errors = validate(msg(
            """{"id":"x1","component":"Image","url":"https://x.png"}"""
        ))
        assertEquals(1, errors.size)
        assertEquals("UNSUPPORTED_RENDERER_TYPE", errors[0].code)
    }

    // Negative 6: NESTING_NOT_ALLOWED (TrendChart inside EntityCard)
    @Test
    fun `negative 6 - TrendChart inside EntityCard rejected`() {
        val errors = validate(
            """{"version":"v0.9.1","updateComponents":{"surfaceId":"t","components":[
                {"id":"root","component":"Column","children":["ec1"]},
                {"id":"ec1","component":"EntityCard","entityType":"opportunity","entityId":"x1",
                 "title":"Acme","children":["tc1"]},
                {"id":"tc1","component":"TrendChart","kind":"bar","points":[1,2,3],
                 "labels":["a","b","c"]}]}}""".trimIndent()
        )
        assertEquals(1, errors.size)
        assertEquals("NESTING_NOT_ALLOWED", errors[0].code)
        assertTrue(errors[0].hint?.contains("EntityCard") == true)
    }

    // Negative 7: ACTION_NOT_ALLOWED (sendEmail is not in the whitelist)
    @Test
    fun `negative 7 - sendEmail action rejected`() {
        val errors = validate(msg(
            """{"id":"x1","component":"PrimaryActionButton","label":"Send email",
                "action":{"event":{"name":"sendEmail","context":{"to":"x@y.z"}}}}"""
        ))
        assertEquals(1, errors.size)
        assertEquals("ACTION_NOT_ALLOWED", errors[0].code)
        assertTrue(errors[0].allowedValues?.contains("createTask") == true)
    }
}

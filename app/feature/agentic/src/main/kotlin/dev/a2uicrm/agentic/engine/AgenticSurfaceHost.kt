package dev.a2uicrm.agentic.engine

import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.protocol.A2uiException
import androidx.a2ui.model.protocol.A2uiServerToClientMessage
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import dev.a2uicrm.agentic.catalog.CatalogDefinition
import dev.a2uicrm.agentic.catalog.RuntimeCatalog
import dev.a2uicrm.agentic.json.jsonLexerReaderFactory
import dev.a2uicrm.agentic.validate.CatalogValidator
import androidx.a2ui.model.processor.A2uiJsonMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The A2UI sandbox host: wires the full pipeline (analysis.md §7.3):
 *
 *   JSON -> A2uiJsonMessageParser -> CatalogValidator (layer 1) ->
 *   A2uiCoreMessageProcessor (layer 2, full schema) -> ComposeComponentRegistry -> Renderer
 *
 * All messages arrive as raw JSON (0.9.1 envelope: version + createSurface/updateComponents/
 * updateDataModel/deleteSurface). The host is role-agnostic: the SAME catalog serves both
 * roles; only the JSON compositions differ (demo assets home_manager.json / home_head.json).
 */
class AgenticSurfaceHost(
    catalogJson: String,
    scope: CoroutineScope,
) {
    val catalogDefinition: CatalogDefinition = CatalogDefinition.parse(catalogJson)
    val validator: CatalogValidator = CatalogValidator(catalogDefinition)
    val registry = ComposeComponentRegistry()
    val dataModel = ComposeDataModel()

    val catalogId: String get() = catalogDefinition.catalogId

    private val runtimeCatalog = RuntimeCatalog.build(catalogDefinition)
    private val processor = A2uiCoreMessageProcessor(
        catalogs = listOf(runtimeCatalog),
        dataModelFactory = { dataModel },
        componentRegistryFactory = { registry },
        actionInterceptors = emptyList(),
    )

    private val collectJob: Job = scope.launch { processor.collectMessages() }

    /** Structured, agent-facing outcome of submitting raw JSON (validation layer 1). */
    data class SubmissionResult(
        val accepted: Boolean,
        val message: A2uiServerToClientMessage?,
        val errors: List<CatalogValidator.ValidationError>,
    )

    /** Errors surfaced asynchronously by the engine (layer 2, full-schema validation). */
    fun engineErrors(): List<Pair<String, String>> = registry.errors

    /** Parses + validates + (if valid) processes one raw JSON message. */
    fun submit(json: String): SubmissionResult {
        registry.clearErrors()
        val parsed = try {
            parser.parse(json)
        } catch (e: A2uiException) {
            return SubmissionResult(
                accepted = false,
                message = null,
                errors = listOf(
                    CatalogValidator.ValidationError(
                        code = "VALIDATION_FAILED",
                        path = (e.context["path"] as? String) ?: "/",
                        message = e.message ?: "Malformed JSON message",
                    )
                ),
            )
        }
        val errors = validator.validate(parsed)
        if (errors.isNotEmpty()) {
            return SubmissionResult(accepted = false, message = null, errors = errors)
        }
        // The engine's surface actor can throw on full-schema violations (e.g. an LLM
        // component that passes layer 1 but breaks layer 2 shape). That must NEVER
        // propagate to the caller — report it as a structured rejection instead.
        return try {
            processor.processMessage(parsed)
            SubmissionResult(accepted = true, message = parsed, errors = emptyList())
        } catch (e: Exception) {
            SubmissionResult(
                accepted = false,
                message = null,
                errors = listOf(
                    CatalogValidator.ValidationError(
                        code = "ENGINE_ERROR",
                        path = "/",
                        message = "Движок отклонил сообщение: ${e.message?.take(200) ?: e.javaClass.simpleName}",
                    ),
                ),
            )
        }
    }

    /** Convenience: createSurface + updateComponents in one call (sandbox demo flow). */
    fun submitScreen(surfaceId: String, componentsJson: String): SubmissionResult {
        val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"$surfaceId","catalogId":"$catalogId"}}"""
        val createResult = submit(create)
        if (!createResult.accepted) return createResult
        return submit(componentsJson)
    }

    /** Components-only submit (AgentSession creates the surface first, then pushes data). */
    fun submitComponents(surfaceId: String, componentsJson: String): SubmissionResult =
        submit(componentsJson)

    /** Clears the rendered component list (used between surface rebuilds). */
    fun resetRegistry() {
        registry.update(emptyList())
    }

    fun deleteSurface(surfaceId: String) {
        processor.processMessage(A2uiDeleteSurfaceMessage(surfaceId))
    }

    fun createSurface(surfaceId: String) {
        processor.processMessage(A2uiCreateSurfaceMessage(surfaceId, catalogId))
    }

    fun updateComponents(message: A2uiUpdateComponentsMessage) {
        processor.processMessage(message)
    }

    fun activeSurfaces(): List<A2uiSurfaceModel> = processor.activeSurfaces.value

    fun dispose() {
        collectJob.cancel()
        registry.close()
        dataModel.close()
    }

    companion object {
        private val parser = A2uiJsonMessageParser(jsonLexerReaderFactory())
    }
}

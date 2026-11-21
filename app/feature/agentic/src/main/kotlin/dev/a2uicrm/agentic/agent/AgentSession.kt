package dev.a2uicrm.agentic.agent

import dev.a2uicrm.agentic.engine.AgenticSurfaceHost
import dev.a2uicrm.agentic.validate.CatalogValidator
import kotlinx.coroutines.delay

/**
 * The agent request loop (analysis.md §7.3): user request → [AgentGateway] → raw JSON →
 * host two-layer validation → rendered screen. Structured layer-1 errors are fed BACK to
 * the agent (self-correction, ≤ [maxAttempts] per the catalog feedbackLoop contract);
 * after the last failed attempt the session reports the errors to the UI (level-3 UX).
 */
class AgentSession(
    private val host: AgenticSurfaceHost,
    private val agent: AgentGateway,
    private val maxAttempts: Int = 2,
) {

    data class SessionResult(
        val accepted: Boolean,
        val attempts: Int,
        val inference: List<String>,
        val errors: List<CatalogValidator.ValidationError> = emptyList(),
        /** True when the last action changed repository data (renderer-triggered refresh). */
        val refreshed: Boolean = false,
        /** The accepted (or last) agent response — the app layer saves/restores it. */
        val response: AgentResponse? = null,
    )

    suspend fun run(request: AgentRequest): SessionResult {
        var feedback: List<String> = emptyList()
        var lastErrors: List<CatalogValidator.ValidationError> = emptyList()
        var lastResponse: AgentResponse? = null

        for (attempt in 1..maxAttempts) {
            val response = agent.apply(request, feedback)
            lastResponse = response
            val applied = applyResponse(response)
            if (applied.accepted) {
                return applied.copy(attempts = attempt)
            }
            // Structured errors -> agent feedback for the next attempt.
            feedback = applied.errors.map { it.toString() }
            lastErrors = applied.errors
        }
        return SessionResult(
            accepted = false,
            attempts = maxAttempts,
            inference = listOf("The agent could not build the screen in $maxAttempts attempt(s) — error details follow"),
            errors = lastErrors,
            response = lastResponse,
        )
    }

    /**
     * Pushes an already-built [AgentResponse] into the host (createSurface → data →
     * components). Used by [run] and by the app layer to restore a saved screen
     * after process death or to rebuild after a repository mutation.
     */
    suspend fun applyResponse(response: AgentResponse): SessionResult {
        host.deleteSurface(response.surfaceId)
        host.resetRegistry()
        delay(80) // let the single-threaded surface actor settle
        // Order matters: create the surface FIRST so the engine binds the data model,
        // then push data, then components (updateDataModel on a missing surface is dropped).
        host.createSurface(response.surfaceId)
        delay(40)
        response.updateDataModelJson?.let { host.submit(it) }
        delay(40)
        val result = host.submitComponents(response.surfaceId, response.componentsJson)
        return SessionResult(
            accepted = result.accepted,
            attempts = 1,
            inference = response.inference,
            errors = result.errors,
            response = response,
        )
    }
}

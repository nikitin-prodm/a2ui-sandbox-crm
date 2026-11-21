package dev.a2uicrm.agentic.agent

/**
 * The seam between the app and "the agent" (analysis.md §7.3).
 *
 * v1: implemented by [DemoAgent] — a deterministic simulator that classifies free-form
 * RU/EN requests, FILLS IN the missing requirements (the "сделай красиво и не грузи
 * вопросами" behavior) and emits catalog-valid A2UI JSON.
 * v2: a real LLM-backed implementation behind the SAME interface (network + prompt with
 * the catalog contract; the two-layer validation stays unchanged).
 */
interface AgentGateway {

    /**
     * Produces a screen composition for [request].
     *
     * @param feedback structured validation errors from a previous attempt (empty on the
     * first call) — a real agent uses them to self-correct; the loop lives in [AgentSession].
     */
    suspend fun apply(request: AgentRequest, feedback: List<String> = emptyList()): AgentResponse
}

/** What the user asked for (voice-recognition output or typed text) + active role. */
data class AgentRequest(
    val role: String,
    val text: String,
    /** Compact repository snapshot so the LLM sees REAL tasks/deals, not invented ones. */
    val repoSnapshot: String? = null,
    /**
     * Current screen composition (components JSON) when the user asks to AUGMENT an
     * existing screen («добавь сюда…»). The gateway must preserve everything in it.
     */
    val currentScreenJson: String? = null,
)

/** A generated screen: raw 0.9.1 JSON messages + transparency notes about inference. */
data class AgentResponse(
    val surfaceId: String,
    val updateDataModelJson: String?,
    val componentsJson: String,
    /** What the agent inferred/filled in — shown to the user (level-1 UX, §5.2). */
    val inference: List<String> = emptyList(),
    /**
     * 5.4i app-extension SHELL line `{"shell":{"tabTitles":{...}}}` — agent-requested
     * RENAMES of section titles (bottom-nav labels, native/A2UI headers). Pure data,
     * validated and applied by the app layer; the protocol core is untouched.
     */
    val shellJson: String? = null,
)

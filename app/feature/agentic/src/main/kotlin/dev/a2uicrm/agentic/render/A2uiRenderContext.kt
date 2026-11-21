package dev.a2uicrm.agentic.render

import androidx.a2ui.model.protocol.A2uiComponentPayload
import dev.a2uicrm.agentic.action.ActionDispatcher
import dev.a2uicrm.agentic.engine.ComposeDataModel

/**
 * Everything a renderer node needs beyond the tree: DataBinding resolution
 * (`{"path": ...}` -> ComposeDataModel), action dispatching with the catalog's
 * rendererSemantics conventions (defaultActions + `<this X>` placeholders), and the
 * adjacency-list lookup. Passed down the composable tree instead of globals.
 */
class A2uiRenderContext(
    val surfaceId: String,
    val byId: Map<String, A2uiComponentPayload>,
    val dataModel: ComposeDataModel,
    val dispatcher: ActionDispatcher,
) {

    /** DataBinding resolution; literals pass through unchanged. */
    fun resolve(value: Any?): Any? = when {
        value is Map<*, *> && value.containsKey("path") && value["path"] is String ->
            dataModel.get(value["path"] as String)
        else -> value
    }

    /**
     * Dispatches the component's action property (or the rendererSemantics default).
     * `<this X>` placeholder values are replaced with the owning component's props.
     */
    fun dispatchAction(node: A2uiComponentPayload, defaultEvent: Map<String, Any?>? = null) {
        val raw: Any? = node.properties["action"]
            ?: defaultEvent?.let { mapOf("event" to it) }
            ?: return
        val resolved = resolvePlaceholders(raw, node.properties)
        dispatcher.dispatch(surfaceId, node.id, resolved)
    }

    private val placeholder = Regex("^<this ([A-Za-z0-9_]+)>$")

    private fun resolvePlaceholders(value: Any?, props: Map<String, Any?>): Any? = when (value) {
        is String -> placeholder.matchEntire(value)?.let { props[it.groupValues[1]] } ?: value
        is Map<*, *> -> {
            val newMap = mutableMapOf<String, Any?>()
            @Suppress("UNCHECKED_CAST")
            for ((k, v) in value as Map<String, Any?>) {
                newMap[k] = if (v is String) {
                    placeholder.matchEntire(v)?.let { props[it.groupValues[1]] } ?: v
                } else v
            }
            newMap
        }
        else -> value
    }
}

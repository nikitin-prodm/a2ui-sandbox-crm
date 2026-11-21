package dev.a2uicrm.agentic.validate

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiServerToClientMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import dev.a2uicrm.agentic.catalog.CatalogDefinition

/**
 * MVP CatalogValidator — LAYER 1 of the two-layer validation model (analysis.md §7.2).
 *
 * Runs BEFORE the message is submitted to the engine and reports structured,
 * agent-facing errors (self-correction loop, ≤2 retries per the catalog feedbackLoop):
 *  - UNKNOWN_COMPONENT          (type not in the catalog)
 *  - UNSUPPORTED_RENDERER_TYPE  (type valid per spec but not budgeted in this renderer)
 *  - MISSING_REQUIRED_PROPERTY  (catalog.json schema: required)
 *  - INVALID_PROPERTY_VALUE     (catalog.json schema: enum)
 *  - NESTING_NOT_ALLOWED        (machine-readable nestingRules + tree walk)
 *  - ACTION_NOT_ALLOWED         (action whitelist)
 *
 * LAYER 2 (engine surface actor, FULL-SCHEMA) is the second line of defense — verified by
 * probe/MESSAGEPROCESSOR_PROBE_OUTPUT.txt case [5].
 */
class CatalogValidator(private val catalog: CatalogDefinition) {

    data class ValidationError(
        val code: String,
        val path: String,
        val message: String,
        val allowedValues: List<String>? = null,
        val hint: String? = null,
    ) {
        override fun toString(): String = "$code: $message (path=$path" +
            (allowedValues?.let { ", allowed=$it" } ?: "") +
            (hint?.let { ", hint='$it'" } ?: "") + ")"
    }

    private val nativeRendererSupport: Map<String, String> =
        catalog.nativeComponents?.components?.associate { it.name to it.rendererSupport } ?: emptyMap()

    private val crmComponents: Set<String> = catalog.components.keys

    private val allowedActions: Set<String> = catalog.actions.keys

    fun validate(message: A2uiServerToClientMessage): List<ValidationError> {
        if (message !is A2uiUpdateComponentsMessage) return emptyList()
        val payloads = message.components
        val errors = mutableListOf<ValidationError>()
        for (payload in payloads) {
            validateComponent(payload, errors)
        }
        validateNesting(payloads, errors)
        return errors
    }

    private fun validateComponent(payload: A2uiComponentPayload, errors: MutableList<ValidationError>) {
        val type = payload.type

        val nativeSupport = nativeRendererSupport[type]
        if (nativeSupport != null) {
            if (nativeSupport == NOT_IMPLEMENTED_V1) {
                errors += ValidationError(
                    code = "UNSUPPORTED_RENDERER_TYPE",
                    path = "/updateComponents/components/${payload.id}/component",
                    message = "'$type' is valid per spec 0.9.1 but deferred in this renderer",
                    hint = "Use the budgeted catalog types (see rendererSupport) or a dedicated flow instead",
                )
            }
            return
        }

        val def = catalog.components[type] ?: run {
            errors += ValidationError(
                code = "UNKNOWN_COMPONENT",
                path = "/updateComponents/components/${payload.id}/component",
                message = "'$type' is not in the catalog",
                allowedValues = (nativeRendererSupport.keys + crmComponents).sorted(),
                hint = "Use one of the catalog types",
            )
            return
        }

        for ((propName, prop) in def.schema) {
            if (prop.required && !payload.properties.containsKey(propName)) {
                errors += ValidationError(
                    code = "MISSING_REQUIRED_PROPERTY",
                    path = "/updateComponents/components/${payload.id}/$propName",
                    message = "$type.$propName is required",
                )
                continue
            }
            val value = payload.properties[propName] ?: continue
            if (propName == "action") {
                validateAction(type, payload.id, value, errors)
            }
            if (prop.enum != null && value is String) {
                if (prop.enum.none { it == value }) {
                    errors += ValidationError(
                        code = "INVALID_PROPERTY_VALUE",
                        path = "/updateComponents/components/${payload.id}/$propName",
                        message = "$type.$propName='$value' not in enum",
                        allowedValues = prop.enum,
                    )
                }
            }
        }
    }

    private fun validateAction(type: String, id: String, value: Any?, errors: MutableList<ValidationError>) {
        val eventName = (value as? Map<*, *>)
            ?.get("event")?.let { it as? Map<*, *> }
            ?.get("name") as? String
            ?: return
        if (eventName !in allowedActions) {
            errors += ValidationError(
                code = "ACTION_NOT_ALLOWED",
                path = "/updateComponents/components/$id/action/event/name",
                message = "$type: action '$eventName' is not in the whitelist",
                allowedValues = allowedActions.sorted(),
                hint = "sendEmail / openUrl / arbitrary code execution are not available",
            )
        }
    }

    /** Nesting constraints: tree walk from root with ancestor stack (catalog.json nestingRules). */
    private fun validateNesting(payloads: List<A2uiComponentPayload>, errors: MutableList<ValidationError>) {
        val byId = payloads.associateBy { it.id }
        val root = byId["root"] ?: return
        walk(root, byId, ArrayDeque(), errors)
    }

    private fun walk(
        node: A2uiComponentPayload,
        byId: Map<String, A2uiComponentPayload>,
        ancestors: ArrayDeque<String>,
        errors: MutableList<ValidationError>,
    ) {
        val type = node.type
        for (parent in ancestors) {
            for (rule in catalog.nestingRules) {
                if (rule.parent != parent) continue
                if (type in rule.forbiddenChildren) {
                    errors += ValidationError(
                        code = "NESTING_NOT_ALLOWED",
                        path = "/updateComponents/components/${node.id}/component",
                        message = "$type inside $parent",
                        hint = "Ancestor chain: ${ancestors.reversed().joinToString(" -> ")} -> $type",
                    )
                }
            }
        }
        val children = node.properties["children"] as? List<*>
        if (children != null) {
            ancestors.addLast(type)
            for (childId in children) {
                val child = byId[childId as? String ?: continue] ?: continue
                walk(child, byId, ancestors, errors)
            }
            ancestors.removeLast()
        }
    }

    companion object {
        const val NOT_IMPLEMENTED_V1 = "not_implemented_v1"
    }
}

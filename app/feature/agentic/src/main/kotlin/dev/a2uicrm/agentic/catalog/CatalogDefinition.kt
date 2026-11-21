package dev.a2uicrm.agentic.catalog

import dev.a2uicrm.agentic.json.JsonValue

/**
 * Typed view of catalog/catalog.json — the single source of truth for what the renderer
 * can render (spec 0.9.1). Parsed with the module's own JSON DOM (no compiler plugins
 * needed — see JsonDom.kt); unknown keys are ignored so the catalog can carry
 * human-facing documentation fields.
 */
data class CatalogDefinition(
    val catalogId: String,
    val version: String = "1.0.0",
    val protocolVersion: String = "0.9.1",
    val components: Map<String, ComponentDefinition> = emptyMap(),
    val nativeComponents: NativeComponents? = null,
    val nestingRules: List<NestingRule> = emptyList(),
    val actions: Map<String, ActionDefinition> = emptyMap(),
) {
    data class ComponentDefinition(
        val spec_support: String = "app_extension",
        val schema: Map<String, PropertyDefinition> = emptyMap(),
        /** Full 0.9.1 envelope example — the round-trip fixture (probe 10/10). */
        val messageExample: JsonValue.JsonObject? = null,
    )

    data class PropertyDefinition(
        val type: String? = null,
        val required: Boolean = false,
        val enum: List<String>? = null,
        val maxLength: Int? = null,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    )

    data class NativeComponents(
        val components: List<NativeComponent> = emptyList(),
    )

    data class NativeComponent(
        val name: String,
        val spec_support: String = "native",
        val rendererSupport: String = "native",
    )

    data class NestingRule(
        val parent: String,
        val forbiddenChildren: List<String> = emptyList(),
    )

    data class ActionDefinition(
        val repositoryMapping: String? = null,
    )

    companion object {
        fun parse(text: String): CatalogDefinition {
            val root = JsonValue.parse(text) as? JsonValue.JsonObject
                ?: error("catalog.json root must be an object")
            return parseCatalog(root)
        }

        private fun parseCatalog(root: JsonValue.JsonObject): CatalogDefinition {
            val components = mutableMapOf<String, ComponentDefinition>()
            root.obj("components")?.entries?.forEach { (name, raw) ->
                val obj = raw as? JsonValue.JsonObject ?: return@forEach
                val schema = mutableMapOf<String, PropertyDefinition>()
                obj.obj("schema")?.entries?.forEach { (propName, propRaw) ->
                    val prop = propRaw as? JsonValue.JsonObject ?: return@forEach
                    schema[propName] = PropertyDefinition(
                        type = prop.string("type"),
                        required = prop.bool("required") ?: false,
                        enum = (prop["enum"] as? JsonValue.JsonArray)
                            ?.items?.mapNotNull { (it as? JsonValue.JsonString)?.value },
                        maxLength = prop.int("maxLength"),
                        minItems = prop.int("minItems"),
                        maxItems = prop.int("maxItems"),
                    )
                }
                components[name] = ComponentDefinition(
                    spec_support = obj.string("spec_support") ?: "app_extension",
                    schema = schema,
                    messageExample = obj.obj("messageExample"),
                )
            }

            val native = root.obj("nativeComponents")?.array("components")?.items
                ?.mapNotNull { raw ->
                    val obj = raw as? JsonValue.JsonObject ?: return@mapNotNull null
                    NativeComponent(
                        name = obj.string("name") ?: return@mapNotNull null,
                        spec_support = obj.string("spec_support") ?: "native",
                        rendererSupport = obj.string("rendererSupport") ?: "native",
                    )
                } ?: emptyList()

            val nesting = root.array("nestingRules")?.items?.mapNotNull { raw ->
                val obj = raw as? JsonValue.JsonObject ?: return@mapNotNull null
                NestingRule(
                    parent = obj.string("parent") ?: return@mapNotNull null,
                    forbiddenChildren = (obj["forbiddenChildren"] as? JsonValue.JsonArray)
                        ?.items?.mapNotNull { (it as? JsonValue.JsonString)?.value } ?: emptyList(),
                )
            } ?: emptyList()

            val actions = mutableMapOf<String, ActionDefinition>()
            root.obj("actions")?.entries?.forEach { (name, raw) ->
                val obj = raw as? JsonValue.JsonObject ?: return@forEach
                actions[name] = ActionDefinition(repositoryMapping = obj.string("repositoryMapping"))
            }

            return CatalogDefinition(
                catalogId = root.string("catalogId") ?: error("catalog.json: catalogId is required"),
                version = root.string("version") ?: "1.0.0",
                protocolVersion = root.string("protocolVersion") ?: "0.9.1",
                components = components,
                nativeComponents = NativeComponents(native),
                nestingRules = nesting,
                actions = actions,
            )
        }
    }
}

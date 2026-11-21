package dev.a2uicrm.agentic.catalog

import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinition
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinitionCollection
import androidx.a2ui.model.catalog.A2uiFunctionCollection
import androidx.a2ui.model.schema.A2uiAnySchema
import androidx.a2ui.model.schema.A2uiArraySchema
import androidx.a2ui.model.schema.A2uiBooleanSchema
import androidx.a2ui.model.schema.A2uiNumberSchema
import androidx.a2ui.model.schema.A2uiObjectSchema
import androidx.a2ui.model.schema.A2uiSchema
import androidx.a2ui.model.schema.A2uiSchemaKeyword
import androidx.a2ui.model.schema.A2uiStringSchema

/**
 * Builds the runtime [A2uiCoreCatalog] consumed by A2uiCoreMessageProcessor.
 *
 * §7.1 (analysis.md): the surface actor validates components against the runtime catalog,
 * so it must register EVERY type the renderer can draw: the budgeted native types
 * (8/18, §3.1) plus all 10 CRM components. Property schemas are generated from
 * catalog.json (source of truth); the engine full-schema-validates against them
 * (probe MESSAGEPROCESSOR_PROBE_OUTPUT.txt, case [5]).
 */
object RuntimeCatalog {

    fun build(definition: CatalogDefinition): A2uiCoreCatalog {
        val componentDefs = buildList {
            addAll(nativeDefinitions())
            definition.components.forEach { (name, def) -> add(crmDefinition(name, def)) }
        }
        val components = A2uiCoreComponentDefinitionCollection(componentDefs)
        val functions = A2uiFunctionCollection(emptyList())

        return object : A2uiCoreCatalog {
            override val id: String = definition.catalogId
            override val title: String? = "A2UI CRM Catalog"
            override val description: String? =
                "Budgeted runtime catalog generated from catalog.json (${definition.version})"
            override val componentDefinitions: A2uiCoreComponentDefinitionCollection = components
            override val functions: A2uiFunctionCollection = functions
            override val themeSchema: A2uiSchema? = null
            override val isInline: Boolean = false
        }
    }

    /** 8 budgeted native types (§3.1): Text, Row, Column, Card, Divider, Button, Icon, List. */
    private fun nativeDefinitions(): List<A2uiCoreComponentDefinition> = listOf(
        definition("Text", "A line of text; simple Markdown without HTML/links (basic-catalog l.8).") {
            A2uiObjectSchema(
                properties = mapOf("text" to A2uiStringSchema()),
                required = setOf("text"),
            )
        },
        definition("Row", "Horizontal layout container (basic-catalog l.237).") {
            A2uiObjectSchema(
                properties = mapOf("children" to A2uiArraySchema(items = A2uiStringSchema())),
                required = setOf("children"),
            )
        },
        definition("Column", "Vertical layout container; the root of every screen (basic-catalog l.283).") {
            A2uiObjectSchema(
                properties = mapOf("children" to A2uiArraySchema(items = A2uiStringSchema())),
                required = setOf("children"),
            )
        },
        definition("Card", "A single child container (basic-catalog l.366).") {
            A2uiObjectSchema(
                properties = mapOf("child" to A2uiStringSchema()),
                required = setOf("child"),
            )
        },
        definition("Divider", "A horizontal divider (basic-catalog l.461).") {
            A2uiObjectSchema(properties = emptyMap())
        },
        definition("Button", "A button with a single child and an action (basic-catalog l.488).") {
            A2uiObjectSchema(
                properties = mapOf(
                    "child" to A2uiStringSchema(),
                    "action" to A2uiAnySchema(),
                ),
                required = setOf("child", "action"),
            )
        },
        definition("Icon", "An icon from the spec enum; 5 budgeted icons in v1 (basic-catalog l.80).") {
            A2uiObjectSchema(
                properties = mapOf("name" to A2uiStringSchema()),
                required = setOf("name"),
            )
        },
        definition("List", "A list container for lazy item scrolling (basic-catalog l.329).") {
            A2uiObjectSchema(
                properties = mapOf("children" to A2uiArraySchema(items = A2uiStringSchema())),
                required = setOf("children"),
            )
        },
    )

    private fun crmDefinition(name: String, def: CatalogDefinition.ComponentDefinition): A2uiCoreComponentDefinition {
        val schema = crmPropertySchema(def)
        return definition(name, "CRM component '$name' (app_extension): see catalog/catalog.json.") { schema }
    }

    /** Maps catalog.json property definitions to a2ui engine schemas. */
    private fun crmPropertySchema(def: CatalogDefinition.ComponentDefinition): A2uiSchema {
        val properties = mutableMapOf<String, A2uiSchema>()
        val required = mutableSetOf<String>()
        def.schema.forEach { (propName, prop) ->
            properties[propName] = propSchema(prop)
            if (prop.required) required += propName
        }
        return A2uiObjectSchema(properties = properties, required = required)
    }

    private fun propSchema(prop: CatalogDefinition.PropertyDefinition): A2uiSchema {
        val keywords = mutableListOf<A2uiSchemaKeyword<String>>()
        prop.enum?.let { allowed ->
            @Suppress("UNCHECKED_CAST")
            keywords.add(A2uiSchemaKeyword.Enum(allowed as List<Any>) as A2uiSchemaKeyword<String>)
        }
        return when (prop.type) {
            "DynamicString", "string" -> A2uiStringSchema(keywords = keywords)
            "DynamicNumber", "number", "integer" -> A2uiNumberSchema()
            "DynamicBoolean", "boolean" -> A2uiBooleanSchema()
            "array" -> A2uiArraySchema(
                items = A2uiAnySchema(),
                minItems = prop.minItems ?: -1,
                maxItems = prop.maxItems ?: -1,
            )
            else -> A2uiAnySchema() // Action, free-form
        }
    }

    private inline fun definition(
        name: String,
        description: String,
        crossinline schema: () -> A2uiSchema,
    ): A2uiCoreComponentDefinition = object : A2uiCoreComponentDefinition {
        override val name: String = name
        override val description: String = description
        override val propertySchema: A2uiSchema = schema()
    }
}

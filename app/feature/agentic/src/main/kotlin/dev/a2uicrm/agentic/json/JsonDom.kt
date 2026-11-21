package dev.a2uicrm.agentic.json

/**
 * Minimal JSON DOM + parser built on top of the same lexer discipline as
 * [JsonLexerAdapter]. Used for catalog.json parsing in :feature:agentic so the module
 * needs NO Kotlin compiler plugins (AGP 9 built-in Kotlin; kotlinx.serialization
 * compiler plugin would add a KGP dependency the built-in toolchain does not accept).
 */
sealed class JsonValue {
    object JsonNull : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    data class JsonNumber(val value: Double) : JsonValue() {
        val isIntegral: Boolean get() = value == Math.floor(value) && !value.isInfinite()
        fun toInt(): Int = value.toInt()
    }
    data class JsonString(val value: String) : JsonValue()
    data class JsonArray(val items: List<JsonValue>) : JsonValue()
    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]
        fun string(key: String): String? = (entries[key] as? JsonString)?.value
        fun bool(key: String): Boolean? = (entries[key] as? JsonBool)?.value
        fun int(key: String): Int? = (entries[key] as? JsonNumber)?.takeIf { it.isIntegral }?.toInt()
        fun obj(key: String): JsonObject? = entries[key] as? JsonObject
        fun array(key: String): JsonArray? = entries[key] as? JsonArray
    }

    companion object {
        fun parse(text: String): JsonValue = DomParser(text).parseDocument()
    }
}

/** Serializes a [JsonValue] back to a JSON string (round-trip for message examples). */
fun JsonValue.stringify(): String = StringBuilder().also { write(this, it) }.toString()

private fun write(value: JsonValue, sb: StringBuilder) {
    when (value) {
        is JsonValue.JsonNull -> sb.append("null")
        is JsonValue.JsonBool -> sb.append(if (value.value) "true" else "false")
        is JsonValue.JsonNumber ->
            if (value.isIntegral) sb.append(value.toInt()) else sb.append(value.value)
        is JsonValue.JsonString -> writeString(value.value, sb)
        is JsonValue.JsonArray -> {
            sb.append('[')
            value.items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                write(item, sb)
            }
            sb.append(']')
        }
        is JsonValue.JsonObject -> {
            sb.append('{')
            var first = true
            value.entries.forEach { (k, v) ->
                if (!first) sb.append(',')
                first = false
                writeString(k, sb)
                sb.append(':')
                write(v, sb)
            }
            sb.append('}')
        }
    }
}

private fun writeString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}

private class DomParser(private val text: String) {

    private val reader = JsonLexerAdapter(text)

    fun parseDocument(): JsonValue {
        val value = parseValue()
        reader.close()
        return value
    }

    private fun parseValue(): JsonValue = when (reader.peek()) {
        androidx.a2ui.model.processor.A2uiJsonToken.BEGIN_OBJECT -> parseObject()
        androidx.a2ui.model.processor.A2uiJsonToken.BEGIN_ARRAY -> parseArray()
        androidx.a2ui.model.processor.A2uiJsonToken.STRING -> JsonValue.JsonString(reader.nextString())
        androidx.a2ui.model.processor.A2uiJsonToken.NUMBER -> JsonValue.JsonNumber(reader.nextDouble())
        androidx.a2ui.model.processor.A2uiJsonToken.BOOLEAN -> JsonValue.JsonBool(reader.nextBoolean())
        androidx.a2ui.model.processor.A2uiJsonToken.NULL -> { reader.nextNull(); JsonValue.JsonNull }
        else -> throw IllegalArgumentException("Unexpected JSON token at top level")
    }

    private fun parseObject(): JsonValue.JsonObject {
        reader.beginObject()
        val map = mutableMapOf<String, JsonValue>()
        while (reader.hasNext()) {
            val name = reader.nextName()
            map[name] = parseValue()
        }
        reader.endObject()
        return JsonValue.JsonObject(map)
    }

    private fun parseArray(): JsonValue.JsonArray {
        reader.beginArray()
        val items = mutableListOf<JsonValue>()
        while (reader.hasNext()) {
            items.add(parseValue())
        }
        reader.endArray()
        return JsonValue.JsonArray(items)
    }
}

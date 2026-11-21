package dev.a2uicrm.agentic.agent

import dev.a2uicrm.agentic.catalog.CatalogDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * REAL LLM implementation of [AgentGateway] over an OpenAI-compatible API
 * (default: https://ask.chadgpt.ru, verified live: /api/v1/chat/completions, Bearer auth).
 *
 * The LLM receives the catalog contract as the system prompt (AgentPrompt) and must reply
 * with raw 0.9.1 JSON messages. Structured validation errors from a previous attempt are
 * passed back as a user message (self-correction). Config comes from [AgentSettings]
 * (settings screen writes it; the user can point it at any OpenAI-compatible provider).
 *
 * No extra dependencies: plain HttpURLConnection on Dispatchers.IO + own JSON helpers
 * (org.json is Android-only and would break JVM unit tests).
 */
class LlmAgentGateway(
    private val catalog: CatalogDefinition,
    private val config: AgentSettings,
) : AgentGateway {

    /** Runtime config for the LLM gateway (the settings screen persists it). */
    data class AgentSettings(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val timeoutSeconds: Int = 60,
    ) {
        val usable: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

        companion object {
            const val DEFAULT_BASE_URL = "https://ask.chadgpt.ru"
            /** Cheap, follows the JSON format well; alternatives: deepseek-v4.1-flash, gemini-3.5-flash-lite. */
            const val DEFAULT_MODEL = "qwen3.8-flash"
            /** No key is shipped: the user enters their own in Settings (stored only in SharedPreferences). */
            const val DEV_API_KEY = ""
        }
    }

    override suspend fun apply(request: AgentRequest, feedback: List<String>): AgentResponse {
        if (!config.usable) {
            fail("LLM is not configured (see Settings — base URL, key, model)")
        }
        return withContext(Dispatchers.IO) {
            val system = AgentPrompt.systemPrompt(catalog, request.role)
            val user = buildString {
                if (feedback.isEmpty()) {
                    appendLine("User request: \"${request.text.ifBlank { "show the home screen" }}\"")
                    appendLine("Role: ${request.role}.")
                    request.repoSnapshot?.let {
                        appendLine()
                        appendLine(it)
                    }
                    // 5.4h LLM-first EDIT CONTRACT: the CURRENT screen is ALWAYS attached
                    // (before: only for keyword-matched "augment" requests). The model edits
                    // this tree; it does not rebuild from scratch.
                    request.currentScreenJson?.let { current ->
                        appendLine()
                        appendLine("CURRENT SCREEN (updateComponents JSON) — edit THIS tree:")
                        appendLine(current.take(24_000))
                        appendLine()
                        appendLine("TASK: apply the user's request to the tree above. Keep every component")
                        appendLine("the user did not ask to change (same ids/properties/order). Return the")
                        appendLine("FULL updated updateComponents line + the changes line first.")
                    } ?: run {
                        appendLine()
                        appendLine("No screen exists yet — compose one from catalog components.")
                    }
                } else {
                    appendLine("Your previous JSON failed validation. Errors:")
                    feedback.forEach { appendLine("- $it") }
                    appendLine("Send the FIXED JSON (same format, changes line + message lines).")
                }
            }
            // 5.4h: runInterruptible — a hanging LLM stream (blocked socket read) is
            // interruptible by the caller's withTimeout, so the chat's hard timeout
            // actually WORKS instead of leaving an eternal spinner.
            val content = try {
                kotlinx.coroutines.runInterruptible { chat(config, system, user) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("LLM call failed: ${e.message?.take(160)}")
            }
            parseAgentReply(content, request)
                ?: fail("LLM returned no valid JSON (check the model name in Settings)")
        }
    }

    private fun chat(settings: AgentSettings, system: String, user: String): String {
        // 5.4h: the user may paste EITHER the host (https://ask.chadgpt.ru) or the FULL
        // endpoint (.../api/v1/chat/completions) into Settings — normalize, never double.
        val base = settings.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/api/v1/chat/completions"
        println("A2UI-LLM -> POST $endpoint model=${settings.model}")
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        val t0 = System.currentTimeMillis()
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            connection.readTimeout = TimeUnit.SECONDS.toMillis(settings.timeoutSeconds.toLong()).toInt()
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            // Body: {"model":...,"messages":[{"role":...,"content":...},...]} — built manually.
            val body = buildString {
                append("{")
                append("\"model\":").append(jsonString(settings.model))
                append(",\"temperature\":0.2")
                append(",\"messages\":[")
                append("{\"role\":\"system\",\"content\":").append(jsonString(system)).append("},")
                append("{\"role\":\"user\",\"content\":").append(jsonString(user)).append("}")
                append("]}")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = connection.responseCode
            println("A2UI-LLM <- HTTP $code in ${System.currentTimeMillis() - t0}ms")
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code: ${err.take(200)}")
            }
            extractChatContent(text)
                ?: throw IllegalStateException("Не найден content в ответе: ${text.take(150)}")
        } finally {
            connection.disconnect()
        }
    }

    /** Extracts choices[0].message.content from a chat completion response (own parser). */
    fun extractChatContent(responseJson: String): String? {
        val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(responseJson) ?: return null
        return unescape(m.groupValues[1]).trim()
    }

    /**
     * Extracts the two 0.9.1 JSON messages from the model reply (tolerates markdown
     * fences and stray prose) and packs them into an [AgentResponse].
     */
    /**
     * Extracts the changes line + the two 0.9.1 JSON messages from the model reply
     * (tolerates markdown fences and stray prose) and packs them into an [AgentResponse].
     * The `{"changes":[...]}` line becomes the human-readable inference (5.4h).
     */
    fun parseAgentReply(content: String, request: AgentRequest): AgentResponse? {
        val cleaned = content
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        // Line-oriented first (the prompt asks for one message per line), regex fallback.
        val lines = cleaned.lines().filter { it.trim().startsWith("{") }
        val candidates = (if (lines.isNotEmpty()) lines else Regex("\\{.*}").findAll(cleaned).map { it.value }.toList())
        if (candidates.isEmpty()) return null

        var dataModelJson: String? = null
        var componentsJson: String? = null
        var shellJson: String? = null
        val changes = mutableListOf<String>()
        for (candidate in candidates) {
            val c = candidate.trim()
            if (c.contains("\"updateDataModel\"") && dataModelJson == null) dataModelJson = c
            if (c.contains("\"updateComponents\"") && componentsJson == null) componentsJson = c
            if (c.contains("\"shell\"") && shellJson == null) shellJson = c
            if (c.contains("\"changes\"")) {
                Regex("\"changes\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(c)
                    ?.groupValues?.get(1)?.let { arr ->
                        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).forEach { changes.add(unescape(it.groupValues[1])) }
                    }
            }
        }
        if (componentsJson == null) return null

        val surfaceId = if (request.role == "head") "home-head" else "home-mgr"
        return AgentResponse(
            surfaceId = surfaceId,
            updateDataModelJson = dataModelJson,
            componentsJson = componentsJson,
            inference = if (changes.isEmpty()) listOf("Screen edited by LLM ${config.model}") else changes,
            shellJson = shellJson,
        )
    }

    /**
     * 5.4h honest failure: the LLM gateway THROWS instead of silently replacing the
     * current screen with a stub — the caller (chat) shows the error and the screen
     * stays intact. The user retries or fixes Settings.
     */
    private fun fail(note: String): Nothing = throw IllegalStateException(note)

    companion object {
        fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
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
            return sb.append("\"").toString()
        }

        fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'u' -> {
                            if (i + 5 < s.length + 1 && i + 6 <= s.length) {
                                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 6
                            } else {
                                sb.append(c); i++
                            }
                        }
                        else -> { sb.append(c); sb.append(next); i += 2 }
                    }
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}

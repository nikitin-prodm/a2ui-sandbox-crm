package dev.a2uicrm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.agentic.agent.LlmAgentGateway

/**
 * User-configurable agent settings (5.4): point the app at any OpenAI-compatible
 * provider, or fall back to the built-in deterministic simulator.
 * Persisted in SharedPreferences. No key is bundled — the user enters their own
 * (it never leaves the device except as the Bearer header to the chosen provider).
 */
class AgentPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("a2ui_agent", Context.MODE_PRIVATE)

    var useLlm: Boolean
        get() = prefs.getBoolean(KEY_USE_LLM, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_LLM, value).apply() }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, LlmAgentGateway.AgentSettings.DEFAULT_BASE_URL)
            ?: LlmAgentGateway.AgentSettings.DEFAULT_BASE_URL
        set(value) { prefs.edit().putString(KEY_BASE_URL, value).apply() }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, LlmAgentGateway.AgentSettings.DEV_API_KEY)
            ?: LlmAgentGateway.AgentSettings.DEV_API_KEY
        set(value) { prefs.edit().putString(KEY_API_KEY, value).apply() }

    var model: String
        get() = prefs.getString(KEY_MODEL, LlmAgentGateway.AgentSettings.DEFAULT_MODEL)
            ?.ifBlank { LlmAgentGateway.AgentSettings.DEFAULT_MODEL }
            ?: LlmAgentGateway.AgentSettings.DEFAULT_MODEL
        set(value) { prefs.edit().putString(KEY_MODEL, value.ifBlank { LlmAgentGateway.AgentSettings.DEFAULT_MODEL }).apply() }

    var role: String
        get() = prefs.getString(KEY_ROLE, "manager") ?: "manager"
        set(value) { prefs.edit().putString(KEY_ROLE, value).apply() }

    private companion object {
        const val KEY_USE_LLM = "use_llm"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_ROLE = "role"
    }
}


@Composable
fun SettingsScreen(prefs: AgentPrefs, onClose: () -> Unit, onResetDemoData: () -> Unit = {}) {
    var useLlm by remember { mutableStateOf(prefs.useLlm) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var role by remember { mutableStateOf(prefs.role) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving")
            }
            Text("Agent settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Connect any OpenAI-compatible provider or use the built-in simulator.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        Text("Role (for the agent and empty requests)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = role == "manager",
                onClick = { role = "manager" },
                label = { Text("Manager") },
            )
            FilterChip(
                selected = role == "head",
                onClick = { role = "head" },
                label = { Text("Head") },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use LLM", fontWeight = FontWeight.Medium)
                Text(
                    "off = offline demo simulator",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useLlm, onCheckedChange = { useLlm = it })
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://ask.chadgpt.ru") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            enabled = useLlm,
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (Bearer)") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            enabled = useLlm,
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            placeholder = { Text(LlmAgentGateway.AgentSettings.DEFAULT_MODEL) },
            supportingText = { Text("cheap: qwen3.8-flash, deepseek-v4.1-flash, gemini-3.5-flash-lite") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            enabled = useLlm,
            singleLine = true,
        )

        Text(
            "The key is stored locally (SharedPreferences) and sent only to your provider.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (apiKey.isBlank()) {
            Text(
                "Warning: no API key set — the agent falls back to the offline DemoAgent.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.padding(8.dp))
        Button(
            onClick = {
                prefs.useLlm = useLlm
                prefs.baseUrl = baseUrl.trim()
                prefs.apiKey = apiKey.trim()
                // 5.4h: an empty model silently killed every LLM call (model=\"\" in prefs).
                prefs.model = model.trim()
                prefs.role = role
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        Spacer(modifier = Modifier.padding(12.dp))
        Text("Demo data", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "Reset tasks/deals to the canned fixtures and drop all agent-composed screens. " +
                "Settings (provider, key, role) are kept.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onResetDemoData,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { Text("Reset demo data") }
    }
}

/**
 * Persists EVERY tab's agent-composed screen (raw 0.9.1 JSON) so each раздел keeps its
 * individual view across tab switches AND process death. The banner «Экран собран
 * A2UI-агентом» + «Вернуться» are part of A2UIScreen and come back with the composition.
 */
class SavedScreenPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("a2ui_screen", Context.MODE_PRIVATE)

    init {
        // One-time migration (5.4g): compositions saved BEFORE this schema embody the old
        // template behavior (banner/metrics chrome the user never asked for). Drop them
        // once — the user starts clean and the agent re-composes on request. Repo data
        // (tasks/deals) is NOT touched (separate prefs file).
        if (prefs.getInt("schema", 0) < SCHEMA) {
            prefs.edit().clear().putInt("schema", SCHEMA).apply()
        }
    }

    data class Saved(
        val componentsJson: String,
        val dataModelJson: String?,
        val surfaceId: String,
        val inference: List<String>,
        val tabIndex: Int,
        val requestText: String?,
        val active: Boolean = true,
    )

    fun setActive(tabIndex: Int, active: Boolean) {
        prefs.edit().putBoolean("a_$tabIndex", active).apply()
    }

    fun save(tabIndex: Int, componentsJson: String, dataModelJson: String?, surfaceId: String, inference: List<String>, requestText: String?) {
        prefs.edit()
            .putString("c_$tabIndex", componentsJson)
            .putString("d_$tabIndex", dataModelJson)
            .putString("s_$tabIndex", surfaceId)
            .putString("n_$tabIndex", inference.joinToString("\n"))
            .putString("r_$tabIndex", requestText)
            .apply()
    }

    fun load(tabIndex: Int): Saved? {
        val components = prefs.getString("c_$tabIndex", null) ?: return null
        return Saved(
            componentsJson = components,
            dataModelJson = prefs.getString("d_$tabIndex", null),
            surfaceId = prefs.getString("s_$tabIndex", null) ?: "home-mgr",
            inference = prefs.getString("n_$tabIndex", "")?.lines().orEmpty(),
            tabIndex = tabIndex,
            requestText = prefs.getString("r_$tabIndex", null),
            active = prefs.getBoolean("a_$tabIndex", true),
        )
    }

    fun loadAll(): Map<Int, Saved> {
        val out = mutableMapOf<Int, Saved>()
        for (i in 0..2) load(i)?.let { out[i] = it }
        return out
    }

    fun clear(tabIndex: Int) {
        prefs.edit()
            .remove("c_$tabIndex").remove("d_$tabIndex").remove("s_$tabIndex")
            .remove("n_$tabIndex").remove("r_$tabIndex")
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().putInt("schema", SCHEMA).apply()
    }

    companion object {
        private const val SCHEMA = 2
    }
}

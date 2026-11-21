package dev.a2uicrm.agentic.engine

import androidx.a2ui.engine.platform.A2uiCoreComponentRegistry
import androidx.a2ui.engine.platform.A2uiCoreDataModel
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-backed [A2uiCoreComponentRegistry]: the surface actor pushes validated component
 * payloads here; the renderer observes [components] as Compose state (the engine docs
 * suggest exactly this pattern: "Host frameworks back this with native reactive states
 * (e.g., SnapshotStateMap in Compose)").
 */
class ComposeComponentRegistry : A2uiCoreComponentRegistry {

    var components by mutableStateOf<List<A2uiComponentPayload>>(emptyList())
        private set

    var errors by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    override fun update(components: List<A2uiComponentPayload>) {
        this.components = components
    }

    override fun reportError(id: String, exception: A2uiException) {
        errors = errors + (id to (exception.message ?: "unknown error"))
    }

    override fun close() {
        components = emptyList()
    }

    fun clearErrors() {
        errors = emptyList()
    }
}

/**
 * Simple map-backed [A2uiCoreDataModel] for the sandbox demo. The engine stores
 * updateDataModel values under the PATH the agent sent (e.g. "/stats" -> {..}), while
 * DataBindings reference deeper pointers (e.g. "/stats/overdueCount") — [get] resolves
 * both exact keys and segment navigation into stored nested maps.
 */
class ComposeDataModel : A2uiCoreDataModel {

    private val values = mutableMapOf<String, Any?>()

    override fun update(path: A2uiDataPath, value: Any?) {
        values[path.normalizedPath] = value
    }

    override fun get(path: A2uiDataPath): Any? {
        val norm = path.normalizedPath
        values[norm]?.let { return it }
        // Segment navigation: find the longest stored prefix and walk into nested maps.
        val segments = path.segments
        if (segments.isEmpty()) return values[""]
        for (i in segments.indices) {
            val prefix = "/" + segments.subList(0, i + 1).joinToString("/")
            val stored = values[prefix] ?: values[prefix.removePrefix("/")] ?: continue
            var current: Any? = stored
            for (j in i + 1 until segments.size) {
                current = (current as? Map<*, *>)?.get(segments[j]) ?: return null
            }
            if (current != null) return current
        }
        return null
    }

    fun get(path: String): Any? = get(A2uiDataPath(path))

    override fun close() {
        values.clear()
    }
}


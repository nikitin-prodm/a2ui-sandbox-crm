package dev.a2uicrm.agentic.action

import dev.a2uicrm.core.domain.CrmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dispatches renderer-side user actions to the CRM layer.
 *
 * The catalog actions whitelist (catalog.json "actions") is the contract: the agent
 * cannot invent actions, and the dispatcher refuses anything outside the whitelist
 * (defense in depth on top of CatalogValidator.ACTION_NOT_ALLOWED).
 *
 * NOTE (honest architecture note, analysis.md §7.3): the engine's A2uiActionInterceptor
 * is a *middleware inside platform renderers*; a2ui 1.0.0-alpha02 publishes no public
 * API for submitting A2uiUserAction from a custom renderer. Our dispatcher is therefore
 * an application-layer component — the natural seam for the sandbox, and where a
 * production build would additionally emit A2uiClientToServerMessage events.
 */
interface ActionDispatcher {
    /** Called by the renderer on user interaction. `action` is the component's action property. */
    fun dispatch(surfaceId: String, componentId: String, action: Any?)
}

/**
 * CRM implementation: maps whitelisted event names to [CrmRepository] methods.
 * Mutations trigger [onRepositoryChanged] so the host can refresh the agent composition.
 */
class CrmActionDispatcher(
    private val repository: CrmRepository,
    private val scope: CoroutineScope,
    private val allowedActions: Set<String>,
    private val onRepositoryChanged: () -> Unit = {},
    private val onInfo: (String) -> Unit = {},
    private val onOpenEntity: (entityType: String, entityId: String) -> Unit = { _, _ -> },
    private val onNavigate: (route: String) -> Unit = {},
    private val onActionCompleted: (name: String) -> Unit = {},
    private val onTaskCompleted: ((taskId: String, completed: Boolean) -> Unit)? = null,
    private val onCreateTaskRequest: ((context: Map<String, Any?>) -> Unit)? = null,
) : ActionDispatcher {

    override fun dispatch(surfaceId: String, componentId: String, action: Any?) {
        val event = (action as? Map<*, *>)?.get("event") as? Map<*, *> ?: return
        val name = event["name"] as? String ?: return
        val context = (event["context"] as? Map<*, *>) ?: emptyMap<String, Any?>()

        if (name !in allowedActions) {
            onInfo("Action '$name' is not in the whitelist — rejected")
            return
        }

        when (name) {
            "createTask" -> {
                val handler = onCreateTaskRequest
                if (handler != null) {
                    // App layer wants a fill-in form instead of silent defaults.
                    val ctx = context.entries.associate { it.key.toString() to it.value }
                    handler(ctx)
                } else {
                    scope.launch {
                        val title = context["title"] as? String
                            ?: DEFAULT_TASK_TITLE.also { onInfo("createTask без title → '$DEFAULT_TASK_TITLE'") }
                        val dueAt = parseIsoToEpoch(context["dueAt"] as? String)
                            ?: defaultTomorrow9am().also { onInfo("createTask without dueAt → tomorrow 09:00") }
                        repository.createTask(title = title, dueAtEpochMs = dueAt, contactId = context["contactId"] as? String)
                        onRepositoryChanged()
                        onActionCompleted("createTask")
                    }
                }
            }
            "completeTask" -> scope.launch {
                val taskId = context["taskId"] as? String
                if (taskId == null) {
                    onInfo("completeTask without taskId — skipped")
                    return@launch
                }
                // The renderer passes the checkbox state (default: complete).
                val completed = context["completed"] as? Boolean ?: true
                repository.updateTask(taskId = taskId, completed = completed)
                onRepositoryChanged()
                val handler = onTaskCompleted
                if (handler != null) handler(taskId, completed) else onActionCompleted("completeTask")
            }
            "openEntity" -> {
                // 5.4: task -> native Detail screen; other entity types — info (fake has tasks only).
                val type = context["entityType"] as? String ?: "entity"
                val id = context["entityId"] as? String ?: ""
                if (id.isBlank()) {
                    onInfo("openEntity without entityId — skipped")
                } else {
                    onOpenEntity(type, id)
                }
            }
            "navigate" -> onNavigate(context["route"] as? String ?: "home")
            else -> onInfo("Action '$name' is whitelisted but has no handler yet (v1)")
        }
    }

    companion object {
        private const val DEFAULT_TASK_TITLE = "New task"

        /** ISO-8601 "yyyy-MM-dd'T'HH:mm:ss'Z'" -> epoch millis (no java.time: minSdk 24, no desugaring). */
        fun parseIsoToEpoch(iso: String?): Long? {
            if (iso == null) return null
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(iso.removeSuffix("Z"))?.time
            } catch (e: Exception) {
                null
            }
        }

        /** Epoch millis -> ISO-8601 UTC (for compositions the agent builds from repository data). */
        fun epochToIso(epochMs: Long): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(epochMs)) + "Z"
        }

        fun defaultTomorrow9am(now: Long = System.currentTimeMillis()): Long =
            epochToCalendarTomorrow9am(now)
    }
}

private fun epochToCalendarTomorrow9am(now: Long): Long {
    val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = now
    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

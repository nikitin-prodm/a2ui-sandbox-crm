package dev.a2uicrm.agentic

import dev.a2uicrm.agentic.action.CrmActionDispatcher
import dev.a2uicrm.core.data.FakeCrmConnector
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whitelisted actions -> CRM repository (5.2): createTask / completeTask / updateTask
 * mutate FakeCrmConnector and trigger the refresh callback; non-whitelisted actions are
 * refused (defense in depth behind CatalogValidator.ACTION_NOT_ALLOWED).
 */
class ActionDispatcherTest {

    /** The 9 whitelisted actions (catalog.json "actions"). */
    private val whitelist = setOf(
        "navigate", "openEntity", "createTask", "completeTask", "updateOpportunityStage",
        "createNote", "triggerWorkflow", "refreshSurface", "filterStage",
    )

    private fun action(name: String, context: Map<String, Any?> = emptyMap()): Map<String, Any> =
        mapOf("event" to mapOf("name" to name, "context" to context))

    @Test
    fun `createTask - repository mutated and refresh fired`() = runBlocking {
        val repo = FakeCrmConnector()
        var refreshes = 0
        val dispatcher = CrmActionDispatcher(repo, this, whitelist, { refreshes++ })
        try {
            dispatcher.dispatch(
                "s", "cta1",
                action(
                    "createTask",
                    mapOf(
                        "title" to "Call the client",
                        // Relative date: a hardcoded calendar day becomes "today" when the clock rolls over.
                        "dueAt" to CrmActionDispatcher.epochToIso(CrmActionDispatcher.defaultTomorrow9am()),
                    ),
                ),
            )
            delay(400) // repository latency simulation
            // The new task is scheduled for tomorrow (inferred default) — check its bucket.
            val tasks = repo.getTomorrowTasks()
            assertTrue(tasks.any { it.title == "Call the client" })
            assertEquals(1, refreshes)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `completeTask - task marked completed via default event`() = runBlocking {
        val repo = FakeCrmConnector()
        val dispatcher = CrmActionDispatcher(repo, this, whitelist)
        try {
            // The renderer default: completeTask with taskId + completed from props.
            dispatcher.dispatch(
                "s", "ov0",
                action("completeTask", mapOf("taskId" to "tt11", "completed" to true)),
            )
            delay(400)
            // The repository confirms the mutation (updateTask returns the updated task).
            val updated = repo.updateTask("tt11", null)
            assertTrue("task should be completed", updated?.completed == true)
            // Completed tasks no longer appear in any active bucket.
            assertFalse(repo.getOverdueTasks().any { it.id == "tt11" })
            assertFalse(repo.getTodayTasks().any { it.id == "tt11" })
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `createTask without dueAt - default tomorrow 9am inferred`() = runBlocking {
        val repo = FakeCrmConnector()
        val notes = mutableListOf<String>()
        val dispatcher = CrmActionDispatcher(repo, this, whitelist, onInfo = { notes.add(it) })
        try {
            dispatcher.dispatch("s", "cta1", action("createTask", mapOf("title" to "X")))
            delay(400)
            assertTrue(notes.any { it.contains("09:00") })
            assertTrue(repo.getTomorrowTasks().any { it.title == "X" })
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `non-whitelisted action refused - no mutation`() = runBlocking {
        val repo = FakeCrmConnector()
        val notes = mutableListOf<String>()
        val dispatcher = CrmActionDispatcher(repo, this, whitelist, onInfo = { notes.add(it) })
        try {
            dispatcher.dispatch("s", "b1", action("sendEmail", mapOf("to" to "x@y.z")))
            delay(300)
            assertEquals(1, notes.size)
            assertTrue(notes[0].contains("whitelist"))
            assertEquals(2, repo.getOverdueTasks().size) // untouched
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `iso parsing roundtrip`() {
        val epoch = CrmActionDispatcher.parseIsoToEpoch("2026-11-14T09:00:00Z")
        assertTrue(epoch != null)
        assertEquals("2026-11-14T09:00:00Z", CrmActionDispatcher.epochToIso(epoch!!))
    }

    @Test
    fun `openEntity task - callback fired with type and id`() = runBlocking {
        val repo = FakeCrmConnector()
        val opened = mutableListOf<Pair<String, String>>()
        val dispatcher = CrmActionDispatcher(
            repo, this, whitelist,
            onOpenEntity = { type, id -> opened.add(type to id) },
        )
        try {
            dispatcher.dispatch(
                "s", "card1",
                action("openEntity", mapOf("entityType" to "task", "entityId" to "tt11")),
            )
            delay(50)
            assertEquals(listOf("task" to "tt11"), opened)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `openEntity without entityId - skipped with info`() = runBlocking {
        val repo = FakeCrmConnector()
        val notes = mutableListOf<String>()
        val opened = mutableListOf<Pair<String, String>>()
        val dispatcher = CrmActionDispatcher(
            repo, this, whitelist,
            onInfo = { notes.add(it) },
            onOpenEntity = { type, id -> opened.add(type to id) },
        )
        try {
            dispatcher.dispatch("s", "card1", action("openEntity", mapOf("entityType" to "task")))
            delay(50)
            assertTrue(opened.isEmpty())
            assertEquals(1, notes.size)
            assertTrue(notes[0].contains("entityId"))
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `navigate - callback fired with route`() = runBlocking {
        val repo = FakeCrmConnector()
        val routes = mutableListOf<String>()
        val dispatcher = CrmActionDispatcher(
            repo, this, whitelist,
            onNavigate = { route -> routes.add(route) },
        )
        try {
            dispatcher.dispatch("s", "cta1", action("navigate", mapOf("route" to "pipeline")))
            delay(50)
            assertEquals(listOf("pipeline"), routes)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `getTaskById - seeded task returned`() = runBlocking {
        val repo = FakeCrmConnector()
        val task = repo.getTaskById("tt11")
        assertTrue(task != null)
        assertEquals("Send proposal to Acme", task!!.title)
        assertEquals("John Doe", task.contactName)
    }
}

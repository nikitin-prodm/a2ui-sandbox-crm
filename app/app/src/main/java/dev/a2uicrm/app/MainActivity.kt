package dev.a2uicrm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.agentic.action.CrmActionDispatcher
import dev.a2uicrm.agentic.agent.AgentRequest
import dev.a2uicrm.agentic.agent.AgentResponse
import dev.a2uicrm.agentic.agent.AgentSession
import dev.a2uicrm.agentic.agent.CompositionBuilder
import dev.a2uicrm.agentic.agent.CompositionMerger
import dev.a2uicrm.agentic.agent.DemoAgent
import dev.a2uicrm.core.data.FakeCrmConnector
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.PipelineStage
import dev.a2uicrm.core.domain.Task
import dev.a2uicrm.core.domain.TeamStats
import dev.a2uicrm.core.domain.groupByCalendar
import dev.a2uicrm.agentic.json.JsonValue
import dev.a2uicrm.agentic.json.stringify
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val crashFile = java.io.File(filesDir, "crash.log")
        if (crashFile.exists()) {
            android.util.Log.e("A2UI-CRASH", crashFile.readText().take(8000))
            crashFile.renameTo(java.io.File(filesDir, "crash-prev.log"))
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash.log").writeText("thread=${t.name}\n" + e.stackTraceToString())
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
        super.onCreate(savedInstanceState)
        val catalogJson = assets.open("catalog.json").bufferedReader().use { it.readText() }
        setContent { MaterialTheme { AppShell(catalogJson) } }
    }
}

/** An agent-composed view of ONE tab (response + the request that produced it). */
private data class TabScreen(
    val response: AgentResponse,
    val request: AgentRequest?,
)

@Composable
fun AppShell(catalogJson: String) {
    val scope = rememberCoroutineScope()
    val repository = remember { FakeCrmConnector() }
    val host = remember { dev.a2uicrm.agentic.engine.AgenticSurfaceHost(catalogJson, scope) }
    val snackbarHostState = remember { SnackbarHostState() }
    val demo = remember(repository) { DemoAgent(repository) }
    val context0 = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { AgentPrefs(context0) }
    val savedScreen = remember { SavedScreenPrefs(context0) }
    // 5.4i: agent-editable section titles (pure data; see TabShellPrefs).
    val tabShell = remember { TabShellPrefs(context0) }
    var tabTitles by remember { mutableStateOf<Map<Tab, String>>(emptyMap()) }
    fun titleOf(tab: Tab): String = tabTitles[tab] ?: tab.title
    // 5.5: agent-editable tab ICONS (pure data; same TabShellPrefs store). The agent
    // renames icons via the validated shell line {"shell":{"tabIcons":{...}}} —
    // tokens come from the SAME icon vocabulary the A2UI renderer whitelists.
    var tabIcons by remember { mutableStateOf<Map<Tab, String>>(emptyMap()) }
    fun tabIconOf(tab: Tab): androidx.compose.ui.graphics.vector.ImageVector =
        tabIcons[tab]?.let { dev.a2uicrm.agentic.render.A2uiRendererRegistry.iconVector(it) }
            ?: when (tab) {
                Tab.TODAY -> Icons.Filled.Today
                Tab.TEAM -> Icons.Filled.Groups
                Tab.ANALYTICS -> Icons.Filled.Analytics
            }
    val repoPrefs = remember { context0.getSharedPreferences("a2ui_repo", android.content.Context.MODE_PRIVATE) }

    var allTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var doneTasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var teamStats by remember { mutableStateOf<List<TeamStats>>(emptyList()) }
    var pipelineStages by remember { mutableStateOf<List<PipelineStage>>(emptyList()) }
    var allDeals by remember { mutableStateOf<List<dev.a2uicrm.core.domain.Deal>>(emptyList()) }
    val memberDetailState = remember { mutableStateOf<TeamStats?>(null) }
    val dealDetailState = remember { mutableStateOf<dev.a2uicrm.core.domain.Deal?>(null) }

    val tabState = remember { mutableStateOf(Tab.TODAY) }
    val detailState = remember { mutableStateOf<Task?>(null) }
    val screens = remember { mutableStateMapOf<Tab, TabScreen>() }
    val a2uiActive = remember { mutableStateMapOf<Tab, Boolean>() }
    val chatOpen = remember { mutableStateOf(false) }
    val showSettings = remember { mutableStateOf(false) }
    val showCreateTask = remember { mutableStateOf(false) }
    val showCreateDeal = remember { mutableStateOf(false) }
    val createTaskContext = remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }

    fun reportError(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun persistRepo() {
        val fake = repository as? FakeCrmConnector ?: return
        try {
            val tasksJson = fake.allTasksRaw().joinToString(",", "[", "]") { t ->
                val due = t.dueAtEpochMs?.toString() ?: "null"
                val contact = t.contactName?.let { JsonValue.JsonString(it).stringify() } ?: "null"
                """{"id":${JsonValue.JsonString(t.id).stringify()},"title":${JsonValue.JsonString(t.title).stringify()},"completed":${t.completed},"dueAt":$due,"contactName":$contact}"""
            }
            // 5.4i: deals persist too — created contracts and the real funnel numbers
            // survive restarts (a2ui_repo).
            val dealsJson = fake.allDealsRaw().joinToString(",", "[", "]") { d ->
                val contact = d.contactName?.let { JsonValue.JsonString(it).stringify() } ?: "null"
                """{"id":${JsonValue.JsonString(d.id).stringify()},"title":${JsonValue.JsonString(d.title).stringify()},"amountMicros":${d.amountMicros},"stage":${JsonValue.JsonString(d.stage).stringify()},"ownerId":${JsonValue.JsonString(d.ownerId).stringify()},"contactName":$contact}"""
            }
            repoPrefs.edit().putString("tasks", tasksJson).putString("deals", dealsJson).apply()
        } catch (e: Exception) {
            reportError("Persist failed: ${e.message?.take(60)}")
        }
    }

    fun restoreRepo() {
        val fake = repository as? FakeCrmConnector ?: return
        val json = repoPrefs.getString("tasks", null) ?: return
        try {
            val arr = JsonValue.parse(json) as? JsonValue.JsonArray ?: return
            val tasks = arr.items.mapNotNull { item ->
                val o = item as? JsonValue.JsonObject ?: return@mapNotNull null
                Task(
                    id = o.string("id") ?: return@mapNotNull null,
                    title = o.string("title") ?: return@mapNotNull null,
                    completed = o.bool("completed") ?: false,
                    dueAtEpochMs = (o.entries["dueAt"] as? JsonValue.JsonNumber)?.let { it.value.toLong() },
                    contactName = o.string("contactName"),
                )
            }
            fake.replaceTasks(tasks)
            // 5.4i: restore deals as well (persisted with the tasks in a2ui_repo).
            val dealsJson = repoPrefs.getString("deals", null)
            if (dealsJson != null) {
                val dArr = JsonValue.parse(dealsJson) as? JsonValue.JsonArray
                val deals = dArr?.items?.mapNotNull { item ->
                    val o = item as? JsonValue.JsonObject ?: return@mapNotNull null
                    dev.a2uicrm.core.domain.Deal(
                        id = o.string("id") ?: return@mapNotNull null,
                        title = o.string("title") ?: return@mapNotNull null,
                        amountMicros = (o.entries["amountMicros"] as? JsonValue.JsonNumber)?.value?.toLong() ?: 0,
                        stage = o.string("stage") ?: "Lead",
                        ownerId = o.string("ownerId") ?: "wm-1",
                        contactName = o.string("contactName"),
                    )
                }
                deals?.let { fake.replaceDeals(it) }
            }
        } catch (e: Exception) {
            reportError("Restore failed: ${e.message?.take(60)}")
        }
    }

    fun refreshData() {
        scope.launch {
            try {
                allTasks = repository.getAllActiveTasks()
                doneTasks = repository.getCompletedTasks()
                teamStats = repository.getTeamStats()
                pipelineStages = repository.getPipelineStages()
                allDeals = repository.getDeals()
                persistRepo()
            } catch (e: Exception) {
                reportError("Data error: ${e.message?.take(80)}")
            }
        }
    }

    val taskGroups = remember(allTasks) { groupByCalendar(allTasks) }

    /**
     * The My day TWIN: calendar sections exactly like the native view (ACTIVE tasks only —
     * completed tasks are NOT duplicated into day buckets; they appear only when the user
     * explicitly asks for the Completed section, 5.4h fix of the duplication bug).
     */
    val twinDaySections = remember(allTasks) {
        groupByCalendar(allTasks).asSections
    }

    fun applyTabScreen(tab: Tab, screen: TabScreen, save: Boolean = false) {
        scope.launch {
            try {
                val result = AgentSession(host, demo).applyResponse(screen.response)
                if (result.accepted) {
                    if (save) {
                        savedScreen.save(
                            tabIndex = tab.ordinal,
                            componentsJson = screen.response.componentsJson,
                            dataModelJson = screen.response.updateDataModelJson,
                            surfaceId = screen.response.surfaceId,
                            inference = screen.response.inference,
                            requestText = screen.request?.text,
                        )
                    }
                } else {
                    screens.remove(tab)
                    savedScreen.clear(tab.ordinal)
                    reportError("Tab screen could not be restored — native view shown")
                }
            } catch (e: Exception) {
                reportError("Screen error: ${e.message?.take(80)}")
            }
        }
    }

    fun rebuildAfterAction(name: String) {
        val tab = tabState.value
        if (screens[tab] == null) return
        val req = screens[tab]?.request ?: AgentRequest(prefs.role, "")
        scope.launch {
            try {
                val response = demo.composeImmediately(req)
                if (AgentSession(host, demo).applyResponse(response).accepted) {
                    screens[tab] = TabScreen(response, req)
                    savedScreen.save(
                        tabIndex = tab.ordinal,
                        componentsJson = response.componentsJson,
                        dataModelJson = response.updateDataModelJson,
                        surfaceId = response.surfaceId,
                        inference = response.inference,
                        requestText = req.text,
                    )
                }
            } catch (e: Exception) {
                reportError("Rebuild failed: ${e.message?.take(80)}")
            }
        }
    }

    fun patchCompleted(taskId: String, completed: Boolean) {
        val tab = tabState.value
        val screen = screens[tab] ?: return
        // The task's calendar bucket — where an UN-completed task belongs on the twin.
        // The toggle never changes dueAt, so the (possibly stale, pre-refresh) entry is
        // good enough. IMPORTANT: allTasks holds ACTIVE tasks only — a task that is
        // completed right now (the un-check case) lives in doneTasks instead, so both
        // lists are searched; copy(completed = false) passes the group filter.
        val task = (allTasks + doneTasks).firstOrNull { it.id == taskId }
        val homeSection = task?.copy(completed = false)
            ?.let { groupByCalendar(listOf(it)).asSections.firstOrNull()?.first }
        // 18.11: the merger MOVES the node (into «Completed» / back into its home
        // section — creating it in calendar order when missing), updates the counts,
        // swaps the Completed placeholder, and fills a missing deadline/contact from
        // [task] so the meta line under the title survives the toggle.
        val patched = CompositionMerger.patchCompleted(screen.response.componentsJson, taskId, completed, homeSection, task)
        if (patched == null) {
            rebuildAfterAction("completeTask")
            return
        }
        val newScreen = TabScreen(screen.response.copy(componentsJson = patched), screen.request)
        screens[tab] = newScreen
        applyTabScreen(tab, newScreen, save = true)
    }

    fun appendTaskToScreen(task: Task) {
        val tab = tabState.value
        val screen = screens[tab] ?: return
        val groups = groupByCalendar(listOf(task))
        val title = groups.asSections.firstOrNull()?.first ?: "Tasks"
        val stamp = "nw${System.currentTimeMillis() % 100000}"
        val merged = CompositionMerger.insertActionItem(screen.response.componentsJson, title, task, stamp)
            ?: CompositionMerger.appendComponents(
                screen.response.componentsJson,
                CompositionMerger.sectionComponents(title, listOf(task), stamp),
            )
        if (merged != null) {
            val newScreen = TabScreen(screen.response.copy(componentsJson = merged), screen.request)
            screens[tab] = newScreen
            applyTabScreen(tab, newScreen, save = true)
        } else {
            rebuildAfterAction("createTask")
        }
    }

    /** New deal: append its EntityCard to the current A2UI screen (analytics context). */
    fun appendDealToScreen(deal: Deal) {
        val tab = tabState.value
        val screen = screens[tab] ?: return
        val stamp = "nw${System.currentTimeMillis() % 100000}"
        val comps = listOf(
            JsonValue.JsonObject(
                linkedMapOf(
                    "id" to JsonValue.JsonString(stamp),
                    "component" to JsonValue.JsonString("EntityCard"),
                    "entityType" to JsonValue.JsonString("opportunity"),
                    "entityId" to JsonValue.JsonString(deal.id),
                    "title" to JsonValue.JsonString(deal.title),
                    "subtitle" to JsonValue.JsonString("Stage: ${deal.stage}"),
                    "amount" to JsonValue.JsonString("\$${deal.amountMicros / 1_000_000}"),
                    "badge" to JsonValue.JsonString(deal.stage),
                ),
            ),
        )
        val merged = CompositionMerger.appendComponents(screen.response.componentsJson, comps)
        if (merged != null) {
            val newScreen = TabScreen(screen.response.copy(componentsJson = merged), screen.request)
            screens[tab] = newScreen
            applyTabScreen(tab, newScreen, save = true)
        }
    }

    // ---------- 5.4g undo (per-tab stack, last 10 agent changes) ----------

    val undoStacks = mutableMapOf<Tab, MutableList<TabScreen>>()

    fun pushUndo(tab: Tab) {
        val cur = screens[tab] ?: return
        val stack = undoStacks.getOrPut(tab) { mutableListOf() }
        if (stack.lastOrNull() !== cur) stack.add(cur)
        while (stack.size > 10) stack.removeAt(0)
    }

    fun undoLast() {
        val tab = tabState.value
        val stack = undoStacks[tab]
        val prev = stack?.removeLastOrNull()
        if (stack == null || prev == null) {
            reportError("Nothing to undo on this section yet")
            return
        }
        screens[tab] = prev
        applyTabScreen(tab, prev)
    }

    fun setTabScreen(response: AgentResponse, request: AgentRequest?) {
        val tab = tabState.value
        // 5.4g undo: remember what the tab looked like BEFORE this agent change.
        pushUndo(tab)
        screens[tab] = TabScreen(response, request)
        a2uiActive[tab] = true
        savedScreen.save(
            tabIndex = tab.ordinal,
            componentsJson = response.componentsJson,
            dataModelJson = response.updateDataModelJson,
            surfaceId = response.surfaceId,
            inference = response.inference,
            requestText = request?.text,
        )
        // Apply to the engine registry as well: a cross-domain switch (LaunchedEffect
        // re-applying the OLD tab screen) must not win the race over the fresh merge.
        applyTabScreen(tab, screens[tab] ?: return)
    }


    /**
     * The augment base: the tab's A2UI screen OR the NATIVE TWIN — the user looks at
     * the current interface and asks to EXTEND it («add deals under each employee» on
     * the native Team tab augments a composition mirroring what they see).
     */
    fun currentScreenJson(): Pair<String, String>? {
        val tab = tabState.value
        screens[tab]?.let { return it.response.componentsJson to it.response.surfaceId }
        val surfaceId = if (prefs.role == "head") "home-head" else "home-mgr"
        val twin = try {
            when (tab) {
                // 5.4g: the My day twin mirrors the NATIVE view — calendar sections ONLY
                // (with done tasks in their buckets). NO banner/metrics/CTA: the user
                // never asked for them; "managerHome" here was the "random squares on
                // top" root cause.
                Tab.TODAY -> CompositionBuilder.tasksScreen(surfaceId, twinDaySections)
                Tab.TEAM -> CompositionBuilder.teamScreen(surfaceId, teamStats)
                // 5.5: the analytics twin is a VISUAL CLONE of the native AnalyticsScreen
                // (caption total + vertical per-stage rows with icons + New deal CTA).
                // Before: MetricCard + horizontal PipelineStrip — every LLM edit of that
                // twin surfaced elements the native screen did not have (the "extra
                // blocks on top" bug). Now an augment changes ONLY what was asked.
                Tab.ANALYTICS -> CompositionBuilder.analyticsTwin(surfaceId, pipelineStages)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return twin.componentsJson to surfaceId
    }

    // Restore the repo state, every tab's persisted screen, and the active view flag.
    LaunchedEffect(Unit) {
        restoreRepo()
        tabTitles = tabShell.loadAll()
        tabIcons = tabShell.loadAllIcons()
        refreshData()
        savedScreen.loadAll().forEach { (tabIndex, saved) ->
            val tab = Tab.entries.getOrNull(tabIndex) ?: return@forEach
            screens[tab] = TabScreen(
                AgentResponse(
                    surfaceId = saved.surfaceId,
                    updateDataModelJson = saved.dataModelJson,
                    componentsJson = saved.componentsJson,
                    inference = saved.inference,
                ),
                saved.requestText?.let { AgentRequest(prefs.role, it) },
            )
            a2uiActive[tab] = saved.active
        }
        screens[tabState.value]?.let { applyTabScreen(tabState.value, it) }
    }

    // Switching tabs re-applies THAT tab's composition — each section keeps its own view.
    LaunchedEffect(tabState.value) {
        screens[tabState.value]?.let { applyTabScreen(tabState.value, it) }
    }

    val dispatcher = remember(host) {
        CrmActionDispatcher(
            repository = repository,
            scope = scope,
            allowedActions = host.catalogDefinition.actions.keys,
            onRepositoryChanged = { refreshData() },
            onInfo = { msg -> reportError(msg) },
            onOpenEntity = { type, id ->
                if (type == "task") {
                    scope.launch {
                        try {
                            val t = repository.getTaskById(id)
                            if (t == null) {
                                reportError("Task '$id' not found — draft not saved yet")
                            } else {
                                detailState.value = t
                            }
                        } catch (e: Exception) {
                            reportError("Error: ${e.message?.take(80)}")
                        }
                    }
                } else if (type == "contact" || type == "workspaceMember") {
                    // 5.4g: team member detail (was a dead "coming in v2" stub) —
                    // stats + the deals they own, from the repository.
                    val member = teamStats.firstOrNull { it.memberId == id }
                    if (member == null) {
                        reportError("Member '$id' not found in the team")
                    } else {
                        memberDetailState.value = member
                    }
                } else if (type == "opportunity") {
                    val deal = allDeals.firstOrNull { it.id == id }
                    if (deal == null) {
                        reportError("Deal '$id' not found — draft not saved yet")
                    } else {
                        dealDetailState.value = deal
                    }
                } else {
                    reportError("Details for $type/$id — coming in v2 (fake has tasks and deals)")
                }
            },
            onNavigate = { route ->
                when (route) {
                    "pipeline" -> { tabState.value = Tab.ANALYTICS; detailState.value = null }
                    "settings" -> showSettings.value = true
                    else -> { tabState.value = Tab.TODAY; detailState.value = null }
                }
            },
            onActionCompleted = { name -> rebuildAfterAction(name) },
            onTaskCompleted = { taskId, completed -> patchCompleted(taskId, completed) },
            onCreateTaskRequest = { ctx ->
                // Analytics context: the CTA means a DEAL, not a task.
                if (tabState.value == Tab.ANALYTICS) {
                    showCreateDeal.value = true
                } else {
                    createTaskContext.value = ctx
                    showCreateTask.value = true
                }
            },
        )
    }

    BackHandler(enabled = !chatOpen.value && !showCreateTask.value && !showCreateDeal.value && (showSettings.value || detailState.value != null || memberDetailState.value != null || dealDetailState.value != null || a2uiActive[tabState.value] == true)) {
        when {
            showSettings.value -> showSettings.value = false
            detailState.value != null -> detailState.value = null
            memberDetailState.value != null -> memberDetailState.value = null
            dealDetailState.value != null -> dealDetailState.value = null
            a2uiActive[tabState.value] == true -> a2uiActive[tabState.value] = false
        }
    }

    if (showSettings.value) {
        SettingsScreen(
            prefs = prefs,
            onClose = { showSettings.value = false },
            onResetDemoData = {
                // Wipe the user's test junk ("123" tasks etc.): tasks/deals back to the
                // canned fixtures, all agent screens dropped. Settings are kept.
                scope.launch {
                    try {
                        repoPrefs.edit().remove("tasks").remove("deals").apply()
                        (repository as? FakeCrmConnector)?.resetToFixtures()
                        savedScreen.clearAll()
                        tabShell.clearAll()
                        tabTitles = emptyMap()
                        tabIcons = emptyMap()
                        screens.clear()
                        a2uiActive.clear()
                        refreshData()
                        showSettings.value = false
                        reportError("Demo data reset — fixtures restored, agent screens dropped")
                    } catch (e: Exception) {
                        reportError("Reset failed: ${e.message?.take(80)}")
                    }
                }
            },
        )
        return
    }

    val currentDetail = detailState.value
    if (currentDetail != null) {
        TaskDetailScreen(
            task = currentDetail,
            onToggle = { task, checked ->
                scope.launch {
                    try {
                        repository.updateTask(task.id, checked)
                        refreshData()
                        detailState.value = task.copy(completed = checked)
                        // Keep the A2UI screen in sync too (patch, no rebuild).
                        if (screens[tabState.value] != null) patchCompleted(task.id, checked)
                    } catch (e: Exception) {
                        reportError("Error: ${e.message?.take(80)}")
                    }
                }
            },
            onBack = { detailState.value = null },
        )
        return
    }

    var swipeAccum by remember { mutableStateOf(0f) }
    val hasScreen = screens[tabState.value] != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tabState.value == t,
                        onClick = { tabState.value = t; detailState.value = null },
                        icon = {
                            // 5.5: agent-editable tab ICONS — pure data in TabShellPrefs,
                            // resolved from the SAME icon vocabulary the A2UI renderer
                            // uses ({"shell":{"tabIcons":{"today":"star",...}}}).
                            Icon(
                                imageVector = tabIconOf(t),
                                contentDescription = t.title,
                            )
                        },
                        label = { Text(titleOf(t), fontSize = 11.sp) },
                    )
                }
                NavigationBarItem(
                    selected = false,
                    onClick = { showSettings.value = true },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("More", fontSize = 11.sp) },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { chatOpen.value = true },
                icon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                text = { Text("A2UI", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
                .pointerInput(hasScreen) {
                    if (!hasScreen) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val tab = tabState.value
                            if (screens[tab] != null && abs(swipeAccum) > 80) {
                                a2uiActive[tab] = swipeAccum < 0
                            }
                            swipeAccum = 0f
                        },
                    ) { _, dragAmount -> swipeAccum += dragAmount }
                },
        ) {
            val screen = screens[tabState.value]
            if (screen != null && a2uiActive[tabState.value] == true) {
                A2UIScreen(
                    host = host,
                    dispatcher = dispatcher,
                    // "Back" keeps the screen — swipe right (or the pill) returns to it.
                    onExit = { a2uiActive[tabState.value] = false },
                    preferredSurfaceId = screen.response.surfaceId,
                    // The tab's own header stays on top — the agent view reads as the
                    // CURRENT section extended, not as a brand-new screen.
                    title = titleOf(tabState.value),
                    // Full reset: the VIEW of this tab only. Tasks/deals/contracts live in
                    // the repository (+ its persistence) and are NOT deleted; the agent can
                    // compose a new screen at any time ("show overdue tasks" etc.).
                    onDelete = {
                        pushUndo(tabState.value)
                        screens.remove(tabState.value)
                        savedScreen.clear(tabState.value.ordinal)
                        a2uiActive[tabState.value] = false
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (screen != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clickable { a2uiActive[tabState.value] = true },
                        ) {
                            Text(
                                text = "A2UI view of this section — tap or swipe left to open",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (tabState.value) {
                            Tab.TODAY -> TodayScreen(
                                groups = taskGroups,
                                title = titleOf(Tab.TODAY),
                                onToggle = { task, checked ->
                                    scope.launch {
                                        try {
                                            repository.updateTask(task.id, checked)
                                            refreshData()
                                        } catch (e: Exception) {
                                            reportError("Error: ${e.message?.take(80)}")
                                        }
                                    }
                                },
                                onOpen = { task -> detailState.value = task },
                                onCreate = {
                                    createTaskContext.value = emptyMap()
                                    showCreateTask.value = true
                                },
                            )
                            Tab.TEAM -> TeamScreen(teamStats, title = titleOf(Tab.TEAM))
                            Tab.ANALYTICS -> AnalyticsScreen(pipelineStages, onCreateDeal = { showCreateDeal.value = true }, title = titleOf(Tab.ANALYTICS))
                        }
                    }
                }
            }
        }
    }

    if (chatOpen.value) {
        AgentChatSheet(
            host = host,
            repository = repository,
            prefs = prefs,
            currentScreen = { currentScreenJson() },
            onDismiss = { chatOpen.value = false },
            onApplied = { response, request -> setTabScreen(response, request) },
            onDelete = {
                pushUndo(tabState.value)
                screens.remove(tabState.value)
                savedScreen.clear(tabState.value.ordinal)
                a2uiActive[tabState.value] = false
            },
            onUndo = { undoLast() },
            onShellTitles = { renames ->
                // 5.4i: validated agent renames — persist as data and recompose.
                val updated = tabTitles.toMutableMap()
                renames.forEach { (key, name) ->
                    val tab = when (key) {
                        "today" -> Tab.TODAY
                        "team" -> Tab.TEAM
                        "analytics" -> Tab.ANALYTICS
                        else -> return@forEach
                    }
                    if (tabShell.setTitle(tab, name)) updated[tab] = name
                }
                tabTitles = updated
            },
            // 5.5: validated agent ICON changes — pure data, persisted in TabShellPrefs,
            // resolved from the renderer's icon vocabulary (bug: icons were hardcoded).
            onShellIcons = { icons ->
                val updated = tabIcons.toMutableMap()
                icons.forEach { (key, token) ->
                    val tab = when (key) {
                        "today" -> Tab.TODAY
                        "team" -> Tab.TEAM
                        "analytics" -> Tab.ANALYTICS
                        else -> return@forEach
                    }
                    if (tabShell.setIcon(tab, token)) updated[tab] = token
                }
                tabIcons = updated
            },
        )
    }

    if (showCreateTask.value) {
        val ctx = createTaskContext.value
        CreateTaskSheet(
            initialTitle = ctx["title"] as? String ?: "",
            initialContact = ctx["contactName"] as? String ?: "",
            onDismiss = { showCreateTask.value = false },
            onSave = { title, dueAtEpochMs, contactName ->
                scope.launch {
                    try {
                        val task = repository.createTask(title = title, dueAtEpochMs = dueAtEpochMs, contactName = contactName)
                        refreshData()
                        showCreateTask.value = false
                        appendTaskToScreen(task)
                    } catch (e: Exception) {
                        reportError("Could not create: ${e.message?.take(80)}")
                    }
                }
            },
        )
    }

    if (showCreateDeal.value) {
        CreateDealSheet(
            members = teamStats.map { it.memberId to it.memberName },
            onDismiss = { showCreateDeal.value = false },
            onSave = { title, amountMicros, stage, ownerId, contactName ->
                scope.launch {
                    try {
                        val deal = repository.createDeal(title, amountMicros, stage, ownerId, contactName)
                        refreshData()
                        showCreateDeal.value = false
                        appendDealToScreen(deal)
                    } catch (e: Exception) {
                        reportError("Could not create the deal: ${e.message?.take(80)}")
                    }
                }
            },
        )
    }

    memberDetailState.value?.let { member ->
        MemberDetailSheet(
            stats = member,
            deals = allDeals.filter { it.ownerId == member.memberId },
            onDismiss = { memberDetailState.value = null },
        )
    }

    dealDetailState.value?.let { deal ->
        DealDetailSheet(deal = deal, onDismiss = { dealDetailState.value = null })
    }
}

internal enum class Tab(val title: String) { TODAY("My day"), TEAM("Team"), ANALYTICS("Analytics") }

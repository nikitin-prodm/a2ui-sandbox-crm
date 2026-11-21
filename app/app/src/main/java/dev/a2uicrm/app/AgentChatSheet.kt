package dev.a2uicrm.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.agentic.action.CrmActionDispatcher
import dev.a2uicrm.agentic.agent.AgentRequest
import dev.a2uicrm.agentic.agent.AgentSession
import dev.a2uicrm.agentic.agent.DemoAgent
import dev.a2uicrm.agentic.agent.LlmAgentGateway
import dev.a2uicrm.agentic.engine.AgenticSurfaceHost
import dev.a2uicrm.core.domain.CrmRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The A2UI agent chat sheet (5.4): opened from the FAB, ~1/4 of the screen, keeps a
 * message history, accepts voice or text, and applies requests through AgentSession
 * (LLM via the user's settings; the DemoAgent simulator as fallback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentChatSheet(
    host: AgenticSurfaceHost,
    repository: CrmRepository,
    prefs: AgentPrefs,
    currentScreen: () -> Pair<String, String>?,
    onDismiss: () -> Unit,
    onApplied: (dev.a2uicrm.agentic.agent.AgentResponse, AgentRequest) -> Unit,
    /** "delete screen" chat command: drops the tab's agent VIEW (data is untouched). */
    onDelete: () -> Unit = {},
    /** "undo" chat command: reverts the last agent change on this tab. */
    onUndo: () -> Unit = {},
    /**
     * 5.4i: the agent renamed section titles via the shell line (validated here,
     * persisted by the caller — pure data, no code).
     */
    onShellTitles: (Map<String, String>) -> Unit = {},
    /**
     * 5.5: the agent changed tab ICONS via the shell line (validated here against the
     * renderer icon vocabulary, persisted by the caller — pure data, no code).
     */
    onShellIcons: (Map<String, String>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatEntry>() }
    val listState = rememberLazyListState()

    // 5.4h: new replies must be VISIBLE — the fixed-height list used to swallow them
    // below the fold, which looked like "the agent never answered".
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
    }

    if (messages.isEmpty()) {
        messages.add(
            ChatEntry(
                role = "agent",
                text = if (prefs.useLlm) {
                    "I am the A2UI agent (${prefs.model}). Describe the screen you want — I will compose it from catalog components."
                } else {
                    "I am the A2UI demo-simulator (enable the LLM in Settings). Tell me what to show."
                },
            )
        )
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) query = text
        }
    }

    fun apply() {
        if (busy || query.isBlank()) return
        val text = query.trim()
        messages.add(ChatEntry("user", text))
        query = ""
        // Capabilities / "what can you do" — answer from the catalog locally, without
        // the LLM (a free-form LLM answer used to hang/crash on a non-JSON reply).
        capabilitiesReply(text, host.catalogDefinition)?.let { caps ->
            messages.add(ChatEntry("agent", caps))
            return
        }
        // Chat command: full reset of this tab's agent screen. Deletes the VIEW only —
        // tasks, deals and contracts created via the screen stay in the repository.
        val lower = text.lowercase()
        if (listOf("delete screen", "delete the screen", "remove screen", "reset screen",
                "удали экран", "удалить экран", "удали этот экран", "сбрось экран", "удали а2ui", "удали a2ui").any { it in lower }) {
            onDelete()
            messages.add(
                ChatEntry(
                    "agent",
                    "Screen deleted — back to the standard view. Your tasks and deals are untouched; ask me anytime to compose a new one.",
                ),
            )
            scope.launch {
                delay(700)
                onDismiss()
            }
            return
        }
        // Chat command (5.4g): undo the last agent change on this tab.
        if (listOf("undo", "отмени", "верни как было", "убери последнее", "верни экран").any { it in lower }) {
            onUndo()
            messages.add(ChatEntry("agent", "Reverted the last agent change on this section."))
            scope.launch {
                delay(700)
                onDismiss()
            }
            return
        }
        busy = true
        scope.launch {
            val t = text.lowercase()
            val merger = dev.a2uicrm.agentic.agent.CompositionMerger

            // The LLM edit base: the tab's A2UI screen OR the native twin — the user looks
            // at the CURRENT interface, so the agent EDITS it (5.4h LLM-first).
            val current = currentScreen()

            // ---------- 5.4h LOCAL commands (the ONLY non-LLM paths) ----------
            // Instant, lossless engine ops: remove/hide a block, move, sort.
            // Everything else goes to the LLM which edits the current tree.
            val editCmdRegex = Regex("перемести|подними|опусти|move |отсортируй|ранжируй|sort |выше|ниже|вверх|вниз|в начало|в конец|to top|to bottom|\\bup\\b|\\bdown\\b")
            val removeCmd = current != null &&
                Regex("удали|удалить|скрой|скрыть|remove |hide |delete ").containsMatchIn(t) &&
                Regex("блок|block|секци|section|подсекц").containsMatchIn(t)
            if (current != null && (editCmdRegex.containsMatchIn(t) || removeCmd)) {
                val edit = if (removeCmd) {
                    parseRemoveBlock(t, current.first, merger)
                } else {
                    parseEditCommand(t, current.first, merger)
                }
                if (edit != null) {
                    val response = dev.a2uicrm.agentic.agent.AgentResponse(
                        surfaceId = current.second,
                        updateDataModelJson = null,
                        componentsJson = edit.first,
                        inference = listOf(edit.second),
                    )
                    if (AgentSession(host, DemoAgent(repository)).applyResponse(response).accepted) {
                        busy = false
                        messages.add(ChatEntry("agent", edit.second))
                        onApplied(response, AgentRequest(prefs.role, text))
                        delay(700)
                        onDismiss()
                        return@launch
                    }
                }
                // The command did not map onto this screen → the LLM handles the request.
            }

            // Deterministic data-edit fallback for the case when the LLM is unavailable or
            // its JSON fails validation twice (shown as a fallback, never silent).
            suspend fun deterministicAugment(): Pair<dev.a2uicrm.agentic.agent.AgentResponse, String>? {
                val cur = current ?: return null
                val merged = try {
                    augmentMerge(text, repository, cur)
                } catch (e: Exception) {
                    null
                } ?: return null
                val session = AgentSession(host, DemoAgent(repository))
                return if (session.applyResponse(merged.first).accepted) merged else null
            }

            // ---------- 5.5 SIMULATOR: deterministic augment FIRST (add semantics) ----------
            // With the LLM off, an «добавь X» request used to REPLACE the screen (the
            // simulator's focus intents are show-semantics). The add/show contract is
            // enforced deterministically here: append to the current tree, exactly what
            // the LLM is instructed to do. Non-additive requests fall through to the
            // simulator's focus screens (show semantics).
            if (!prefs.useLlm && current != null) {
                val outcome = try {
                    deterministicAugment()
                } catch (e: Exception) {
                    null
                }
                if (outcome != null) {
                    busy = false
                    messages.add(ChatEntry("agent", outcome.second))
                    onApplied(outcome.first, AgentRequest(prefs.role, text))
                    delay(700)
                    onDismiss()
                    return@launch
                }
                // Not an additive request → the simulator composes (show semantics).
            }

            // ---------- 5.4h LLM-first: EVERYTHING else goes to the model ----------
            // The model receives the CURRENT screen tree + repo data + the request and
            // edits the tree (the EDIT CONTRACT in the system prompt). No keyword routing,
            // no scenario table — the sandbox is the agent, not a script.
            val snapshot = try {
                buildRepoSnapshot(repository)
            } catch (e: Exception) {
                null
            }
            val request = AgentRequest(prefs.role, text, snapshot, current?.first)
            val gateway = if (prefs.useLlm) {
                LlmAgentGateway(
                    host.catalogDefinition,
                    LlmAgentGateway.AgentSettings(
                        baseUrl = prefs.baseUrl,
                        apiKey = prefs.apiKey,
                        model = prefs.model,
                    ),
                )
            } else {
                DemoAgent(repository)
            }
            val result = try {
                // 5.4h: hard overall timeout — a hanging/slow LLM stream must not spin
                // forever; the user gets a clear message and the screen stays unchanged.
                kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                    AgentSession(host, gateway).run(request)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Honest failure (5.4h): the current screen is NOT replaced by a stub —
                // the error is shown, the user retries or fixes Settings.
                busy = false
                messages.add(
                    ChatEntry(
                        "agent",
                        "Agent error: ${e.message?.take(200)} — the screen is unchanged. Retry or check Settings.",
                    ),
                )
                null
            }
            if (result == null) {
                busy = false
                if (messages.lastOrNull()?.role != "agent") {
                    messages.add(
                        ChatEntry(
                            "agent",
                            "The agent timed out (90 s) — the screen is unchanged. The model may be busy; try again.",
                        ),
                    )
                }
                return@launch
            }
            busy = false
            if (result.accepted) {
                messages.add(
                    ChatEntry(
                        "agent",
                        "Done (${result.attempts} attempt(s)). Changes: " + result.inference.joinToString("; ").take(400),
                    ),
                )
                var response = result.response ?: return@launch
                // 5.5 ADD-REQUEST STRUCTURAL GUARD: an «add X» must NOT restructure the
                // screen. If the model rebuilt it (old ids gone / order broken), fall
                // back to the deterministic augment; when that is impossible too, keep
                // the screen UNCHANGED (honest refusal beats silently losing content).
                if (current != null && isAdditiveRequest(t)) {
                    val structural = try {
                        merger.validateStructure(current.first, response.componentsJson)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (structural.isNotEmpty()) {
                        val outcome = deterministicAugment()
                        if (outcome != null) {
                            response = outcome.first
                            messages.add(
                                ChatEntry(
                                    "agent",
                                    "The model restructured the screen instead of adding to it — applied a deterministic edit instead: " + outcome.second,
                                ),
                            )
                        } else {
                            messages.add(
                                ChatEntry(
                                    "agent",
                                    "Refused: the request looked additive, but the model rebuilt the whole screen " +
                                        "(${structural.firstOrNull()?.take(120)}). Your screen is unchanged — rephrase or explicitly ask to replace it.",
                                ),
                            )
                            delay(900)
                            onDismiss()
                            return@launch
                        }
                    }
                }
                // 5.4h DIFF (transparency instead of a hard "do not touch" guard): the user
                // SEES what the model changed and can "undo" with one word.
                var diff: List<String> = emptyList()
                if (current != null) {
                    diff = try {
                        merger.diffSummary(current.first, response.componentsJson)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (diff.isNotEmpty()) {
                        messages.add(ChatEntry("agent", "Diff: " + diff.joinToString("; ").take(240)))
                    }
                }
                // 5.4i ANTI-PHANTOM: the model CLAIMED a change but nothing structurally
                // changed (an unsupported attribute — e.g. a knob no renderer reads).
                // Say it out loud instead of pretending the edit happened.
                val claimed = result.inference.filter { it.isNotBlank() && !it.startsWith("Screen edited by LLM") && !it.startsWith("repaired") }
                if (current != null && diff.isEmpty() && claimed.isNotEmpty()) {
                    messages.add(
                        ChatEntry(
                            "agent",
                            "Heads-up: the model reported a change, but NO supported property actually changed — " +
                                "the requested attribute is outside the catalog knobs, so nothing was applied. Ask differently or check the component list.",
                        ),
                    )
                }
                // 5.4g sanitizer: the ENGINE enforces the DATA RULE — components that
                // reference ids absent from the repository (invented "opp-acme",
                // "Manager One") are dropped deterministically, with a visible note.
                val sanitized = try {
                    val deals = repository.getDeals()
                    merger.sanitizeEntityRefs(
                        response.componentsJson,
                        validTaskIds = (repository.getAllActiveTasks() + repository.getCompletedTasks()).map { it.id }.toSet(),
                        validDealIds = deals.map { it.id }.toSet(),
                        validMemberIds = repository.getTeamStats().map { it.memberId }.toSet(),
                        dealIdByTitle = deals.associate { it.title to it.id },
                    )
                } catch (e: Exception) {
                    null
                }
                if (sanitized != null) {
                    val (cleanJson, notes) = sanitized
                    response = response.copy(componentsJson = cleanJson, inference = response.inference + notes)
                    AgentSession(host, DemoAgent(repository)).applyResponse(response)
                    messages.add(ChatEntry("agent", "Sanitized: ${notes.joinToString("; ").take(160)}"))
                }
                // 5.4i SHELL: the agent may rename section titles — validate and hand over
                // (the app layer persists; the caller recomposes nav labels + headers).
                response.shellJson?.let { shell ->
                    val applied = parseShellTitles(shell)
                    if (applied.isNotEmpty()) {
                        onShellTitles(applied)
                        messages.add(ChatEntry("agent", "Renamed sections: " + applied.entries.joinToString { "${it.key} → \"${it.value}\"" }))
                    } else {
                        messages.add(ChatEntry("agent", "Rejected a shell rename (invalid key or name)."))
                    }
                    // 5.5: tab icon changes from the same shell line — tokens validated
                    // against the renderer vocabulary, then handed to the caller.
                    val icons = parseShellIcons(shell)
                    if (icons.isNotEmpty()) {
                        onShellIcons(icons)
                        messages.add(ChatEntry("agent", "Changed tab icons: " + icons.entries.joinToString { "${it.key} → ${it.value}" }))
                    }
                }
                onApplied(response, request)
                // Give the user a beat to read "Done", then collapse the sheet so the
                // composed A2UI screen (banner + components) is actually visible.
                delay(900)
                onDismiss()
            } else {
                // Validation rejected the model twice → deterministic data-edit fallback
                // (VISIBLE, never silent), else the structured errors are reported.
                val outcome = if (current != null) deterministicAugment() else null
                if (outcome != null) {
                    messages.add(
                        ChatEntry(
                            "agent",
                            "The model's JSON failed validation twice — applied a deterministic data edit instead: " + outcome.second,
                        ),
                    )
                    onApplied(outcome.first, request)
                    delay(800)
                    onDismiss()
                } else {
                    messages.add(ChatEntry("agent", "Failed: " + result.errors.joinToString("; ").take(240)))
                }
            }
        }
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "A2UI agent",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.height(160.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages) { entry -> MessageBubble(entry) }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp).height(16.dp))
                            Text(
                                text = if (prefs.useLlm) "LLM is composing the screen…" else "Simulator is composing the screen…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
            LaunchedEffect(messages.size, busy) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1 + if (busy) 1 else 0)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to change on the screen")
                }
                try {
                    speechLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    messages.add(ChatEntry("agent", "Voice input unavailable — type the text instead"))
                }
                    },
                    enabled = !busy,
                ) { Icon(Icons.Filled.Mic, contentDescription = "Voice input") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("show overdue tasks…", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !busy,
                )
                IconButton(
                    onClick = { apply() },
                    enabled = !busy && query.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private data class ChatEntry(val role: String, val text: String)

/**
 * Instant, offline answer to "какие есть компоненты / что ты умеешь" — from the SAME
 * catalog.json the validator uses. Never goes to the LLM (that path used to freeze
 * and crash when the model replied with prose instead of 0.9.1 JSON).
 */
internal fun capabilitiesReply(text: String, catalog: dev.a2uicrm.agentic.catalog.CatalogDefinition): String? {
    val t = text.lowercase()
    val asked = listOf(
        "компонент", "умеешь", "можешь", "что ты", "какие экран", "помощь", "help",
        "capabilities", "каталог", "возможности", "какими", "what can you",
    ).any { it in t }
    if (!asked) return null

    // Human-readable names — the user does not speak in catalog identifiers.
    val friendly = mapOf(
        "Column" to "Column — vertical list",
        "Row" to "Row — horizontal strip",
        "Card" to "Card",
        "Text" to "Text",
        "Divider" to "Divider",
        "Button" to "Button",
        "Icon" to "Icon",
        "List" to "Scrollable list",
        "MetricCard" to "Metric (KPI card with trend)",
        "SectionHeader" to "Section header (with count)",
        "AlertBanner" to "Alert banner",
        "LeaderboardRow" to "Leaderboard row (member)",
        "EmptyState" to "Empty state (placeholder)",
        "EntityCard" to "Entity card (contact / deal / task)",
        "ActionItem" to "Task with a checkbox",
        "PrimaryActionButton" to "Primary action button",
        "PipelineStrip" to "Sales funnel (stages)",
        "TrendChart" to "Weekly trend chart",
    )
    val native = catalog.nativeComponents?.components
        ?.filter { it.rendererSupport == "native" }
        ?.mapNotNull { friendly[it.name] ?: it.name }
        .orEmpty()
    val crm = catalog.components.keys.map { friendly[it] ?: it }
    val actionFriendly = mapOf(
        "navigate" to "navigate (home / pipeline / settings)",
        "openEntity" to "open a task card",
        "createTask" to "create a task",
        "completeTask" to "mark a task completed",
        "updateOpportunityStage" to "move a deal to another stage",
        "createNote" to "create a note",
        "triggerWorkflow" to "trigger a workflow",
        "refreshSurface" to "refresh the screen",
        "filterStage" to "filter the funnel by stage",
    )
    val actions = catalog.actions.keys.map { actionFriendly[it] ?: it }
    return buildString {
        append("I compose screens only from the approved catalog (A2UI 0.9.1). ")
        append("Here is what I can work with:\n\n")
        append("Native components:\n")
        native.forEach { append("• $it\n") }
        append("\nCRM components:\n")
        crm.forEach { append("• $it\n") }
        append("\nActions:\n")
        actions.forEach { append("• $it\n") }
        append("\nDescribe the screen you need — I will compose it from these blocks. ")
        append("PieChart, email, HTML and arbitrary code are outside the catalog: I will politely refuse.")
    }
}

/**
 * Local commands: move/sort over the CURRENT screen (5.4g/5.4h sandbox ops):
 * «перемести выполненные вверх», «отсортируй сделки по сумме» → (editedJson, note).
 * null when the command does not map onto this screen (falls through to the LLM).
 */
private fun parseEditCommand(
    t: String,
    baseJson: String,
    merger: dev.a2uicrm.agentic.agent.CompositionMerger,
): Pair<String, String>? {
    val titles = merger.sectionTitles(baseJson)
    if (titles.isEmpty()) return null
    val kwToTitle = listOf(
        "выполнен" to "Completed", "completed" to "Completed", "done" to "Completed",
        "просроч" to "Overdue", "overdue" to "Overdue",
        "сегодня" to "Today", "today" to "Today",
        "завтра" to "Tomorrow", "tomorrow" to "Tomorrow",
        "сделк" to "deals", "deal" to "deals", "этап" to "deals", "stage" to "deals",
        "воронк" to "Funnel", "funnel" to "Funnel",
        "тренд" to "Trend", "trend" to "Trend",
    )
    val target = titles.firstOrNull { title -> t.contains(title.lowercase()) }
        ?: kwToTitle.firstOrNull { t.contains(it.first) }?.second?.let { hint ->
            titles.firstOrNull { it.lowercase().contains(hint.lowercase()) }
        }
        ?: return null

    if (Regex("отсортируй|ранжируй|sort|по (сумме|дате|приоритету|алфавиту)").containsMatchIn(t)) {
        val by = when {
            Regex("сумм|amount|money").containsMatchIn(t) -> "amount"
            Regex("дат|date|due|дедлайн|deadline").containsMatchIn(t) -> "dueAt"
            Regex("приоритет|priority|важн").containsMatchIn(t) -> "priority"
            else -> "title"
        }
        val json = merger.sortSection(baseJson, target, by) ?: return null
        return json to "Sorted \"$target\" by $by — a deterministic engine op, nothing was removed."
    }
    val pos = when {
        Regex("в начало|to top|наверх|первым|top").containsMatchIn(t) -> "top"
        Regex("в конец|to bottom|последним|bottom").containsMatchIn(t) -> "bottom"
        Regex("вверх|подними|выше|\\bup\\b|раньше").containsMatchIn(t) -> "up"
        Regex("вниз|опусти|ниже|\\bdown\\b|позже").containsMatchIn(t) -> "down"
        else -> return null
    }
    val json = merger.moveSection(baseJson, target, pos) ?: return null
    val humanPos = when (pos) {
        "top" -> "the top"; "bottom" -> "the bottom"; "up" -> "one slot up"; else -> "one slot down"
    }
    return json to "Moved \"$target\" to $humanPos."
}

/**
 * Local command: "удали блок выполненные" / "remove the completed block" —
 * deterministic block removal (5.4h: the sandbox was missing a delete op).
 */
private fun parseRemoveBlock(
    t: String,
    baseJson: String,
    merger: dev.a2uicrm.agentic.agent.CompositionMerger,
): Pair<String, String>? {
    val titles = merger.sectionTitles(baseJson)
    if (titles.isEmpty()) return null
    val kwToTitle = listOf(
        "выполнен" to "Completed", "completed" to "Completed", "done" to "Completed",
        "просроч" to "Overdue", "overdue" to "Overdue",
        "сегодня" to "Today", "today" to "Today",
        "завтра" to "Tomorrow", "tomorrow" to "Tomorrow",
        "сделк" to "deals", "deal" to "deals", "этап" to "deals", "stage" to "deals",
        "воронк" to "Funnel", "funnel" to "Funnel",
        "тренд" to "Trend", "trend" to "Trend",
    )
    val target = titles.firstOrNull { title -> t.contains(title.lowercase()) }
        ?: kwToTitle.firstOrNull { t.contains(it.first) }?.second?.let { hint ->
            titles.firstOrNull { it.lowercase().contains(hint.lowercase()) }
        }
        ?: return null
    val edited = merger.removeSection(baseJson, target) ?: return null
    return edited to "Removed the \"$target\" block — a deterministic engine op (undo with \"undo\")."
}

/**
 * 5.4i: parses/validates the shell line {"shell":{"tabTitles":{...}}}.
 * Keys are whitelisted (today|team|analytics), names must be 1–24 printable chars.
 * Returns only the VALID renames (empty = nothing to apply).
 */
internal fun parseShellTitles(shellJson: String): Map<String, String> {
    return try {
        val obj = dev.a2uicrm.agentic.json.JsonValue.parse(shellJson) as? dev.a2uicrm.agentic.json.JsonValue.JsonObject
        val titles = (obj?.get("shell") as? dev.a2uicrm.agentic.json.JsonValue.JsonObject)
            ?.get("tabTitles") as? dev.a2uicrm.agentic.json.JsonValue.JsonObject
            ?: return emptyMap()
        val allowed = setOf("today", "team", "analytics")
        titles.entries.mapNotNull { (k, v) ->
            val name = (v as? dev.a2uicrm.agentic.json.JsonValue.JsonString)?.value?.trim() ?: return@mapNotNull null
            if (k in allowed && name.length in 1..24 && name.all { it.code >= 32 }) k to name else null
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * 5.5: parses/validates the shell line {"shell":{"tabIcons":{...}}}.
 * Keys are whitelisted (today|team|analytics), tokens MUST come from the renderer
 * icon vocabulary (A2uiRendererRegistry.ICONS) — the same list the validator and
 * the LLM prompt share. Returns only the VALID changes (empty = nothing to apply).
 */
internal fun parseShellIcons(shellJson: String): Map<String, String> {
    return try {
        val obj = dev.a2uicrm.agentic.json.JsonValue.parse(shellJson) as? dev.a2uicrm.agentic.json.JsonValue.JsonObject
        val icons = (obj?.get("shell") as? dev.a2uicrm.agentic.json.JsonValue.JsonObject)
            ?.get("tabIcons") as? dev.a2uicrm.agentic.json.JsonValue.JsonObject
            ?: return emptyMap()
        val allowed = setOf("today", "team", "analytics")
        val vocabulary = dev.a2uicrm.agentic.render.A2uiRendererRegistry.ICONS.keys
        icons.entries.mapNotNull { (k, v) ->
            val token = (v as? dev.a2uicrm.agentic.json.JsonValue.JsonString)?.value?.trim() ?: return@mapNotNull null
            if (k in allowed && token in vocabulary) k to token else null
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * 5.5 add/show classification. ADD = keep the current screen + strictly add what was
 * asked (enforced by a structural guard on LLM output). SHOW = replace the screen with
 * only what was asked. Positional references («под этапами», «under each») anchor a
 * request to the CURRENT screen, so even a «покажи … под …» is an ADD.
 */
internal fun isAdditiveRequest(t: String): Boolean {
    val addWords = Regex("добав|дополн|append|\\badd\\b|под каждым|под этап|under each|under the|below the|после этого|ниже")
    if (addWords.containsMatchIn(t)) return true
    val showWords = "покажи" in t || "show" in t || "показать" in t
    val anchors = "под" in t || "under" in t || "below" in t
    return showWords && anchors
}

/** Deterministic «augment» merge: append the requested entity to the CURRENT screen. */
internal suspend fun augmentMerge(
    text: String,
    repository: CrmRepository,
    current: Pair<String, String>,
): Pair<dev.a2uicrm.agentic.agent.AgentResponse, String>? {
    val (json, surfaceId) = current
    val t = text.lowercase()
    val prefix = "au${(System.currentTimeMillis() % 10000)}"
    val merger = dev.a2uicrm.agentic.agent.CompositionMerger

    // «add deadlines to tasks»: fill missing dueAt in place (pure property addition).
    if ("дедлайн" in t || "deadline" in t) {
        val patched = merger.ensureDueDates(json, repository.getAllActiveTasks()) ?: return null
        return dev.a2uicrm.agentic.agent.AgentResponse(
            surfaceId = surfaceId,
            updateDataModelJson = null,
            componentsJson = patched,
            inference = listOf("Deadlines filled into the task rows in place"),
        ) to "Deadlines added to the task rows."
    }

    // 5.5 add/show contract: an AUGMENT is only for additive requests. «покажи X»
    // without a positional anchor is SHOW semantics (the screen gets replaced) —
    // handled by the LLM/simulator focus paths, not here.
    if (!isAdditiveRequest(t)) return null

    // 5.5 «show deals under each funnel stage»: deals DIRECTLY UNDER their stage row —
    // the funnel itself stays pixel-identical (the analytics twin carries stage rows).
    if (("воронк" in t || "funnel" in t || "этап" in t || "stage" in t) && ("сделк" in t || "deal" in t)) {
        val inserted = merger.insertDealCardsAfterStages(json, repository.getDeals(), prefix)
        if (inserted != null) {
            return dev.a2uicrm.agentic.agent.AgentResponse(
                surfaceId = surfaceId,
                updateDataModelJson = null,
                componentsJson = inserted,
                inference = listOf("Deals inserted under each funnel stage; everything else preserved"),
            ) to "Deals are now shown under each funnel stage. The funnel itself is untouched."
        }
        // No stage rows on this screen → grouped per-stage sections fallback.
        val stages = try { repository.getPipelineStages().map { it.label } } catch (e: Exception) { emptyList() }
        val comps = merger.dealStageSectionComponents(repository.getDeals(), stages, prefix)
        if (comps.isEmpty()) return null
        val merged = merger.appendComponents(json, comps) ?: return null
        return dev.a2uicrm.agentic.agent.AgentResponse(
            surfaceId = surfaceId,
            updateDataModelJson = null,
            componentsJson = merged,
            inference = listOf("Deals grouped by funnel stage appended; everything else preserved"),
        ) to "Deals grouped by stage were added below. Everything you had is untouched."
    }

    // 5.5 «add chart»: a TrendChart with REAL repository numbers appended to the
    // CURRENT screen — nothing else changes (the "add chart must not rebuild" bug).
    if ("chart" in t || "график" in t || "динамик" in t || "тренд" in t || "trend" in t) {
        val trend = try { repository.getWeeklyTrend().map { it.label to it.value } } catch (e: Exception) { emptyList() }
        val comps = merger.trendChartComponents(trend, prefix, "Open deals value by day", unit = "k$")
        if (comps.isEmpty()) return null
        val merged = merger.appendComponents(json, comps) ?: return null
        return dev.a2uicrm.agentic.agent.AgentResponse(
            surfaceId = surfaceId,
            updateDataModelJson = null,
            componentsJson = merged,
            inference = listOf("TrendChart appended with real repository numbers; everything else preserved"),
        ) to "Chart added with the real weekly numbers. Everything you had is untouched."
    }

    val newComponents = when {
        "выполнен" in t || "заверш" in t || "completed" in t ->
            merger.sectionComponents("Completed", repository.getCompletedTasks(), prefix)
        "просроч" in t || "overdue" in t ->
            merger.sectionComponents("Overdue", repository.getOverdueTasks(), "${prefix}o", error = true)
        "сделк" in t || "deal" in t ->
            merger.dealSectionComponents(repository.getDeals(), prefix)
        else -> null
    } ?: return null
    if (newComponents.isEmpty()) return null
    val merged = merger.appendComponents(json, newComponents) ?: return null
    val what = when {
        "выполнен" in t || "заверш" in t || "completed" in t -> "completed tasks"
        "просроч" in t || "overdue" in t -> "overdue tasks"
        else -> "open deals"
    }
    return dev.a2uicrm.agentic.agent.AgentResponse(
        surfaceId = surfaceId,
        updateDataModelJson = null,
        componentsJson = merged,
        inference = listOf("Screen augmented: added $what, everything else preserved"),
    ) to "Appended to the current screen: $what. Everything you had is untouched."
}

/** Compact REAL repository state for the LLM (so it does not invent tasks). */
internal suspend fun buildRepoSnapshot(repository: CrmRepository): String = buildString {
    appendLine("REPOSITORY DATA (use ONLY these ids, names, dates):")
    val active = repository.getAllActiveTasks()
    val done = repository.getCompletedTasks()
    appendLine("ACTIVE TASKS (${active.size}):")
    active.forEach { t ->
        val due = t.dueAtEpochMs?.let { CrmActionDispatcher.epochToIso(it) } ?: "no date"
        appendLine("- id=${t.id}, title='${t.title}', due=$due, completed=${t.completed}, contact='${t.contactName ?: "-"}'")
    }
    appendLine("COMPLETED (${done.size}):")
    done.forEach { t ->
        appendLine("- id=${t.id}, title='${t.title}'")
    }
    val deals = repository.getDeals()
    appendLine("OPEN DEALS (entityId for EntityCard MUST be the id= value):")
    deals.forEach { d ->
        appendLine("- id=${d.id}, title='${d.title}', stage=${d.stage}, micros=${d.amountMicros}, ownerId=${d.ownerId}, contact=${d.contactName ?: "-"}")
    }
    // Team members (the LLM was inventing "Manager One/Two/Three" before this was added):
    // the ONLY names it may use in LeaderboardRow/EntityCard are listed here.
    val stats = repository.getTeamStats()
    appendLine("TEAM MEMBERS (use ONLY these names in team/leaderboard components):")
    stats.forEach { s ->
        appendLine("- ${s.memberName} (${s.memberId}): pipeline=${s.pipelineValueMicros}, won=${s.wonCount}")
    }
    // Funnel — so metric/funnel numbers also come from real data.
    val stages = repository.getPipelineStages()
    appendLine("PIPELINE FUNNEL:")
    stages.forEach { st ->
        appendLine("- ${st.label}: count=${st.count}, micros=${st.valueMicros}")
    }
}

@Composable
private fun MessageBubble(entry: ChatEntry) {
    val isUser = entry.role == "user"
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = (if (isUser) "You: " else "") + entry.text,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

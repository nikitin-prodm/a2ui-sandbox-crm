package dev.a2uicrm.agentic.agent

import dev.a2uicrm.agentic.action.CrmActionDispatcher
import dev.a2uicrm.core.domain.CrmRepository
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * v1 agent SIMULATOR: deterministic classifier over free-form RU/EN requests + inference
 * policy (analysis.md §4.1 "додумывание"). It demonstrates the product behavior the
 * sandbox targets — "сделай красиво и не грузи вопросами":
 *
 *  1. classify the (often vague, incomplete, or awkward) request into a screen intent;
 *  2. fill in everything the user did not specify (role, metrics, sections, empty
 *     branches, dates, CTA) following the Home template so the result always looks
 *     complete and consistent;
 *  3. report what was inferred (transparency, level-1 UX §5.2) instead of asking;
 *  4. self-correct: on structured validation errors (feedback), re-emit a valid screen.
 *
 * v2 replaces this class with an LLM implementation of [AgentGateway] — the interface,
 * the two-layer validation and the UI stay unchanged.
 */
class DemoAgent(private val repository: CrmRepository) : AgentGateway {

    enum class Intent {
        MANAGER_HOME, OVERDUE_FOCUS, TODAY_FOCUS, TEAM_LEADERBOARD, CREATE_TASK_DRAFT,
        REFUSAL_CHART, REFUSAL_EMAIL, REFUSAL_HTML, ANALYTICS, COMPLETED_TASKS, ALL_TASKS,
    }

    override suspend fun apply(request: AgentRequest, feedback: List<String>): AgentResponse {
        // Simulated agent "thinking" latency (600–1200 ms) so the Apply spinner is real.
        delay(600 + Random.nextLong(600))

        // Self-correction: if the previous attempt was rejected, fall back to the
        // always-valid composition and tell the user (a real LLM would repair its JSON).
        if (feedback.isNotEmpty()) {
            return AgentResponse(
                surfaceId = surfaceIdFor(request),
                updateDataModelJson = null,
                componentsJson = CompositionBuilder.safeFallback(surfaceIdFor(request)),
                inference = listOf(
                    "Previous composition was rejected by the validator (${feedback.size} errors) — safe screen shown",
                ) + feedback.take(3),
            )
        }

        return compose(request)
    }

    /**
     * Same classification + composition as [apply], without the simulated thinking delay.
     * Used for silent rebuilds after a repository mutation (checkbox / create-task) so
     * the A2UI screen reflects the new data in ~200 ms instead of ~1 s.
     */
    suspend fun composeImmediately(request: AgentRequest): AgentResponse = compose(request)

    /**
     * Coarse NLU: keyword matching over RU/EN free-form text; blank text -> role home.
     * REFUSAL patterns are matched FIRST (a request for PieChart/email/HTML must be
     * refused, not misread as "add task").
     */
    fun classify(request: AgentRequest): Intent {
        val text = request.text.lowercase()
        return when {
            "диаграм" in text || "pie" in text || "кругов" in text ||
                "инфографик" in text || "дэшборд" in text -> Intent.REFUSAL_CHART
            // "воронка"/"график"/"тренд"/"метрики"/"аналитика" -> ANALYTICS. Priority:
            // a PieChart/"диаграмма" request is refused above; a pipeline/trend request is served.
            "воронк" in text || "funnel" in text || "этап" in text || "stage" in text ||
                "тренд" in text || "метрик" in text ||
                "аналитик" in text || "динамик" in text || "график" in text ||
                ("chart" in text && "pie" !in text) -> Intent.ANALYTICS
            "email" in text || "письм" in text || "почт" in text || "mail" in text -> Intent.REFUSAL_EMAIL
            "html" in text || "веб-страниц" in text || "верстк" in text || "код экран" in text -> Intent.REFUSAL_HTML
            "просроч" in text || "overdue" in text -> Intent.OVERDUE_FOCUS
            "выполнен" in text || "заверш" in text || "done" in text || "completed" in text -> Intent.COMPLETED_TASKS
            "все задач" in text || "все задан" in text || "покажи все" in text ||
                "all task" in text || "show all" in text -> Intent.ALL_TASKS
            "команд" in text || "лидерборд" in text || "рейтинг" in text ||
                "team" in text || "leaderboard" in text ||
                "сделк" in text || "сотрудник" in text || "под кажд" in text ||
                "deal" in text || "employee" in text -> Intent.TEAM_LEADERBOARD
            "добав" in text || "созда" in text || "новую задачу" in text ||
                "новая задача" in text || "create" in text -> Intent.CREATE_TASK_DRAFT
            "сегодня" in text || "today" in text -> Intent.TODAY_FOCUS
            text.isBlank() && request.role == "head" -> Intent.TEAM_LEADERBOARD
            else -> Intent.MANAGER_HOME
        }
    }

    private suspend fun compose(request: AgentRequest): AgentResponse {
        val intent = classify(request)
        return compose(request, intent)
    }

    private suspend fun compose(request: AgentRequest, intent: Intent): AgentResponse {
        var surfaceId = surfaceIdFor(request)
        val inference = mutableListOf<String>()

        // Inference #1: role. Requests rarely mention it — take it from the switcher.
        val role = if (intent == Intent.TEAM_LEADERBOARD && request.role == "manager") {
            inference += "Team request → role switched from manager to head"
            "head"
        } else {
            if (request.text.isBlank()) inference += "Empty request → role home screen for ${request.role}"
            request.role
        }

        val composed: Composition = when (intent) {
            // FOCUSED intents (user asked for ONE thing → show EXACTLY that, nothing else:
            // no metrics row, no alert banner, no CTA — those belong to the full Home
            // template only and were the "random squares on top" complaint, 5.4f).
            Intent.OVERDUE_FOCUS -> {
                val overdue = repository.getOverdueTasks()
                inference += "Focused request → only the Overdue section (no metrics/banner/CTA)"
                if (overdue.isEmpty()) inference += "No overdue tasks → empty state"
                CompositionBuilder.tasksScreen(surfaceId, listOf("Overdue" to overdue))
            }
            Intent.TODAY_FOCUS -> {
                val today = repository.getTodayTasks()
                inference += "Focused request → only the Today section (no metrics/banner/CTA)"
                if (today.isEmpty()) inference += "No tasks for today → empty state"
                CompositionBuilder.tasksScreen(surfaceId, listOf("Today" to today))
            }
            // MANAGER_HOME (blank request / "make it pretty" / home screen): the full
            // canonical template — banner, metrics, sections, empty branch, CTA — is
            // the correct response here; the user asked for the whole home screen.
            Intent.MANAGER_HOME -> {
                if (request.text.isNotBlank()) {
                    inference += "Request narrowed ('${request.text.take(40)}') → focus + role context"
                }
                inference += "Home template: metrics, sections and the empty branch (explicit home/blank request)"
                if ("красив" in request.text.lowercase() || "pretty" in request.text.lowercase()) {
                    inference += "Make it pretty → canonical template: metrics on top, task groups, CTA; fields placed semantically"
                }
                val overdue = repository.getOverdueTasks()
                val today = repository.getTodayTasks()
                val tomorrow = repository.getTomorrowTasks()
                val all = repository.getAllActiveTasks()
                val groups = dev.a2uicrm.core.domain.groupByCalendar(all)
                val extra = listOf(
                    "This week" to groups.thisWeek,
                    "Next week" to groups.nextWeek,
                    "This month" to groups.thisMonth,
                    "Next month" to groups.nextMonth,
                    "This year" to groups.thisYear,
                    "Next year" to groups.nextYear,
                    "Later" to groups.later,
                )
                val built = CompositionBuilder.managerHome(surfaceId, overdue, today, tomorrow, extra, doneToday = 5)
                inference += "Task data filled from the repository; metrics wired via DataBinding"
                built
            }
            Intent.TEAM_LEADERBOARD -> {
                inference += "Team metrics, leaderboard and open deals — from TeamStats + Deal"
                CompositionBuilder.headHome(surfaceId, repository.getTeamStats(), repository.getDeals())
            }
            Intent.COMPLETED_TASKS -> {
                val done = repository.getCompletedTasks()
                inference += if (done.isEmpty()) "No completed tasks yet" else "Completed tasks (${done.size}) — from the repository"
                CompositionBuilder.tasksScreen(surfaceId, listOf("Completed" to done))
            }
            Intent.ALL_TASKS -> {
                val groups = dev.a2uicrm.core.domain.groupByCalendar(repository.getAllActiveTasks())
                val done = repository.getCompletedTasks()
                val sections = groups.asSections + listOf("Completed" to done)
                inference += "All tasks (active by calendar + completed) — from the repository"
                CompositionBuilder.tasksScreen(surfaceId, sections)
            }
            Intent.ANALYTICS -> {
                // Inference #4: the user asked for "analytics" without specifying KPIs —
                // the agent picks the funnel + weekly trend (the canonical head view, §4.1)
                // and computes the conversion metric from the repository data.
                inference += "No specific KPIs → canonical view: funnel + weekly trend"
                inference += "Conversion and total pipeline computed from the repository data"
                if (request.role == "manager") {
                    inference += "Analytics is a head context → surface switched to home-head"
                    surfaceId = "home-head"
                }
                CompositionBuilder.analyticsScreen(
                    surfaceId,
                    repository.getPipelineStages(),
                    repository.getWeeklyTrend(),
                )
            }
            Intent.CREATE_TASK_DRAFT -> {
                // Inference #3: fill in the details the user omitted.
                val title = extractTitle(request.text) ?: "New task".also {
                    inference += "Title not recognized → 'New task' (editable)"
                }
                val dueIso = CrmActionDispatcher.epochToIso(CrmActionDispatcher.defaultTomorrow9am())
                inference += "No date → tomorrow 09:00; no contact → skipped"
                inference += "The task is not created immediately — a Create-task CTA is prepared (whitelisted action)"
                CompositionBuilder.createTaskDraft(surfaceId, title, dueIso)
            }
            // Refusals (§5.2): explain WHY the request is out of scope and offer a
            // catalog-supported alternative. The unsupported component is never rendered.
            Intent.REFUSAL_CHART -> {
                val isPie = "pie" in request.text.lowercase()
                val what = if (isPie) "pie chart (PieChart)" else "chart/graph"
                inference += "Refusal: '$what' is not in the catalog (outside 0.9.1 basic + CRM extensions)"
                inference += "Alternative: TrendChart (bar chart); for now a return to tasks is offered"
                CompositionBuilder.refusalScreen(
                    surfaceId,
                    requested = what,
                    reason = "The A2UI CRM catalog has no charts of this type — the renderer draws only approved components (MetricCard, TrendChart).",
                    alternativeLabel = "Show my tasks",
                    alternativeAction = mapOf("name" to "navigate", "context" to mapOf("route" to "home")),
                )
            }
            Intent.REFUSAL_EMAIL -> {
                inference += "Refusal: sendEmail is not in the action whitelist"
                inference += "Alternative: create a reminder task (createTask is whitelisted)"
                CompositionBuilder.refusalScreen(
                    surfaceId,
                    requested = "send an email",
                    reason = "Sending emails is not available in A2UI CRM: actions are strictly limited to the catalog whitelist (no openUrl, no arbitrary code).",
                    alternativeLabel = "Create a reminder task",
                    alternativeAction = mapOf(
                        "name" to "createTask",
                        "context" to mapOf(
                            "title" to "Write the email",
                            "dueAt" to CrmActionDispatcher.epochToIso(CrmActionDispatcher.defaultTomorrow9am()),
                        ),
                    ),
                )
            }
            Intent.REFUSAL_HTML -> {
                inference += "Refusal: raw HTML/markup is incompatible with the protocol (UI = validated data, not code)"
                CompositionBuilder.refusalScreen(
                    surfaceId,
                    requested = "a custom HTML screen",
                    reason = "A2UI does not render HTML or code — screens are composed only from catalog components. Describe what to show and the agent will compose it from approved blocks.",
                    alternativeLabel = "Show my tasks",
                    alternativeAction = mapOf("name" to "navigate", "context" to mapOf("route" to "home")),
                )
            }
        }

        val components = composed.componentsJson
        val data = composed.dataModelJson
        return AgentResponse(
            surfaceId = surfaceId,
            updateDataModelJson = data,
            componentsJson = components,
            inference = inference,
        )
    }

    /** "добавь задачу позвонить клиенту" -> "позвонить клиенту". */
    private fun extractTitle(text: String): String? {
        val lower = text.lowercase()
        val markers = listOf("задачу", "задача", "таску", "task")
        for (m in markers) {
            val idx = lower.indexOf(m)
            if (idx >= 0) {
                val tail = text.substring(idx + m.length).trim(':', ' ', '.', ',')
                if (tail.isNotBlank() && tail.length <= 60) return tail.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    private fun surfaceIdFor(request: AgentRequest): String =
        if (request.role == "head") "home-head" else "home-mgr"
}

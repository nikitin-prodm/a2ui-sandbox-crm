package dev.a2uicrm.core.data

import dev.a2uicrm.core.domain.Contact
import dev.a2uicrm.core.domain.CrmRepository
import dev.a2uicrm.core.domain.Deal
import dev.a2uicrm.core.domain.PipelineStage
import dev.a2uicrm.core.domain.Task
import dev.a2uicrm.core.domain.TeamStats
import dev.a2uicrm.core.domain.TrendPoint
import dev.a2uicrm.core.domain.WorkspaceMember
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone

/**
 * In-memory fake connector for sub-blocks 5.1–5.4.
 * Simulates latency so the demo shows shimmer/loading states; swap for a real
 * TwentyConnector in v2 (Connector Pattern, reference only — analysis.md §7.1).
 */
class FakeCrmConnector : CrmRepository {

    private val tasks = mutableListOf(
        Task("tt11", "Send proposal to Acme", completed = false, dueAtEpochMs = 1_760_000_000_000, contactName = "John Doe"),
        Task("tt12", "Call Jane about renewal", completed = false, dueAtEpochMs = 1_760_100_000_000, contactName = "Jane Cooper"),
    )

    private val members = listOf(
        WorkspaceMember("wm-1", "Jane", "Cooper"),
        WorkspaceMember("wm-2", "John", "Doe"),
        WorkspaceMember("wm-3", "Alice", "Smith"),
    )

    private fun dayStart(now: Long, offsetDays: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun bucket(now: Long, offsetDays: Int): List<Task> {
        delay(200)
        val start = dayStart(now, offsetDays)
        val end = dayStart(now, offsetDays + 1)
        return tasks.filter { !it.completed && it.dueAtEpochMs != null && it.dueAtEpochMs!! >= start && it.dueAtEpochMs!! < end }
    }

    override suspend fun getCurrentUserName(): String {
        delay(150)
        return "Alex Manager"
    }

    override suspend fun getOverdueTasks(): List<Task> {
        delay(200)
        return tasks.filter { !it.completed && (it.dueAtEpochMs == null || it.dueAtEpochMs!! < dayStart(System.currentTimeMillis())) }
    }

    override suspend fun getTodayTasks(): List<Task> = bucket(System.currentTimeMillis(), 0)

    override suspend fun getTomorrowTasks(): List<Task> = bucket(System.currentTimeMillis(), 1)

    override suspend fun getWorkspaceMembers(): List<WorkspaceMember> {
        delay(200)
        return members
    }

    override suspend fun getTeamStats(): List<TeamStats> {
        delay(250)
        // 5.4i: REAL deal aggregates per member (the fixtures lied: pipeline/won did not
        // match the deals list, and a created deal never moved these numbers).
        // Task stats (open/overdue/done7d) stay canned — tasks carry no assignee in the fake.
        return members.map { m ->
            val owned = deals.filter { it.ownerId == m.id }
            TeamStats(
                memberId = m.id,
                memberName = m.displayName,
                openTasks = 12 - members.indexOf(m) * 2,
                overdueTasks = 2 - members.indexOf(m),
                doneTasks7d = 8 - members.indexOf(m) * 2,
                pipelineValueMicros = owned.sumOf { it.amountMicros },
                wonCount = owned.count { it.stage == "Won" },
            )
        }
    }

    /** Pipeline funnel (view-aggregate §2.7): 4 stages, computed from the REAL deals. */
    override suspend fun getPipelineStages(): List<PipelineStage> {
        delay(250)
        val order = listOf("Lead", "Qualified", "Proposal", "Won")
        val known = deals.map { it.stage }.toSet()
        // Keep the canonical order; unknown stages append after (never hidden).
        val stages = order + (known - order.toSet())
        var previous = -1
        return stages.mapIndexed { i, stage ->
            val list = deals.filter { it.stage == stage }
            val conv = if (i == 0) null else if (previous <= 0) 0.0 else list.size.toDouble() / previous
            previous = list.size
            PipelineStage("st-${stage.lowercase()}", stage, list.size, list.sumOf { it.amountMicros }, conv)
        }
    }

    override suspend fun getWeeklyTrend(): List<TrendPoint> {
        delay(200)
        return listOf(
            TrendPoint("Mon", 61.0), TrendPoint("Tue", 68.5), TrendPoint("Wed", 64.0),
            TrendPoint("Thu", 75.0), TrendPoint("Fri", 82.3), TrendPoint("Sat", 80.0),
            TrendPoint("Sun", 91.0),
        )
    }

    override suspend fun createTask(
        title: String,
        dueAtEpochMs: Long?,
        contactId: String?,
        contactName: String?,
    ): Task {
        delay(250)
        val task = Task(
            "tt${tasks.size + 100}",
            title,
            completed = false,
            dueAtEpochMs = dueAtEpochMs,
            contactId = contactId,
            contactName = contactName,
        )
        tasks.add(task)
        return task
    }

    override suspend fun updateTask(taskId: String, completed: Boolean?): Task? {
        delay(250)
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return null
        val updated = tasks[index].copy(completed = completed ?: tasks[index].completed)
        tasks[index] = updated
        return updated
    }

    override suspend fun getTaskById(id: String): Task? {
        delay(150)
        return tasks.firstOrNull { it.id == id }
    }

    override suspend fun getAllActiveTasks(): List<Task> {
        delay(200)
        return tasks.filter { !it.completed }
    }

    override suspend fun getCompletedTasks(): List<Task> {
        delay(200)
        return tasks.filter { it.completed }
    }

    private val deals = mutableListOf(
        Deal("d1", "Acme — rollout", 12_000_000_000, "Lead", "wm-1", "John Doe"),
        Deal("d2", "Globex — licenses", 8_000_000_000, "Qualified", "wm-1", "Jane Cooper"),
        Deal("d3", "Initech — upgrade", 15_000_000_000, "Proposal", "wm-2", "John Doe"),
        Deal("d4", "Umbrella — pilot", 6_000_000_000, "Lead", "wm-2", "Jane Cooper"),
        Deal("d5", "Stark — contract", 22_000_000_000, "Qualified", "wm-3", "Alice Smith"),
        Deal("d6", "Wayne — support", 4_000_000_000, "Proposal", "wm-3", "John Doe"),
        Deal("d7", "Oscorp — tender", 9_500_000_000, "Lead", "wm-1", "Jane Cooper"),
    )

    override suspend fun getDeals(): List<Deal> {
        delay(200)
        return deals.toList()
    }

    override suspend fun createDeal(
        title: String,
        amountMicros: Long,
        stage: String,
        ownerId: String,
        contactName: String?,
    ): Deal {
        delay(250)
        val deal = Deal("d${deals.size + 100}", title, amountMicros, stage, ownerId, contactName)
        deals.add(deal)
        return deal
    }

    // ---- sandbox persistence (the app layer snapshots/restores across restarts) ----

    fun allTasksRaw(): List<Task> = tasks.toList()

    fun replaceTasks(list: List<Task>) {
        tasks.clear()
        tasks.addAll(list)
    }

    // 5.4i: deals persistence (the app layer snapshots/restores across restarts,
    // same as tasks — created deals and the REAL funnel numbers survive).

    fun allDealsRaw(): List<Deal> = deals.toList()

    fun replaceDeals(list: List<Deal>) {
        deals.clear()
        deals.addAll(list)
    }

    /** Settings → "Reset demo data": wipe the user's test junk, restore the fixtures. */
    fun resetToFixtures() {
        replaceTasks(
            listOf(
                Task("tt11", "Send proposal to Acme", completed = false, dueAtEpochMs = 1_760_000_000_000, contactName = "John Doe"),
                Task("tt12", "Call Jane about renewal", completed = false, dueAtEpochMs = 1_760_100_000_000, contactName = "Jane Cooper"),
            ),
        )
        deals.clear()
        deals.addAll(
            listOf(
                Deal("d1", "Acme — rollout", 12_000_000_000, "Lead", "wm-1", "John Doe"),
                Deal("d2", "Globex — licenses", 8_000_000_000, "Qualified", "wm-1", "Jane Cooper"),
                Deal("d3", "Initech — upgrade", 15_000_000_000, "Proposal", "wm-2", "John Doe"),
                Deal("d4", "Umbrella — pilot", 6_000_000_000, "Lead", "wm-2", "Jane Cooper"),
                Deal("d5", "Stark — contract", 22_000_000_000, "Qualified", "wm-3", "Alice Smith"),
                Deal("d6", "Wayne — support", 4_000_000_000, "Proposal", "wm-3", "John Doe"),
                Deal("d7", "Oscorp — tender", 9_500_000_000, "Lead", "wm-1", "Jane Cooper"),
            ),
        )
    }
}

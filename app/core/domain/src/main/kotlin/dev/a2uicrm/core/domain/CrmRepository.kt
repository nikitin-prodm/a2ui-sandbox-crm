package dev.a2uicrm.core.domain

/**
 * Repository contract (Connector Pattern, following the reference architecture of
 * TwentyMobile — reference only, no code copied; see analysis.md §2.2/§2.7).
 *
 * v1 scope (sub-block 5.1/5.2): read-side queries needed by the demo screens plus the
 * actions whitelisted in catalog/catalog.json. The full 30-method surface of the
 * reference is intentionally NOT mirrored — only what the sandbox consumes.
 */
interface CrmRepository {
    suspend fun getCurrentUserName(): String

    // Tasks ("My day" — manager scenario, reference: getTodayTasks/getOverdueTasks)
    suspend fun getOverdueTasks(): List<Task>
    suspend fun getTodayTasks(): List<Task>
    suspend fun getTomorrowTasks(): List<Task>

    // Team (head scenario, §2.7 Domain Extension)
    suspend fun getWorkspaceMembers(): List<WorkspaceMember>
    suspend fun getTeamStats(): List<TeamStats>

    // Analytics (5.3): pipeline funnel (view-aggregate) + weekly trend
    suspend fun getPipelineStages(): List<PipelineStage>
    suspend fun getWeeklyTrend(): List<TrendPoint>

    // Whitelisted actions (catalog/actions -> repositoryMapping)
    suspend fun createTask(
        title: String,
        dueAtEpochMs: Long? = null,
        contactId: String? = null,
        contactName: String? = null,
    ): Task
    suspend fun updateTask(taskId: String, completed: Boolean? = null): Task?

    /** Detail (5.4): single task by id, or null if missing. */
    suspend fun getTaskById(id: String): Task?

    /** All incomplete tasks — for calendar grouping on «Мой день». */
    suspend fun getAllActiveTasks(): List<Task>

    /** Completed tasks — agent can show them; «Мой день» does not. */
    suspend fun getCompletedTasks(): List<Task>

    /** Open deals by owner (invented fake — for team/analytics drilldown). */
    suspend fun getDeals(): List<Deal>

    /** New deal (Analytics «New deal»). */
    suspend fun createDeal(
        title: String,
        amountMicros: Long,
        stage: String,
        ownerId: String,
        contactName: String? = null,
    ): Deal
}

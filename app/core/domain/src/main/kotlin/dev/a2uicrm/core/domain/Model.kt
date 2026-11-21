package dev.a2uicrm.core.domain

/**
 * Domain models for the A2UI CRM sandbox.
 *
 * Design follows the reference patterns of TwentyMobile (Flutter, AGPL-3.0, used as a
 * reference ONLY): models are immutable, fields mirror the verified Twenty CRM entities
 * (see analysis.md §2.1/§2.7). No code is copied from the reference; everything is
 * written from scratch.
 */
data class Task(
    val id: String,
    val title: String,
    val body: String? = null,
    val completed: Boolean = false,
    val dueAtEpochMs: Long? = null,
    val contactId: String? = null,
    val contactName: String? = null,
    val assigneeId: String? = null,
)

data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String? = null,
    val companyId: String? = null,
    val companyName: String? = null,
)

data class WorkspaceMember(
    val id: String,
    val firstName: String,
    val lastName: String,
) {
    val displayName: String get() = "$firstName $lastName".trim()
}

data class TeamStats(
    val memberId: String,
    val memberName: String,
    val openTasks: Int,
    val overdueTasks: Int,
    val doneTasks7d: Int,
    val pipelineValueMicros: Long,
    val wonCount: Int,
)

/**
 * Pipeline funnel stage — a VIEW AGGREGATE (analysis.md §2.7: current Twenty has no
 * pipeline entities; stages are computed from opportunities by stage name).
 */
data class PipelineStage(
    val stageId: String,
    val label: String,
    val count: Int,
    val valueMicros: Long,
    val conversionFromPrevious: Double?, // null for the first stage
)

/** Single point of a weekly trend series (pipeline value / won count over time). */
data class TrendPoint(
    val label: String, // e.g. "Пн" / "W42"
    val value: Double,
)

/** Open deal (invented for the sandbox: Twenty has opportunities, we expose a compact view). */
data class Deal(
    val id: String,
    val title: String,
    val amountMicros: Long,
    val stage: String,
    val ownerId: String,
    val contactName: String? = null,
)

/**
 * Calendar grouping for "Мой день" (NOT rolling 30-day buckets):
 * сегодня / завтра / календарная неделя / следующий календарный месяц / год.
 * Priority: overdue → today → tomorrow → this week → next week → this month →
 * next month → this year → next year → later.
 */
data class TaskGroups(
    val overdue: List<Task> = emptyList(),
    val today: List<Task> = emptyList(),
    val tomorrow: List<Task> = emptyList(),
    val thisWeek: List<Task> = emptyList(),
    val nextWeek: List<Task> = emptyList(),
    val thisMonth: List<Task> = emptyList(),
    val nextMonth: List<Task> = emptyList(),
    val thisYear: List<Task> = emptyList(),
    val nextYear: List<Task> = emptyList(),
    val later: List<Task> = emptyList(),
) {
    /** Non-empty sections in display order. */
    val asSections: List<Pair<String, List<Task>>>
        get() = listOf(
            "Overdue" to overdue,
            "Today" to today,
            "Tomorrow" to tomorrow,
            "This week" to thisWeek,
            "Next week" to nextWeek,
            "This month" to thisMonth,
            "Next month" to nextMonth,
            "This year" to thisYear,
            "Next year" to nextYear,
            "Later" to later,
        ).filter { it.second.isNotEmpty() }
}

/** Device-timezone calendar grouping (Mon = first day of week). */
fun groupByCalendar(tasks: List<Task>, now: Long = System.currentTimeMillis(), includeCompleted: Boolean = false): TaskGroups {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now

    fun midnight(offsetDays: Int = 0): Long {
        val c = cal.clone() as java.util.Calendar
        c.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun firstOfMonth(offsetMonths: Int = 0): Long {
        val c = cal.clone() as java.util.Calendar
        c.add(java.util.Calendar.MONTH, offsetMonths)
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun firstOfYear(offsetYears: Int = 0): Long {
        val c = cal.clone() as java.util.Calendar
        c.add(java.util.Calendar.YEAR, offsetYears)
        c.set(java.util.Calendar.DAY_OF_YEAR, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    val todayStart = midnight(0)
    val tomorrowStart = midnight(1)
    val dayAfterTomorrow = midnight(2)

    // ISO week: Monday first (Sunday = 6 days from Monday).
    val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
    val daysFromMonday = if (dow == java.util.Calendar.SUNDAY) 6 else dow - java.util.Calendar.MONDAY
    val thisWeekStart = midnight(-daysFromMonday)
    val nextWeekStart = thisWeekStart + 7L * 24 * 60 * 60 * 1000
    val weekAfterNext = thisWeekStart + 14L * 24 * 60 * 60 * 1000

    val thisMonthStart = firstOfMonth(0)
    val nextMonthStart = firstOfMonth(1)
    val monthAfterNext = firstOfMonth(2)

    val thisYearStart = firstOfYear(0)
    val nextYearStart = firstOfYear(1)
    val yearAfterNext = firstOfYear(2)

    val overdue = mutableListOf<Task>()
    val today = mutableListOf<Task>()
    val tomorrow = mutableListOf<Task>()
    val thisWeek = mutableListOf<Task>()
    val nextWeek = mutableListOf<Task>()
    val thisMonth = mutableListOf<Task>()
    val nextMonth = mutableListOf<Task>()
    val thisYear = mutableListOf<Task>()
    val nextYear = mutableListOf<Task>()
    val later = mutableListOf<Task>()

    for (task in tasks) {
        if (!includeCompleted && task.completed) continue
        val due = task.dueAtEpochMs
        when {
            due == null -> later += task
            due < todayStart -> overdue += task
            due < tomorrowStart -> today += task
            due < dayAfterTomorrow -> tomorrow += task
            due < nextWeekStart -> thisWeek += task
            due < weekAfterNext -> nextWeek += task
            due < nextMonthStart -> thisMonth += task
            due < monthAfterNext -> nextMonth += task
            due < nextYearStart -> thisYear += task
            due < yearAfterNext -> nextYear += task
            else -> later += task
        }
    }
    return TaskGroups(
        overdue = overdue, today = today, tomorrow = tomorrow,
        thisWeek = thisWeek, nextWeek = nextWeek,
        thisMonth = thisMonth, nextMonth = nextMonth,
        thisYear = thisYear, nextYear = nextYear,
        later = later,
    )
}


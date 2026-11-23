package dev.vtodo.tasks.data

import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.VTodoItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface TaskViewFilter {
    data object AllOpen : TaskViewFilter
    data object Today : TaskViewFilter
    data object Upcoming : TaskViewFilter
    data object Completed : TaskViewFilter
    data class ByList(val listUrl: String) : TaskViewFilter
}

object TaskQuery {

    private val SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun filterAndSort(
        tasks: List<VTodoItem>,
        filter: TaskViewFilter,
        includeCompletedInList: Boolean = false,
        searchQuery: String = "",
        recentlyCompletedUids: Set<String> = emptySet(),
        today: LocalDate = LocalDate.now(),
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<VTodoItem> {
        val query = searchQuery.trim().lowercase()
        val filtered = tasks.filter { task ->
            val keptForVisualConfirm = task.uid in recentlyCompletedUids
            val matchesFilter = when (filter) {
                TaskViewFilter.AllOpen -> !task.isClosed || keptForVisualConfirm
                TaskViewFilter.Today -> {
                    (!task.isClosed || keptForVisualConfirm) && task.due != null &&
                        (task.due.isDueToday(today, zoneId) || task.due.isOverdue(today, now))
                }
                TaskViewFilter.Upcoming -> (!task.isClosed || keptForVisualConfirm) && task.due != null
                TaskViewFilter.Completed -> task.isCompleted
                is TaskViewFilter.ByList -> {
                    task.listUrl == filter.listUrl && (includeCompletedInList || !task.isClosed || keptForVisualConfirm)
                }
            }
            if (!matchesFilter) return@filter false
            if (query.isEmpty()) return@filter true
            task.summary.lowercase().contains(query) ||
                task.description.lowercase().contains(query) ||
                task.categories.any { it.lowercase().contains(query) }
        }

        return if (filter == TaskViewFilter.Completed) {
            filtered.sortedByDescending { it.completedAt ?: it.lastModifiedAt ?: Instant.EPOCH }
        } else {
            filtered.sortedWith(openTaskComparator(recentlyCompletedUids))
        }
    }

    fun tasksForWidget(
        tasks: List<VTodoItem>,
        selectedListUrl: String?,
        showCompleted: Boolean,
        recentlyCompletedUids: Set<String> = emptySet()
    ): List<VTodoItem> {
        val scoped = if (selectedListUrl != null) {
            tasks.filter { it.listUrl == selectedListUrl }
        } else {
            tasks
        }
        val filtered = if (showCompleted) {
            scoped
        } else {
            scoped.filter { !it.isClosed || it.uid in recentlyCompletedUids }
        }
        return filtered.sortedWith(openTaskComparator(recentlyCompletedUids))
    }

    fun openCount(tasks: List<VTodoItem>, listUrl: String? = null): Int =
        tasks.count { !it.isClosed && (listUrl == null || it.listUrl == listUrl) }

    fun formatDueLabel(
        due: DueSpec,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val date = due.dateTimeUtc?.atZone(zoneId)?.toLocalDate() ?: due.date
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> SHORT_DATE_FORMATTER.format(date)
        }
    }

    private fun openTaskComparator(recentlyCompletedUids: Set<String> = emptySet()): Comparator<VTodoItem> = Comparator { a, b ->
        // 1. Open tasks (and recently completed tasks held in place) before older completed tasks
        val aEffectivelyClosed = a.isClosed && a.uid !in recentlyCompletedUids
        val bEffectivelyClosed = b.isClosed && b.uid !in recentlyCompletedUids
        if (aEffectivelyClosed != bEffectivelyClosed) return@Comparator if (aEffectivelyClosed) 1 else -1
        // 2. Pinned tasks first
        if (a.pinned != b.pinned) return@Comparator if (a.pinned) -1 else 1
        // 3. Tasks with due dates before undated tasks, earliest first
        val dueA = a.due?.date
        val dueB = b.due?.date
        if (dueA != null || dueB != null) {
            if (dueA == null) return@Comparator 1
            if (dueB == null) return@Comparator -1
            val cmp = dueA.compareTo(dueB)
            if (cmp != 0) return@Comparator cmp
        }
        // 4. Priority (1..9 where 1 is highest; 0 means no priority -> lowest)
        val prioA = if (a.priority in 1..9) a.priority else 10
        val prioB = if (b.priority in 1..9) b.priority else 10
        if (prioA != prioB) return@Comparator prioA.compareTo(prioB)
        // 5. Explicit sort order if present
        if (a.sortOrder != null && b.sortOrder != null && a.sortOrder != b.sortOrder) {
            return@Comparator a.sortOrder.compareTo(b.sortOrder)
        }
        // 6. Alphabetical summary
        a.summary.compareTo(b.summary, ignoreCase = true)
    }
}

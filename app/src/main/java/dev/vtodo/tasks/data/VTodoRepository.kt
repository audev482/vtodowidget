package dev.vtodo.tasks.data

import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.SyncState
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStatus
import dev.vtodo.tasks.domain.VTodoStoreState
import dev.vtodo.tasks.protocol.CalDavException
import dev.vtodo.tasks.protocol.CalDavService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

interface WidgetUpdaterPort {
    fun notifyWidgetsUpdated(state: VTodoStoreState)
}

interface SyncSchedulerPort {
    fun schedulePeriodicSync(intervalMinutes: Int)
}

object NoOpWidgetUpdater : WidgetUpdaterPort {
    override fun notifyWidgetsUpdated(state: VTodoStoreState) = Unit
}

object NoOpSyncScheduler : SyncSchedulerPort {
    override fun schedulePeriodicSync(intervalMinutes: Int) = Unit
}

/**
 * Single source of truth coordinating RFC 4791 CalDAV sync, local offline cache,
 * optimistic UI updates, and desktop widget refresh.
 */
class VTodoRepository(
    private val calDav: CalDavService,
    private val localStore: VTodoLocalStore,
    private val widgetUpdater: WidgetUpdaterPort = NoOpWidgetUpdater,
    private val syncScheduler: SyncSchedulerPort = NoOpSyncScheduler,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val completionClearDelayMillis: Long = DEFAULT_COMPLETION_HOLD_MS
) {
    private val _state = MutableStateFlow(VTodoStoreState())
    val state: StateFlow<VTodoStoreState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        val loaded = localStore.loadSnapshot()
        _state.value = loaded
        initialized = true
        if (loaded.config.isConfigured) {
            syncScheduler.schedulePeriodicSync(loaded.config.syncIntervalMinutes)
        }
    }

    suspend fun testConnection(config: ServerConfig): Result<List<TaskList>> {
        return try {
            val lists = calDav.discoverTaskLists(config)
            Result.success(lists)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveServerAndSync(config: ServerConfig): Result<List<TaskList>> {
        _state.update { it.copy(syncState = SyncState.Syncing) }
        return try {
            val lists = calDav.discoverTaskLists(config)
            val validSelectedList = config.selectedListUrl?.takeIf { sel -> lists.any { it.url == sel } }
            val updatedConfig = config.copy(selectedListUrl = validSelectedList)
            val tasks = fetchAllTasksForLists(updatedConfig, lists)
            val now = nowProvider()

            persistAndPublish(
                config = updatedConfig,
                lists = lists,
                tasks = tasks,
                syncState = SyncState.Idle,
                lastSyncedAt = now
            )
            syncScheduler.schedulePeriodicSync(updatedConfig.syncIntervalMinutes)
            Result.success(lists)
        } catch (e: Exception) {
            val msg = friendlyError(e)
            _state.update { it.copy(syncState = SyncState.Error(msg)) }
            widgetUpdater.notifyWidgetsUpdated(_state.value)
            Result.failure(e)
        }
    }

    suspend fun syncAll(): Result<Unit> {
        initialize()
        val current = _state.value
        if (!current.config.isConfigured) {
            return Result.failure(IllegalStateException("CalDAV server is not configured"))
        }

        _state.update { it.copy(syncState = SyncState.Syncing) }
        return try {
            val lists = calDav.discoverTaskLists(current.config)
            val validSelectedList = current.config.selectedListUrl?.takeIf { sel -> lists.any { it.url == sel } }
            val updatedConfig = current.config.copy(selectedListUrl = validSelectedList)
            val tasks = fetchAllTasksForLists(updatedConfig, lists)
            val now = nowProvider()

            persistAndPublish(
                config = updatedConfig,
                lists = lists,
                tasks = tasks,
                syncState = SyncState.Idle,
                lastSyncedAt = now
            )
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = friendlyError(e)
            _state.update { it.copy(syncState = SyncState.Error(msg)) }
            widgetUpdater.notifyWidgetsUpdated(_state.value)
            Result.failure(e)
        }
    }

    suspend fun createTask(
        summary: String,
        listUrl: String? = null,
        description: String = "",
        due: DueSpec? = null,
        priority: Int = 0,
        categories: List<String> = emptyList()
    ): Result<VTodoItem> {
        initialize()
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Task title cannot be empty"))

        val current = _state.value
        val targetList = resolveWritableTargetList(current, listUrl)
            ?: return Result.failure(IllegalStateException("No writable CalDAV task list available"))

        val draft = VTodoItem(
            uid = uuidProvider(),
            href = "",
            listUrl = targetList.url,
            summary = trimmed,
            description = description.trim(),
            status = VTodoStatus.NEEDS_ACTION,
            due = due,
            priority = priority.coerceIn(0, 9),
            categories = categories.map { it.trim() }.filter { it.isNotEmpty() },
            createdAt = nowProvider(),
            lastModifiedAt = nowProvider()
        )

        return try {
            val created = calDav.createTask(current.config, targetList, draft)
            val updatedTasks = _state.value.tasks + created
            persistAndPublish(
                config = _state.value.config,
                lists = _state.value.lists,
                tasks = updatedTasks,
                syncState = SyncState.Idle,
                lastSyncedAt = _state.value.lastSyncedAt
            )
            Result.success(created)
        } catch (e: Exception) {
            val msg = friendlyError(e)
            _state.update { it.copy(syncState = SyncState.Error(msg)) }
            Result.failure(e)
        }
    }

    suspend fun toggleComplete(uid: String): Result<VTodoItem> {
        initialize()
        val current = _state.value
        val existing = current.tasks.firstOrNull { it.uid == uid }
            ?: return Result.failure(NoSuchElementException("Task not found"))

        val now = nowProvider()
        val markingCompleted = !existing.isCompleted
        val toggled = if (markingCompleted) {
            existing.copy(
                status = VTodoStatus.COMPLETED,
                completedAt = now,
                percentComplete = 100,
                lastModifiedAt = now
            )
        } else {
            existing.copy(
                status = VTodoStatus.NEEDS_ACTION,
                completedAt = null,
                percentComplete = 0,
                lastModifiedAt = now
            )
        }

        val bannerText = if (markingCompleted) {
            "✓ Completed \"${existing.summary}\""
        } else {
            "Reopened \"${existing.summary}\""
        }
        val nextRecent = if (markingCompleted) {
            current.recentlyCompletedUids + uid
        } else {
            current.recentlyCompletedUids - uid
        }

        // Optimistic update: keep completed item visible briefly with filled checkmark + strikethrough
        val optimisticTasks = current.tasks.map { if (it.uid == uid) toggled else it }
        persistAndPublish(
            config = current.config,
            lists = current.lists,
            tasks = optimisticTasks,
            syncState = current.syncState,
            lastSyncedAt = current.lastSyncedAt,
            recentlyCompletedUids = nextRecent,
            statusBanner = bannerText
        )

        val startedMs = System.currentTimeMillis()
        return try {
            val synced = calDav.updateTask(current.config, toggled)
            val confirmedTasks = _state.value.tasks.map { if (it.uid == uid) synced else it }
            persistAndPublish(
                config = _state.value.config,
                lists = _state.value.lists,
                tasks = confirmedTasks,
                syncState = SyncState.Idle,
                lastSyncedAt = _state.value.lastSyncedAt,
                recentlyCompletedUids = _state.value.recentlyCompletedUids,
                statusBanner = bannerText
            )

            // Hold the checked-off visual state briefly before clearing from the open list
            if (completionClearDelayMillis > 0L) {
                val elapsed = System.currentTimeMillis() - startedMs
                val remaining = completionClearDelayMillis - elapsed
                if (remaining > 0L) {
                    delay(remaining)
                }
            }
            val latest = _state.value
            val clearedRecent = latest.recentlyCompletedUids - uid
            val clearedBanner = if (latest.statusBanner == bannerText) null else latest.statusBanner
            if (clearedRecent != latest.recentlyCompletedUids || clearedBanner != latest.statusBanner) {
                val next = latest.copy(
                    recentlyCompletedUids = clearedRecent,
                    statusBanner = clearedBanner
                )
                _state.value = next
                widgetUpdater.notifyWidgetsUpdated(next)
            }
            Result.success(synced)
        } catch (e: Exception) {
            // Rollback optimistic change on failure
            val rolledBack = _state.value.tasks.map { if (it.uid == uid) existing else it }
            persistAndPublish(
                config = _state.value.config,
                lists = _state.value.lists,
                tasks = rolledBack,
                syncState = SyncState.Error(friendlyError(e)),
                lastSyncedAt = _state.value.lastSyncedAt,
                recentlyCompletedUids = _state.value.recentlyCompletedUids - uid,
                statusBanner = null
            )
            Result.failure(e)
        }
    }

    suspend fun updateTask(updated: VTodoItem): Result<VTodoItem> {
        initialize()
        val current = _state.value
        return try {
            val synced = calDav.updateTask(current.config, updated)
            val newTasks = _state.value.tasks.map { if (it.uid == updated.uid) synced else it }
            persistAndPublish(
                config = _state.value.config,
                lists = _state.value.lists,
                tasks = newTasks,
                syncState = SyncState.Idle,
                lastSyncedAt = _state.value.lastSyncedAt
            )
            Result.success(synced)
        } catch (e: Exception) {
            _state.update { it.copy(syncState = SyncState.Error(friendlyError(e))) }
            Result.failure(e)
        }
    }

    suspend fun deleteTask(uid: String): Result<Unit> {
        initialize()
        val current = _state.value
        val existing = current.tasks.firstOrNull { it.uid == uid }
            ?: return Result.failure(NoSuchElementException("Task not found"))

        return try {
            calDav.deleteTask(current.config, existing)
            val remaining = _state.value.tasks.filterNot { it.uid == uid }
            persistAndPublish(
                config = _state.value.config,
                lists = _state.value.lists,
                tasks = remaining,
                syncState = SyncState.Idle,
                lastSyncedAt = _state.value.lastSyncedAt
            )
            Result.success(Unit)
        } catch (e: Exception) {
            _state.update { it.copy(syncState = SyncState.Error(friendlyError(e))) }
            Result.failure(e)
        }
    }

    suspend fun selectWidgetList(listUrl: String?) {
        initialize()
        val current = _state.value
        val updatedConfig = current.config.copy(selectedListUrl = listUrl)
        persistAndPublish(
            config = updatedConfig,
            lists = current.lists,
            tasks = current.tasks,
            syncState = current.syncState,
            lastSyncedAt = current.lastSyncedAt
        )
    }

    suspend fun cycleWidgetList() {
        initialize()
        val current = _state.value
        if (current.lists.isEmpty()) return
        val options: List<String?> = listOf(null) + current.lists.map { it.url }
        val currentIdx = options.indexOf(current.config.selectedListUrl).coerceAtLeast(0)
        val nextListUrl = options[(currentIdx + 1) % options.size]
        selectWidgetList(nextListUrl)
    }

    suspend fun updatePreferences(showCompleted: Boolean? = null, syncIntervalMinutes: Int? = null) {
        initialize()
        val current = _state.value
        val updatedConfig = current.config.copy(
            showCompleted = showCompleted ?: current.config.showCompleted,
            syncIntervalMinutes = syncIntervalMinutes ?: current.config.syncIntervalMinutes
        )
        persistAndPublish(
            config = updatedConfig,
            lists = current.lists,
            tasks = current.tasks,
            syncState = current.syncState,
            lastSyncedAt = current.lastSyncedAt
        )
        if (syncIntervalMinutes != null && updatedConfig.isConfigured) {
            syncScheduler.schedulePeriodicSync(updatedConfig.syncIntervalMinutes)
        }
    }

    suspend fun disconnectServer() {
        initialize()
        syncScheduler.schedulePeriodicSync(0)
        persistAndPublish(
            config = ServerConfig(),
            lists = emptyList(),
            tasks = emptyList(),
            syncState = SyncState.Idle,
            lastSyncedAt = null
        )
    }

    fun clearError() {
        _state.update { if (it.syncState is SyncState.Error) it.copy(syncState = SyncState.Idle) else it }
    }

    private suspend fun fetchAllTasksForLists(
        config: ServerConfig,
        lists: List<TaskList>
    ): List<VTodoItem> = coroutineScope {
        lists.map { list ->
            async { calDav.fetchTasks(config, list) }
        }.awaitAll().flatten()
    }

    private fun resolveWritableTargetList(state: VTodoStoreState, requestedListUrl: String?): TaskList? {
        val candidates = listOfNotNull(
            requestedListUrl?.let { url -> state.lists.firstOrNull { it.url == url } },
            state.config.selectedListUrl?.let { url -> state.lists.firstOrNull { it.url == url } },
            state.lists.firstOrNull { !it.readOnly },
            state.lists.firstOrNull()
        )
        return candidates.firstOrNull { !it.readOnly }
    }

    private suspend fun persistAndPublish(
        config: ServerConfig,
        lists: List<TaskList>,
        tasks: List<VTodoItem>,
        syncState: SyncState,
        lastSyncedAt: Instant?,
        recentlyCompletedUids: Set<String> = _state.value.recentlyCompletedUids,
        statusBanner: String? = _state.value.statusBanner
    ) {
        localStore.saveSnapshot(config, lists, tasks, lastSyncedAt)
        val next = VTodoStoreState(
            config = config,
            lists = lists,
            tasks = tasks,
            syncState = syncState,
            lastSyncedAt = lastSyncedAt,
            recentlyCompletedUids = recentlyCompletedUids,
            statusBanner = statusBanner
        )
        _state.value = next
        widgetUpdater.notifyWidgetsUpdated(next)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is CalDavException -> e.message ?: "CalDAV sync failed"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Unexpected sync error"
    }

    companion object {
        const val DEFAULT_COMPLETION_HOLD_MS = 2_000L
    }
}

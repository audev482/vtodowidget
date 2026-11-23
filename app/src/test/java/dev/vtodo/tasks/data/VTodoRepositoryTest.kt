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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

class VTodoRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCalDavService : CalDavService {
        var lists: List<TaskList> = emptyList()
        var tasksByList: MutableMap<String, MutableList<VTodoItem>> = mutableMapOf()
        var failNextUpdate: Boolean = false
        var failNextSync: Boolean = false

        override suspend fun discoverTaskLists(config: ServerConfig): List<TaskList> {
            if (failNextSync) throw CalDavException(401, "Invalid credentials")
            return lists
        }

        override suspend fun fetchTasks(config: ServerConfig, list: TaskList): List<VTodoItem> {
            return tasksByList[list.url]?.toList().orEmpty()
        }

        override suspend fun createTask(config: ServerConfig, list: TaskList, item: VTodoItem): VTodoItem {
            val created = item.copy(
                href = "${list.url}${item.uid}.ics",
                etag = "\"etag-new\"",
                rawIcs = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"
            )
            tasksByList.getOrPut(list.url) { mutableListOf() }.add(created)
            return created
        }

        override suspend fun updateTask(config: ServerConfig, item: VTodoItem): VTodoItem {
            if (failNextUpdate) throw CalDavException(500, "Server offline")
            val updated = item.copy(etag = "\"etag-updated\"")
            tasksByList[item.listUrl]?.replaceAll { if (it.uid == item.uid) updated else it }
            return updated
        }

        override suspend fun deleteTask(config: ServerConfig, item: VTodoItem) {
            tasksByList[item.listUrl]?.removeAll { it.uid == item.uid }
        }
    }

    private class RecordingWidgetUpdater : WidgetUpdaterPort {
        val snapshots = mutableListOf<VTodoStoreState>()
        override fun notifyWidgetsUpdated(state: VTodoStoreState) {
            snapshots += state
        }
    }

    private class RecordingSyncScheduler : SyncSchedulerPort {
        var lastInterval: Int? = null
        override fun schedulePeriodicSync(intervalMinutes: Int) {
            lastInterval = intervalMinutes
        }
    }

    @Test
    fun saveServerAndSync_persistsEncryptedSnapshotAndNotifiesWidgets() = runTest {
        val file = File(tempFolder.root, "state.json")
        val cipher = object : CredentialCipher {
            override fun encrypt(plain: String): String = "ENC:$plain"
            override fun decrypt(encoded: String): String = encoded.removePrefix("ENC:")
        }
        val store = JsonVTodoLocalStore(file, cipher)
        val calDav = FakeCalDavService().apply {
            lists = listOf(
                TaskList("https://cloud.test/dav/work/", "Work", "#2563EB"),
                TaskList("https://cloud.test/dav/home/", "Home", "#059669")
            )
            tasksByList["https://cloud.test/dav/work/"] = mutableListOf(
                VTodoItem(
                    uid = "u1",
                    href = "https://cloud.test/dav/work/u1.ics",
                    etag = "\"e1\"",
                    listUrl = "https://cloud.test/dav/work/",
                    summary = "Prepare roadmap",
                    priority = 1,
                    due = DueSpec(LocalDate.of(2026, 11, 22))
                )
            )
        }
        val widgetUpdater = RecordingWidgetUpdater()
        val scheduler = RecordingSyncScheduler()
        val fixedNow = Instant.parse("2026-11-21T22:00:00Z")

        val repo = VTodoRepository(
            calDav = calDav,
            localStore = store,
            widgetUpdater = widgetUpdater,
            syncScheduler = scheduler,
            nowProvider = { fixedNow }
        )

        val config = ServerConfig(
            serverUrl = "https://cloud.test",
            username = "alice",
            password = "my-secret-app-password",
            syncIntervalMinutes = 30
        )

        val result = repo.saveServerAndSync(config)
        assertTrue(result.isSuccess)
        assertEquals(2, repo.state.value.lists.size)
        assertEquals(1, repo.state.value.tasks.size)
        assertEquals(30, scheduler.lastInterval)
        assertTrue(widgetUpdater.snapshots.isNotEmpty())

        // Verify password is encrypted on disk, not stored in plaintext
        val rawFile = file.readText()
        assertTrue(rawFile.contains("ENC:my-secret-app-password"))

        // Verify cold reload restores full snapshot
        val reloaded = store.loadSnapshot()
        assertEquals("my-secret-app-password", reloaded.config.password)
        assertEquals(2, reloaded.lists.size)
        assertEquals("Prepare roadmap", reloaded.tasks[0].summary)
    }

    @Test
    fun toggleComplete_optimisticallyUpdatesAndRollsBackOnServerFailure() = runTest {
        val store = JsonVTodoLocalStore(File(tempFolder.root, "state2.json"), PassthroughCredentialCipher())
        val calDav = FakeCalDavService().apply {
            lists = listOf(TaskList("https://cloud.test/dav/work/", "Work"))
            tasksByList["https://cloud.test/dav/work/"] = mutableListOf(
                VTodoItem(
                    uid = "u1",
                    href = "https://cloud.test/dav/work/u1.ics",
                    etag = "\"e1\"",
                    listUrl = "https://cloud.test/dav/work/",
                    summary = "Ship widget"
                )
            )
        }
        val widgetUpdater = RecordingWidgetUpdater()
        val repo = VTodoRepository(calDav, store, widgetUpdater, completionClearDelayMillis = 0L)
        repo.saveServerAndSync(ServerConfig(serverUrl = "https://cloud.test", username = "a", password = "p"))

        // Successful toggle -> Completed (emits intermediate visual-confirmation state with recentlyCompletedUids + statusBanner)
        val ok = repo.toggleComplete("u1")
        assertTrue(ok.isSuccess)
        assertTrue(repo.state.value.tasks.first().isCompleted)
        assertEquals(100, repo.state.value.tasks.first().percentComplete)
        assertEquals("\"etag-updated\"", repo.state.value.tasks.first().etag)
        assertTrue(
            "Widget must receive visual confirmation snapshot before clearing",
            widgetUpdater.snapshots.any { snap ->
                "u1" in snap.recentlyCompletedUids &&
                    snap.statusBanner == "✓ Completed \"Ship widget\"" &&
                    TaskQuery.tasksForWidget(snap.tasks, null, showCompleted = false, snap.recentlyCompletedUids)
                        .any { it.uid == "u1" && it.isCompleted }
            }
        )
        assertFalse("Recently completed UID must clear after grace period", "u1" in repo.state.value.recentlyCompletedUids)
        assertNull("Status banner must clear after grace period", repo.state.value.statusBanner)

        // Failing toggle -> Rolls back to Completed and exposes Error state
        calDav.failNextUpdate = true
        val failed = repo.toggleComplete("u1")
        assertTrue(failed.isFailure)
        assertTrue("Failed toggle must rollback task status", repo.state.value.tasks.first().isCompleted)
        assertTrue(repo.state.value.syncState is SyncState.Error)
    }

    @Test
    fun cycleWidgetList_cyclesAllThenEachListAndBackToAll() = runTest {
        val store = JsonVTodoLocalStore(File(tempFolder.root, "state3.json"), PassthroughCredentialCipher())
        val calDav = FakeCalDavService().apply {
            lists = listOf(
                TaskList("https://cloud.test/dav/work/", "Work"),
                TaskList("https://cloud.test/dav/home/", "Home")
            )
        }
        val repo = VTodoRepository(calDav, store)
        repo.saveServerAndSync(ServerConfig(serverUrl = "https://cloud.test", username = "a", password = "p"))

        assertNull(repo.state.value.config.selectedListUrl)
        repo.cycleWidgetList()
        assertEquals("https://cloud.test/dav/work/", repo.state.value.config.selectedListUrl)
        repo.cycleWidgetList()
        assertEquals("https://cloud.test/dav/home/", repo.state.value.config.selectedListUrl)
        repo.cycleWidgetList()
        assertNull(repo.state.value.config.selectedListUrl)
    }

    @Test
    fun taskQuery_sortsPinnedDuePriorityAndFiltersToday() {
        val today = LocalDate.of(2026, 11, 21)
        val now = Instant.parse("2026-11-21T15:00:00Z")

        val lowLater = VTodoItem("1", "/1", listUrl = "/l", summary = "Later", due = DueSpec(today.plusDays(3)), priority = 9)
        val dueTodayHigh = VTodoItem("2", "/2", listUrl = "/l", summary = "Today High", due = DueSpec(today), priority = 1)
        val pinned = VTodoItem("3", "/3", listUrl = "/l", summary = "Pinned Note", pinned = true)
        val overdue = VTodoItem("4", "/4", listUrl = "/l", summary = "Overdue", due = DueSpec(today.minusDays(1)))
        val done = VTodoItem("5", "/5", listUrl = "/l", summary = "Done", status = VTodoStatus.COMPLETED, completedAt = now)

        val allOpen = TaskQuery.filterAndSort(
            tasks = listOf(lowLater, dueTodayHigh, pinned, overdue, done),
            filter = TaskViewFilter.AllOpen,
            today = today,
            now = now
        )
        assertEquals(listOf("3", "4", "2", "1"), allOpen.map { it.uid })

        val todayItems = TaskQuery.filterAndSort(
            tasks = listOf(lowLater, dueTodayHigh, pinned, overdue, done),
            filter = TaskViewFilter.Today,
            today = today,
            now = now
        )
        assertEquals(listOf("4", "2"), todayItems.map { it.uid })
        assertFalse(todayItems.any { it.isCompleted })
    }
}

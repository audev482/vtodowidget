package dev.vtodo.tasks.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.vtodo.tasks.R
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.data.JsonVTodoLocalStore
import dev.vtodo.tasks.data.NoOpSyncScheduler
import dev.vtodo.tasks.data.PassthroughCredentialCipher
import dev.vtodo.tasks.data.WidgetUpdaterPort
import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.SyncState
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStoreState
import dev.vtodo.tasks.protocol.CalDavService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VTodoApp::class)
class VTodoWidgetProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: VTodoApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun allThreeWidgetBuckets_inflateCleanlyInUnconfiguredPopulatedAndErrorStates() {
        val parent = FrameLayout(app)
        val today = LocalDate.of(2026, 11, 21)
        val now = Instant.parse("2026-11-21T16:00:00Z")

        val unconfigured = VTodoStoreState()
        val populated = VTodoStoreState(
            config = ServerConfig(serverUrl = "https://cloud.example.com", username = "alice", password = "pw"),
            lists = listOf(
                TaskList("https://cloud.example.com/dav/work/", "Work", "#1D4ED8"),
                TaskList("https://cloud.example.com/dav/home/", "Home", "#059669")
            ),
            tasks = listOf(
                VTodoItem(
                    uid = "t1",
                    href = "https://cloud.example.com/dav/work/t1.ics",
                    listUrl = "https://cloud.example.com/dav/work/",
                    summary = "Finish RFC 4791 widget",
                    priority = 1,
                    due = DueSpec(today)
                ),
                VTodoItem(
                    uid = "t2",
                    href = "https://cloud.example.com/dav/home/t2.ics",
                    listUrl = "https://cloud.example.com/dav/home/",
                    summary = "Overdue errand",
                    due = DueSpec(today.minusDays(1))
                )
            ),
            lastSyncedAt = now
        )
        val errored = populated.copy(syncState = SyncState.Error("Authentication failed (401)"))

        for (bucket in VTodoWidgetProvider.WidgetSizeBucket.entries) {
            val unconfView = VTodoWidgetProvider
                .buildBucketRemoteViews(app, unconfigured, bucket, today, now)
                .apply(app, parent)
            assertNotNull(unconfView)

            val popView = VTodoWidgetProvider
                .buildBucketRemoteViews(app, populated, bucket, today, now)
                .apply(app, parent)
            assertNotNull(popView)

            if (bucket == VTodoWidgetProvider.WidgetSizeBucket.COMPACT_1X1) {
                val countText = popView.findViewById<TextView>(R.id.txt_compact_count)
                assertEquals("2", countText.text.toString())
            } else {
                val countPill = popView.findViewById<TextView>(R.id.txt_widget_count_pill)
                assertEquals("2", countPill.text.toString())
                assertEquals(View.VISIBLE, popView.findViewById<View>(R.id.widget_task_list).visibility)
                assertEquals(View.GONE, popView.findViewById<View>(R.id.widget_empty_view).visibility)
            }

            if (bucket == VTodoWidgetProvider.WidgetSizeBucket.EXPANDED_3X3) {
                val errView = VTodoWidgetProvider
                    .buildBucketRemoteViews(app, errored, bucket, today, now)
                    .apply(app, parent)
                val footer = errView.findViewById<TextView>(R.id.txt_widget_footer)
                assertTrue(footer.text.toString().contains("401"))

                val completedBannerState = populated.copy(
                    tasks = populated.tasks.map {
                        if (it.uid == "t1") it.copy(completedAt = now) else it
                    },
                    recentlyCompletedUids = setOf("t1"),
                    statusBanner = "✓ Completed \"Finish RFC 4791 widget\""
                )
                val bannerView = VTodoWidgetProvider
                    .buildBucketRemoteViews(app, completedBannerState, bucket, today, now)
                    .apply(app, parent)
                val bannerFooter = bannerView.findViewById<TextView>(R.id.txt_widget_footer)
                assertEquals("✓ Completed \"Finish RFC 4791 widget\"", bannerFooter.text.toString())
            }
        }
    }

    @Test
    fun widgetActionReceiver_togglesTaskCyclesListAndOpensTask() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val workList = TaskList("https://cloud.test/dav/work/", "Work", "#2563EB")
        val homeList = TaskList("https://cloud.test/dav/home/", "Home", "#059669")
        val task = VTodoItem(
            uid = "task-1",
            href = "https://cloud.test/dav/work/task-1.ics",
            etag = "\"e1\"",
            listUrl = workList.url,
            summary = "Test checkbox from widget"
        )
        val fakeCalDav = object : CalDavService {
            override suspend fun discoverTaskLists(config: ServerConfig) = listOf(workList, homeList)
            override suspend fun fetchTasks(config: ServerConfig, list: TaskList) =
                if (list.url == workList.url) listOf(task) else emptyList()
            override suspend fun createTask(config: ServerConfig, list: TaskList, item: VTodoItem) = item
            override suspend fun updateTask(config: ServerConfig, item: VTodoItem) = item.copy(etag = "\"e2\"")
            override suspend fun deleteTask(config: ServerConfig, item: VTodoItem) = Unit
        }
        var lastWidgetState: VTodoStoreState? = null
        val store = JsonVTodoLocalStore(
            File(tempFolder.root, "widget_test.json"),
            PassthroughCredentialCipher(),
            ioDispatcher = dispatcher
        )
        app.configureForTesting(
            calDav = fakeCalDav,
            localStore = store,
            widgetUpdater = object : WidgetUpdaterPort {
                override fun notifyWidgetsUpdated(state: VTodoStoreState) {
                    lastWidgetState = state
                }
            },
            syncScheduler = NoOpSyncScheduler,
            scope = backgroundScope
        )
        app.repository.saveServerAndSync(ServerConfig(serverUrl = "https://cloud.test", username = "u", password = "p"))

        val receiver = VTodoWidgetActionReceiver()

        // 1. Toggle checkbox from widget row
        receiver.onReceive(
            app,
            Intent(app, VTodoWidgetActionReceiver::class.java).apply {
                action = VTodoWidgetProvider.ACTION_WIDGET_ROW_CLICK
                putExtra(VTodoWidgetProvider.EXTRA_ROW_ACTION, VTodoWidgetProvider.ROW_ACTION_TOGGLE)
                putExtra(VTodoWidgetProvider.EXTRA_TASK_UID, "task-1")
            }
        )
        assertTrue(app.repository.state.value.tasks.first().isCompleted)
        assertTrue(lastWidgetState!!.tasks.first().isCompleted)

        // 2. Cycle list from All Tasks -> Work -> Home -> All Tasks
        assertNull(app.repository.state.value.config.selectedListUrl)
        receiver.onReceive(
            app,
            Intent(app, VTodoWidgetActionReceiver::class.java).apply {
                action = VTodoWidgetProvider.ACTION_WIDGET_CYCLE_LIST
            }
        )
        assertEquals(workList.url, app.repository.state.value.config.selectedListUrl)

        // 3. Tap row body opens MainActivity with EXTRA_TASK_UID
        receiver.onReceive(
            app,
            Intent(app, VTodoWidgetActionReceiver::class.java).apply {
                action = VTodoWidgetProvider.ACTION_WIDGET_ROW_CLICK
                putExtra(VTodoWidgetProvider.EXTRA_ROW_ACTION, VTodoWidgetProvider.ROW_ACTION_OPEN)
                putExtra(VTodoWidgetProvider.EXTRA_TASK_UID, "task-1")
            }
        )
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals("task-1", started.getStringExtra(VTodoWidgetProvider.EXTRA_TASK_UID))
    }

    @Test
    fun widgetProviderInfoXml_definesResizable3x3DefaultDownTo1x1() {
        val parser = app.resources.getXml(R.xml.vtodo_widget_info)
        val androidNs = "http://schemas.android.com/apk/res/android"
        var found = false
        var targetW = 0
        var targetH = 0
        var resizeMode = 0
        var minWidth = ""
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                found = true
                targetW = parser.getAttributeIntValue(androidNs, "targetCellWidth", 0)
                targetH = parser.getAttributeIntValue(androidNs, "targetCellHeight", 0)
                resizeMode = parser.getAttributeIntValue(androidNs, "resizeMode", 0)
                minWidth = parser.getAttributeValue(androidNs, "minWidth") ?: ""
            }
        }
        assertTrue(found)
        assertEquals(3, targetW)
        assertEquals(3, targetH)
        assertEquals("56.0dip", minWidth)
        assertEquals(
            AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL,
            resizeMode
        )
    }
}

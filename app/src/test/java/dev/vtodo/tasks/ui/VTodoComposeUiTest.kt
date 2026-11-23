package dev.vtodo.tasks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.data.JsonVTodoLocalStore
import dev.vtodo.tasks.data.NoOpSyncScheduler
import dev.vtodo.tasks.data.NoOpWidgetUpdater
import dev.vtodo.tasks.data.PassthroughCredentialCipher
import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.protocol.CalDavService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h720dp", application = VTodoApp::class)
class VTodoComposeUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun configureNextcloudServer_andQuickAddAndCompleteTask_endToEnd() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val app = ApplicationProvider.getApplicationContext<VTodoApp>()
        val personalList = TaskList("https://cloud.test/remote.php/dav/calendars/alice/personal/", "Personal", "#1D4ED8")
        val remoteTasks = mutableListOf(
            VTodoItem(
                uid = "existing-1",
                href = "${personalList.url}existing-1.ics",
                etag = "\"e1\"",
                listUrl = personalList.url,
                summary = "Synced from Nextcloud"
            )
        )
        val fakeCalDav = object : CalDavService {
            override suspend fun discoverTaskLists(config: ServerConfig) = listOf(personalList)
            override suspend fun fetchTasks(config: ServerConfig, list: TaskList) = remoteTasks.toList()
            override suspend fun createTask(config: ServerConfig, list: TaskList, item: VTodoItem): VTodoItem {
                val created = item.copy(href = "${list.url}${item.uid}.ics", etag = "\"e-new\"")
                remoteTasks += created
                return created
            }
            override suspend fun updateTask(config: ServerConfig, item: VTodoItem): VTodoItem {
                remoteTasks.replaceAll { if (it.uid == item.uid) item else it }
                return item
            }
            override suspend fun deleteTask(config: ServerConfig, item: VTodoItem) {
                remoteTasks.removeAll { it.uid == item.uid }
            }
        }

        val store = JsonVTodoLocalStore(
            File(tempFolder.root, "ui_test.json"),
            PassthroughCredentialCipher(),
            ioDispatcher = dispatcher
        )
        app.configureForTesting(
            calDav = fakeCalDav,
            localStore = store,
            widgetUpdater = NoOpWidgetUpdater,
            syncScheduler = NoOpSyncScheduler,
            scope = backgroundScope
        )

        Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // Initially shows server configuration screen
        composeRule.onNodeWithTag("screen_server_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("input_server_url").performTextInput("https://cloud.test")
        composeRule.onNodeWithTag("input_username").performTextInput("alice")
        composeRule.onNodeWithTag("input_password").performTextInput("app-pass-xyz")

        // Save & Sync connects and transitions to Task Workspace
        composeRule.onNodeWithTag("btn_save_server").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Synced from Nextcloud").assertIsDisplayed()

        // Quick-add a new VTODO right from the inline bar
        composeRule.onNodeWithTag("input_quick_add").performTextInput("Buy espresso beans")
        composeRule.onNodeWithTag("btn_quick_add_submit").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Buy espresso beans").assertIsDisplayed()
        assertEquals(2, app.repository.state.value.tasks.size)

        // Check off the synced task
        composeRule.onNodeWithTag("task_check_existing-1").performClick()
        composeRule.waitForIdle()

        assertTrue(app.repository.state.value.tasks.first { it.uid == "existing-1" }.isCompleted)
    }
}

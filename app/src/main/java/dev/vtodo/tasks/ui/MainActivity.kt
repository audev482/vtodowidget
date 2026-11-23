package dev.vtodo.tasks.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.widget.VTodoWidgetProvider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var openSettingsRequest by mutableStateOf(false)
    private var openTaskUidRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleLaunchIntent(intent)
        val app = applicationContext as VTodoApp

        setContent {
            val state by app.repository.state.collectAsState()

            LaunchedEffect(Unit) {
                app.repository.initialize()
                if (app.repository.state.value.config.isConfigured &&
                    app.repository.state.value.tasks.isEmpty()
                ) {
                    app.repository.syncAll()
                }
            }

            VTodoTheme {
                VTodoMainScreen(
                    state = state,
                    initialOpenSettings = openSettingsRequest,
                    initialTaskUid = openTaskUidRequest,
                    onSyncNow = {
                        app.appScope.launch { app.repository.syncAll() }
                    },
                    onCreateTask = { summary, listUrl, description, due, priority, categories ->
                        app.repository.createTask(
                            summary = summary,
                            listUrl = listUrl,
                            description = description,
                            due = due,
                            priority = priority,
                            categories = categories
                        )
                    },
                    onToggleComplete = { uid ->
                        app.appScope.launch { app.repository.toggleComplete(uid) }
                    },
                    onUpdateTask = { updated ->
                        app.repository.updateTask(updated)
                    },
                    onDeleteTask = { uid ->
                        app.repository.deleteTask(uid)
                    },
                    onTestConnection = { config ->
                        app.repository.testConnection(config)
                    },
                    onSaveServer = { config ->
                        app.repository.saveServerAndSync(config)
                    },
                    onUpdatePreferences = { showCompleted, syncInterval, widgetListUrl, updateWidgetList ->
                        app.appScope.launch {
                            if (showCompleted != null || syncInterval != null) {
                                app.repository.updatePreferences(
                                    showCompleted = showCompleted,
                                    syncIntervalMinutes = syncInterval
                                )
                            }
                            if (updateWidgetList) {
                                app.repository.selectWidgetList(widgetListUrl)
                            }
                        }
                    },
                    onDisconnectServer = {
                        app.appScope.launch { app.repository.disconnectServer() }
                    },
                    onClearError = {
                        app.repository.clearError()
                    },
                    onPinWidget = {
                        requestPinVTodoWidget()
                    }
                )
            }
        }

        enableSeamlessDesktopWindowHeader()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(VTodoWidgetProvider.EXTRA_OPEN_SETTINGS, false)) {
            openSettingsRequest = true
        }
        val uid = intent.getStringExtra(VTodoWidgetProvider.EXTRA_TASK_UID)
        if (!uid.isNullOrBlank()) {
            openTaskUidRequest = uid
        }
    }

    private fun enableSeamlessDesktopWindowHeader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val controller = window.decorView.windowInsetsController ?: window.insetsController ?: return
            val clearCaption = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
            val lightCaption = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
            val appearance = if (!isNight) clearCaption or lightCaption else clearCaption
            controller.setSystemBarsAppearance(appearance, clearCaption or lightCaption)
        }
    }

    private fun requestPinVTodoWidget() {
        runCatching {
            val manager = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, VTodoWidgetProvider::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(provider, null, null)
            } else {
                Toast.makeText(
                    this,
                    "Long-press the desktop to add the VTodo Tasks widget.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

package dev.vtodo.tasks.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.ui.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Non-exported internal receiver for VTodo desktop widget interactions.
 * Keeps custom widget mutations unreachable from third-party apps.
 */
class VTodoWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? VTodoApp ?: return
        val action = intent.action ?: return

        when (action) {
            VTodoWidgetProvider.ACTION_WIDGET_ROW_CLICK -> {
                val rowAction = intent.getStringExtra(VTodoWidgetProvider.EXTRA_ROW_ACTION)
                val taskUid = intent.getStringExtra(VTodoWidgetProvider.EXTRA_TASK_UID)
                if (taskUid.isNullOrBlank()) return

                when (rowAction) {
                    VTodoWidgetProvider.ROW_ACTION_TOGGLE -> {
                        val pendingResult = goAsync()
                        app.appScope.launch {
                            try {
                                withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                                    app.repository.toggleComplete(taskUid)
                                }
                            } finally {
                                pendingResult?.finish()
                            }
                        }
                    }
                    VTodoWidgetProvider.ROW_ACTION_OPEN -> {
                        val openIntent = Intent(context, MainActivity::class.java).apply {
                            putExtra(VTodoWidgetProvider.EXTRA_TASK_UID, taskUid)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(openIntent)
                    }
                }
            }
            VTodoWidgetProvider.ACTION_WIDGET_SYNC -> {
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                            app.repository.syncAll()
                        }
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
            VTodoWidgetProvider.ACTION_WIDGET_CYCLE_LIST -> {
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                            app.repository.cycleWidgetList()
                        }
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val ACTION_TIMEOUT_MS = 10_000L
        const val SYNC_TIMEOUT_MS = 20_000L
    }
}

package dev.vtodo.tasks.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.vtodo.tasks.VTodoApp

/**
 * Lightweight floating dialog activity launched from the widget's '+' button
 * so users can add a CalDAV VTODO directly from the Googlebook desktop.
 */
class QuickAddActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as VTodoApp

        setContent {
            val state by app.repository.state.collectAsState()

            LaunchedEffect(Unit) {
                app.repository.initialize()
            }

            VTodoTheme {
                QuickAddDialogContent(
                    lists = state.lists,
                    initialSelectedListUrl = state.config.selectedListUrl,
                    onDismiss = { finish() },
                    onCreate = { summary, listUrl, description, due, priority ->
                        app.repository.createTask(
                            summary = summary,
                            listUrl = listUrl,
                            description = description,
                            due = due,
                            priority = priority
                        )
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

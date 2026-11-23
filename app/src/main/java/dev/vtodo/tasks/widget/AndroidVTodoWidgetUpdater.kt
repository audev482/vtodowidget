package dev.vtodo.tasks.widget

import android.content.Context
import dev.vtodo.tasks.data.WidgetUpdaterPort
import dev.vtodo.tasks.domain.VTodoStoreState

class AndroidVTodoWidgetUpdater(
    private val context: Context
) : WidgetUpdaterPort {
    override fun notifyWidgetsUpdated(state: VTodoStoreState) {
        VTodoWidgetProvider.updateAllWidgets(context, state)
    }
}

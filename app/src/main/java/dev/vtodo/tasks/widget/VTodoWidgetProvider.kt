package dev.vtodo.tasks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import dev.vtodo.tasks.R
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.data.TaskQuery
import dev.vtodo.tasks.domain.SyncState
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoPriority
import dev.vtodo.tasks.domain.VTodoStoreState
import dev.vtodo.tasks.ui.MainActivity
import dev.vtodo.tasks.ui.QuickAddActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Responsive Googlebook Desktop VTODO Widget (RFC 4791 / RFC 5545).
 * - Compact 1x1 (`SizeF(56f, 56f)`): Squircle badge showing open task count.
 * - Medium 2x2 (`SizeF(180f, 110f)`): Focused scrollable task list with list switcher, sync, and quick-add.
 * - Expanded 3x3+ (`SizeF(250f, 220f)`): Full desktop todo replacement with due chips, priority bars,
 *   list switching, interactive checkboxes, quick-add, and sync status footer.
 */
class VTodoWidgetProvider : AppWidgetProvider() {

    enum class WidgetSizeBucket(@param:LayoutRes val layoutRes: Int) {
        COMPACT_1X1(R.layout.widget_vtodo_compact),
        MEDIUM_2X2(R.layout.widget_vtodo_medium),
        EXPANDED_3X3(R.layout.widget_vtodo_expanded)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val app = context.applicationContext as? VTodoApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS) {
                    app.repository.initialize()
                    val state = app.repository.state.value
                    for (widgetId in appWidgetIds) {
                        appWidgetManager.updateAppWidget(
                            widgetId,
                            buildResponsiveRemoteViews(context, state)
                        )
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val app = context.applicationContext as? VTodoApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS) {
                    app.repository.initialize()
                    val state = app.repository.state.value
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        buildResponsiveRemoteViews(context, state)
                    )
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_ROW_CLICK = "dev.vtodo.tasks.action.WIDGET_ROW_CLICK"
        const val ACTION_WIDGET_SYNC = "dev.vtodo.tasks.action.WIDGET_SYNC"
        const val ACTION_WIDGET_CYCLE_LIST = "dev.vtodo.tasks.action.WIDGET_CYCLE_LIST"

        const val EXTRA_ROW_ACTION = "extra_row_action"
        const val EXTRA_TASK_UID = "extra_task_uid"
        const val EXTRA_OPEN_SETTINGS = "extra_open_settings"

        const val ROW_ACTION_TOGGLE = "toggle"
        const val ROW_ACTION_OPEN = "open"

        private const val WIDGET_UPDATE_TIMEOUT_MS = 8_000L
        private const val MAX_WIDGET_ITEMS = 60

        private const val RC_OPEN_APP = 1001
        private const val RC_OPEN_SETTINGS = 1002
        private const val RC_QUICK_ADD = 1003
        private const val RC_SYNC = 1004
        private const val RC_CYCLE_LIST = 1005
        private const val RC_ROW_TEMPLATE = 1006

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun updateAllWidgets(context: Context, state: VTodoStoreState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, VTodoWidgetProvider::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val remoteViews = buildResponsiveRemoteViews(context, state)
            for (widgetId in ids) {
                manager.updateAppWidget(widgetId, remoteViews)
            }
        }

        internal fun buildResponsiveRemoteViews(
            context: Context,
            state: VTodoStoreState,
            today: LocalDate = LocalDate.now(),
            now: Instant = Instant.now()
        ): RemoteViews {
            val compact = buildBucketRemoteViews(context, state, WidgetSizeBucket.COMPACT_1X1, today, now)
            val medium = buildBucketRemoteViews(context, state, WidgetSizeBucket.MEDIUM_2X2, today, now)
            val expanded = buildBucketRemoteViews(context, state, WidgetSizeBucket.EXPANDED_3X3, today, now)
            return RemoteViews(
                mapOf(
                    SizeF(56f, 56f) to compact,
                    SizeF(180f, 110f) to medium,
                    SizeF(250f, 220f) to expanded
                )
            )
        }

        internal fun buildBucketRemoteViews(
            context: Context,
            state: VTodoStoreState,
            bucket: WidgetSizeBucket,
            today: LocalDate = LocalDate.now(),
            now: Instant = Instant.now()
        ): RemoteViews {
            val views = RemoteViews(context.packageName, bucket.layoutRes)
            val selectedList = state.config.selectedListUrl?.let { url ->
                state.lists.firstOrNull { it.url == url }
            }
            val openCount = TaskQuery.openCount(state.tasks, selectedList?.url)

            if (bucket == WidgetSizeBucket.COMPACT_1X1) {
                if (!state.config.isConfigured) {
                    views.setTextViewText(R.id.txt_compact_count, "Setup")
                    views.setTextViewText(R.id.txt_compact_label, context.getString(R.string.app_name))
                    views.setOnClickPendingIntent(
                        R.id.widget_compact_root,
                        createOpenAppPendingIntent(context, openSettings = true)
                    )
                } else {
                    views.setTextViewText(R.id.txt_compact_count, openCount.toString())
                    views.setTextViewText(
                        R.id.txt_compact_label,
                        selectedList?.displayName ?: context.getString(R.string.widget_all_tasks)
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_compact_root,
                        createOpenAppPendingIntent(context, openSettings = false)
                    )
                }
                return views
            }

            // Header bindings (Medium & Expanded)
            val headerTitle = selectedList?.displayName ?: context.getString(R.string.widget_all_tasks)
            views.setTextViewText(R.id.txt_widget_list_title, headerTitle)
            views.setTextViewText(R.id.txt_widget_count_pill, openCount.toString())

            val dotColor = parseColorOrDefault(
                selectedList?.colorHex,
                ContextCompat.getColor(context, R.color.accent_primary)
            )
            views.setInt(R.id.img_widget_list_dot, "setColorFilter", dotColor)

            views.setOnClickPendingIntent(R.id.btn_widget_switch_list, createCycleListPendingIntent(context))
            views.setOnClickPendingIntent(R.id.btn_widget_refresh, createSyncPendingIntent(context))
            views.setOnClickPendingIntent(
                R.id.btn_widget_add,
                if (state.config.isConfigured) {
                    createQuickAddPendingIntent(context)
                } else {
                    createOpenAppPendingIntent(context, openSettings = true)
                }
            )

            if (bucket == WidgetSizeBucket.EXPANDED_3X3) {
                views.setOnClickPendingIntent(
                    R.id.btn_widget_open_app,
                    createOpenAppPendingIntent(context, openSettings = false)
                )
                bindFooter(context, views, state)
            }

            // Task List Items via API 31 RemoteCollectionItems
            val visibleTasks = TaskQuery.tasksForWidget(
                tasks = state.tasks,
                selectedListUrl = selectedList?.url,
                showCompleted = state.config.showCompleted,
                recentlyCompletedUids = state.recentlyCompletedUids
            ).take(MAX_WIDGET_ITEMS)

            if (!state.config.isConfigured || visibleTasks.isEmpty()) {
                views.setViewVisibility(R.id.widget_task_list, View.GONE)
                views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                if (!state.config.isConfigured) {
                    views.setTextViewText(R.id.txt_empty_title, context.getString(R.string.widget_empty_unconfigured_title))
                    views.setTextViewText(R.id.txt_empty_subtitle, context.getString(R.string.widget_empty_unconfigured_subtitle))
                    views.setOnClickPendingIntent(
                        R.id.widget_empty_view,
                        createOpenAppPendingIntent(context, openSettings = true)
                    )
                } else {
                    views.setTextViewText(R.id.txt_empty_title, context.getString(R.string.widget_empty_done_title))
                    views.setTextViewText(R.id.txt_empty_subtitle, context.getString(R.string.widget_empty_done_subtitle))
                    views.setOnClickPendingIntent(
                        R.id.widget_empty_view,
                        createQuickAddPendingIntent(context)
                    )
                }
            } else {
                views.setViewVisibility(R.id.widget_empty_view, View.GONE)
                views.setViewVisibility(R.id.widget_task_list, View.VISIBLE)

                val listNameByUrl = state.lists.associateBy({ it.url }, { it.displayName })
                val showListName = selectedList == null && state.lists.size > 1
                val collectionBuilder = RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(1)

                visibleTasks.forEachIndexed { index, task ->
                    val rowView = buildTaskRowRemoteViews(
                        context = context,
                        task = task,
                        listName = if (showListName) listNameByUrl[task.listUrl] else null,
                        today = today,
                        now = now
                    )
                    collectionBuilder.addItem(task.uid.hashCode().toLong() xor index.toLong(), rowView)
                }

                views.setRemoteAdapter(R.id.widget_task_list, collectionBuilder.build())
                views.setPendingIntentTemplate(R.id.widget_task_list, createRowTemplatePendingIntent(context))
            }

            return views
        }

        private fun buildTaskRowRemoteViews(
            context: Context,
            task: VTodoItem,
            listName: String?,
            today: LocalDate,
            now: Instant
        ): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_vtodo_row)

            // Distinct tinted row background when completed for unmistakable visual confirmation
            row.setInt(
                R.id.widget_row_root,
                "setBackgroundResource",
                if (task.isCompleted) R.drawable.bg_widget_row_completed else R.drawable.bg_widget_row
            )

            // Priority strip
            when (task.priorityLevel) {
                VTodoPriority.HIGH -> {
                    row.setViewVisibility(R.id.img_row_priority_bar, View.VISIBLE)
                    row.setImageViewResource(R.id.img_row_priority_bar, R.drawable.bg_priority_high)
                }
                VTodoPriority.MEDIUM -> {
                    row.setViewVisibility(R.id.img_row_priority_bar, View.VISIBLE)
                    row.setImageViewResource(R.id.img_row_priority_bar, R.drawable.bg_priority_medium)
                }
                VTodoPriority.LOW -> {
                    row.setViewVisibility(R.id.img_row_priority_bar, View.VISIBLE)
                    row.setImageViewResource(R.id.img_row_priority_bar, R.drawable.bg_priority_low)
                }
                VTodoPriority.NONE -> {
                    row.setViewVisibility(R.id.img_row_priority_bar, View.GONE)
                }
            }

            // Checkbox icon
            row.setImageViewResource(
                R.id.img_row_checkbox,
                if (task.isCompleted) R.drawable.ic_widget_check_checked else R.drawable.ic_widget_check_unchecked
            )

            // Task summary & strikethrough for completed items
            row.setTextViewText(R.id.txt_row_summary, task.summary.ifBlank { "(Untitled task)" })
            if (task.isCompleted) {
                row.setTextColor(R.id.txt_row_summary, ContextCompat.getColor(context, R.color.text_muted))
                row.setInt(
                    R.id.txt_row_summary,
                    "setPaintFlags",
                    Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                )
            } else {
                row.setTextColor(R.id.txt_row_summary, ContextCompat.getColor(context, R.color.text_primary))
                row.setInt(R.id.txt_row_summary, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            }

            // Subtitle (list name and/or tags)
            val subtitleParts = buildList {
                if (!listName.isNullOrBlank()) add(listName)
                if (task.categories.isNotEmpty()) add(task.categories.take(2).joinToString(" · ") { "#$it" })
            }
            if (subtitleParts.isNotEmpty()) {
                row.setViewVisibility(R.id.txt_row_subtitle, View.VISIBLE)
                row.setTextViewText(R.id.txt_row_subtitle, subtitleParts.joinToString("  "))
            } else {
                row.setViewVisibility(R.id.txt_row_subtitle, View.GONE)
            }

            // Due chip
            val due = task.due
            if (due != null) {
                row.setViewVisibility(R.id.txt_row_due, View.VISIBLE)
                row.setTextViewText(R.id.txt_row_due, TaskQuery.formatDueLabel(due, today))
                val (bgRes, textColorRes) = when {
                    !task.isCompleted && due.isOverdue(today, now) ->
                        R.drawable.bg_widget_due_overdue to R.color.overdue_text
                    !task.isCompleted && due.isDueToday(today) ->
                        R.drawable.bg_widget_due_today to R.color.due_today_text
                    else ->
                        R.drawable.bg_widget_due_chip to R.color.due_upcoming_text
                }
                row.setInt(R.id.txt_row_due, "setBackgroundResource", bgRes)
                row.setTextColor(R.id.txt_row_due, ContextCompat.getColor(context, textColorRes))
            } else {
                row.setViewVisibility(R.id.txt_row_due, View.GONE)
            }

            // Fill-in intents: checkbox toggles complete, body opens task editor
            row.setOnClickFillInIntent(
                R.id.btn_row_checkbox,
                Intent().apply {
                    putExtra(EXTRA_ROW_ACTION, ROW_ACTION_TOGGLE)
                    putExtra(EXTRA_TASK_UID, task.uid)
                }
            )
            row.setOnClickFillInIntent(
                R.id.row_text_container,
                Intent().apply {
                    putExtra(EXTRA_ROW_ACTION, ROW_ACTION_OPEN)
                    putExtra(EXTRA_TASK_UID, task.uid)
                }
            )

            return row
        }

        private fun bindFooter(context: Context, views: RemoteViews, state: VTodoStoreState) {
            val banner = state.statusBanner
            val footerText = when {
                !banner.isNullOrBlank() -> banner
                state.syncState == SyncState.Syncing -> "Syncing with CalDAV server…"
                state.syncState is SyncState.Error -> "Sync error: ${state.syncState.message}"
                !state.config.isConfigured -> "Not connected"
                state.lastSyncedAt != null -> {
                    val localTime = state.lastSyncedAt.atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
                    "Synced at $localTime"
                }
                else -> context.getString(R.string.widget_footer_not_synced)
            }
            views.setTextViewText(R.id.txt_widget_footer, footerText)
            val footerColor = when {
                !banner.isNullOrBlank() -> ContextCompat.getColor(context, R.color.accent_teal)
                state.syncState is SyncState.Error -> ContextCompat.getColor(context, R.color.overdue_text)
                else -> ContextCompat.getColor(context, R.color.text_muted)
            }
            views.setTextColor(R.id.txt_widget_footer, footerColor)
            views.setOnClickPendingIntent(
                R.id.txt_widget_footer,
                if (state.config.isConfigured) {
                    createSyncPendingIntent(context)
                } else {
                    createOpenAppPendingIntent(context, openSettings = true)
                }
            )
        }

        private fun parseColorOrDefault(hex: String?, fallback: Int): Int {
            if (hex.isNullOrBlank()) return fallback
            return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
        }

        private fun createOpenAppPendingIntent(context: Context, openSettings: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_SETTINGS, openSettings)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val rc = if (openSettings) RC_OPEN_SETTINGS else RC_OPEN_APP
            return PendingIntent.getActivity(
                context,
                rc,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createQuickAddPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickAddActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                RC_QUICK_ADD,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createSyncPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, VTodoWidgetActionReceiver::class.java).apply {
                action = ACTION_WIDGET_SYNC
            }
            return PendingIntent.getBroadcast(
                context,
                RC_SYNC,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createCycleListPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, VTodoWidgetActionReceiver::class.java).apply {
                action = ACTION_WIDGET_CYCLE_LIST
            }
            return PendingIntent.getBroadcast(
                context,
                RC_CYCLE_LIST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createRowTemplatePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, VTodoWidgetActionReceiver::class.java).apply {
                action = ACTION_WIDGET_ROW_CLICK
            }
            // Explicit non-exported internal receiver; FLAG_MUTABLE is required for RemoteViews fillInIntent extras
            return PendingIntent.getBroadcast(
                context,
                RC_ROW_TEMPLATE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }
    }
}

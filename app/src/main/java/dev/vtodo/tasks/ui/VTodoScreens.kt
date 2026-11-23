package dev.vtodo.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.vtodo.tasks.data.TaskQuery
import dev.vtodo.tasks.data.TaskViewFilter
import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.SyncState
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoPriority
import dev.vtodo.tasks.domain.VTodoStatus
import dev.vtodo.tasks.domain.VTodoStoreState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun VTodoMainScreen(
    state: VTodoStoreState,
    initialOpenSettings: Boolean,
    initialTaskUid: String?,
    onSyncNow: () -> Unit,
    onCreateTask: suspend (summary: String, listUrl: String?, description: String, due: DueSpec?, priority: Int, categories: List<String>) -> Result<VTodoItem>,
    onToggleComplete: (uid: String) -> Unit,
    onUpdateTask: suspend (VTodoItem) -> Result<VTodoItem>,
    onDeleteTask: suspend (uid: String) -> Result<Unit>,
    onTestConnection: suspend (ServerConfig) -> Result<List<TaskList>>,
    onSaveServer: suspend (ServerConfig) -> Result<List<TaskList>>,
    onUpdatePreferences: (showCompleted: Boolean?, syncIntervalMinutes: Int?, widgetListUrl: String?, updateWidgetList: Boolean) -> Unit,
    onDisconnectServer: () -> Unit,
    onClearError: () -> Unit,
    onPinWidget: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    val scope = rememberCoroutineScope()

    var showSettings by rememberSaveable { mutableStateOf(initialOpenSettings || !state.config.isConfigured) }
    var activeFilter by remember { mutableStateOf<TaskViewFilter>(TaskViewFilter.AllOpen) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCompletedInList by rememberSaveable { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<VTodoItem?>(null) }

    LaunchedEffect(initialOpenSettings) {
        if (initialOpenSettings) showSettings = true
    }
    LaunchedEffect(initialTaskUid, state.tasks) {
        if (!initialTaskUid.isNullOrBlank()) {
            state.tasks.firstOrNull { it.uid == initialTaskUid }?.let {
                editingTask = it
                showSettings = false
            }
        }
    }

    val safeInsets = WindowInsets.systemBars.union(WindowInsets.captionBar).asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.windowBg)
            .padding(safeInsets)
    ) {
        val isWideDesktop = maxWidth >= 700.dp

        if (isWideDesktop) {
            Row(modifier = Modifier.fillMaxSize()) {
                DesktopSidebar(
                    state = state,
                    activeFilter = activeFilter,
                    showSettings = showSettings,
                    onSelectFilter = {
                        activeFilter = it
                        showSettings = false
                    },
                    onOpenSettings = { showSettings = true },
                    onPinWidget = onPinWidget
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    if (showSettings) {
                        ServerSettingsPane(
                            state = state,
                            onTestConnection = onTestConnection,
                            onSaveServer = { cfg ->
                                val result = onSaveServer(cfg)
                                if (result.isSuccess) showSettings = false
                                result
                            },
                            onUpdatePreferences = onUpdatePreferences,
                            onDisconnectServer = {
                                onDisconnectServer()
                                showSettings = true
                            },
                            onPinWidget = onPinWidget,
                            onBack = if (state.config.isConfigured) ({ showSettings = false }) else null
                        )
                    } else {
                        TaskWorkspacePane(
                            state = state,
                            activeFilter = activeFilter,
                            searchQuery = searchQuery,
                            showCompletedInList = showCompletedInList,
                            onSearchChange = { searchQuery = it },
                            onToggleShowCompleted = { showCompletedInList = it },
                            onSyncNow = onSyncNow,
                            onCreateTask = onCreateTask,
                            onToggleComplete = onToggleComplete,
                            onEditTask = { editingTask = it },
                            onClearError = onClearError,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        } else {
            // Compact narrow window layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                CompactTopBar(
                    state = state,
                    activeFilter = activeFilter,
                    showSettings = showSettings,
                    onSelectFilter = {
                        activeFilter = it
                        showSettings = false
                    },
                    onOpenSettings = { showSettings = !showSettings },
                    onPinWidget = onPinWidget
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (showSettings) {
                        ServerSettingsPane(
                            state = state,
                            onTestConnection = onTestConnection,
                            onSaveServer = { cfg ->
                                val result = onSaveServer(cfg)
                                if (result.isSuccess) showSettings = false
                                result
                            },
                            onUpdatePreferences = onUpdatePreferences,
                            onDisconnectServer = {
                                onDisconnectServer()
                                showSettings = true
                            },
                            onPinWidget = onPinWidget,
                            onBack = if (state.config.isConfigured) ({ showSettings = false }) else null
                        )
                    } else {
                        TaskWorkspacePane(
                            state = state,
                            activeFilter = activeFilter,
                            searchQuery = searchQuery,
                            showCompletedInList = showCompletedInList,
                            onSearchChange = { searchQuery = it },
                            onToggleShowCompleted = { showCompletedInList = it },
                            onSyncNow = onSyncNow,
                            onCreateTask = onCreateTask,
                            onToggleComplete = onToggleComplete,
                            onEditTask = { editingTask = it },
                            onClearError = onClearError,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    editingTask?.let { task ->
        TaskEditorDialog(
            task = task,
            lists = state.lists,
            onDismiss = { editingTask = null },
            onSave = { updated ->
                scope.launch {
                    val res = onUpdateTask(updated)
                    if (res.isSuccess) editingTask = null
                }
            },
            onDelete = { uid ->
                scope.launch {
                    val res = onDeleteTask(uid)
                    if (res.isSuccess) editingTask = null
                }
            }
        )
    }
}

@Composable
private fun DesktopSidebar(
    state: VTodoStoreState,
    activeFilter: TaskViewFilter,
    showSettings: Boolean,
    onSelectFilter: (TaskViewFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onPinWidget: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    val today = remember { LocalDate.now() }
    val now = remember { Instant.now() }

    val allOpenCount = remember(state.tasks) { TaskQuery.openCount(state.tasks) }
    val todayCount = remember(state.tasks) {
        TaskQuery.filterAndSort(state.tasks, TaskViewFilter.Today, today = today, now = now).size
    }
    val upcomingCount = remember(state.tasks) {
        TaskQuery.filterAndSort(state.tasks, TaskViewFilter.Upcoming, today = today, now = now).size
    }
    val completedCount = remember(state.tasks) { state.tasks.count { it.isCompleted } }

    Column(
        modifier = Modifier
            .width(248.dp)
            .fillMaxHeight()
            .padding(start = 14.dp, top = 16.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) {
        // Brand Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "VTodo",
                    color = palette.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "CalDAV Tasks",
                    color = palette.textMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SidebarNavItem(
                label = "All Tasks",
                count = allOpenCount,
                dotColor = palette.primary,
                selected = !showSettings && activeFilter == TaskViewFilter.AllOpen,
                onClick = { onSelectFilter(TaskViewFilter.AllOpen) }
            )
            SidebarNavItem(
                label = "Today",
                count = todayCount,
                dotColor = palette.priorityHigh,
                selected = !showSettings && activeFilter == TaskViewFilter.Today,
                onClick = { onSelectFilter(TaskViewFilter.Today) }
            )
            SidebarNavItem(
                label = "Upcoming",
                count = upcomingCount,
                dotColor = palette.priorityMedium,
                selected = !showSettings && activeFilter == TaskViewFilter.Upcoming,
                onClick = { onSelectFilter(TaskViewFilter.Upcoming) }
            )
            SidebarNavItem(
                label = "Completed",
                count = completedCount,
                dotColor = palette.teal,
                selected = !showSettings && activeFilter == TaskViewFilter.Completed,
                onClick = { onSelectFilter(TaskViewFilter.Completed) }
            )

            if (state.lists.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "TASK LISTS",
                    color = palette.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                for (list in state.lists) {
                    val count = TaskQuery.openCount(state.tasks, list.url)
                    SidebarNavItem(
                        label = if (list.readOnly) "${list.displayName} (RO)" else list.displayName,
                        count = count,
                        dotColor = parseHexColorSafe(list.colorHex, palette.primary),
                        selected = !showSettings && activeFilter == TaskViewFilter.ByList(list.url),
                        onClick = { onSelectFilter(TaskViewFilter.ByList(list.url)) }
                    )
                }
            }
        }

        HorizontalDivider(color = palette.cardStroke, modifier = Modifier.padding(vertical = 10.dp))

        OutlinedButton(
            onClick = onPinWidget,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_pin_widget"),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("Pin Desktop Widget", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (showSettings) palette.primaryContainer else palette.tonalBg)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("btn_server_settings")
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Server Settings",
                tint = if (showSettings) palette.onPrimaryContainer else palette.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.config.isConfigured) "Server & Widget" else "Connect Server",
                color = if (showSettings) palette.onPrimaryContainer else palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SidebarNavItem(
    label: String,
    count: Int,
    dotColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) palette.onPrimaryContainer else palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                color = if (selected) palette.onPrimaryContainer else palette.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CompactTopBar(
    state: VTodoStoreState,
    activeFilter: TaskViewFilter,
    showSettings: Boolean,
    onSelectFilter: (TaskViewFilter) -> Unit,
    onOpenSettings: () -> Unit,
    onPinWidget: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "VTodo",
                color = palette.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPinWidget) {
                Text("Pin Widget", fontSize = 12.sp)
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("btn_server_settings")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Server Settings",
                    tint = if (showSettings) palette.primary else palette.textSecondary
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = !showSettings && activeFilter == TaskViewFilter.AllOpen,
                onClick = { onSelectFilter(TaskViewFilter.AllOpen) },
                label = { Text("All") }
            )
            FilterChip(
                selected = !showSettings && activeFilter == TaskViewFilter.Today,
                onClick = { onSelectFilter(TaskViewFilter.Today) },
                label = { Text("Today") }
            )
            FilterChip(
                selected = !showSettings && activeFilter == TaskViewFilter.Upcoming,
                onClick = { onSelectFilter(TaskViewFilter.Upcoming) },
                label = { Text("Upcoming") }
            )
            FilterChip(
                selected = !showSettings && activeFilter == TaskViewFilter.Completed,
                onClick = { onSelectFilter(TaskViewFilter.Completed) },
                label = { Text("Done") }
            )
            for (list in state.lists) {
                FilterChip(
                    selected = !showSettings && activeFilter == TaskViewFilter.ByList(list.url),
                    onClick = { onSelectFilter(TaskViewFilter.ByList(list.url)) },
                    label = { Text(list.displayName, maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun TaskWorkspacePane(
    state: VTodoStoreState,
    activeFilter: TaskViewFilter,
    searchQuery: String,
    showCompletedInList: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleShowCompleted: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onCreateTask: suspend (String, String?, String, DueSpec?, Int, List<String>) -> Result<VTodoItem>,
    onToggleComplete: (String) -> Unit,
    onEditTask: (VTodoItem) -> Unit,
    onClearError: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    val now = remember { Instant.now() }

    val selectedList = (activeFilter as? TaskViewFilter.ByList)?.let { f ->
        state.lists.firstOrNull { it.url == f.listUrl }
    }
    val heading = when (activeFilter) {
        TaskViewFilter.AllOpen -> "All Tasks"
        TaskViewFilter.Today -> "Today"
        TaskViewFilter.Upcoming -> "Upcoming"
        TaskViewFilter.Completed -> "Completed"
        is TaskViewFilter.ByList -> selectedList?.displayName ?: "Tasks"
    }

    val visibleTasks = remember(state.tasks, state.recentlyCompletedUids, activeFilter, showCompletedInList, searchQuery) {
        TaskQuery.filterAndSort(
            tasks = state.tasks,
            filter = activeFilter,
            includeCompletedInList = showCompletedInList,
            searchQuery = searchQuery,
            recentlyCompletedUids = state.recentlyCompletedUids,
            today = today,
            now = now
        )
    }

    var quickSummary by rememberSaveable { mutableStateOf("") }
    var quickDue by remember { mutableStateOf<DueSpec?>(null) }
    var quickPriority by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    val submitQuickAdd: () -> Unit = {
        val text = quickSummary.trim()
        if (text.isNotEmpty() && !creating) {
            creating = true
            scope.launch {
                val targetListUrl = selectedList?.url ?: state.config.selectedListUrl
                val res = onCreateTask(text, targetListUrl, "", quickDue, quickPriority, emptyList())
                creating = false
                if (res.isSuccess) {
                    quickSummary = ""
                    quickDue = null
                    quickPriority = 0
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        // Header Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = heading,
                    color = palette.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                val syncedText = state.lastSyncedAt?.let {
                    val t = it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                    "Synced at $t · ${visibleTasks.size} shown"
                } ?: "${visibleTasks.size} tasks"
                Text(
                    text = syncedText,
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
            }

            if (activeFilter is TaskViewFilter.ByList) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Completed", color = palette.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = showCompletedInList,
                        onCheckedChange = onToggleShowCompleted
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(min = 140.dp, max = 220.dp)
                    .padding(end = 8.dp)
            )

            IconButton(
                onClick = onSyncNow,
                enabled = state.syncState != SyncState.Syncing && state.config.isConfigured,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.tonalBg)
                    .testTag("btn_sync_now")
            ) {
                if (state.syncState == SyncState.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync now", tint = palette.primary)
                }
            }
        }

        // Error Banner
        val syncError = state.syncState as? SyncState.Error
        if (syncError != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.overdueBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = syncError.message,
                    color = palette.overdueText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSyncNow) {
                    Text("Retry", color = palette.overdueText, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = palette.overdueText, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Inline Quick-Add Bar (when configured and not viewing Completed/read-only list)
        val canAddHere = state.config.isConfigured &&
            state.lists.any { !it.readOnly } &&
            selectedList?.readOnly != true &&
            activeFilter != TaskViewFilter.Completed

        if (canAddHere) {
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.rowBg)
                    .border(1.dp, palette.cardStroke, RoundedCornerShape(16.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quickSummary,
                        onValueChange = { quickSummary = it },
                        placeholder = {
                            val listLabel = selectedList?.displayName
                                ?: state.lists.firstOrNull { !it.readOnly }?.displayName
                                ?: "Tasks"
                            Text("Add a task to $listLabel…", fontSize = 14.sp)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitQuickAdd() }),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_quick_add")
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = submitQuickAdd,
                        enabled = quickSummary.isNotBlank() && !creating,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("btn_quick_add_submit")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = quickDue?.date == today,
                        onClick = { quickDue = if (quickDue?.date == today) null else DueSpec(today) },
                        label = { Text("Today", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = quickDue?.date == today.plusDays(1),
                        onClick = {
                            val tomorrow = today.plusDays(1)
                            quickDue = if (quickDue?.date == tomorrow) null else DueSpec(tomorrow)
                        },
                        label = { Text("Tomorrow", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = quickPriority in 1..4,
                        onClick = { quickPriority = if (quickPriority in 1..4) 0 else VTodoPriority.HIGH.defaultRfcValue },
                        label = { Text("High Priority", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = quickPriority == 5,
                        onClick = { quickPriority = if (quickPriority == 5) 0 else VTodoPriority.MEDIUM.defaultRfcValue },
                        label = { Text("Medium", fontSize = 12.sp) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Task List / Empty States
        if (!state.config.isConfigured) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Connect your Nextcloud or CalDAV server",
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "VTodo syncs RFC 5545 VTODO items directly over RFC 4791 CalDAV.",
                        color = palette.textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                        Text("Configure CalDAV Server")
                    }
                }
            }
        } else if (visibleTasks.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching tasks" else "All caught up",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "Try a different search term." else "Add a task above or sync with your CalDAV server.",
                        color = palette.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            val listByUrl = remember(state.lists) { state.lists.associateBy { it.url } }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleTasks, key = { "${it.listUrl}|${it.uid}" }) { task ->
                    TaskRowCard(
                        task = task,
                        taskList = listByUrl[task.listUrl],
                        showListBadge = activeFilter !is TaskViewFilter.ByList && state.lists.size > 1,
                        today = today,
                        now = now,
                        onToggleComplete = { onToggleComplete(task.uid) },
                        onClick = { onEditTask(task) }
                    )
                }
            }
        }

        // Visual confirmation banner when a task is checked off or reopened
        val banner = state.statusBanner
        if (!banner.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.teal.copy(alpha = 0.14f))
                    .border(1.dp, palette.teal.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = banner,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TaskRowCard(
    task: VTodoItem,
    taskList: TaskList?,
    showListBadge: Boolean,
    today: LocalDate,
    now: Instant,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit
) {
    val palette = LocalVTodoPalette.current
    val priorityColor = when (task.priorityLevel) {
        VTodoPriority.HIGH -> palette.priorityHigh
        VTodoPriority.MEDIUM -> palette.priorityMedium
        VTodoPriority.LOW -> palette.priorityLow
        VTodoPriority.NONE -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (task.isCompleted) palette.teal.copy(alpha = 0.10f) else palette.rowBg)
            .border(
                1.dp,
                if (task.isCompleted) palette.teal.copy(alpha = 0.40f) else palette.cardStroke,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("task_row_${task.uid}")
    ) {
        if (priorityColor != null) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(priorityColor)
            )
            Spacer(Modifier.width(10.dp))
        }

        // Interactive circular checkbox
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (task.isCompleted) palette.teal else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (task.isCompleted) palette.teal else palette.textMuted,
                    shape = CircleShape
                )
                .clickable(onClick = onToggleComplete)
                .testTag("task_check_${task.uid}")
        ) {
            if (task.isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.summary.ifBlank { "(Untitled task)" },
                color = if (task.isCompleted) palette.textMuted else palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description.replace('\n', ' '),
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showListBadge || task.categories.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showListBadge && taskList != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColorSafe(taskList.colorHex, palette.primary))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(taskList.displayName, color = palette.textMuted, fontSize = 11.sp)
                        }
                    }
                    for (cat in task.categories.take(3)) {
                        Text("#$cat", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        task.due?.let { due ->
            Spacer(Modifier.width(10.dp))
            val isOverdue = !task.isCompleted && due.isOverdue(today, now)
            val isToday = !task.isCompleted && due.isDueToday(today)
            val chipBg = when {
                isOverdue -> palette.overdueBg
                isToday -> palette.dueTodayBg
                else -> palette.dueUpcomingBg
            }
            val chipText = when {
                isOverdue -> palette.overdueText
                isToday -> palette.dueTodayText
                else -> palette.dueUpcomingText
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chipBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = TaskQuery.formatDueLabel(due, today),
                    color = chipText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ServerSettingsPane(
    state: VTodoStoreState,
    onTestConnection: suspend (ServerConfig) -> Result<List<TaskList>>,
    onSaveServer: suspend (ServerConfig) -> Result<List<TaskList>>,
    onUpdatePreferences: (showCompleted: Boolean?, syncIntervalMinutes: Int?, widgetListUrl: String?, updateWidgetList: Boolean) -> Unit,
    onDisconnectServer: () -> Unit,
    onPinWidget: () -> Unit,
    onBack: (() -> Unit)?
) {
    val palette = LocalVTodoPalette.current
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable(state.config.serverUrl) { mutableStateOf(state.config.serverUrl) }
    var username by rememberSaveable(state.config.username) { mutableStateOf(state.config.username) }
    var password by rememberSaveable(state.config.password) { mutableStateOf(state.config.password) }
    var directCollectionUrl by rememberSaveable(state.config.directCollectionUrl) {
        mutableStateOf(state.config.directCollectionUrl)
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(state.config.directCollectionUrl.isNotBlank()) }

    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var discoveredPreview by remember { mutableStateOf<List<TaskList>>(state.lists) }

    fun buildDraftConfig(): ServerConfig = state.config.copy(
        serverUrl = serverUrl.trim(),
        username = username.trim(),
        password = password,
        directCollectionUrl = directCollectionUrl.trim()
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("screen_server_settings")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CalDAV Server & Widget",
                    color = palette.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "RFC 4791 CalDAV + RFC 5545 VTODO (Nextcloud Tasks, Radicale, Baïkal, SabreDAV)",
                    color = palette.textSecondary,
                    fontSize = 12.sp
                )
            }
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("Done")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://cloud.example.com") },
            supportingText = {
                Text("Enter your Nextcloud root URL or CalDAV endpoint. HTTPS required.")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("input_server_url")
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("input_username")
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show", fontSize = 11.sp)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("input_password")
            )
        }

        Text(
            text = "Tip for Nextcloud: generate a dedicated App Password in Nextcloud → Personal Settings → Security → Devices & sessions. Credentials are encrypted with AndroidKeyStore and never backed up to the cloud.",
            color = palette.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                if (showAdvanced) "Hide advanced direct collection URL" else "Advanced: Direct VTODO collection URL",
                fontSize = 12.sp
            )
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = directCollectionUrl,
                onValueChange = { directCollectionUrl = it },
                label = { Text("Direct Collection URL (optional)") },
                placeholder = { Text("https://cloud.example.com/remote.php/dav/calendars/user/personal/") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (feedbackMessage != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (feedbackIsError) palette.overdueBg else palette.primaryContainer)
                    .padding(12.dp)
            ) {
                Text(
                    text = feedbackMessage!!,
                    color = if (feedbackIsError) palette.overdueText else palette.onPrimaryContainer,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("txt_settings_feedback")
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    if (testing || saving) return@OutlinedButton
                    testing = true
                    feedbackMessage = null
                    scope.launch {
                        val res = onTestConnection(buildDraftConfig())
                        testing = false
                        res.fold(
                            onSuccess = { lists ->
                                discoveredPreview = lists
                                feedbackIsError = false
                                feedbackMessage = if (lists.isEmpty()) {
                                    "Connected, but no VTODO-enabled task lists were found."
                                } else {
                                    "Connected! Discovered ${lists.size} task list(s): ${lists.joinToString { it.displayName }}"
                                }
                            },
                            onFailure = { err ->
                                feedbackIsError = true
                                feedbackMessage = err.message ?: "Connection test failed"
                            }
                        )
                    }
                },
                enabled = (serverUrl.isNotBlank() || directCollectionUrl.isNotBlank()) && !testing && !saving,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("btn_test_connection")
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            Button(
                onClick = {
                    if (testing || saving) return@Button
                    saving = true
                    feedbackMessage = null
                    scope.launch {
                        val res = onSaveServer(buildDraftConfig())
                        saving = false
                        res.fold(
                            onSuccess = { lists ->
                                discoveredPreview = lists
                                feedbackIsError = false
                                feedbackMessage = "Saved & synced ${lists.size} task list(s)."
                            },
                            onFailure = { err ->
                                feedbackIsError = true
                                feedbackMessage = err.message ?: "Failed to connect and sync"
                            }
                        )
                    }
                },
                enabled = (serverUrl.isNotBlank() || directCollectionUrl.isNotBlank()) && !testing && !saving,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("btn_save_server")
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = palette.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save & Sync")
            }
        }

        if (discoveredPreview.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Discovered VTODO Lists",
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                for (list in discoveredPreview) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.rowBg)
                            .border(1.dp, palette.cardStroke, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(parseHexColorSafe(list.colorHex, palette.primary))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(list.displayName, color = palette.textPrimary, fontSize = 12.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = palette.cardStroke, modifier = Modifier.padding(vertical = 18.dp))

        // Widget & Background Sync Settings
        Text(
            text = "Desktop Widget & Sync",
            color = palette.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        Text("Background sync interval", color = palette.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val intervals = listOf(0 to "Manual", 15 to "15 min", 30 to "30 min", 60 to "1 hour")
            for ((mins, label) in intervals) {
                FilterChip(
                    selected = state.config.syncIntervalMinutes == mins,
                    onClick = { onUpdatePreferences(null, mins, null, false) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show completed tasks in widget", color = palette.textPrimary, fontSize = 14.sp)
                Text("Keep finished tasks visible with strikethrough", color = palette.textMuted, fontSize = 12.sp)
            }
            Switch(
                checked = state.config.showCompleted,
                onCheckedChange = { onUpdatePreferences(it, null, null, false) }
            )
        }

        if (state.lists.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Default widget list", color = palette.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = state.config.selectedListUrl == null,
                    onClick = { onUpdatePreferences(null, null, null, true) },
                    label = { Text("All Tasks", fontSize = 12.sp) }
                )
                for (list in state.lists) {
                    FilterChip(
                        selected = state.config.selectedListUrl == list.url,
                        onClick = { onUpdatePreferences(null, null, list.url, true) },
                        label = { Text(list.displayName, fontSize = 12.sp) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPinWidget, shape = RoundedCornerShape(12.dp)) {
                Text("Pin VTodo Widget to Desktop")
            }
            if (state.config.isConfigured) {
                TextButton(
                    onClick = onDisconnectServer,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.overdueText)
                ) {
                    Text("Disconnect Server")
                }
            }
        }
    }
}

@Composable
fun TaskEditorDialog(
    task: VTodoItem,
    lists: List<TaskList>,
    onDismiss: () -> Unit,
    onSave: (VTodoItem) -> Unit,
    onDelete: (String) -> Unit
) {
    val palette = LocalVTodoPalette.current
    val today = remember { LocalDate.now() }
    val isReadOnly = remember(task, lists) { lists.firstOrNull { it.url == task.listUrl }?.readOnly == true }

    var summary by remember(task.uid) { mutableStateOf(task.summary) }
    var description by remember(task.uid) { mutableStateOf(task.description) }
    var dueText by remember(task.uid) { mutableStateOf(task.due?.date?.toString().orEmpty()) }
    var priority by remember(task.uid) { mutableIntStateOf(task.priority) }
    var categoriesText by remember(task.uid) { mutableStateOf(task.categories.joinToString(", ")) }
    var completed by remember(task.uid) { mutableStateOf(task.isCompleted) }
    var dateError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.cardBg,
            modifier = Modifier
                .widthIn(min = 340.dp, max = 520.dp)
                .border(1.dp, palette.cardStroke, RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isReadOnly) "Task Details (Read-Only)" else "Edit Task",
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    enabled = !isReadOnly,
                    label = { Text("Summary") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    enabled = !isReadOnly,
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = dueText,
                    onValueChange = {
                        dueText = it
                        dateError = null
                    },
                    enabled = !isReadOnly,
                    label = { Text("Due Date (YYYY-MM-DD)") },
                    placeholder = { Text("2026-11-25") },
                    isError = dateError != null,
                    supportingText = dateError?.let { err -> { Text(err) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isReadOnly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = dueText == today.toString(),
                            onClick = { dueText = today.toString(); dateError = null },
                            label = { Text("Today", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = dueText == today.plusDays(1).toString(),
                            onClick = { dueText = today.plusDays(1).toString(); dateError = null },
                            label = { Text("Tomorrow", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = dueText == today.plusDays(7).toString(),
                            onClick = { dueText = today.plusDays(7).toString(); dateError = null },
                            label = { Text("+1 Week", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = dueText.isBlank(),
                            onClick = { dueText = ""; dateError = null },
                            label = { Text("No Date", fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text("Priority", color = palette.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (level in VTodoPriority.entries) {
                        FilterChip(
                            selected = VTodoPriority.fromRfc(priority) == level,
                            onClick = { if (!isReadOnly) priority = level.defaultRfcValue },
                            label = { Text(level.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = categoriesText,
                    onValueChange = { categoriesText = it },
                    enabled = !isReadOnly,
                    label = { Text("Tags / Categories (comma-separated)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Completed", color = palette.textPrimary, modifier = Modifier.weight(1f))
                    Switch(
                        checked = completed,
                        enabled = !isReadOnly,
                        onCheckedChange = { completed = it }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isReadOnly) {
                        TextButton(
                            onClick = { onDelete(task.uid) },
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.overdueText)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    if (!isReadOnly) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsedDue = if (dueText.isBlank()) {
                                    null
                                } else {
                                    val d = runCatching { LocalDate.parse(dueText.trim()) }.getOrNull()
                                    if (d == null) {
                                        dateError = "Use YYYY-MM-DD format"
                                        return@Button
                                    }
                                    DueSpec(date = d)
                                }
                                val parsedCats = categoriesText.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val now = Instant.now()
                                onSave(
                                    task.copy(
                                        summary = summary.trim().ifBlank { task.summary },
                                        description = description.trim(),
                                        due = parsedDue,
                                        priority = priority,
                                        categories = parsedCats,
                                        status = if (completed) VTodoStatus.COMPLETED else VTodoStatus.NEEDS_ACTION,
                                        completedAt = if (completed) (task.completedAt ?: now) else null,
                                        percentComplete = if (completed) 100 else 0
                                    )
                                )
                            },
                            enabled = summary.isNotBlank(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAddDialogContent(
    lists: List<TaskList>,
    initialSelectedListUrl: String?,
    onDismiss: () -> Unit,
    onCreate: suspend (summary: String, listUrl: String?, description: String, due: DueSpec?, priority: Int) -> Result<VTodoItem>
) {
    val palette = LocalVTodoPalette.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    val writableLists = remember(lists) { lists.filter { !it.readOnly } }
    val focusRequester = remember { FocusRequester() }

    var summary by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedListUrl by remember(writableLists, initialSelectedListUrl) {
        mutableStateOf(
            initialSelectedListUrl?.takeIf { url -> writableLists.any { it.url == url } }
                ?: writableLists.firstOrNull()?.url
        )
    }
    var due by remember { mutableStateOf<DueSpec?>(null) }
    var priority by remember { mutableIntStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val submit: () -> Unit = {
        val text = summary.trim()
        if (text.isNotEmpty() && !submitting) {
            submitting = true
            errorMsg = null
            scope.launch {
                val res = onCreate(text, selectedListUrl, notes.trim(), due, priority)
                submitting = false
                res.fold(
                    onSuccess = { onDismiss() },
                    onFailure = { errorMsg = it.message ?: "Failed to create task" }
                )
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
            .padding(20.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.cardBg,
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 480.dp)
                .border(1.dp, palette.cardStroke, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Quick Add VTODO",
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    placeholder = { Text("What needs to be done?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("quick_add_dialog_input")
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Notes (optional)") },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (writableLists.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        for (list in writableLists) {
                            FilterChip(
                                selected = selectedListUrl == list.url,
                                onClick = { selectedListUrl = list.url },
                                label = { Text(list.displayName, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = due?.date == today,
                        onClick = { due = if (due?.date == today) null else DueSpec(today) },
                        label = { Text("Today", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = due?.date == today.plusDays(1),
                        onClick = {
                            val tmr = today.plusDays(1)
                            due = if (due?.date == tmr) null else DueSpec(tmr)
                        },
                        label = { Text("Tomorrow", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = priority in 1..4,
                        onClick = { priority = if (priority in 1..4) 0 else VTodoPriority.HIGH.defaultRfcValue },
                        label = { Text("High", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = priority == 5,
                        onClick = { priority = if (priority == 5) 0 else VTodoPriority.MEDIUM.defaultRfcValue },
                        label = { Text("Med", fontSize = 12.sp) }
                    )
                }

                if (errorMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorMsg!!, color = palette.overdueText, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = submit,
                        enabled = summary.isNotBlank() && !submitting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("quick_add_dialog_submit")
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = palette.onPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Add Task")
                    }
                }
            }
        }
    }
}

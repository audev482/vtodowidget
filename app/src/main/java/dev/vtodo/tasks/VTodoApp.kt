package dev.vtodo.tasks

import android.app.Application
import dev.vtodo.tasks.data.AndroidKeyStoreCipher
import dev.vtodo.tasks.data.JsonVTodoLocalStore
import dev.vtodo.tasks.data.SyncSchedulerPort
import dev.vtodo.tasks.data.VTodoLocalStore
import dev.vtodo.tasks.data.VTodoRepository
import dev.vtodo.tasks.data.WidgetUpdaterPort
import dev.vtodo.tasks.protocol.CalDavClient
import dev.vtodo.tasks.protocol.CalDavService
import dev.vtodo.tasks.sync.WorkManagerSyncScheduler
import dev.vtodo.tasks.widget.AndroidVTodoWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class VTodoApp : Application() {

    var appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private set

    lateinit var repository: VTodoRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val store = JsonVTodoLocalStore(
            file = File(filesDir, "vtodo_state.json"),
            cipher = AndroidKeyStoreCipher()
        )
        repository = VTodoRepository(
            calDav = CalDavClient(),
            localStore = store,
            widgetUpdater = AndroidVTodoWidgetUpdater(this),
            syncScheduler = WorkManagerSyncScheduler(this)
        )
        appScope.launch {
            repository.initialize()
        }
    }

    fun configureForTesting(
        calDav: CalDavService,
        localStore: VTodoLocalStore,
        widgetUpdater: WidgetUpdaterPort,
        syncScheduler: SyncSchedulerPort,
        scope: CoroutineScope,
        completionClearDelayMillis: Long = 0L
    ) {
        appScope = scope
        repository = VTodoRepository(
            calDav = calDav,
            localStore = localStore,
            widgetUpdater = widgetUpdater,
            syncScheduler = syncScheduler,
            completionClearDelayMillis = completionClearDelayMillis
        )
    }
}

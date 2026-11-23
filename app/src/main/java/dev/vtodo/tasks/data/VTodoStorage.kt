package dev.vtodo.tasks.data

import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStatus
import dev.vtodo.tasks.domain.VTodoStoreState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

interface VTodoLocalStore {
    suspend fun loadSnapshot(): VTodoStoreState
    suspend fun saveSnapshot(
        config: ServerConfig,
        lists: List<TaskList>,
        tasks: List<VTodoItem>,
        lastSyncedAt: Instant?
    )
}

/**
 * Lightweight, atomic JSON local store for CalDAV configuration (with encrypted password),
 * discovered task lists, and cached VTODO items.
 */
class JsonVTodoLocalStore(
    private val file: File,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VTodoLocalStore {

    private val mutex = Mutex()

    override suspend fun loadSnapshot(): VTodoStoreState = withContext(ioDispatcher) {
        mutex.withLock {
            if (!file.exists()) return@withLock VTodoStoreState()
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val configObj = root.optJSONObject("config")
                val config = if (configObj != null) {
                    ServerConfig(
                        serverUrl = configObj.optString("serverUrl", ""),
                        username = configObj.optString("username", ""),
                        password = cipher.decrypt(configObj.optString("passwordEnc", "")),
                        directCollectionUrl = configObj.optString("directCollectionUrl", ""),
                        selectedListUrl = configObj.optString("selectedListUrl", "").ifBlank { null },
                        showCompleted = configObj.optBoolean("showCompleted", false),
                        syncIntervalMinutes = configObj.optInt("syncIntervalMinutes", 30)
                    )
                } else {
                    ServerConfig()
                }

                val lists = mutableListOf<TaskList>()
                val listsArr = root.optJSONArray("lists")
                if (listsArr != null) {
                    for (i in 0 until listsArr.length()) {
                        val obj = listsArr.optJSONObject(i) ?: continue
                        lists += TaskList(
                            url = obj.optString("url"),
                            displayName = obj.optString("displayName", "Tasks"),
                            colorHex = obj.optString("colorHex", TaskList.DEFAULT_COLOR_HEX),
                            ctag = obj.optString("ctag", "").ifBlank { null },
                            order = obj.optInt("order", 0),
                            readOnly = obj.optBoolean("readOnly", false)
                        )
                    }
                }

                val tasks = mutableListOf<VTodoItem>()
                val tasksArr = root.optJSONArray("tasks")
                if (tasksArr != null) {
                    for (i in 0 until tasksArr.length()) {
                        val obj = tasksArr.optJSONObject(i) ?: continue
                        val dueDateStr = obj.optString("dueDate", "").ifBlank { null }
                        val dueInstantMillis = obj.optLong("dueDateTimeMillis", Long.MIN_VALUE)
                        val due = dueDateStr?.let { dateStr ->
                            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { localDate ->
                                DueSpec(
                                    date = localDate,
                                    dateTimeUtc = if (dueInstantMillis != Long.MIN_VALUE) {
                                        Instant.ofEpochMilli(dueInstantMillis)
                                    } else null
                                )
                            }
                        }

                        val cats = mutableListOf<String>()
                        val catsArr = obj.optJSONArray("categories")
                        if (catsArr != null) {
                            for (c in 0 until catsArr.length()) {
                                catsArr.optString(c)?.takeIf { it.isNotBlank() }?.let { cats += it }
                            }
                        }

                        val completedMillis = obj.optLong("completedAtMillis", Long.MIN_VALUE)
                        val createdMillis = obj.optLong("createdAtMillis", Long.MIN_VALUE)
                        val modifiedMillis = obj.optLong("lastModifiedAtMillis", Long.MIN_VALUE)
                        val sortOrder = obj.optLong("sortOrder", Long.MIN_VALUE)

                        tasks += VTodoItem(
                            uid = obj.optString("uid"),
                            href = obj.optString("href"),
                            etag = obj.optString("etag", "").ifBlank { null },
                            listUrl = obj.optString("listUrl"),
                            summary = obj.optString("summary"),
                            description = obj.optString("description", ""),
                            status = VTodoStatus.fromRfc(obj.optString("status")),
                            completedAt = if (completedMillis != Long.MIN_VALUE) Instant.ofEpochMilli(completedMillis) else null,
                            percentComplete = obj.optInt("percentComplete", 0),
                            due = due,
                            priority = obj.optInt("priority", 0),
                            categories = cats,
                            parentUid = obj.optString("parentUid", "").ifBlank { null },
                            pinned = obj.optBoolean("pinned", false),
                            sortOrder = if (sortOrder != Long.MIN_VALUE) sortOrder else null,
                            createdAt = if (createdMillis != Long.MIN_VALUE) Instant.ofEpochMilli(createdMillis) else null,
                            lastModifiedAt = if (modifiedMillis != Long.MIN_VALUE) Instant.ofEpochMilli(modifiedMillis) else null,
                            rawIcs = obj.optString("rawIcs", "").ifBlank { null }
                        )
                    }
                }

                val lastSyncedMillis = root.optLong("lastSyncedAtMillis", Long.MIN_VALUE)
                VTodoStoreState(
                    config = config,
                    lists = lists,
                    tasks = tasks,
                    lastSyncedAt = if (lastSyncedMillis != Long.MIN_VALUE) Instant.ofEpochMilli(lastSyncedMillis) else null
                )
            }.getOrElse { VTodoStoreState() }
        }
    }

    override suspend fun saveSnapshot(
        config: ServerConfig,
        lists: List<TaskList>,
        tasks: List<VTodoItem>,
        lastSyncedAt: Instant?
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val root = JSONObject()
            val configObj = JSONObject().apply {
                put("serverUrl", config.serverUrl)
                put("username", config.username)
                put("passwordEnc", cipher.encrypt(config.password))
                put("directCollectionUrl", config.directCollectionUrl)
                put("selectedListUrl", config.selectedListUrl ?: "")
                put("showCompleted", config.showCompleted)
                put("syncIntervalMinutes", config.syncIntervalMinutes)
            }
            root.put("config", configObj)

            val listsArr = JSONArray()
            for (list in lists) {
                listsArr.put(
                    JSONObject().apply {
                        put("url", list.url)
                        put("displayName", list.displayName)
                        put("colorHex", list.colorHex)
                        put("ctag", list.ctag ?: "")
                        put("order", list.order)
                        put("readOnly", list.readOnly)
                    }
                )
            }
            root.put("lists", listsArr)

            val tasksArr = JSONArray()
            for (task in tasks) {
                tasksArr.put(
                    JSONObject().apply {
                        put("uid", task.uid)
                        put("href", task.href)
                        put("etag", task.etag ?: "")
                        put("listUrl", task.listUrl)
                        put("summary", task.summary)
                        put("description", task.description)
                        put("status", task.status.rfcValue)
                        if (task.completedAt != null) put("completedAtMillis", task.completedAt.toEpochMilli())
                        put("percentComplete", task.percentComplete)
                        if (task.due != null) {
                            put("dueDate", task.due.date.toString())
                            if (task.due.dateTimeUtc != null) {
                                put("dueDateTimeMillis", task.due.dateTimeUtc.toEpochMilli())
                            }
                        }
                        put("priority", task.priority)
                        val catsArr = JSONArray()
                        task.categories.forEach { catsArr.put(it) }
                        put("categories", catsArr)
                        put("parentUid", task.parentUid ?: "")
                        put("pinned", task.pinned)
                        if (task.sortOrder != null) put("sortOrder", task.sortOrder)
                        if (task.createdAt != null) put("createdAtMillis", task.createdAt.toEpochMilli())
                        if (task.lastModifiedAt != null) put("lastModifiedAtMillis", task.lastModifiedAt.toEpochMilli())
                        put("rawIcs", task.rawIcs ?: "")
                    }
                )
            }
            root.put("tasks", tasksArr)

            if (lastSyncedAt != null) {
                root.put("lastSyncedAtMillis", lastSyncedAt.toEpochMilli())
            }

            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        }
    }
}

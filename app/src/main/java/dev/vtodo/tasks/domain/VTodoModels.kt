package dev.vtodo.tasks.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class VTodoStatus(val rfcValue: String) {
    NEEDS_ACTION("NEEDS-ACTION"),
    IN_PROCESS("IN-PROCESS"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromRfc(raw: String?): VTodoStatus = when (raw?.trim()?.uppercase()) {
            "IN-PROCESS" -> IN_PROCESS
            "COMPLETED" -> COMPLETED
            "CANCELLED" -> CANCELLED
            else -> NEEDS_ACTION
        }
    }
}

enum class VTodoPriority(val defaultRfcValue: Int, val label: String) {
    NONE(0, "None"),
    HIGH(1, "High"),
    MEDIUM(5, "Medium"),
    LOW(9, "Low");

    companion object {
        fun fromRfc(value: Int): VTodoPriority = when (value) {
            in 1..4 -> HIGH
            5 -> MEDIUM
            in 6..9 -> LOW
            else -> NONE
        }
    }
}

/**
 * RFC 5545 DUE value representation.
 * - All-day (`VALUE=DATE`): [dateTimeUtc] is null, [date] holds the calendar date.
 * - Specific instant (`DATE-TIME`): [dateTimeUtc] holds the UTC instant, [date] holds its local date.
 */
data class DueSpec(
    val date: LocalDate,
    val dateTimeUtc: Instant? = null
) {
    val isAllDay: Boolean get() = dateTimeUtc == null

    fun isOverdue(today: LocalDate, now: Instant): Boolean {
        return if (dateTimeUtc != null) {
            dateTimeUtc.isBefore(now)
        } else {
            date.isBefore(today)
        }
    }

    fun isDueToday(today: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val effectiveDate = dateTimeUtc?.atZone(zoneId)?.toLocalDate() ?: date
        return effectiveDate == today
    }
}

data class TaskList(
    val url: String,
    val displayName: String,
    val colorHex: String = DEFAULT_COLOR_HEX,
    val ctag: String? = null,
    val order: Int = 0,
    val readOnly: Boolean = false
) {
    val id: String
        get() = url.trimEnd('/').substringAfterLast('/').ifBlank { url }

    companion object {
        const val DEFAULT_COLOR_HEX = "#2563EB"
    }
}

data class VTodoItem(
    val uid: String,
    val href: String,
    val etag: String? = null,
    val listUrl: String,
    val summary: String,
    val description: String = "",
    val status: VTodoStatus = VTodoStatus.NEEDS_ACTION,
    val completedAt: Instant? = null,
    val percentComplete: Int = 0,
    val due: DueSpec? = null,
    val priority: Int = 0,
    val categories: List<String> = emptyList(),
    val parentUid: String? = null,
    val pinned: Boolean = false,
    val sortOrder: Long? = null,
    val createdAt: Instant? = null,
    val lastModifiedAt: Instant? = null,
    val rawIcs: String? = null
) {
    val isCompleted: Boolean
        get() = status == VTodoStatus.COMPLETED || completedAt != null

    val isClosed: Boolean
        get() = isCompleted || status == VTodoStatus.CANCELLED

    val priorityLevel: VTodoPriority
        get() = VTodoPriority.fromRfc(priority)
}

data class ServerConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val directCollectionUrl: String = "",
    val selectedListUrl: String? = null,
    val showCompleted: Boolean = false,
    val syncIntervalMinutes: Int = 30
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() || directCollectionUrl.isNotBlank()
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Error(val message: String) : SyncState
}

data class VTodoStoreState(
    val config: ServerConfig = ServerConfig(),
    val lists: List<TaskList> = emptyList(),
    val tasks: List<VTodoItem> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val lastSyncedAt: Instant? = null,
    val recentlyCompletedUids: Set<String> = emptySet(),
    val statusBanner: String? = null
)

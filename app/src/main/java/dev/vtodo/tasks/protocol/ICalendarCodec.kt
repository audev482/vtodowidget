package dev.vtodo.tasks.protocol

import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * RFC 5545 iCalendar VTODO parser and lossless serializer compatible with Nextcloud Tasks
 * and standard CalDAV (RFC 4791) servers.
 */
object ICalendarCodec {

    private val UTC_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd")

    private val MANAGED_UPDATE_PROPS = setOf(
        "SUMMARY",
        "DESCRIPTION",
        "STATUS",
        "COMPLETED",
        "PERCENT-COMPLETE",
        "DUE",
        "PRIORITY",
        "CATEGORIES",
        "LAST-MODIFIED",
        "DTSTAMP"
    )

    internal data class ContentLine(
        val name: String,
        val params: Map<String, String>,
        val value: String,
        val rawLine: String
    )

    fun parseVTodo(
        ics: String,
        href: String,
        etag: String?,
        listUrl: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): VTodoItem? {
        if (ics.isBlank()) return null

        val lines = unfoldLines(ics)
        val stack = ArrayDeque<String>()
        var foundVTodo = false

        var uid: String? = null
        var summary = ""
        var description = ""
        var status = VTodoStatus.NEEDS_ACTION
        var completedAt: Instant? = null
        var percentComplete = 0
        var due: DueSpec? = null
        var priority = 0
        val categories = mutableListOf<String>()
        var parentUid: String? = null
        var pinned = false
        var sortOrder: Long? = null
        var createdAt: Instant? = null
        var lastModifiedAt: Instant? = null

        for (rawLine in lines) {
            if (rawLine.isBlank()) continue
            val parsed = parseContentLine(rawLine) ?: continue
            val upperName = parsed.name.uppercase()

            if (upperName == "BEGIN") {
                val component = parsed.value.trim().uppercase()
                stack.addLast(component)
                if (component == "VTODO" && !foundVTodo) {
                    foundVTodo = true
                }
                continue
            }
            if (upperName == "END") {
                val component = parsed.value.trim().uppercase()
                if (stack.isNotEmpty() && stack.last() == component) {
                    stack.removeLast()
                }
                if (component == "VTODO" && foundVTodo) {
                    break
                }
                continue
            }

            // Only extract direct properties of the primary VTODO (ignore VALARM / VTIMEZONE)
            if (stack.lastOrNull() != "VTODO") continue

            when (upperName) {
                "UID" -> uid = parsed.value.trim()
                "SUMMARY" -> summary = unescapeText(parsed.value)
                "DESCRIPTION" -> description = unescapeText(parsed.value)
                "STATUS" -> status = VTodoStatus.fromRfc(parsed.value)
                "COMPLETED" -> completedAt = parseInstant(parsed.value, parsed.params, zoneId)
                "PERCENT-COMPLETE" -> percentComplete = parsed.value.trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
                "DUE" -> due = parseDue(parsed.value, parsed.params, zoneId)
                "PRIORITY" -> priority = parsed.value.trim().toIntOrNull()?.coerceIn(0, 9) ?: 0
                "CATEGORIES" -> {
                    for (token in splitUnescapedCommas(parsed.value)) {
                        val cleaned = unescapeText(token).trim()
                        if (cleaned.isNotEmpty() && cleaned !in categories) {
                            categories.add(cleaned)
                        }
                    }
                }
                "RELATED-TO" -> {
                    val relType = parsed.params["RELTYPE"]?.uppercase()
                    if (parentUid == null && (relType == null || relType == "PARENT")) {
                        val relVal = parsed.value.trim()
                        if (relVal.isNotEmpty()) parentUid = relVal
                    }
                }
                "X-PINNED" -> pinned = parsed.value.trim().equals("true", ignoreCase = true)
                "X-APPLE-SORT-ORDER" -> sortOrder = parsed.value.trim().toLongOrNull()
                "CREATED" -> createdAt = parseInstant(parsed.value, parsed.params, zoneId)
                "LAST-MODIFIED" -> lastModifiedAt = parseInstant(parsed.value, parsed.params, zoneId)
            }
        }

        if (!foundVTodo) return null

        val effectiveUid = uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return VTodoItem(
            uid = effectiveUid,
            href = href,
            etag = etag,
            listUrl = listUrl,
            summary = summary,
            description = description,
            status = status,
            completedAt = completedAt,
            percentComplete = percentComplete,
            due = due,
            priority = priority,
            categories = categories,
            parentUid = parentUid,
            pinned = pinned,
            sortOrder = sortOrder,
            createdAt = createdAt,
            lastModifiedAt = lastModifiedAt,
            rawIcs = ics
        )
    }

    fun serializeNewVTodo(
        item: VTodoItem,
        now: Instant = Instant.now()
    ): String {
        val nowStr = UTC_FORMATTER.format(now)
        val createdStr = UTC_FORMATTER.format(item.createdAt ?: now)
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//VTodo//Googlebook Tasks 1.0//EN"
        lines += "BEGIN:VTODO"
        lines += "UID:${item.uid}"
        lines += "CREATED:$createdStr"
        lines += "DTSTAMP:$nowStr"
        lines += "LAST-MODIFIED:$nowStr"
        appendManagedProperties(lines, item, nowStr, includeTimestamps = false)
        if (!item.parentUid.isNullOrBlank()) {
            lines += "RELATED-TO:${item.parentUid}"
        }
        if (item.pinned) {
            lines += "X-PINNED:true"
        }
        lines += "END:VTODO"
        lines += "END:VCALENDAR"

        return lines.joinToString("\r\n") { foldLine(it) } + "\r\n"
    }

    fun updateRawIcs(
        item: VTodoItem,
        now: Instant = Instant.now()
    ): String {
        val existing = item.rawIcs
        if (existing.isNullOrBlank()) {
            return serializeNewVTodo(item, now)
        }

        val nowStr = UTC_FORMATTER.format(now)
        val unfolded = unfoldLines(existing)
        val stack = ArrayDeque<String>()
        val output = mutableListOf<String>()
        var updatedFirstVTodo = false
        var insertedProps = false

        for (rawLine in unfolded) {
            if (rawLine.isBlank()) continue
            val parsed = parseContentLine(rawLine)
            val upperName = parsed?.name?.uppercase()

            if (upperName == "BEGIN") {
                val component = parsed.value.trim().uppercase()
                // Insert managed VTODO properties right before the first subcomponent (e.g., VALARM)
                if (!updatedFirstVTodo && !insertedProps && stack.lastOrNull() == "VTODO") {
                    appendManagedProperties(output, item, nowStr, includeTimestamps = true)
                    insertedProps = true
                }
                stack.addLast(component)
                output += rawLine
                continue
            }

            if (upperName == "END") {
                val component = parsed.value.trim().uppercase()
                if (component == "VTODO" && !updatedFirstVTodo && stack.lastOrNull() == "VTODO") {
                    if (!insertedProps) {
                        appendManagedProperties(output, item, nowStr, includeTimestamps = true)
                        insertedProps = true
                    }
                    updatedFirstVTodo = true
                }
                if (stack.isNotEmpty() && stack.last() == component) {
                    stack.removeLast()
                }
                output += rawLine
                continue
            }

            // Inside first VTODO at direct level: drop managed properties so we re-emit updated values
            if (!updatedFirstVTodo && stack.lastOrNull() == "VTODO" && upperName in MANAGED_UPDATE_PROPS) {
                continue
            }

            output += rawLine
        }

        if (!updatedFirstVTodo) {
            return serializeNewVTodo(item, now)
        }

        return output.joinToString("\r\n") { foldLine(it) } + "\r\n"
    }

    private fun appendManagedProperties(
        lines: MutableList<String>,
        item: VTodoItem,
        nowStr: String,
        includeTimestamps: Boolean
    ) {
        if (includeTimestamps) {
            lines += "DTSTAMP:$nowStr"
            lines += "LAST-MODIFIED:$nowStr"
        }
        lines += "SUMMARY:${escapeText(item.summary)}"
        if (item.description.isNotEmpty()) {
            lines += "DESCRIPTION:${escapeText(item.description)}"
        }
        lines += "STATUS:${item.status.rfcValue}"
        if (item.isCompleted) {
            val completedInstant = item.completedAt ?: Instant.now()
            lines += "COMPLETED:${UTC_FORMATTER.format(completedInstant)}"
            val pct = if (item.percentComplete > 0) item.percentComplete else 100
            lines += "PERCENT-COMPLETE:$pct"
        } else if (item.percentComplete in 1..99) {
            lines += "PERCENT-COMPLETE:${item.percentComplete}"
        }
        item.due?.let { due ->
            if (due.dateTimeUtc != null) {
                lines += "DUE:${UTC_FORMATTER.format(due.dateTimeUtc)}"
            } else {
                lines += "DUE;VALUE=DATE:${DATE_FORMATTER.format(due.date)}"
            }
        }
        if (item.priority in 1..9) {
            lines += "PRIORITY:${item.priority}"
        }
        if (item.categories.isNotEmpty()) {
            val encoded = item.categories.joinToString(",") { escapeText(it) }
            lines += "CATEGORIES:$encoded"
        }
    }

    fun unfoldLines(ics: String): List<String> {
        val normalized = ics.replace("\r\n", "\n").replace("\r", "\n")
        val rawLines = normalized.split("\n")
        val unfolded = mutableListOf<String>()
        for (line in rawLines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + line.substring(1)
            } else {
                unfolded.add(line)
            }
        }
        return unfolded
    }

    fun foldLine(line: String): String {
        val maxOctets = 75
        if (line.toByteArray(Charsets.UTF_8).size <= maxOctets) return line

        val result = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val segment = line.substring(i, i + charCount)
            val segmentBytes = segment.toByteArray(Charsets.UTF_8).size

            if (currentBytes + segmentBytes > maxOctets && currentBytes > 0) {
                result.append("\r\n ")
                currentBytes = 1
            }
            result.append(segment)
            currentBytes += segmentBytes
            i += charCount
        }
        return result.toString()
    }

    fun escapeText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\r' -> {} // normalize CRLF to \n
                '\n' -> sb.append("\\n")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun unescapeText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    ',' -> sb.append(',')
                    ';' -> sb.append(';')
                    else -> {
                        sb.append('\\')
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    internal fun parseContentLine(line: String): ContentLine? {
        // Find first ':' outside double quotes
        var inQuotes = false
        var colonIndex = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == ':' && !inQuotes) {
                colonIndex = i
                break
            }
        }
        if (colonIndex <= 0) return null

        val header = line.substring(0, colonIndex)
        val value = line.substring(colonIndex + 1)

        // Split header by ';' outside double quotes
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        inQuotes = false
        for (ch in header) {
            if (ch == '"') {
                inQuotes = !inQuotes
                current.append(ch)
            } else if (ch == ';' && !inQuotes) {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        parts.add(current.toString())

        val name = parts.first().trim()
        val params = mutableMapOf<String, String>()
        for (idx in 1 until parts.size) {
            val part = parts[idx]
            val eq = part.indexOf('=')
            if (eq > 0) {
                val pKey = part.substring(0, eq).trim().uppercase()
                val pVal = part.substring(eq + 1).trim().trim('"')
                params[pKey] = pVal
            }
        }
        return ContentLine(name = name, params = params, value = value, rawLine = line)
    }

    private fun splitUnescapedCommas(value: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in value) {
            if (escaped) {
                current.append('\\').append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == ',') {
                items.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        items.add(current.toString())
        return items
    }

    private fun parseDue(raw: String, params: Map<String, String>, zoneId: ZoneId): DueSpec? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val isDateOnly = params["VALUE"]?.equals("DATE", ignoreCase = true) == true || trimmed.length == 8
        return if (isDateOnly) {
            val date = runCatching { LocalDate.parse(trimmed.take(8), DATE_FORMATTER) }.getOrNull() ?: return null
            DueSpec(date = date, dateTimeUtc = null)
        } else {
            val instant = parseInstant(trimmed, params, zoneId) ?: return null
            DueSpec(date = instant.atZone(zoneId).toLocalDate(), dateTimeUtc = instant)
        }
    }

    private fun parseInstant(raw: String, params: Map<String, String>, defaultZone: ZoneId): Instant? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.endsWith("Z") && trimmed.length >= 16 -> {
                runCatching {
                    LocalDateTime.parse(trimmed.substring(0, 15), LOCAL_DATE_TIME_FORMATTER)
                        .toInstant(ZoneOffset.UTC)
                }.getOrNull()
            }
            trimmed.length >= 15 && trimmed[8] == 'T' -> {
                val tz = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
                runCatching {
                    LocalDateTime.parse(trimmed.substring(0, 15), LOCAL_DATE_TIME_FORMATTER)
                        .atZone(tz)
                        .toInstant()
                }.getOrNull()
            }
            trimmed.length == 8 -> {
                runCatching {
                    LocalDate.parse(trimmed, DATE_FORMATTER).atStartOfDay(defaultZone).toInstant()
                }.getOrNull()
            }
            else -> null
        }
    }
}

package dev.vtodo.tasks.protocol

import dev.vtodo.tasks.domain.DueSpec
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ICalendarCodecTest {

    @Test
    fun parseVTodo_parsesNextcloudVTodoWithFoldedLinesAndValarm() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud Tasks v0.16.1
            BEGIN:VTODO
            UID:task-uuid-1234
            CREATED:20261101T120000Z
            LAST-MODIFIED:20261110T153000Z
            DTSTAMP:20261110T153000Z
            SUMMARY:Review RFC 4791\, CalDAV & VTODO
             specification
            DESCRIPTION:Line 1\nLine 2 with\; semicolon and \\ backslash
            PRIORITY:1
            STATUS:IN-PROCESS
            PERCENT-COMPLETE:50
            DUE;VALUE=DATE:20261125
            CATEGORIES:work,caldav
            RELATED-TO:parent-uuid-0001
            X-PINNED:true
            X-APPLE-SORT-ORDER:42
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            SUMMARY:Alarm summary must NOT overwrite VTODO summary
            DESCRIPTION:Alarm description must NOT overwrite VTODO description
            END:VALARM
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val item = ICalendarCodec.parseVTodo(
            ics = ics,
            href = "https://cloud.example.com/remote.php/dav/calendars/alice/personal/task-uuid-1234.ics",
            etag = "\"abc123\"",
            listUrl = "https://cloud.example.com/remote.php/dav/calendars/alice/personal/"
        )

        assertNotNull(item)
        item!!
        assertEquals("task-uuid-1234", item.uid)
        assertEquals("Review RFC 4791, CalDAV & VTODOspecification", item.summary)
        assertEquals("Line 1\nLine 2 with; semicolon and \\ backslash", item.description)
        assertEquals(1, item.priority)
        assertEquals(VTodoStatus.IN_PROCESS, item.status)
        assertEquals(50, item.percentComplete)
        assertFalse(item.isCompleted)
        assertEquals(DueSpec(LocalDate.of(2026, 11, 25), null), item.due)
        assertEquals(listOf("work", "caldav"), item.categories)
        assertEquals("parent-uuid-0001", item.parentUid)
        assertTrue(item.pinned)
        assertEquals(42L, item.sortOrder)
        assertEquals("\"abc123\"", item.etag)
        assertNotNull(item.rawIcs)
    }

    @Test
    fun parseVTodo_parsesUtcDueDateTimeAndCompletedTimestamp() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:done-1
            SUMMARY:Ship release
            STATUS:COMPLETED
            COMPLETED:20261120T184500Z
            PERCENT-COMPLETE:100
            DUE:20261121T090000Z
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val item = ICalendarCodec.parseVTodo(ics, "/dav/done-1.ics", null, "/dav/")
        assertNotNull(item)
        item!!
        assertTrue(item.isCompleted)
        assertEquals(VTodoStatus.COMPLETED, item.status)
        assertEquals(Instant.parse("2026-11-20T18:45:00Z"), item.completedAt)
        assertEquals(Instant.parse("2026-11-21T09:00:00Z"), item.due?.dateTimeUtc)
        assertFalse(item.due!!.isAllDay)
    }

    @Test
    fun parseVTodo_returnsNullForEventOnlyCalendar() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-1
            SUMMARY:Team meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertNull(ICalendarCodec.parseVTodo(ics, "/dav/evt-1.ics", null, "/dav/"))
    }

    @Test
    fun serializeNewVTodo_roundTripsAndFoldsLongLines() {
        val now = Instant.parse("2026-11-21T20:00:00Z")
        val longSummary = "Implement a super clean, reliable RFC 4791 CalDAV VTODO desktop widget with full Nextcloud compatibility, escaping; commas, and line folding"
        val item = VTodoItem(
            uid = "new-uid-999",
            href = "https://cloud.example.com/remote.php/dav/calendars/alice/work/new-uid-999.ics",
            listUrl = "https://cloud.example.com/remote.php/dav/calendars/alice/work/",
            summary = longSummary,
            description = "Notes line 1\nNotes line 2",
            status = VTodoStatus.NEEDS_ACTION,
            due = DueSpec(LocalDate.of(2026, 12, 1)),
            priority = 1,
            categories = listOf("urgent", "googlebook")
        )

        val serialized = ICalendarCodec.serializeNewVTodo(item, now)

        assertTrue(serialized.contains("BEGIN:VCALENDAR\r\n"))
        assertTrue(serialized.contains("VERSION:2.0\r\n"))
        assertTrue(serialized.contains("BEGIN:VTODO\r\n"))
        assertTrue(serialized.contains("UID:new-uid-999\r\n"))
        assertTrue(serialized.contains("DUE;VALUE=DATE:20261201\r\n"))
        assertTrue(serialized.contains("PRIORITY:1\r\n"))
        // Verify RFC 5545 75-octet folding
        for (line in serialized.split("\r\n")) {
            assertTrue("Line exceeded 75 octets: $line", line.toByteArray(Charsets.UTF_8).size <= 75)
        }

        val reparsed = ICalendarCodec.parseVTodo(serialized, item.href, null, item.listUrl)
        assertNotNull(reparsed)
        assertEquals(longSummary, reparsed!!.summary)
        assertEquals("Notes line 1\nNotes line 2", reparsed.description)
        assertEquals(LocalDate.of(2026, 12, 1), reparsed.due?.date)
        assertEquals(1, reparsed.priority)
        assertEquals(listOf("urgent", "googlebook"), reparsed.categories)
    }

    @Test
    fun updateRawIcs_preservesUnknownPropertiesAndValarmWhenTogglingCompletion() {
        val originalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Nextcloud Tasks v0.16.1
            BEGIN:VTODO
            UID:preserve-1
            SUMMARY:Original Title
            STATUS:NEEDS-ACTION
            X-OC-HIDESUBTASKS:1
            X-APPLE-SORT-ORDER:100
            RRULE:FREQ=WEEKLY;INTERVAL=1
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            DESCRIPTION:Keep this alarm!
            END:VALARM
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val parsed = ICalendarCodec.parseVTodo(originalIcs, "/dav/preserve-1.ics", "\"e1\"", "/dav/")!!
        val completedTime = Instant.parse("2026-11-22T01:00:00Z")
        val completedItem = parsed.copy(
            status = VTodoStatus.COMPLETED,
            completedAt = completedTime,
            percentComplete = 100
        )

        val updatedIcs = ICalendarCodec.updateRawIcs(completedItem, completedTime)

        assertTrue(updatedIcs.contains("STATUS:COMPLETED\r\n"))
        assertTrue(updatedIcs.contains("COMPLETED:20261122T010000Z\r\n"))
        assertTrue(updatedIcs.contains("PERCENT-COMPLETE:100\r\n"))
        assertTrue(updatedIcs.contains("X-OC-HIDESUBTASKS:1\r\n"))
        assertTrue(updatedIcs.contains("X-APPLE-SORT-ORDER:100\r\n"))
        assertTrue(updatedIcs.contains("RRULE:FREQ=WEEKLY;INTERVAL=1\r\n"))
        assertTrue(updatedIcs.contains("DESCRIPTION:Keep this alarm!\r\n"))

        // Now reopen the task and verify COMPLETED & PERCENT-COMPLETE are removed
        val reopenedItem = ICalendarCodec.parseVTodo(updatedIcs, "/dav/preserve-1.ics", "\"e2\"", "/dav/")!!
            .copy(
                status = VTodoStatus.NEEDS_ACTION,
                completedAt = null,
                percentComplete = 0
            )
        val reopenedIcs = ICalendarCodec.updateRawIcs(reopenedItem, completedTime.plusSeconds(60))
        assertTrue(reopenedIcs.contains("STATUS:NEEDS-ACTION\r\n"))
        assertFalse(reopenedIcs.contains("COMPLETED:"))
        assertFalse(reopenedIcs.contains("PERCENT-COMPLETE:"))
        assertTrue(reopenedIcs.contains("DESCRIPTION:Keep this alarm!\r\n"))
    }
}

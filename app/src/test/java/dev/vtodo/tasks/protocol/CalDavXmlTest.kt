package dev.vtodo.tasks.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalDavXmlTest {

    @Test
    fun parseCurrentUserPrincipal_parsesNextcloudMultiStatus() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
              <d:response>
                <d:href>/remote.php/dav/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:current-user-principal>
                      <d:href>/remote.php/dav/principals/users/alice/</d:href>
                    </d:current-user-principal>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("/remote.php/dav/principals/users/alice/", CalDavXml.parseCurrentUserPrincipal(xml))
    }

    @Test
    fun parseCalendarHomeSet_parsesNextcloudPrincipalResponse() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/remote.php/dav/principals/users/alice/</d:href>
                <d:propstat>
                  <d:prop>
                    <cal:calendar-home-set>
                      <d:href>/remote.php/dav/calendars/alice/</d:href>
                    </cal:calendar-home-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals("/remote.php/dav/calendars/alice/", CalDavXml.parseCalendarHomeSet(xml))
    }

    @Test
    fun parseCollections_filtersVTodoCollectionsAndExtractsColorAndReadOnly() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                           xmlns:cs="http://calendarserver.org/ns/"
                           xmlns:x1="http://apple.com/ns/ical/">
              <d:response>
                <d:href>/remote.php/dav/calendars/alice/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/calendars/alice/personal/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Personal</d:displayname>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <cal:supported-calendar-component-set>
                      <cal:comp name="VEVENT"/>
                      <cal:comp name="VTODO"/>
                    </cal:supported-calendar-component-set>
                    <cs:getctag>http://sabre.io/ns/sync/42</cs:getctag>
                    <x1:calendar-color>#1D4ED8FF</x1:calendar-color>
                    <x1:calendar-order>1</x1:calendar-order>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                      <d:privilege><d:write/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/calendars/alice/holidays/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Holidays</d:displayname>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <cal:supported-calendar-component-set>
                      <cal:comp name="VEVENT"/>
                    </cal:supported-calendar-component-set>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/calendars/alice/shared-tasks/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Shared Readonly</d:displayname>
                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                    <cal:supported-calendar-component-set>
                      <cal:comp name="VTODO"/>
                    </cal:supported-calendar-component-set>
                    <x1:calendar-color>#059669</x1:calendar-color>
                    <x1:calendar-order>2</x1:calendar-order>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val collections = CalDavXml.parseCollections(xml)
        val vtodoCalendars = collections.filter { it.isCalendar && it.supportsVTodo }
        assertEquals(2, vtodoCalendars.size)

        val personal = vtodoCalendars[0]
        assertEquals("Personal", personal.displayName)
        assertEquals("#1D4ED8", personal.colorHex)
        assertEquals("http://sabre.io/ns/sync/42", personal.ctag)
        assertEquals(1, personal.order)
        assertFalse(personal.readOnly)

        val shared = vtodoCalendars[1]
        assertEquals("Shared Readonly", shared.displayName)
        assertEquals("#059669", shared.colorHex)
        assertTrue(shared.readOnly)
    }

    @Test
    fun parseCalendarObjects_extractsHrefEtagAndCalendarData() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/remote.php/dav/calendars/alice/personal/item-1.ics</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>&quot;etag-1&quot;</d:getetag>
                    <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VTODO
UID:item-1
SUMMARY:Buy coffee beans
END:VTODO
END:VCALENDAR</cal:calendar-data>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val objects = CalDavXml.parseCalendarObjects(xml)
        assertEquals(1, objects.size)
        assertEquals("/remote.php/dav/calendars/alice/personal/item-1.ics", objects[0].href)
        assertEquals("\"etag-1\"", objects[0].etag)
        assertTrue(objects[0].calendarData!!.contains("SUMMARY:Buy coffee beans"))
    }

    @Test
    fun buildVTodoQueryRequest_containsCalDavVTodoCompFilter() {
        val body = CalDavXml.buildVTodoQueryRequest()
        assertTrue(body.contains("urn:ietf:params:xml:ns:caldav"))
        assertTrue(body.contains("comp-filter name=\"VCALENDAR\""))
        assertTrue(body.contains("comp-filter name=\"VTODO\""))
        assertTrue(body.contains("calendar-data"))
    }
}

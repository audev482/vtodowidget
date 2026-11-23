package dev.vtodo.tasks.protocol

import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import dev.vtodo.tasks.domain.VTodoStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class CalDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CalDavClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun discoverTaskLists_followsNextcloudWellKnownDiscoveryAndFiltersVTodo() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Verify Basic Auth header is sent on every request
                val auth = request.getHeader("Authorization")
                if (auth == null || !auth.startsWith("Basic ")) {
                    return MockResponse().setResponseCode(401)
                }

                return when (request.path) {
                    "/", "" -> MockResponse().setResponseCode(405)
                    "/.well-known/caldav" -> MockResponse()
                        .setResponseCode(301)
                        .addHeader("Location", "/remote.php/dav/")
                    "/remote.php/dav/" -> MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <?xml version="1.0"?>
                            <d:multistatus xmlns:d="DAV:">
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
                        )
                    "/remote.php/dav/principals/users/alice/" -> MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
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
                        )
                    "/remote.php/dav/calendars/alice/" -> MockResponse()
                        .setResponseCode(207)
                        .setBody(
                            """
                            <?xml version="1.0"?>
                            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"
                                           xmlns:x1="http://apple.com/ns/ical/">
                              <d:response>
                                <d:href>/remote.php/dav/calendars/alice/personal/</d:href>
                                <d:propstat>
                                  <d:prop>
                                    <d:displayname>Personal Tasks</d:displayname>
                                    <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype>
                                    <cal:supported-calendar-component-set>
                                      <cal:comp name="VTODO"/>
                                    </cal:supported-calendar-component-set>
                                    <x1:calendar-color>#2563EB</x1:calendar-color>
                                  </d:prop>
                                  <d:status>HTTP/1.1 200 OK</d:status>
                                </d:propstat>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent()
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val config = ServerConfig(
            serverUrl = server.url("/").toString(),
            username = "alice",
            password = "app-password-123"
        )

        val lists = client.discoverTaskLists(config)
        assertEquals(1, lists.size)
        assertEquals("Personal Tasks", lists[0].displayName)
        assertEquals("#2563EB", lists[0].colorHex)
        assertTrue(lists[0].url.endsWith("/remote.php/dav/calendars/alice/personal/"))
    }

    @Test
    fun fetchCreateUpdateDeleteTask_executesValidCalDavRequests() = runTest {
        val listUrl = server.url("/remote.php/dav/calendars/alice/personal/").toString()
        val list = TaskList(url = listUrl, displayName = "Personal")
        val config = ServerConfig(serverUrl = server.url("/").toString(), username = "alice", password = "secret")

        // 1. REPORT fetchTasks
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/remote.php/dav/calendars/alice/personal/t1.ics</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:getetag>"v1"</d:getetag>
                        <cal:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VTODO
UID:t1
SUMMARY:First task
STATUS:NEEDS-ACTION
END:VTODO
END:VCALENDAR</cal:calendar-data>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            )
        )

        val fetched = client.fetchTasks(config, list)
        assertEquals(1, fetched.size)
        assertEquals("First task", fetched[0].summary)
        val reportReq = server.takeRequest()
        assertEquals("REPORT", reportReq.method)
        assertEquals("1", reportReq.getHeader("Depth"))

        // 2. PUT createTask
        server.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"new-v1\""))
        val newItem = VTodoItem(
            uid = "t2",
            href = "",
            listUrl = listUrl,
            summary = "Created from widget"
        )
        val created = client.createTask(config, list, newItem)
        assertEquals("\"new-v1\"", created.etag)
        assertTrue(created.href.endsWith("/t2.ics"))
        assertNotNull(created.rawIcs)
        val createReq = server.takeRequest()
        assertEquals("PUT", createReq.method)
        assertEquals("*", createReq.getHeader("If-None-Match"))
        assertTrue(createReq.body.readUtf8().contains("SUMMARY:Created from widget"))

        // 3. PUT updateTask
        server.enqueue(MockResponse().setResponseCode(204).addHeader("ETag", "\"new-v2\""))
        val updated = client.updateTask(
            config,
            created.copy(status = VTodoStatus.COMPLETED, completedAt = Instant.parse("2026-11-22T00:00:00Z"), percentComplete = 100)
        )
        assertEquals("\"new-v2\"", updated.etag)
        val updateReq = server.takeRequest()
        assertEquals("PUT", updateReq.method)
        assertEquals("\"new-v1\"", updateReq.getHeader("If-Match"))
        assertTrue(updateReq.body.readUtf8().contains("STATUS:COMPLETED"))

        // 4. DELETE deleteTask
        server.enqueue(MockResponse().setResponseCode(204))
        client.deleteTask(config, updated)
        val deleteReq = server.takeRequest()
        assertEquals("DELETE", deleteReq.method)
        assertEquals("\"new-v2\"", deleteReq.getHeader("If-Match"))
    }
}

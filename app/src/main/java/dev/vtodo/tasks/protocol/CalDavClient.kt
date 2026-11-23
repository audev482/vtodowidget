package dev.vtodo.tasks.protocol

import dev.vtodo.tasks.domain.ServerConfig
import dev.vtodo.tasks.domain.TaskList
import dev.vtodo.tasks.domain.VTodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

interface CalDavService {
    suspend fun discoverTaskLists(config: ServerConfig): List<TaskList>
    suspend fun fetchTasks(config: ServerConfig, list: TaskList): List<VTodoItem>
    suspend fun createTask(config: ServerConfig, list: TaskList, item: VTodoItem): VTodoItem
    suspend fun updateTask(config: ServerConfig, item: VTodoItem): VTodoItem
    suspend fun deleteTask(config: ServerConfig, item: VTodoItem)
}

class CalDavException(
    val httpCode: Int?,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * RFC 4791 CalDAV client supporting Nextcloud Tasks (HTTP Basic Auth with App Password,
 * /.well-known/caldav and /remote.php/dav/ discovery) as well as generic CalDAV servers.
 */
class CalDavClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) : CalDavService {

    private data class DavHttpResponse(
        val code: Int,
        val body: String,
        val finalUrl: HttpUrl,
        val etag: String?,
        val location: String?
    )

    override suspend fun discoverTaskLists(config: ServerConfig): List<TaskList> = withContext(Dispatchers.IO) {
        // 1. Direct collection URL override
        if (config.directCollectionUrl.isNotBlank()) {
            val directUrl = normalizeUrl(config.directCollectionUrl)
                ?: throw CalDavException(null, "Invalid direct collection URL")
            val resp = executeDav(
                method = "PROPFIND",
                url = directUrl,
                config = config,
                xmlBody = CalDavXml.buildCollectionsRequest(),
                depth = "0"
            )
            ensureSuccess(resp, "Could not access task collection")
            val parsed = CalDavXml.parseCollections(resp.body).firstOrNull()
            val resolvedUrl = ensureTrailingSlash(resp.finalUrl.toString())
            val name = parsed?.displayName
                ?: resolvedUrl.trimEnd('/').substringAfterLast('/').ifBlank { "Tasks" }
            return@withContext listOf(
                TaskList(
                    url = resolvedUrl,
                    displayName = name,
                    colorHex = parsed?.colorHex ?: TaskList.DEFAULT_COLOR_HEX,
                    ctag = parsed?.ctag,
                    order = parsed?.order ?: 0,
                    readOnly = parsed?.readOnly ?: false
                )
            )
        }

        val baseUrl = normalizeUrl(config.serverUrl)
            ?: throw CalDavException(null, "Please enter a valid server URL (e.g. https://cloud.example.com)")

        // Build ordered discovery candidates:
        // 1) User-supplied URL (could already be /remote.php/dav/ or a direct calendar collection)
        // 2) Standard RFC 6764 /.well-known/caldav
        // 3) Nextcloud standard /remote.php/dav/
        val candidates = buildDiscoveryCandidates(baseUrl)
        var principalUrl: HttpUrl? = null
        var lastError: CalDavException? = null

        for (candidate in candidates) {
            val resp = try {
                executeDav(
                    method = "PROPFIND",
                    url = candidate,
                    config = config,
                    xmlBody = CalDavXml.buildCurrentUserPrincipalRequest(),
                    depth = "0"
                )
            } catch (e: CalDavException) {
                lastError = e
                continue
            }

            if (resp.code == 401 || resp.code == 403) {
                throw CalDavException(
                    resp.code,
                    "Authentication failed (${resp.code}). Check your username and App Password."
                )
            }

            if (resp.code in 200..299) {
                // Check if the supplied URL is ALREADY a direct VTODO calendar collection
                val directCollections = CalDavXml.parseCollections(resp.body)
                val directCal = directCollections.firstOrNull { it.isCalendar && it.supportsVTodo }
                if (directCal != null && candidate == baseUrl) {
                    val resolved = ensureTrailingSlash(resp.finalUrl.toString())
                    return@withContext listOf(
                        TaskList(
                            url = resolved,
                            displayName = directCal.displayName,
                            colorHex = directCal.colorHex ?: TaskList.DEFAULT_COLOR_HEX,
                            ctag = directCal.ctag,
                            order = directCal.order,
                            readOnly = directCal.readOnly
                        )
                    )
                }

                val principalHref = CalDavXml.parseCurrentUserPrincipal(resp.body)
                if (!principalHref.isNullOrBlank()) {
                    principalUrl = resp.finalUrl.resolve(principalHref)
                    if (principalUrl != null) break
                }
            }
        }

        if (principalUrl == null) {
            throw lastError ?: CalDavException(
                null,
                "Could not discover CalDAV endpoint at ${baseUrl.host}. Verify the URL or enter a direct collection URL."
            )
        }

        // 2. Discover calendar-home-set from current-user-principal
        val homeResp = executeDav(
            method = "PROPFIND",
            url = principalUrl,
            config = config,
            xmlBody = CalDavXml.buildCalendarHomeSetRequest(),
            depth = "0"
        )
        ensureSuccess(homeResp, "Failed to query CalDAV principal")
        val homeHref = CalDavXml.parseCalendarHomeSet(homeResp.body)
            ?: throw CalDavException(null, "Server did not return a calendar-home-set for this account")
        val homeUrl = homeResp.finalUrl.resolve(homeHref)
            ?: throw CalDavException(null, "Invalid calendar-home-set URL: $homeHref")

        // 3. List collections in calendar-home-set and filter for VTODO support
        val listResp = executeDav(
            method = "PROPFIND",
            url = homeUrl,
            config = config,
            xmlBody = CalDavXml.buildCollectionsRequest(),
            depth = "1"
        )
        ensureSuccess(listResp, "Failed to list CalDAV collections")

        val collections = CalDavXml.parseCollections(listResp.body)
            .filter { it.isCalendar && it.supportsVTodo }
            .mapNotNull { col ->
                val resolved = listResp.finalUrl.resolve(col.href) ?: return@mapNotNull null
                TaskList(
                    url = ensureTrailingSlash(resolved.toString()),
                    displayName = col.displayName,
                    colorHex = col.colorHex ?: TaskList.DEFAULT_COLOR_HEX,
                    ctag = col.ctag,
                    order = col.order,
                    readOnly = col.readOnly
                )
            }
            .sortedWith(compareBy<TaskList> { it.order }.thenBy { it.displayName.lowercase() })

        collections
    }

    override suspend fun fetchTasks(config: ServerConfig, list: TaskList): List<VTodoItem> =
        withContext(Dispatchers.IO) {
            val listHttpUrl = list.url.toHttpUrlOrNull()
                ?: throw CalDavException(null, "Invalid task list URL: ${list.url}")
            val resp = executeDav(
                method = "REPORT",
                url = listHttpUrl,
                config = config,
                xmlBody = CalDavXml.buildVTodoQueryRequest(),
                depth = "1"
            )
            ensureSuccess(resp, "Failed to fetch tasks from ${list.displayName}")

            val rawObjects = CalDavXml.parseCalendarObjects(resp.body)
            rawObjects.mapNotNull { obj ->
                val data = obj.calendarData ?: return@mapNotNull null
                val resolvedHref = resp.finalUrl.resolve(obj.href)?.toString() ?: obj.href
                ICalendarCodec.parseVTodo(
                    ics = data,
                    href = resolvedHref,
                    etag = obj.etag,
                    listUrl = list.url
                )
            }
        }

    override suspend fun createTask(
        config: ServerConfig,
        list: TaskList,
        item: VTodoItem
    ): VTodoItem = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val safeFileName = item.uid.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".ics"
        val targetUrlStr = ensureTrailingSlash(list.url) + safeFileName
        val targetUrl = targetUrlStr.toHttpUrlOrNull()
            ?: throw CalDavException(null, "Invalid target task URL: $targetUrlStr")

        val icsPayload = ICalendarCodec.serializeNewVTodo(item, now)
        val resp = executePutOrDelete(
            method = "PUT",
            url = targetUrl,
            config = config,
            icsBody = icsPayload,
            ifNoneMatch = "*"
        )
        ensureSuccess(resp, "Failed to create task on server")

        item.copy(
            href = resp.finalUrl.toString(),
            etag = resp.etag,
            listUrl = list.url,
            createdAt = item.createdAt ?: now,
            lastModifiedAt = now,
            rawIcs = icsPayload
        )
    }

    override suspend fun updateTask(config: ServerConfig, item: VTodoItem): VTodoItem =
        withContext(Dispatchers.IO) {
            val targetUrl = item.href.toHttpUrlOrNull()
                ?: throw CalDavException(null, "Invalid task URL: ${item.href}")
            val now = Instant.now()
            val icsPayload = ICalendarCodec.updateRawIcs(item, now)

            val resp = executePutOrDelete(
                method = "PUT",
                url = targetUrl,
                config = config,
                icsBody = icsPayload,
                ifMatch = item.etag
            )
            if (resp.code == 412) {
                throw CalDavException(412, "Task changed on server; refresh to sync latest version.")
            }
            ensureSuccess(resp, "Failed to update task on server")

            item.copy(
                etag = resp.etag ?: item.etag,
                lastModifiedAt = now,
                rawIcs = icsPayload
            )
        }

    override suspend fun deleteTask(config: ServerConfig, item: VTodoItem) {
        withContext(Dispatchers.IO) {
            val targetUrl = item.href.toHttpUrlOrNull()
                ?: throw CalDavException(null, "Invalid task URL: ${item.href}")
            val resp = executePutOrDelete(
                method = "DELETE",
                url = targetUrl,
                config = config,
                icsBody = null,
                ifMatch = item.etag
            )
            if (resp.code == 404) return@withContext // Already deleted on server
            ensureSuccess(resp, "Failed to delete task on server")
        }
    }

    private fun executeDav(
        method: String,
        url: HttpUrl,
        config: ServerConfig,
        xmlBody: String,
        depth: String
    ): DavHttpResponse {
        var currentUrl = url
        for (hop in 0 until MAX_REDIRECTS) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .method(method, xmlBody.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", depth)
                .header("Accept", "application/xml, text/xml")
                .header("User-Agent", USER_AGENT)
            applyAuthIfSameHost(reqBuilder, url, currentUrl, config)

            val resp = executeOnce(reqBuilder.build())
            if (resp.code in REDIRECT_CODES && !resp.location.isNullOrBlank()) {
                val nextUrl = currentUrl.resolve(resp.location) ?: return resp
                currentUrl = nextUrl
                continue
            }
            return resp
        }
        throw CalDavException(null, "Too many redirects from ${url.host}")
    }

    private fun executePutOrDelete(
        method: String,
        url: HttpUrl,
        config: ServerConfig,
        icsBody: String?,
        ifMatch: String? = null,
        ifNoneMatch: String? = null
    ): DavHttpResponse {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (method == "PUT") {
            reqBuilder.put((icsBody ?: "").toRequestBody(ICS_MEDIA_TYPE))
        } else {
            reqBuilder.delete()
        }
        if (!ifMatch.isNullOrBlank()) {
            reqBuilder.header("If-Match", ifMatch)
        }
        if (!ifNoneMatch.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", ifNoneMatch)
        }
        applyAuthIfSameHost(reqBuilder, url, url, config)
        return executeOnce(reqBuilder.build())
    }

    private fun executeOnce(request: Request): DavHttpResponse {
        try {
            httpClient.newCall(request).execute().use { response ->
                return DavHttpResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    finalUrl = response.request.url,
                    etag = response.header("ETag"),
                    location = response.header("Location")
                )
            }
        } catch (e: IOException) {
            throw CalDavException(null, "Network error: ${e.message ?: "Unable to reach server"}", e)
        }
    }

    private fun applyAuthIfSameHost(
        builder: Request.Builder,
        originalUrl: HttpUrl,
        currentUrl: HttpUrl,
        config: ServerConfig
    ) {
        if (config.username.isBlank() && config.password.isBlank()) return
        if (originalUrl.host.equals(currentUrl.host, ignoreCase = true)) {
            builder.header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
        }
    }

    private fun ensureSuccess(resp: DavHttpResponse, fallbackMessage: String) {
        if (resp.code in 200..299) return
        val detail = when (resp.code) {
            401 -> "Authentication failed (401). Check your username and App Password."
            403 -> "Permission denied (403). This task list may be read-only."
            404 -> "CalDAV resource not found (404)."
            412 -> "Conflict (412): Task changed on server."
            else -> "$fallbackMessage (HTTP ${resp.code})"
        }
        throw CalDavException(resp.code, detail)
    }

    internal fun buildDiscoveryCandidates(baseUrl: HttpUrl): List<HttpUrl> {
        val candidates = linkedSetOf<HttpUrl>()
        candidates.add(baseUrl)

        val origin = baseUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        origin.resolve("/.well-known/caldav")?.let { candidates.add(it) }

        val basePathWithSlash = ensureTrailingSlash(baseUrl.toString())
        basePathWithSlash.toHttpUrlOrNull()?.resolve(".well-known/caldav")?.let { candidates.add(it) }
        basePathWithSlash.toHttpUrlOrNull()?.resolve("remote.php/dav/")?.let { candidates.add(it) }
        origin.resolve("/remote.php/dav/")?.let { candidates.add(it) }

        return candidates.toList()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "VTodo-Googlebook/1.0"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICS_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun normalizeUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.toHttpUrlOrNull()
        }

        fun ensureTrailingSlash(url: String): String =
            if (url.endsWith("/")) url else "$url/"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}

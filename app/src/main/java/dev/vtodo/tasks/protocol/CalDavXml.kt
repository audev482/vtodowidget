package dev.vtodo.tasks.protocol

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class DiscoveredCollection(
    val href: String,
    val displayName: String,
    val colorHex: String?,
    val ctag: String?,
    val order: Int,
    val isCalendar: Boolean,
    val supportsVTodo: Boolean,
    val readOnly: Boolean
)

data class CalDavObjectResponse(
    val href: String,
    val etag: String?,
    val calendarData: String?
)

/**
 * RFC 4791 (CalDAV) & RFC 4918 (WebDAV) XML builder and namespace-aware Multi-Status parser.
 */
object CalDavXml {

    private const val NS_DAV = "DAV:"
    private const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
    private const val NS_CS = "http://calendarserver.org/ns/"
    private const val NS_APPLE = "http://apple.com/ns/ical/"

    fun buildCurrentUserPrincipalRequest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="$NS_DAV" xmlns:C="$NS_CALDAV">
          <D:prop>
            <D:current-user-principal/>
            <D:resourcetype/>
            <D:displayname/>
            <C:supported-calendar-component-set/>
            <CS:getctag xmlns:CS="$NS_CS"/>
            <A:calendar-color xmlns:A="$NS_APPLE"/>
            <D:current-user-privilege-set/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    fun buildCalendarHomeSetRequest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="$NS_DAV" xmlns:C="$NS_CALDAV">
          <D:prop>
            <C:calendar-home-set/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    fun buildCollectionsRequest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="$NS_DAV" xmlns:C="$NS_CALDAV" xmlns:CS="$NS_CS" xmlns:A="$NS_APPLE">
          <D:prop>
            <D:displayname/>
            <D:resourcetype/>
            <C:supported-calendar-component-set/>
            <CS:getctag/>
            <A:calendar-color/>
            <A:calendar-order/>
            <D:current-user-privilege-set/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    /**
     * Exact Nextcloud Tasks / RFC 4791 REPORT query to retrieve all VTODO objects in a collection.
     */
    fun buildVTodoQueryRequest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <C:calendar-query xmlns:D="$NS_DAV" xmlns:C="$NS_CALDAV">
          <D:prop>
            <D:getetag/>
            <C:calendar-data/>
          </D:prop>
          <C:filter>
            <C:comp-filter name="VCALENDAR">
              <C:comp-filter name="VTODO"/>
            </C:comp-filter>
          </C:filter>
        </C:calendar-query>
    """.trimIndent()

    fun parseCurrentUserPrincipal(xml: String): String? {
        val doc = parseDocument(xml) ?: return null
        for (response in childElements(doc.documentElement, NS_DAV, "response")) {
            for (prop in okProps(response)) {
                val principal = firstChild(prop, NS_DAV, "current-user-principal") ?: continue
                val href = firstChildText(principal, NS_DAV, "href")
                if (!href.isNullOrBlank()) return href.trim()
            }
        }
        return null
    }

    fun parseCalendarHomeSet(xml: String): String? {
        val doc = parseDocument(xml) ?: return null
        for (response in childElements(doc.documentElement, NS_DAV, "response")) {
            for (prop in okProps(response)) {
                val homeSet = firstChild(prop, NS_CALDAV, "calendar-home-set") ?: continue
                val href = firstChildText(homeSet, NS_DAV, "href")
                if (!href.isNullOrBlank()) return href.trim()
            }
        }
        return null
    }

    fun parseCollections(xml: String): List<DiscoveredCollection> {
        val doc = parseDocument(xml) ?: return emptyList()
        val result = mutableListOf<DiscoveredCollection>()

        for (response in childElements(doc.documentElement, NS_DAV, "response")) {
            val href = firstChildText(response, NS_DAV, "href")?.trim() ?: continue
            var displayName: String? = null
            var colorHex: String? = null
            var ctag: String? = null
            var order = 0
            var isCalendar = false
            var sawSupportedSet = false
            var supportsVTodo = false
            var sawPrivilegeSet = false
            var writable = true

            for (prop in okProps(response)) {
                firstChildText(prop, NS_DAV, "displayname")?.takeIf { it.isNotBlank() }?.let {
                    displayName = it.trim()
                }
                firstChildText(prop, NS_CS, "getctag")?.takeIf { it.isNotBlank() }?.let {
                    ctag = it.trim()
                }
                firstChildText(prop, NS_APPLE, "calendar-color")?.takeIf { it.isNotBlank() }?.let {
                    colorHex = normalizeHexColor(it.trim())
                }
                firstChildText(prop, NS_APPLE, "calendar-order")?.trim()?.toIntOrNull()?.let {
                    order = it
                }
                firstChild(prop, NS_DAV, "resourcetype")?.let { resourceType ->
                    if (firstChild(resourceType, NS_CALDAV, "calendar") != null) {
                        isCalendar = true
                    }
                }
                firstChild(prop, NS_CALDAV, "supported-calendar-component-set")?.let { compSet ->
                    sawSupportedSet = true
                    val comps = childElements(compSet, NS_CALDAV, "comp")
                    if (comps.any { it.getAttribute("name").equals("VTODO", ignoreCase = true) }) {
                        supportsVTodo = true
                    }
                }
                firstChild(prop, NS_DAV, "current-user-privilege-set")?.let { privSet ->
                    sawPrivilegeSet = true
                    val privileges = childElements(privSet, NS_DAV, "privilege")
                    writable = privileges.any { priv ->
                        firstChild(priv, NS_DAV, "write") != null ||
                            firstChild(priv, NS_DAV, "write-content") != null ||
                            firstChild(priv, NS_DAV, "all") != null
                    }
                }
            }

            // Per RFC 4791 §5.2.3, omitted supported-calendar-component-set means all components supported
            val effectiveSupportsVTodo = if (sawSupportedSet) supportsVTodo else isCalendar
            val fallbackName = href.trimEnd('/').substringAfterLast('/').ifBlank { "Tasks" }

            result += DiscoveredCollection(
                href = href,
                displayName = displayName ?: fallbackName,
                colorHex = colorHex,
                ctag = ctag,
                order = order,
                isCalendar = isCalendar,
                supportsVTodo = effectiveSupportsVTodo,
                readOnly = sawPrivilegeSet && !writable
            )
        }
        return result
    }

    fun parseCalendarObjects(xml: String): List<CalDavObjectResponse> {
        val doc = parseDocument(xml) ?: return emptyList()
        val results = mutableListOf<CalDavObjectResponse>()

        for (response in childElements(doc.documentElement, NS_DAV, "response")) {
            val href = firstChildText(response, NS_DAV, "href")?.trim() ?: continue
            var etag: String? = null
            var calendarData: String? = null

            for (prop in okProps(response)) {
                firstChildText(prop, NS_DAV, "getetag")?.takeIf { it.isNotBlank() }?.let {
                    etag = it.trim()
                }
                firstChildText(prop, NS_CALDAV, "calendar-data")?.takeIf { it.isNotBlank() }?.let {
                    calendarData = it
                }
            }

            if (calendarData != null) {
                results += CalDavObjectResponse(
                    href = href,
                    etag = etag,
                    calendarData = calendarData
                )
            }
        }
        return results
    }

    internal fun normalizeHexColor(raw: String): String? {
        val cleaned = raw.trim().removePrefix("#")
        return when {
            cleaned.length == 6 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                "#${cleaned.uppercase()}"
            cleaned.length == 8 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                // Apple/Nextcloud uses #RRGGBBAA; strip alpha channel to #RRGGBB
                "#${cleaned.substring(0, 6).uppercase()}"
            else -> null
        }
    }

    private fun parseDocument(xml: String): Document? {
        val cleaned = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (cleaned.isEmpty()) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Guard against XXE safely across Android & JVM
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
        }.getOrNull()
    }

    private fun okProps(response: Element): List<Element> {
        val props = mutableListOf<Element>()
        for (propstat in childElements(response, NS_DAV, "propstat")) {
            val status = firstChildText(propstat, NS_DAV, "status") ?: "200"
            if (status.contains(" 200") || status == "200") {
                firstChild(propstat, NS_DAV, "prop")?.let { props.add(it) }
            }
        }
        return props
    }

    private fun childElements(parent: Element, namespace: String, localName: String): List<Element> {
        val list = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                val elLocal = el.localName ?: el.nodeName.substringAfter(':')
                val nsMatches = el.namespaceURI == namespace ||
                    (el.namespaceURI == null && namespace == NS_DAV)
                if (nsMatches && elLocal.equals(localName, ignoreCase = true)) {
                    list.add(el)
                }
            }
        }
        return list
    }

    private fun firstChild(parent: Element, namespace: String, localName: String): Element? =
        childElements(parent, namespace, localName).firstOrNull()

    private fun firstChildText(parent: Element, namespace: String, localName: String): String? =
        firstChild(parent, namespace, localName)?.textContent
}

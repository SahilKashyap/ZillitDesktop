package com.zillit.desktop.feature.budgetbuilder

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zillit.desktop.feature.budgetbuilder.server.BudgetBuilderGateway
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loopback gateway, against fake upstreams.
 *
 * Everything the embedded page relies on is asserted through real HTTP —
 * the bridge landing ahead of the app's scripts, the API prefix swap, the
 * blob minted fresh per handshake — because each of these fails silently in
 * the browser: the page just sits on its splash screen with nothing in any
 * Kotlin log to say why.
 */
class BudgetBuilderGatewayTest {

    private lateinit var pageUpstream: HttpServer
    private lateinit var apiUpstream: HttpServer
    private lateinit var gateway: BudgetBuilderGateway
    private lateinit var origin: String

    private val blobAsks = AtomicInteger()
    private var blob: () -> String? = { "blob-${blobAsks.incrementAndGet()}" }

    /** What the API upstream saw last: method, path+query, moduledata, body. */
    private var seenMethod: String? = null
    private var seenPath: String? = null
    private var seenModuledata: String? = null
    private var seenBody: String? = null

    private val html = """
        |<!DOCTYPE html>
        |<html>
        |<head>
        |<meta charset="utf-8">
        |<script src="support.js"></script>
        |</head>
        |<body>app</body>
        |</html>
        |""".trimMargin()

    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        pageUpstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/budget-builder/") { exchange ->
                when {
                    exchange.requestURI.path.endsWith("index.html") ->
                        exchange.answer(200, "text/html; charset=utf-8", html) {
                            // The policies the real deployment attaches — written
                            // for its origin, and expected to be dropped.
                            it.responseHeaders.add("Content-Security-Policy", "frame-ancestors 'self';")
                            it.responseHeaders.add("X-Frame-Options", "DENY")
                        }

                    exchange.requestURI.path.endsWith("support.js") ->
                        exchange.answer(200, "text/javascript", "console.log('support')")

                    else -> exchange.answer(404, "text/plain", "missing")
                }
            }
            start()
        }
        apiUpstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seenMethod = exchange.requestMethod
                seenPath = exchange.requestURI.toString()
                seenModuledata = exchange.requestHeaders.getFirst("moduledata")
                seenBody = exchange.requestBody.readBytes().decodeToString()
                if (exchange.requestURI.path.endsWith("forbidden")) {
                    exchange.answer(403, "application/json", """{"message":"accountant_access_only"}""")
                } else {
                    exchange.answer(200, "application/json", """{"status":1}""")
                }
            }
            start()
        }
        gateway = BudgetBuilderGateway(
            pageUpstream = "http://127.0.0.1:${pageUpstream.address.port}",
            apiUpstream = "http://127.0.0.1:${apiUpstream.address.port}",
            moduledata = { blob() },
        )
        val pageUrl = gateway.start()
        origin = pageUrl.removeSuffix(BudgetBuilderGateway.PAGE_PATH)
    }

    @AfterTest
    fun stop() {
        gateway.stop()
        pageUpstream.stop(0)
        apiUpstream.stop(0)
    }

    private fun HttpExchange.answer(
        status: Int,
        type: String,
        body: String,
        decorate: (HttpExchange) -> Unit = {},
    ) {
        use {
            requestBody.readBytes()
            responseHeaders.set("Content-Type", type)
            decorate(this)
            val bytes = body.encodeToByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.write(bytes)
        }
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$origin$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the gateway serves on loopback only`() {
        assertTrue(origin.startsWith("http://127.0.0.1:"), origin)
    }

    @Test
    fun `the page arrives with the bridge ahead of the app's scripts`() {
        val response = get(BudgetBuilderGateway.PAGE_PATH)

        assertEquals(200, response.statusCode())
        val body = response.body()
        val bridge = body.indexOf("""<script src="/zillit-bridge.js"></script>""")
        val support = body.indexOf("""<script src="support.js">""")
        assertTrue(bridge in 1 until support, "bridge at $bridge, support at $support")
        // The upstream's framing policies are written for its origin, not
        // this one — forwarded, they only break the page's own assets.
        assertNull(response.headers().firstValue("content-security-policy").orElse(null))
        assertNull(response.headers().firstValue("x-frame-options").orElse(null))
    }

    @Test
    fun `assets pass through untouched`() {
        val response = get("/budget-builder/support.js")

        assertEquals(200, response.statusCode())
        assertEquals("console.log('support')", response.body())
    }

    /**
     * The one piece of routing knowledge under test: the page calls its
     * embed-mode default `/api/v2/budget/...`, the service answers on
     * `/v2/budget/...` — the `/api` prefix must come off, and everything
     * else (method, headers, body, query) must travel as sent.
     */
    @Test
    fun `api calls are forwarded with the prefix swapped and identity intact`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$origin/api/v2/budget/doc?rev=7"))
                .header("moduledata", "0abc123")
                .PUT(HttpRequest.BodyPublishers.ofString("""{"doc":{}}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode())
        assertEquals("""{"status":1}""", response.body())
        assertEquals("PUT", seenMethod)
        assertEquals("/v2/budget/doc?rev=7", seenPath)
        assertEquals("0abc123", seenModuledata)
        assertEquals("""{"doc":{}}""", seenBody)
    }

    /** A refusal is the app's to render — the gateway must not dress it up. */
    @Test
    fun `an upstream refusal passes through as itself`() {
        val response = get("/api/v2/budget/forbidden")

        assertEquals(403, response.statusCode())
        assertEquals("""{"message":"accountant_access_only"}""", response.body())
    }

    /**
     * A fresh blob per ask. The blob carries a timestamp, and the handshake
     * re-runs on every reload of the page — a blob minted once at start
     * would grow stale in a window left open overnight.
     */
    @Test
    fun `each handshake mints a fresh blob`() {
        assertEquals("blob-1", get("/zillit-moduledata").body())
        assertEquals("blob-2", get("/zillit-moduledata").body())
        assertEquals(2, blobAsks.get())
    }

    @Test
    fun `no session answers 503, not an empty 200`() {
        blob = { null }

        assertEquals(503, get("/zillit-moduledata").statusCode())
    }

    @Test
    fun `the bridge forces embed mode and answers the ready message`() {
        val response = get("/zillit-bridge.js")

        assertEquals(200, response.statusCode())
        val script = response.body()
        assertTrue("window.ZILLIT_EMBED = true" in script)
        assertTrue("zillit:ready" in script)
        assertTrue("zillit:moduledata" in script)
        assertTrue("/zillit-moduledata" in script)
    }

    @Test
    fun `anything else is refused`() {
        assertEquals(404, get("/etc/passwd").statusCode())
        assertEquals(404, get("/").statusCode())
    }

    @Test
    fun `starting twice serves one origin`() {
        val again = gateway.start()

        assertTrue(again.startsWith(origin), again)
        assertFalse(again.removePrefix(origin).contains("127.0.0.1"))
    }
}

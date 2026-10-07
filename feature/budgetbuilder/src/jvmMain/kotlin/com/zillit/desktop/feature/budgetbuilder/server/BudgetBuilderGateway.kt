package com.zillit.desktop.feature.budgetbuilder.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zillit.desktop.core.common.ZillitLog
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The loopback origin the Budget Builder runs on.
 *
 * ## Why the tool needs a local server at all
 *
 * Budget Builder is not a screen of this app: it is a complete web application
 * (the web client ships it in `public/budget-builder/`) that Zillit hosts
 * rather than reimplements. The web frames it same-origin and the app then
 * calls its API on another host — which works there only because the page and
 * the API arrange themselves around the browser's origin rules.
 *
 * The desktop cannot copy that arrangement directly:
 *
 *  - the budget service answers **no** `Access-Control-Allow-Origin` for any
 *    origin (verified against develop, 2026-08-13 — not even the web's own
 *    `https://dev.zillit.com` gets one), so a page loaded from anywhere else
 *    cannot fetch the API cross-origin;
 *  - the copy of the page the service itself hosts is a stale standalone
 *    build with no Zillit handshake in it (3.9k lines against the web
 *    deployment's 14.8k, and zero occurrences of `zillit:ready`).
 *
 * So this gateway makes everything one origin, the shape the app already
 * expects in embed mode: it serves the page by proxying the **web
 * deployment's** current copy, and forwards the app's API calls server-side —
 * where CORS does not exist — to the budget service. The local API prefix is
 * handed to the page on its own URL as `?api=`, exactly as
 * `BudgetBuilderEmbed.jsx` does, rather than relying on the page's embed-mode
 * default: the web passes it because the page cannot derive another host's
 * base, and matching that keeps one code path in the app under test.
 *
 * ## The identity handshake
 *
 * Framed in the web, the app posts `zillit:ready` to its parent and the host
 * answers with a credential. Loaded top-level in the desktop's embedded
 * Chromium there is no parent, but `window.parent` *is* the window itself, so
 * the app's "parent only" source check accepts a message the page posts to
 * itself. The gateway injects one script tag into the page's head — before the
 * app's own scripts — whose bridge forces embed mode and plays the host's part.
 *
 * What it answers with is now **two** credentials, in the web's order and for
 * the web's reasons (`BudgetBuilderEmbed.jsx`):
 *
 *  - `zillit:token`, a Zillit project Bearer, which is what the page prefers
 *    and the only credential the backend is keeping. It is short-lived (dev
 *    answers `expires_in=900`) while a budget session runs for hours, so this
 *    is not a one-shot hand-off: the bridge re-pushes on a timer, on the wake
 *    seam and when the network returns, and re-mints when the page asks again
 *    with `reauth` after a 401.
 *  - `zillit:moduledata`, the legacy encrypted blob, once per load. Sent
 *    first and un-gated because a **cached** older copy of the page
 *    understands nothing else — `public/` is deployed with no content hash,
 *    so a build that predates the token is exactly what a returning user can
 *    be running. Drop this half when the backend stops accepting blobs.
 *
 * ## The way out
 *
 * The page's own topbar carries "← Film Tools" and posts `zillit:exit`; the
 * web answers it by navigating away. The bridge forwards it here, and [onExit]
 * is how the host closes the tool — which is the whole reason the page may be
 * given the full window with no strip of our own above it.
 *
 * Loopback only, ephemeral port. The gateway carries no ambient credentials
 * beyond the two it is asked to hand the page, both of which any process
 * signed in as this user could obtain for itself.
 */
class BudgetBuilderGateway(
    pageUpstream: String,
    apiUpstream: String,
    /**
     * Mints a fresh moduledata blob, or null when one cannot be built (no
     * key, no open production). Called per handshake rather than once at
     * start, because the blob carries a timestamp and the handshake re-runs
     * on every reload of the page.
     */
    private val moduledata: () -> String?,
    /**
     * The project Bearer to hand the page, or null when none can be minted.
     *
     * `reauth` is the page's post-401 ask: the cache cannot know the server
     * refused the token it holds, so an ordinary ask would hand back the very
     * string that just failed. It means "discard that one and mint".
     */
    private val bearer: (reauth: Boolean) -> String?,
    /** The page asked to leave the tool (`zillit:exit`). */
    private val onExit: () -> Unit,
) {

    private val pageBase = pageUpstream.trimEnd('/')
    private val apiBase = apiUpstream.trimEnd('/')

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    private val upstream: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    /**
     * Starts the gateway, or returns the URL it is already serving.
     *
     * @return the page URL to open, `…/budget-builder/index.html?api=…`.
     */
    @Synchronized
    fun start(): String {
        server?.let { return pageUrl(it) }

        val threads = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
            Thread(runnable, "zillit-budget-gateway").apply { isDaemon = true }
        }
        // Loopback, not 0.0.0.0: this server exists for the embedded browser
        // beside it, and must not be reachable from the network.
        val local = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        local.executor = threads
        local.createContext("/") { exchange -> exchange.use(::route) }
        local.start()

        server = local
        executor = threads
        ZillitLog.i(TAG) { "gateway up on 127.0.0.1:${local.address.port}" }
        return pageUrl(local)
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private fun pageUrl(server: HttpServer): String =
        "http://$LOOPBACK:${server.address.port}$PAGE_PATH?$API_PARAM=$API_PARAM_VALUE"

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path.orEmpty()
        runCatching {
            when {
                path == BRIDGE_PATH -> respond(exchange, HTTP_OK, JS_TYPE, bridgeScript().encodeToByteArray())
                path == MODULEDATA_PATH -> serveCredential(exchange) { moduledata() }
                path == TOKEN_PATH -> serveCredential(exchange) { bearer(exchange.asksReauth()) }
                path == EXIT_PATH -> serveExit(exchange)
                path.startsWith(PAGE_PREFIX) -> proxyPage(exchange)
                path.startsWith(API_PREFIX) -> proxyApi(exchange)
                else -> respond(exchange, HTTP_NOT_FOUND, TEXT_TYPE, "not found".encodeToByteArray())
            }
        }.onFailure { thrown ->
            // A gateway that dies silently reads as a blank page; 502 with the
            // class name at least says which side fell over.
            ZillitLog.w(TAG) { "$path failed: ${thrown::class.simpleName} ${thrown.message}" }
            runCatching {
                respond(exchange, HTTP_BAD_GATEWAY, TEXT_TYPE, "upstream unavailable".encodeToByteArray())
            }
        }
    }

    /** True for the page's post-401 ask — `?reauth=1` on the token request. */
    private fun HttpExchange.asksReauth(): Boolean =
        requestURI.rawQuery?.contains("$REAUTH_PARAM=1") == true

    /**
     * One credential, as plain text.
     *
     * 503 rather than an empty 200 when there is none: the bridge only posts
     * what it actually received, and a blank credential delivered as a
     * success would satisfy the page's waiter and boot it unauthenticated.
     */
    private fun serveCredential(exchange: HttpExchange, mint: () -> String?) {
        val value = runCatching { mint() }.getOrNull()
        if (value.isNullOrBlank()) {
            respond(exchange, HTTP_UNAVAILABLE, TEXT_TYPE, "no session".encodeToByteArray())
        } else {
            respond(exchange, HTTP_OK, TEXT_TYPE, value.encodeToByteArray())
        }
    }

    private fun serveExit(exchange: HttpExchange) {
        runCatching { onExit() }
            .onFailure { thrown -> ZillitLog.w(TAG) { "exit refused: ${thrown.message}" } }
        respond(exchange, HTTP_NO_CONTENT, null, ByteArray(0))
    }

    /**
     * The page and its assets, from the web deployment.
     *
     * `Accept-Encoding` is stripped so the upstream answers identity-encoded
     * bytes — the HTML must be readable to inject into, and one rule for
     * every asset beats a special case (these are five files on a loopback
     * hop; compression buys nothing here).
     */
    private fun proxyPage(exchange: HttpExchange) {
        val answer = fetch(exchange, targetUrl(pageBase, exchange, stripApiPrefix = false), sendEncoding = false)
        val type = answer.headers().firstValue(CONTENT_TYPE).orElse("")
        val body = if (type.contains(HTML_TYPE_MARKER)) {
            injectBridge(answer.body().decodeToString()).encodeToByteArray()
        } else {
            answer.body()
        }
        copyResponseHeaders(answer, exchange, dropPagePolicies = true)
        respond(exchange, answer.statusCode(), type.ifEmpty { null }, body)
    }

    /** The app's API calls, forwarded server-side to the budget service. */
    private fun proxyApi(exchange: HttpExchange) {
        val answer = fetch(exchange, targetUrl(apiBase, exchange, stripApiPrefix = true), sendEncoding = true)
        copyResponseHeaders(answer, exchange, dropPagePolicies = false)
        val type = answer.headers().firstValue(CONTENT_TYPE).orElse(null)
        respond(exchange, answer.statusCode(), type, answer.body())
    }

    /**
     * Maps the local path onto an upstream URL.
     *
     * The API prefix swap is the one piece of routing knowledge here: the
     * page calls `/api/v2/budget/...` (what it is handed as `?api=`, and its
     * embed-mode default besides), while the service answers on
     * `/v2/budget/...` — verified against develop, where the `/api`-prefixed
     * form is `not_found`.
     */
    private fun targetUrl(base: String, exchange: HttpExchange, stripApiPrefix: Boolean): String {
        val uri = exchange.requestURI
        val path = if (stripApiPrefix) uri.path.removePrefix(API_STRIP) else uri.path
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$base$path$query"
    }

    private fun fetch(exchange: HttpExchange, url: String, sendEncoding: Boolean): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        exchange.requestHeaders.forEach { (name, values) ->
            val lower = name.lowercase()
            val blocked = lower in RESTRICTED_HEADERS || (!sendEncoding && lower == ACCEPT_ENCODING)
            if (!blocked) values.forEach { value -> request.header(name, value) }
        }
        val body = exchange.requestBody.readBytes()
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        request.method(exchange.requestMethod, publisher)
        return upstream.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun copyResponseHeaders(
        answer: HttpResponse<ByteArray>,
        exchange: HttpExchange,
        dropPagePolicies: Boolean,
    ) {
        answer.headers().map().forEach { (name, values) ->
            val lower = name.lowercase()
            val policy = dropPagePolicies && lower in PAGE_POLICY_HEADERS
            if (lower !in UNFORWARDED_RESPONSE_HEADERS && !policy) {
                values.forEach { value -> exchange.responseHeaders.add(name, value) }
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String?, body: ByteArray) {
        contentType?.let { exchange.responseHeaders.set(CONTENT_TYPE, it) }
        // -1 means "no body": required for statuses that must not carry one,
        // where writing anything raises on some JDKs and hangs on others.
        val bodiless = body.isEmpty() || status == HTTP_NO_CONTENT || status == HTTP_NOT_MODIFIED
        exchange.sendResponseHeaders(status, if (bodiless) -1 else body.size.toLong())
        if (!bodiless) exchange.responseBody.use { it.write(body) }
    }

    /**
     * Splices the bridge into the document head, ahead of the app's scripts.
     *
     * A script tag in the served HTML rather than `executeJavaScript` from
     * Kotlin, because injection must beat the app's constructor — it reads
     * `window.ZILLIT_EMBED` exactly once — and synchronous script order in
     * the document is the only sequencing the browser actually guarantees.
     */
    private fun injectBridge(html: String): String {
        val head = HEAD_TAG.find(html) ?: return BRIDGE_TAG + html
        val insertAt = head.range.last + 1
        return html.substring(0, insertAt) + BRIDGE_TAG + html.substring(insertAt)
    }

    /**
     * The page-side half of the handshake — the desktop's
     * `BudgetBuilderEmbed.jsx`.
     *
     * Loaded top-level, `window.parent` is the window itself, so the app's
     * `e.source !== window.parent` check accepts a self-posted message, and
     * its `e.origin !== location.origin` check accepts this gateway's origin.
     * Both credentials are fetched from here per push rather than baked into
     * this script: the blob carries a timestamp, and the token expires.
     *
     * Addressed to our own origin and never `'*'`, as the web's host is —
     * nothing else is listening on a top-level window, but the page's own
     * handshake makes the same check in the other direction and the two
     * halves should not disagree about the rule.
     *
     * The three top-up triggers are the web's, for the web's reasons: a timer
     * for someone simply working for an hour, `visibilitychange` for the wake
     * seam (no timer ticks through OS sleep, and Chromium throttles a hidden
     * page — which is exactly what this one is while the tool's tab is away),
     * and `online` for the network coming back. None is load-bearing on its
     * own; the page's own `reauth` retry is what makes a stale token correct,
     * and these only make it rare.
     */
    private fun bridgeScript(): String = """
        |// Injected by Zillit Desktop. Forces the app's Zillit embed mode and
        |// plays the host's part of its identity handshake, in place of a
        |// parent frame — see BudgetBuilderGateway.
        |window.ZILLIT_EMBED = true;
        |(function () {
        |  'use strict';
        |  var origin = window.location.origin;
        |  var sentBlob = false;
        |  function post(message) {
        |    try { window.postMessage(message, origin); } catch (e) {}
        |  }
        |  function pushToken(reauth) {
        |    fetch('$TOKEN_PATH' + (reauth ? '?$REAUTH_PARAM=1' : ''))
        |      .then(function (r) { return r.ok ? r.text() : null; })
        |      .then(function (token) {
        |        if (token) post({ type: 'zillit:token', token: token });
        |      })
        |      .catch(function () {});
        |  }
        |  // Once per load, and never awaited ahead of the token: the page
        |  // prefers the Bearer and only a cached old build needs this.
        |  function pushBlob() {
        |    if (sentBlob) return;
        |    sentBlob = true;
        |    fetch('$MODULEDATA_PATH')
        |      .then(function (r) { return r.ok ? r.text() : null; })
        |      .then(function (blob) {
        |        if (blob) post({ type: 'zillit:moduledata', moduledata: blob });
        |      })
        |      .catch(function () {});
        |  }
        |  window.addEventListener('message', function (event) {
        |    if (event.source !== window) return;
        |    var data = event.data;
        |    if (!data) return;
        |    if (data.type === 'zillit:ready') {
        |      pushBlob();
        |      pushToken(data.reauth === true);
        |    } else if (data.type === 'zillit:exit') {
        |      fetch('$EXIT_PATH', { method: 'POST' }).catch(function () {});
        |    }
        |  });
        |  setInterval(function () { pushToken(false); }, $TOP_UP_MILLIS);
        |  window.addEventListener('online', function () { pushToken(false); });
        |  document.addEventListener('visibilitychange', function () {
        |    if (document.visibilityState === 'visible') pushToken(false);
        |  });
        |})();
        |""".trimMargin()

    companion object {
        private const val TAG = "BudgetGateway"

        const val PAGE_PATH = "/budget-builder/index.html"
        private const val PAGE_PREFIX = "/budget-builder/"
        private const val API_PREFIX = "/api/v2/budget"
        private const val API_STRIP = "/api"
        private const val BRIDGE_PATH = "/zillit-bridge.js"
        private const val MODULEDATA_PATH = "/zillit-moduledata"
        private const val TOKEN_PATH = "/zillit-token"
        private const val EXIT_PATH = "/zillit-exit"
        private const val REAUTH_PARAM = "reauth"
        private const val API_PARAM = "api"
        private const val BRIDGE_TAG = "<script src=\"$BRIDGE_PATH\"></script>"
        private val HEAD_TAG = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)

        /** The web's top-up cadence (`setInterval(topUp, 60000)`). */
        private const val TOP_UP_MILLIS = 60_000

        /** `?api=` as the page receives it: this gateway's own API prefix. */
        private val API_PARAM_VALUE: String = URLEncoder.encode(API_PREFIX, StandardCharsets.UTF_8)

        private const val LOOPBACK = "127.0.0.1"
        private const val WORKER_THREADS = 8
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 60L

        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_BAD_GATEWAY = 502
        private const val HTTP_UNAVAILABLE = 503

        private const val CONTENT_TYPE = "Content-Type"
        private const val ACCEPT_ENCODING = "accept-encoding"
        private const val HTML_TYPE_MARKER = "text/html"
        private const val JS_TYPE = "text/javascript; charset=utf-8"
        private const val TEXT_TYPE = "text/plain; charset=utf-8"

        /** Headers `java.net.http` refuses to set, or that name this hop. */
        private val RESTRICTED_HEADERS = setOf(
            "host", "connection", "content-length", "expect", "upgrade",
            "keep-alive", "transfer-encoding", "te", "trailer",
        )

        /** Hop-by-hop response headers; the local server frames its own. */
        private val UNFORWARDED_RESPONSE_HEADERS = setOf(
            "connection", "keep-alive", "transfer-encoding", "upgrade",
            "content-length", ":status",
        )

        /**
         * Policies written for the upstream's origin, wrong for this one.
         * `frame-ancestors 'self'` and `X-Frame-Options: DENY` are aimed at
         * the web deployment's framing story; the desktop loads the page
         * top-level, and a CSP scoped to another origin only breaks assets.
         */
        private val PAGE_POLICY_HEADERS = setOf(
            "content-security-policy", "x-frame-options",
        )
    }
}

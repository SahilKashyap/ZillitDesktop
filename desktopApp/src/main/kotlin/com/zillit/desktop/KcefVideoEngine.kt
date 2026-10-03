package com.zillit.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.designsystem.component.VideoEngine
import com.zillit.desktop.core.designsystem.component.VideoPlayback
import com.zillit.desktop.core.designsystem.component.VideoPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import java.awt.Component
import java.io.File
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * The clips JavaFX will not take, played by the Chromium the app already
 * ships.
 *
 * The two engines are exact complements, which is why both are here rather
 * than one being chosen. Measured on this runtime, not assumed:
 *
 *     JavaFX   H.264/AAC in mp4 and mov   ✓     WebM/VP8/VP9  ✗
 *     Chromium WebM/VP8/VP9/AV1/Opus      ✓     H.264/AAC     ✗
 *
 * JetBrains builds JCEF without the proprietary codecs, so Chromium cannot
 * play what a camera or a phone produces ([FxVideoEngine] has the numbers).
 * JavaFX, in turn, answers `MEDIA_UNSUPPORTED: Unrecognized file signature!`
 * to a WebM — and the web client records with `MediaRecorder`, which writes
 * WebM, so those arrive in production boards and chats routinely.
 *
 * ## The transport stays ours
 *
 * The page could carry `<video controls>` and save all the bridging below,
 * but then a WebM would have Chromium's transport and an MP4 this app's, in
 * the same viewer. Instead the page reports its state over `window.cefQuery`
 * as one line of JSON per event, and takes play/pause/seek/mute as
 * JavaScript — so [VideoPlayback] looks the same whichever engine is behind
 * it, and the viewer never knows.
 *
 * ## An unfinished recording has no length
 *
 * `MediaRecorder` writes no duration into the header, so the page reports
 * `Infinity` and the scrubber has nothing to scrub within. That is carried
 * through as a zero duration, which the viewer already understands: the clock
 * runs, the bar does not offer a seek. Ending a stall on it would be wrong —
 * the clip plays perfectly well.
 */
internal object KcefVideoEngine : VideoEngine {

    override fun open(url: String): VideoPlayback = KcefPlayback(url).also { it.start() }

    @Composable
    override fun Picture(playback: VideoPlayback, modifier: Modifier) {
        val cef = playback as? KcefPlayback ?: return
        val surface by cef.component.collectAsState()
        Box(modifier.fillMaxSize()) {
            surface?.let { SwingPanel(factory = { it }, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** One clip in its own browser, for as long as the viewer shows it. */
internal class KcefPlayback(private val url: String) : VideoPlayback {

    private val _state = MutableStateFlow(VideoPlaybackState())
    override val state: StateFlow<VideoPlaybackState> = _state.asStateFlow()

    /** Non-null once the browser has a component to show. */
    val component = MutableStateFlow<Component?>(null)

    @Volatile
    private var client: CefClient? = null

    @Volatile
    private var browser: CefBrowser? = null

    @Volatile
    private var closed = false

    private var page: File? = null

    /**
     * Where the browser lives until the viewer shows it.
     *
     * Not an optimisation. JCEF only creates the native browser from a
     * **realised** AWT peer, and the viewer does not mount the picture until
     * playback has started — which cannot happen until the page loads, which
     * cannot happen until the component is in a window. Parking it in an
     * invisible one breaks that circle; the `SwingPanel` reparents it away
     * from here when it mounts. The same trick the crew-list canvas uses, for
     * the same reason.
     */
    private var holder: JWindow? = null

    fun start() {
        Thread({ open() }, "zillit-webm-open").apply {
            isDaemon = true
            start()
        }
    }

    private fun open() {
        val file = write() ?: return fail("page not written")
        // `client()` starts Chromium on first use and so is suspending; this
        // is a thread of its own, created for exactly this wait.
        val cef = runBlocking { KcefRuntime.client() } ?: return fail("no chromium")
        if (closed) return
        client = cef
        cef.addMessageRouter(KcefPage.messageRouter(::onPageEvent))
        cef.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        SwingUtilities.invokeLater {
            if (closed) return@invokeLater
            val made = cef.createBrowser("file://${file.absolutePath}", CefRendering.DEFAULT, false)
            browser = made
            park(made.uiComponent)
            component.value = made.uiComponent
            ZillitLog.i(TAG) { "webm browser created" }
        }
    }

    /**
     * One line of JSON per event from the page.
     *
     * Parsed by hand rather than with a serializer: it is four keys this file
     * writes and this file reads, and a schema for that is more moving parts
     * than the thing it describes.
     */
    private fun onPageEvent(message: String) {
        if (closed) return
        val event = message.field("e") ?: return
        when (event) {
            "ready" -> {
                ZillitLog.i(TAG) { "webm ready: ${message.number("d").toMillis()} ms" }
                _state.update { it.copy(durationMillis = message.number("d").toMillis()) }
            }
            "time" -> _state.update { it.copy(positionMillis = message.number("t").toMillis()) }
            "play" -> _state.update { it.copy(isPlaying = true, started = true) }
            "pause", "end" -> _state.update { it.copy(isPlaying = false) }
            "error" -> fail("the page reported code ${message.field("c").orEmpty()}")
            else -> Unit
        }
    }

    /** An invisible window, far enough out that no display arrangement reaches it. */
    private fun park(surface: Component) {
        val window = JWindow().also { made ->
            made.focusableWindowState = false
            runCatching { made.opacity = 0f }
            holder = made
        }
        window.contentPane.add(surface)
        window.setBounds(HOLDER_OFFSCREEN, HOLDER_OFFSCREEN, HOLDER_SIDE, HOLDER_SIDE)
        window.isVisible = true
        window.toBack()
    }

    private fun fail(reason: String) {
        ZillitLog.w(TAG) { "webm playback failed: $reason" }
        _state.update { it.copy(failed = true, isPlaying = false) }
    }

    override fun toggle() = run("zv.toggle()")

    override fun seek(fraction: Float) = run("zv.seek(${fraction.coerceIn(0f, 1f)})")

    override fun toggleMute() {
        run("zv.mute()")
        _state.update { it.copy(isMuted = !it.isMuted) }
    }

    private fun run(script: String) {
        val made = browser ?: return
        if (closed) return
        runCatching { made.executeJavaScript(script, made.url, 0) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val openBrowser = browser
        val openClient = client
        browser = null
        client = null
        component.value = null
        // Closing the browser is what stops the sound; nothing else is asked to.
        runCatching { openBrowser?.close(true) }
        runCatching { openClient?.dispose() }
        // After the browser: the window that hosted its native view goes last,
        // or the view is orphaned in a window nobody can reach.
        val window = holder
        holder = null
        if (window != null) SwingUtilities.invokeLater { window.dispose() }
        page?.let { file -> runCatching { file.delete() } }
        page = null
    }

    /** The page, with the signed URL in it. Deleted in [close]. */
    private fun write(): File? = runCatching {
        val directory = File(com.zillit.desktop.core.common.ZillitVariant.dataDir, "video")
        directory.mkdirs()
        File(directory, "webm-${System.identityHashCode(this)}.html").also { file ->
            file.writeText(playerHtml(url))
            page = file
        }
    }.onFailure { thrown -> ZillitLog.w(TAG) { "page not written: ${thrown.message}" } }.getOrNull()

    private companion object {
        const val TAG = "KcefVideoEngine"
        const val MILLIS = 1000.0
        const val HOLDER_OFFSCREEN = -4000
        const val HOLDER_SIDE = 640

        /** The value for [key] in a one-level JSON object, as text. */
        fun String.field(key: String): String? =
            substringAfter("\"$key\":", "").takeIf { it.isNotEmpty() }
                ?.trimStart()
                ?.trim('"')
                ?.substringBefore(',')
                ?.substringBefore('}')
                ?.trim('"')

        fun String.number(key: String): Double = field(key)?.toDoubleOrNull() ?: 0.0

        /**
         * Seconds to milliseconds, with the unfinished recording's `Infinity`
         * — and a `NaN` before metadata — reading as "no length", which the
         * viewer already draws as an unseekable bar.
         */
        fun Double.toMillis(): Long =
            takeIf { it.isFinite() && it >= 0 }?.let { (it * MILLIS).toLong() } ?: 0L
    }
}

/**
 * The page: one `<video>` on black, reporting itself over `window.cefQuery`.
 *
 * `controls` is deliberately absent — the viewer draws the transport, so
 * Chromium's own would be a second one inside the first.
 */
private fun playerHtml(url: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>video</title><style>
      html,body{margin:0;height:100%;background:#000;overflow:hidden}
      video{display:block;width:100%;height:100%;background:#000;object-fit:contain}
    </style></head>
    <body>
      <video id="v" autoplay preload="auto"></video>
      <script>
        var v = document.getElementById('v');
        function send(o) { if (window.cefQuery) window.cefQuery({ request: JSON.stringify(o), persistent: false }); }
        v.addEventListener('loadedmetadata', function () { send({ e: 'ready', d: v.duration }); });
        v.addEventListener('durationchange', function () { send({ e: 'ready', d: v.duration }); });
        v.addEventListener('timeupdate', function () { send({ e: 'time', t: v.currentTime }); });
        v.addEventListener('playing', function () { send({ e: 'play' }); });
        v.addEventListener('pause', function () { send({ e: 'pause' }); });
        v.addEventListener('ended', function () { send({ e: 'end' }); });
        v.addEventListener('error', function () { send({ e: 'error', c: (v.error && v.error.code) || 0 }); });
        window.zv = {
          toggle: function () {
            // A clip sitting at its end plays from there and goes nowhere.
            if (v.ended) { v.currentTime = 0; v.play(); }
            else if (v.paused) { v.play(); } else { v.pause(); }
          },
          seek: function (f) { if (isFinite(v.duration)) v.currentTime = f * v.duration; },
          mute: function () { v.muted = !v.muted; },
        };
        v.src = ${jsString(url)};
        var started = v.play();
        if (started && started.catch) started.catch(function () {});
      </script>
    </body></html>
    """.trimIndent()

/**
 * A URL as a JavaScript string literal.
 *
 * Quote and backslash because they end the literal; `<` because a `</script`
 * anywhere in the text closes the block that contains it, whatever the quoting
 * says. A presigned S3 URL is percent-encoded ASCII and carries none of them —
 * this is here so that stays true of the next URL as well.
 */
private fun jsString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

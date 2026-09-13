package com.zillit.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.crewlist.ui.CanvasDocument
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Component
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import java.io.File
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Customise & Preview's canvas: the backend's crew-list HTML in embedded
 * Chromium, the arranger's grips working in the page, and every change it
 * reports carried back over `window.cefQuery` ([KcefPage.messageRouter]).
 *
 * One browser per opening of the dialog, closed with it. While something is
 * drawn over the canvas the browser's component is hidden rather than removed:
 * a JCEF component that loses its parent stops being a browser.
 *
 * The first page loads out of sight. A starting browser is a blank white box
 * for seconds, so its component waits in an invisible window — the map and
 * call engines' trick: JCEF only creates the native browser from a realised
 * AWT peer — while the dialog keeps its document skeleton up, and is handed to
 * the pane once that page is in. The holder goes the moment the pane takes the
 * component, so nothing is left to keep the process alive at quit.
 *
 * Documents arrive as HTML strings and are written to `~/.zillit/crewlist/`
 * for Chromium to load as files, the way the map and call pages load theirs.
 */
internal val crewListCanvas: CrewCanvas = { document, obscured, onMessage, placeholder, modifier ->
    CrewCanvasPane(document, obscured, onMessage, placeholder, modifier)
}

@Composable
private fun CrewCanvasPane(
    document: CanvasDocument?,
    obscured: Boolean,
    onMessage: (String) -> Unit,
    placeholder: @Composable () -> Unit,
    modifier: Modifier,
) {
    val session = remember { CrewCanvasSession() }
    DisposableEffect(session) { onDispose { session.close() } }
    val reportMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(session) { session.messages.collect { reportMessage(it) } }
    LaunchedEffect(session, document?.key) { document?.let { session.show(it) } }

    val component by session.component.collectAsState()
    val failure by session.failure.collectAsState()
    val density = LocalDensity.current
    // AWT lays out in points: the first page is parked at the size it will be shown.
    Box(modifier.onSizeChanged { size -> session.viewport = size.toPoints(density.density) }) {
        val surface = component
        when {
            failure != null -> ZillitText(
                text = failure.orEmpty(),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            surface == null -> if (document != null) placeholder()
            else -> SwingPanel(
                factory = { surface },
                update = { it.isVisible = !obscured },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun androidx.compose.ui.unit.IntSize.toPoints(density: Float) =
    Dimension((width / density).toInt(), (height / density).toInt())

/** One dialog-long browser: created on the first document, shown each new one, closed with the dialog. */
private class CrewCanvasSession {

    val component = MutableStateFlow<Component?>(null)
    val failure = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)

    /** The pane's size in points, for the parked first page. */
    @Volatile
    var viewport: Dimension? = null

    private val lock = Mutex()
    private val directory = File(System.getProperty("user.home"), ".zillit/crewlist")
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var shownKey: Int? = null
    private val written = mutableListOf<File>()

    // Touched on the EDT only.
    private var holder: JWindow? = null
    private var revealTimer: Timer? = null

    @Volatile
    private var created = false

    @Volatile
    private var pendingUrl: String? = null

    @Volatile
    private var closed = false

    suspend fun show(document: CanvasDocument) = lock.withLock {
        if (closed || shownKey == document.key) return@withLock
        val file = withContext(Dispatchers.IO) { write(document) } ?: run {
            failure.value = "The preview could not be prepared."
            return@withLock
        }
        val url = "file://${file.absolutePath}"
        val existing = browser
        if (existing == null) {
            val cef = client ?: KcefRuntime.client()?.also { client = it }
            if (cef == null) {
                failure.value = unavailableReason()
                return@withLock
            }
            withContext(Dispatchers.Swing) { open(cef, url) }
        } else if (created) {
            withContext(Dispatchers.Swing) { existing.loadURL(url) }
        } else {
            // The native browser is not up yet; it loads this the moment it is.
            pendingUrl = url
        }
        shownKey = document.key
        withContext(Dispatchers.IO) { forgetOlderThan(file) }
    }

    private fun open(cef: CefClient, url: String) {
        if (closed) return
        cef.addMessageRouter(KcefPage.messageRouter { message -> messages.tryEmit(message) })
        // JCEF keeps only a client's first load handler: this one logs and reveals.
        cef.addLoadHandler(loadHandler())
        cef.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        cef.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser?) {
                    created = true
                    pendingUrl?.let { waiting ->
                        pendingUrl = null
                        browser?.loadURL(waiting)
                    }
                }
            },
        )
        val made = cef.createBrowser(url, CefRendering.DEFAULT, false)
        browser = made
        park(made.uiComponent)
        // A page that never reports in is shown anyway rather than waited on for ever.
        revealTimer = Timer(REVEAL_TIMEOUT_MS) { reveal() }.apply {
            isRepeats = false
            start()
        }
        ZillitLog.i(TAG) { "canvas browser created" }
    }

    private fun loadHandler(): CefLoadHandler {
        val notes = KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } }
        return object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatus: Int) {
                notes.onLoadEnd(browser, frame, httpStatus)
                if (frame?.isMain != false) SwingUtilities.invokeLater(::reveal)
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                notes.onLoadError(browser, frame, errorCode, errorText, failedUrl)
                if (frame?.isMain != false) SwingUtilities.invokeLater(::reveal)
            }
        }
    }

    /**
     * Keeps the new browser's component in a window nobody sees until its first
     * page is in. The pane taking the component is the holder's cue to go.
     */
    private fun park(surface: Component) {
        val window = JWindow().apply {
            focusableWindowState = false
            runCatching { opacity = 0f }
        }
        holder = window
        surface.addHierarchyListener { event ->
            val moved = (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong()) != 0L
            if (moved && SwingUtilities.getWindowAncestor(surface) !== window) releaseHolder()
        }
        window.contentPane.add(surface)
        window.isVisible = true
        val size = viewport ?: Dimension(HOLDER_SIZE, HOLDER_SIZE)
        window.setBounds(
            HOLDER_OFFSCREEN,
            HOLDER_OFFSCREEN,
            size.width.coerceAtLeast(MIN_HOLDER_SIDE),
            size.height.coerceAtLeast(MIN_HOLDER_SIDE),
        )
        window.validate()
        window.toBack()
    }

    /** The first page is in, or is taking too long: the pane may have the browser. */
    private fun reveal() {
        revealTimer?.stop()
        revealTimer = null
        if (closed) return
        val made = browser ?: return
        if (component.value == null) component.value = made.uiComponent
    }

    private fun releaseHolder() {
        val window = holder ?: return
        holder = null
        SwingUtilities.invokeLater { window.dispose() }
    }

    fun close() {
        closed = true
        revealTimer?.stop()
        revealTimer = null
        val openBrowser = browser
        val openClient = client
        browser = null
        client = null
        component.value = null
        runCatching { openBrowser?.close(true) }
        runCatching { openClient?.dispose() }
        releaseHolder()
        val files = synchronized(written) { written.toList().also { written.clear() } }
        files.forEach { runCatching { it.delete() } }
    }

    /** Written fresh per document: a reload must never read a half-overwritten file. */
    private fun write(document: CanvasDocument): File? = runCatching {
        directory.mkdirs()
        File(directory, "canvas-${System.identityHashCode(this)}-${document.key}.html").also { file ->
            file.writeText(document.html)
            synchronized(written) { written += file }
        }
    }.onFailure { thrown -> ZillitLog.w(TAG) { "canvas page not written: ${thrown.message}" } }.getOrNull()

    /** The page on screen stays; the ones before it go. */
    private fun forgetOlderThan(current: File) {
        val stale = synchronized(written) {
            written.filter { it != current }.also { written.retainAll(listOf(current)) }
        }
        stale.forEach { runCatching { it.delete() } }
    }

    private fun unavailableReason(): String = when (val reason = KcefRuntime.failure) {
        KcefRuntime.Failure.NoJcefRuntime -> "This build has no embedded browser, so the preview cannot be shown."
        is KcefRuntime.Failure.Broken -> "The embedded browser could not start (${reason.reason})."
        null -> "The embedded browser is unavailable."
    }

    private companion object {
        const val TAG = "CrewListCanvas"
        const val MESSAGE_BUFFER = 32

        /** Long enough for a cold browser and a heavy letterhead; short enough not to strand anyone. */
        const val REVEAL_TIMEOUT_MS = 8_000

        /** Far enough out that no arrangement of displays reaches it. */
        const val HOLDER_OFFSCREEN = -8_000

        /** Before the pane has been measured. */
        const val HOLDER_SIZE = 640

        /** Big enough that AWT treats the window as real. */
        const val MIN_HOLDER_SIDE = 200
    }
}

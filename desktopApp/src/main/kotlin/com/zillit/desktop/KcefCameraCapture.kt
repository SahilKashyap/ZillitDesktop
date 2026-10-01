package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitVariant
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.Base64
import javax.swing.JDialog

/**
 * One photograph from the computer's camera, through the embedded Chromium the call engine already runs.
 *
 * The shared modules have no capture API and the JVM has no camera class, but Chromium has `getUserMedia`: this opens a
 * small window hosting a bundled page (`camera/capture.html`) that shows the camera with a page-shaped guide and a
 * Capture button, and hands the frame back as a JPEG over the CEF message router — the call engine's own transport.
 * Used by Costumes & Set Sync's document scanner and QR label reader.
 *
 * Built per capture and torn down after it (as the call engine does at the end of a call): nothing is left running,
 * and the window can never keep the JVM alive. macOS is asked for the camera first through [MediaAccess], as the call
 * page is, because Chromium never asks and an ungranted capture delivers black frames.
 */
internal object KcefCameraCapture {

    private const val TAG = "KcefCameraCapture"
    private const val PAGE_FILE = "capture.html"
    private const val SHOT_PREFIX = "shot:"
    private const val WINDOW_WIDTH = 760
    private const val WINDOW_HEIGHT = 620

    /** One camera window at a time. */
    private val lock = Mutex()

    /** The JPEG the user took, or null when they closed the window, or the camera or Chromium was unavailable. */
    suspend fun capture(title: String): ByteArray? = lock.withLock {
        MediaAccess.ensureCamera()
        val client = KcefRuntime.client() ?: return@withLock null
        val page = withContext(Dispatchers.IO) { runCatching { extractPage() } }
            .onFailure { ZillitLog.w(TAG) { "camera page not extracted: ${it.message}" } }.getOrNull()
        if (page == null) {
            client.dispose()
            return@withLock null
        }
        val result = CompletableDeferred<ByteArray?>()
        var session: Session? = null
        try {
            session = withContext(Dispatchers.Swing) { open(client, page, title, result) }
            result.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Swing) { session?.close() ?: client.dispose() }
        }
    }

    private class Session(val client: CefClient, val browser: CefBrowser, val dialog: JDialog) {
        fun close() {
            runCatching { browser.executeJavaScript("zcam.stop()", browser.url, 0) }
            dialog.isVisible = false
            dialog.contentPane.removeAll()
            dialog.dispose()
            runCatching { browser.close(true) }
            runCatching { client.dispose() }
        }
    }

    /** On the EDT: builds the window and the browser, and wires the page's messages to [result]. */
    private fun open(client: CefClient, page: File, title: String, result: CompletableDeferred<ByteArray?>): Session {
        lateinit var browser: CefBrowser
        val onMessage = { message: String ->
            when {
                message == "ready" -> browser.executeJavaScript(bootScript(), browser.url, 0)
                message == "cancel" -> result.complete(null)
                message.startsWith(SHOT_PREFIX) -> result.complete(
                    runCatching { Base64.getDecoder().decode(message.removePrefix(SHOT_PREFIX)) }.getOrNull(),
                )
                else -> ZillitLog.w(TAG) { "camera page: $message" }
            }
            Unit
        }
        client.addMessageRouter(KcefPage.messageRouter(onMessage))
        client.addLoadHandler(KcefPage.loadHandler { note -> ZillitLog.i(TAG) { note } })
        client.addDisplayHandler(KcefPage.displayHandler { note -> ZillitLog.w(TAG) { "console: $note" } })
        client.addPermissionHandler(KcefPage.permissionHandler { note -> ZillitLog.i(TAG) { note } })
        // Windowed: video takes Chromium's own path, and the native browser exists once its AWT peer does.
        browser = client.createBrowser("file://${page.absolutePath}", CefRendering.DEFAULT, false)
        val dialog = JDialog(null as java.awt.Frame?, title, false).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    result.complete(null)
                }
            })
            contentPane.add(browser.uiComponent)
            preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
            pack()
            setLocationRelativeTo(null)
            isAlwaysOnTop = true
            isVisible = true
        }
        return Session(client, browser, dialog)
    }

    /** The words the page shows, in the app's language, as the `zcam.boot` argument. */
    private fun bootScript(): String {
        val labels = JsonObject(
            mapOf(
                "shoot" to JsonPrimitive(str(S.desktop_csync_scan_capture)),
                "cancel" to JsonPrimitive(str(S.desktop_csync_cancel)),
                "starting" to JsonPrimitive(str(S.desktop_csync_scan_starting)),
                "denied" to JsonPrimitive(str(S.desktop_csync_scan_camera_denied)),
                "none" to JsonPrimitive(str(S.desktop_csync_scan_no_camera)),
            ),
        )
        return "zcam.boot($labels)"
    }

    /** The page, unpacked where `file://` can reach it; re-extracted every launch so a stale copy cannot survive. */
    private fun extractPage(): File {
        val dir = File(ZillitVariant.dataDir, "camera").apply { mkdirs() }
        val resource = checkNotNull(javaClass.getResourceAsStream("/camera/$PAGE_FILE")) {
            "missing bundled resource camera/$PAGE_FILE"
        }
        val page = File(dir, PAGE_FILE)
        resource.use { input -> page.outputStream().use(input::copyTo) }
        return page
    }
}

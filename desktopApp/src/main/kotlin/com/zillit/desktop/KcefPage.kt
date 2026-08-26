package com.zillit.desktop

import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefPermissionHandler

/**
 * The CEF handlers the call page is wired with.
 *
 * Kept out of [KcefCallEngine] so that class stays a transport, and because
 * each of these is an anonymous object with several overrides that say
 * nothing about calling.
 *
 * The load and console handlers exist for one reason: when the media half of
 * a call goes wrong it goes wrong *inside Chromium*, where no Kotlin stack
 * trace reaches. A page that 404s, a script that throws before it can report
 * itself, a `getUserMedia` the OS refused — without these, all of them look
 * identical from Kotlin ("never reported ready").
 */
internal object KcefPage {

    /** The router carrying `window.cefQuery` messages from the page. */
    fun messageRouter(onMessage: (String) -> Unit): CefMessageRouter =
        CefMessageRouter.create(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: CefQueryCallback?,
                ): Boolean {
                    request?.let(onMessage)
                    callback?.success("")
                    return true
                }
            },
        )

    /** Load milestones, reported as short lines for the app log. */
    fun loadHandler(onNote: (String) -> Unit): CefLoadHandler =
        object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatus: Int) {
                if (frame?.isMain != false) onNote("page loaded (http $httpStatus)")
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                onNote("page load failed: $errorCode ${errorText.orEmpty()}")
            }
        }

    /**
     * Answers Chromium's camera, microphone and screen-capture requests.
     *
     * Required, not optional: with no handler CEF denies every media request
     * outright and the page sees `NotAllowedError` for all three. The obvious
     * shortcut — `--use-fake-ui-for-media-stream` — is what this replaces, and
     * it must not come back. That switch does not merely auto-answer the
     * prompt: for a display-capture request it answers *instead of* the
     * browser's own path, handing Chromium the placeholder source id
     * `screen:0:0`. Zero is not a real `CGDirectDisplayID`, so ScreenCaptureKit
     * finds no display to attach to and fails before a stream exists — which
     * the page then reports as `NotReadableError: Could not start video
     * source`. That was every failed screen share this app has ever had; it
     * had nothing to do with macOS permission, which is granted and separate.
     *
     * The granted permissions are the requested ones echoed back, never a
     * constant: CEF asserts that a `getUserMedia` response matches what was
     * asked for, and the bit values are not exposed to Java to hard-code.
     */
    fun permissionHandler(onNote: (String) -> Unit): CefPermissionHandler =
        object : CefPermissionHandler {
            override fun onRequestMediaAccessPermission(
                browser: CefBrowser?,
                frame: CefFrame?,
                requestingUrl: String?,
                requestedPermissions: Int,
                callback: CefMediaAccessCallback?,
            ): Boolean {
                // The page is our own file:// bundle, so there is no third
                // party here to gate — the consent that matters is the OS's,
                // which macOS asks for separately and we cannot answer.
                onNote("media access granted (bits $requestedPermissions)")
                callback?.Continue(requestedPermissions)
                return true
            }
        }

    /** The page's console, so a JS error is visible without a devtools window. */
    fun displayHandler(onConsole: (String) -> Unit): CefDisplayHandler =
        object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?,
                level: CefSettings.LogSeverity?,
                message: String?,
                source: String?,
                line: Int,
            ): Boolean {
                // Only the levels worth waking someone for; the Agora SDK is
                // chatty at info and would drown the log.
                if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR ||
                    level == CefSettings.LogSeverity.LOGSEVERITY_WARNING
                ) {
                    val where = redactKeys(source?.substringAfterLast('/').orEmpty())
                    onConsole("${redactKeys(message.orEmpty())} ($where:$line)")
                }
                return false
            }
        }

    /**
     * Strips API keys out of anything on its way to the log.
     *
     * The map and picker pages inject Google's script as
     * `…/maps/api/js?key=<the production's key>&…`, and that URL is what
     * Chromium names as the *source* of any console message the script emits.
     * Without this, one warning from Google's own SDK writes a live key into
     * the app log — which the engines take care never to do themselves.
     */
    private fun redactKeys(text: String): String = KEY_IN_URL.replace(text, "key=<redacted>")

    private val KEY_IN_URL = Regex("""(?i)\bkey=[^&\s"')]+""")
}

package com.zillit.desktop

import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter

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
                    onConsole("${message.orEmpty()} (${source?.substringAfterLast('/')}:$line)")
                }
                return false
            }
        }
}

package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import java.awt.Desktop
import java.net.URI

/**
 * Opens [url] in the user's browser.
 *
 * Only http(s) is accepted. `Desktop.browse` will happily hand a `file:` URI to
 * the OS handler, so a URL arriving from anywhere but a constant in this
 * codebase could open a local file or a registered custom scheme. The check is
 * cheap and the alternative is a foothold.
 *
 * Failures are logged, not thrown: not being able to open a browser is a
 * disappointment, not a reason to take the window down.
 */
internal fun openInBrowser(url: String) {
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri?.scheme?.lowercase() !in setOf("http", "https")) {
        ZillitLog.w(TAG) { "refused to open a non-web URL" }
        return
    }

    runCatching {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() }
        if (desktop?.isSupported(Desktop.Action.BROWSE) == true) {
            desktop.browse(uri)
        } else {
            ZillitLog.w(TAG) { "this platform cannot open a browser" }
        }
    }.onFailure { ZillitLog.e(TAG, it) { "could not open browser" } }
}

private const val TAG = "Browser"

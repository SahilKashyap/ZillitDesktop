package com.zillit.desktop.core.designsystem.component

/**
 * Puts plain text on the system clipboard — every "Copy" menu item. A
 * platform seam because Compose's common clipboard API has no portable way
 * to build a text entry, and the platform's own is a line.
 */
expect fun copyTextToClipboard(text: String)

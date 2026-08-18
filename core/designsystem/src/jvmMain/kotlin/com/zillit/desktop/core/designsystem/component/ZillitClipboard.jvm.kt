package com.zillit.desktop.core.designsystem.component

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual fun copyTextToClipboard(text: String) {
    // AWT's clipboard is the desktop's; Compose desktop runs on its thread.
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

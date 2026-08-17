package com.zillit.desktop.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A file the OS dropped onto the board.
 *
 * Bytes are read at drop time, exactly as the picker reads them at pick time —
 * the file may move or vanish the moment the drag ends.
 */
class DroppedFile(val name: String, val contentType: String, val bytes: ByteArray)

/**
 * Accepts files dragged in from the OS — Finder, an email, a browser download
 * strip. [onHover] drives the "drop to attach" overlay; [onFiles] fires once
 * per drop with everything that was dragged.
 */
@Composable
expect fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<DroppedFile>) -> Unit,
): Modifier

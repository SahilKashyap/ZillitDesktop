package com.zillit.desktop.feature.pagedistribution.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Accepts files dragged in from the OS — the web's body-level `drop`
 * listener on the D.O.D page (`DoD.jsx:242-289`), which opens the upload
 * dialog on a dropped PDF.
 *
 * [onHover] tracks the drag over the page so the page can say "drop to
 * upload"; [onFiles] gets each file's name and bytes. The JVM actual reads
 * the AWT file list; other hosts are a no-op.
 */
@Composable
expect fun Modifier.pdfFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<Pair<String, ByteArray>>) -> Unit,
): Modifier

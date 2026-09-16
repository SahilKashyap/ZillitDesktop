package com.zillit.desktop.feature.continuity.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile

/**
 * Accepts files dragged in from the OS — the web's body-level `drop`
 * listener on the My Department board (`IntraDepartment.jsx:216-254`),
 * which opens the Add Scene dialog on whatever was let go.
 *
 * [onHover] tracks the drag over the page so the page can say "drop to
 * upload"; [onFiles] gets each file read, typed by its name. The JVM actual
 * reads the AWT file list; other hosts are a no-op.
 */
@Composable
expect fun Modifier.continuityFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedContinuityFile>) -> Unit,
): Modifier

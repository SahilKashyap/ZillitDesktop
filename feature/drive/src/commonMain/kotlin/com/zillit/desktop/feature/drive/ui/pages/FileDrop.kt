package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.drive.ui.PickedFile

/**
 * Accepts files and folders dragged in from the OS — the web's page-level
 * drop zone (`handleDropUpload`), folders walked recursively so a dropped
 * directory keeps its structure (`collectDroppedFiles`).
 *
 * Platform-specific because the transferable is AWT's; common code sees
 * paths, never bytes — a dropped folder can hold gigabytes. [onHover]
 * tracks the drag so the page can draw its overlay; [onFiles] fires once
 * with everything found in the drop.
 */
@Composable
expect fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier

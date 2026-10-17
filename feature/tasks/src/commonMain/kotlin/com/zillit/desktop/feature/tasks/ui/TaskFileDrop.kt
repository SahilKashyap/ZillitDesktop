package com.zillit.desktop.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile

/**
 * Images and files dropped on the description from the OS — the web's drop
 * zone on the same box.
 *
 * Platform-specific because the transferable is AWT's. Unlike the Drive's
 * drop, the files are **read here**: an attachment is at most 25 MB and both
 * the shrinking and the upload want the bytes, so there is nothing to gain by
 * passing paths. A dropped folder is ignored rather than walked: a task takes
 * twenty files, not a tree.
 *
 * [onHover] tracks the drag so the box can say what a drop would do.
 */
@Composable
expect fun Modifier.taskFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<PickedTaskFile>) -> Unit,
): Modifier

/**
 * What the clipboard holds, as attachments: a screenshot, or files copied in
 * a file manager. Empty when it holds neither.
 *
 * Compose's own clipboard gives text only, which is why this is a seam of its
 * own — a pasted screenshot is how most of these arrive.
 */
expect suspend fun clipboardAttachments(): List<PickedTaskFile>

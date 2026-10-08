package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.LocalFolderTree

/**
 * Accepts files dragged in from the OS.
 *
 * Platform-specific because the transferable is AWT's; common code sees only
 * the read files. [onHover] tracks the drag so the page can draw its drop
 * overlay; [onFiles] fires once with everything readable in the drop.
 */
@Composable
expect fun Modifier.externalFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFiles: (List<LocalFile>) -> Unit,
    /**
     * A drop that holds a directory: one tree of everything in it (and of any
     * loose files dragged with it), unread. [onFiles] is used only when the drop
     * is nothing but files.
     */
    onFolders: (LocalFolderTree) -> Unit,
): Modifier

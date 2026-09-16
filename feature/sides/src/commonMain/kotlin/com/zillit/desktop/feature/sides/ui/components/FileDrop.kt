package com.zillit.desktop.feature.sides.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.sides.ui.PickedDoc

/**
 * Accepts a file dragged in from the OS — the web's `Upload.Dragger`.
 * The first file dropped wins; the forms take one document each.
 */
@Composable
expect fun Modifier.sidesFileDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFile: (PickedDoc) -> Unit,
): Modifier

package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Takes a file dragged onto the budget import's upload step — the web's drop
 * zone. [onHover] reports a drag over the zone so it can light up.
 *
 * At most [maxBytes] + 1 bytes are read: a file over the cap arrives one byte
 * too long, so the same size rule that refuses a picked file refuses it, and
 * nobody's 2 GB video is read into memory to be turned away.
 */
@Composable
internal expect fun Modifier.budgetFileDrop(
    enabled: Boolean,
    maxBytes: Long,
    onHover: (Boolean) -> Unit,
    onFile: (name: String, bytes: ByteArray) -> Unit,
): Modifier

package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.feature.bankrec.domain.PickedStatement

/**
 * The bar under a table too wide for its pane: where the rows have been
 * scrolled to, and a thumb to drag them by. Draws nothing when nothing is off
 * screen.
 */
@Composable
internal expect fun BrHorizontalRail(state: ScrollState, modifier: Modifier = Modifier)

/**
 * Takes a statement dragged in from the operating system — the web's drop zone.
 *
 * The first file of a drop is the one taken, as the web's `dataTransfer.files[0]`;
 * whether it is a statement at all is the view model's question, not this one's.
 */
@Composable
internal expect fun Modifier.statementDrop(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onFile: (PickedStatement) -> Unit,
): Modifier

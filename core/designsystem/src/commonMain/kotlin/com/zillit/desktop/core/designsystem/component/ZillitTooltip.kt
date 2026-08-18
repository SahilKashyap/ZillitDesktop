package com.zillit.desktop.core.designsystem.component

import androidx.compose.runtime.Composable

/**
 * A short line that appears when the pointer rests on [content] — who reacted
 * with an emoji, what a truncated name says in full.
 *
 * A platform seam because the tooltip is the desktop's own (`TooltipArea`),
 * which common code cannot see. Blank [text] shows nothing and just draws
 * [content].
 */
@Composable
expect fun ZillitTooltip(text: String, content: @Composable () -> Unit)

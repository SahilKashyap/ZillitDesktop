package com.zillit.desktop.feature.budget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * What the screen needs from outside the module — names for ids, faces for
 * names, and the conversation pane the chat tool draws.
 */
class BudgetScreenSeams(
    /** A crew member's display name; null keeps whatever the row already says. */
    val nameOf: (String) -> String? = { null },
    val loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /**
     * The thread beside the budget: the chat tool's own conversation, scoped
     * to this budget's tool, department and document. Null draws the rail
     * alone — which is what a host without a chat surface, and every render
     * test, gets.
     */
    val conversation: (@Composable (BudgetUiState) -> Unit)? = null,
)

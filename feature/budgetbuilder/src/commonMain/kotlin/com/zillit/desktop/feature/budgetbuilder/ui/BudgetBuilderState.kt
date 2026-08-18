package com.zillit.desktop.feature.budgetbuilder.ui

import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer

/**
 * What the launch page renders.
 *
 * Small on purpose: the budget application owns its own state inside its own
 * window. The only state this side holds is who is asking and whether this
 * install knows where the tool lives.
 */
data class BudgetBuilderUiState(
    val viewer: BudgetBuilderViewer = BudgetBuilderViewer(),
    /**
     * Whether this environment carries both Budget Builder endpoints —
     * the service API and the web deployment that serves the page. False
     * renders the same notice the web shows for a missing
     * `budgetBuilderApiBase`, rather than a button that opens a broken
     * window.
     */
    val configured: Boolean = true,
    /**
     * Whether the network is gone. Budget Builder is a hosted application in
     * an embedded browser, talking to its own service — there is nothing on
     * this computer to show; the launch page says so instead of opening a
     * window that cannot load.
     */
    val offline: Boolean = false,
)

sealed interface BudgetBuilderEvent {
    /** The Open button. */
    data object Open : BudgetBuilderEvent
}

sealed interface BudgetBuilderEffect {
    /** Launch the embedded application, in the host's own window. */
    data object Launch : BudgetBuilderEffect
}

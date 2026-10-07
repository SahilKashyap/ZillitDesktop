package com.zillit.desktop.feature.budgetbuilder.ui

import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer

/**
 * What the tool's window renders.
 *
 * Small on purpose: the budget application owns its own state, inside the
 * embedded browser that fills this window. The only state this side holds is
 * who is asking, whether this install knows where the tool lives, and whether
 * there is a network to reach it over — the three reasons the application is
 * not what gets drawn.
 */
data class BudgetBuilderUiState(
    val viewer: BudgetBuilderViewer = BudgetBuilderViewer(),
    /**
     * Whether this environment carries both Budget Builder endpoints —
     * the service API and the web deployment that serves the page. False
     * renders the same notice the web shows for a missing
     * `budgetBuilderApiBase`, rather than an application that cannot load.
     */
    val configured: Boolean = true,
    /**
     * Whether the network is gone. Budget Builder is a hosted application in
     * an embedded browser, talking to its own service — there is nothing on
     * this computer to show; the window says so instead of a blank page.
     */
    val offline: Boolean = false,
) {

    /** Whether the application itself is what this window should be showing. */
    val showsApplication: Boolean get() = configured && !offline && !viewer.isBlocked
}

sealed interface BudgetBuilderEvent {
    /**
     * The application's own "← Film Tools" button, which posts `zillit:exit`.
     *
     * The web answers it by navigating back to the tools grid. It exists
     * because the page is given the whole window with no strip of ours above
     * it, so this is the only way out that is not the window's close button.
     */
    data object ExitRequested : BudgetBuilderEvent
}

sealed interface BudgetBuilderEffect {
    /** Leave the tool: the window closes, as the web leaves the route. */
    data object Exit : BudgetBuilderEffect
}

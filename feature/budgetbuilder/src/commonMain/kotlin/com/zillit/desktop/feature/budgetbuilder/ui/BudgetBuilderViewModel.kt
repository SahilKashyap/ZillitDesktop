package com.zillit.desktop.feature.budgetbuilder.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer

/**
 * The launch page's view model.
 *
 * There is no repository behind this screen: the budget application talks to
 * its own service from inside its own window, and nothing it does round-trips
 * through here. What is left is the rights question and the launch itself.
 */
class BudgetBuilderViewModel(
    /**
     * Resolves the viewer *now*. A lambda rather than a value because view
     * models are built with the app, before any production is open — and
     * because rights can arrive after this screen has mounted, so [start]
     * and every open re-ask instead of trusting a snapshot.
     */
    private val resolveViewer: () -> BudgetBuilderViewer,
    private val configured: Boolean,
) : ZillitViewModel<BudgetBuilderUiState, BudgetBuilderEvent, BudgetBuilderEffect>(
    BudgetBuilderUiState(),
) {

    fun start() {
        // Resolved before entering the reducer: inside it the State receiver's
        // own members shadow this class's, and `configured = configured` would
        // quietly assign the field to itself.
        val resolved = resolveViewer()
        val known = configured
        setState { copy(viewer = resolved, configured = known) }
    }

    override fun onEvent(event: BudgetBuilderEvent) {
        when (event) {
            BudgetBuilderEvent.Open -> open()
        }
    }

    private fun open() {
        // Re-resolved at the moment of the click, not read from state: the
        // tools call may have answered since the page mounted, and the fresh
        // answer is the one that should decide.
        val resolved = resolveViewer()
        setState { copy(viewer = resolved) }
        if (!configured || resolved.isBlocked) return
        sendEffect(BudgetBuilderEffect.Launch)
    }
}

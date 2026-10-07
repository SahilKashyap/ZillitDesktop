package com.zillit.desktop.feature.budgetbuilder.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import kotlinx.coroutines.flow.StateFlow

/**
 * The tool window's view model.
 *
 * There is no repository behind this screen: the budget application talks to
 * its own service from inside the embedded browser, and nothing it does
 * round-trips through here. What is left is the rights question, whether this
 * environment has the tool at all, and the application's request to leave.
 */
class BudgetBuilderViewModel(
    /**
     * Resolves the viewer *now*. A lambda rather than a value because view
     * models are built with the app, before any production is open — and
     * because rights can arrive after this screen has mounted, so [start]
     * re-asks instead of trusting a snapshot.
     */
    private val resolveViewer: () -> BudgetBuilderViewer,
    private val configured: Boolean,
    /** Whether the network is reachable; null means "assume yes". */
    online: StateFlow<Boolean>? = null,
) : ZillitViewModel<BudgetBuilderUiState, BudgetBuilderEvent, BudgetBuilderEffect>(
    BudgetBuilderUiState(),
) {

    init {
        online?.let { flow -> launch { flow.collect { up -> setState { copy(offline = !up) } } } }
    }

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
            BudgetBuilderEvent.ExitRequested -> sendEffect(BudgetBuilderEffect.Exit)
        }
    }
}

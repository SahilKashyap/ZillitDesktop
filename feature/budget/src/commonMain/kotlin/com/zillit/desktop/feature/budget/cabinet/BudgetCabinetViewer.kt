package com.zillit.desktop.feature.budget.cabinet

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetEffect
import com.zillit.desktop.feature.budget.ui.BudgetEvent
import com.zillit.desktop.feature.budget.ui.BudgetHost
import com.zillit.desktop.feature.budget.ui.BudgetScreen
import com.zillit.desktop.feature.budget.ui.BudgetScreenSeams
import com.zillit.desktop.feature.budget.ui.BudgetViewModel

/**
 * The File Cabinet's `main_budget` ("Budget (Full)") and `department_budget`
 * ("Budget (Department)") rows — one class, two instances, as the tool is.
 *
 * The web's modal mounts `FullBudget` / `DepartmentBudget` with no props at
 * all; the pages only know they are in the cabinet from the `/settings`
 * route. That route changes no request (no budget call takes an
 * `isFileCabinet`), so this is the live tile's own reads — the versions, the
 * department directory, the rooms and their messages — with the writes taken
 * away: no upload, no new conversation, no composer, no badges, no private
 * threads. The rail and the conversation pane are the tool's own; the pane
 * arrives through [seams] because only the host can reach the chat tool.
 *
 * One view model per instance, built on first use: the window can be opened
 * many times, and [BudgetEvent.Load] starts each visit from a fresh state.
 */
class BudgetCabinetViewer(
    private val mode: BudgetMode,
    private val repository: BudgetRepository,
    /** Admin Settings is an admins' door; asked late because the production opens after the graph. */
    private val isAdmin: () -> Boolean,
    private val host: BudgetHost,
    private val seams: BudgetScreenSeams,
    /** Hands a version's file to the host to show or save. */
    private val onOpenFile: (BudgetDocument, Boolean) -> Unit = { _, _ -> },
    private val events: SocketEventBus? = null,
    private val rights: RightsRequestBus? = null,
) : CabinetViewer {

    override val identifier: String
        get() = when (mode) {
            BudgetMode.Main -> MAIN_BUDGET
            BudgetMode.Department -> DEPARTMENT_BUDGET
        }

    private val model: BudgetViewModel by lazy {
        BudgetViewModel(
            mode = mode,
            repository = repository,
            viewer = { BudgetViewer.forCabinet(isAdmin()) },
            host = host,
            events = events,
            rights = rights,
            cabinet = true,
        )
    }

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        val state by model.state.collectAsState()
        LaunchedEffect(model) { model.onEvent(BudgetEvent.Load) }
        LaunchedEffect(model) {
            model.effects.collect { effect ->
                when (effect) {
                    is BudgetEffect.Open -> onOpenFile(effect.document, effect.save)
                }
            }
        }
        Box(modifier) { BudgetScreen(state = state, onEvent = model::onEvent, seams = seams) }
    }

    companion object {
        /** The web's `commonUnits[].identifier` for the production's budget. */
        const val MAIN_BUDGET = "main_budget"

        /** …and for the department budgets. */
        const val DEPARTMENT_BUDGET = "department_budget"
    }
}

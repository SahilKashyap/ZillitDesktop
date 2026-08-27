package com.zillit.desktop.feature.budget.ui

import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetMember
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer

/** Which budget the screen is showing. */
enum class BudgetTab(val label: String, val type: BudgetType) {
    Main("Main budget", BudgetType.Main),
    Department("Department budgets", BudgetType.Department),
}

data class BudgetUiState(
    val viewer: BudgetViewer = BudgetViewer(),
    val tab: BudgetTab = BudgetTab.Main,
    val loading: Boolean = false,
    /** The production's single main budget, when there is one. */
    val mainBudget: BudgetDocument? = null,
    /** One per department, newest name order as the server sends them. */
    val departmentBudgets: List<BudgetDocument> = emptyList(),
    val members: List<BudgetMember> = emptyList(),
    val membersOpen: Boolean = false,
    val selectedId: String? = null,
    val viewCount: Int? = null,
    val downloadCount: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {

    /** The tabs this viewer may open — a tab they cannot use is never drawn. */
    val tabs: List<BudgetTab> get() = buildList {
        if (viewer.canViewMain || viewer.canPostMain) add(BudgetTab.Main)
        if (viewer.canViewDepartment || viewer.canPostDepartment) add(BudgetTab.Department)
    }

    /** May this viewer put a file on the budget now showing? */
    val canPostHere: Boolean get() = when (tab) {
        BudgetTab.Main -> viewer.canPostMain
        BudgetTab.Department -> viewer.canPostDepartment
    }

    val selected: BudgetDocument?
        get() = when (tab) {
            BudgetTab.Main -> mainBudget
            BudgetTab.Department -> departmentBudgets.firstOrNull { it.id == selectedId }
                ?: departmentBudgets.firstOrNull()
        }
}

sealed interface BudgetEvent {
    data object Load : BudgetEvent
    data class TabChanged(val tab: BudgetTab) : BudgetEvent
    data class Select(val documentId: String) : BudgetEvent
    data object Upload : BudgetEvent
    data class Delete(val documentId: String) : BudgetEvent
    data object OpenFile : BudgetEvent
    data object DownloadFile : BudgetEvent
    data object ShowMembers : BudgetEvent
    data object DismissMembers : BudgetEvent
    data object DismissMessage : BudgetEvent
}

sealed interface BudgetEffect {
    /** The chosen document's file, for the host to open or save. */
    data class Open(val document: BudgetDocument, val save: Boolean) : BudgetEffect
}

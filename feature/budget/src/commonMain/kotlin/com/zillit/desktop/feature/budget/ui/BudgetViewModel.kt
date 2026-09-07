package com.zillit.desktop.feature.budget.ui

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer

/**
 * The Budget tool: one screen for both budgets, as the web serves both from
 * `BudgetMain.jsx`.
 *
 * The viewer arrives as a lambda rather than a value because view models are
 * built once per graph — before any production is open — and rights come with
 * the production. See the module note in [BudgetViewer.from] on why an empty
 * permission set is "not answered yet" rather than "no".
 */
class BudgetViewModel(
    private val repository: BudgetRepository,
    private val viewer: () -> BudgetViewer = { BudgetViewer() },
    /** This person's department — whose budget they may upload. */
    private val departmentId: () -> String = { "" },
    /** Picks a file and puts it in storage; null when the host offers no picker. */
    private val pickFile: (suspend () -> BudgetFile?)? = null,
    /**
     * The production's department names by id. The list endpoint has been
     * seen to omit `department_name` (live, 2026-08-26), which left a
     * department budget with nothing to call itself.
     */
    private val departmentName: (String) -> String? = { null },
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<BudgetUiState, BudgetEvent, BudgetEffect>(BudgetUiState()) {

    override fun onEvent(event: BudgetEvent) {
        when (event) {
            BudgetEvent.Load -> load()
            is BudgetEvent.TabChanged -> {
                setState { copy(tab = event.tab, viewCount = null, downloadCount = null) }
                refreshCounts()
            }

            is BudgetEvent.Select -> {
                setState { copy(selectedId = event.documentId, viewCount = null, downloadCount = null) }
                refreshCounts()
            }

            BudgetEvent.Upload -> upload()
            is BudgetEvent.Delete -> delete(event.documentId)
            BudgetEvent.OpenFile -> openFile(save = false)
            BudgetEvent.DownloadFile -> openFile(save = true)
            BudgetEvent.ShowMembers -> showMembers()
            BudgetEvent.DismissMembers -> setState { copy(membersOpen = false) }
            BudgetEvent.DismissMessage -> setState { copy(error = null, notice = null) }
        }
    }

    /**
     * Rights first, then documents.
     *
     * The tab opens on whichever budget this viewer may actually see: the web
     * starts on Main and falls back to the department when the main budget is
     * not theirs (`BudgetPrimaryComponent.jsx:130-141`).
     */
    private fun load() {
        val rights = viewer()
        val opening = if (rights.departmentOnly) BudgetTab.Department else BudgetTab.Main
        setState { copy(viewer = rights, tab = opening, loading = true, error = null) }
        if (rights.hasNoAccess) {
            setState { copy(loading = false) }
            return
        }
        launch {
            when (val answer = repository.documents()) {
                is ZillitResult.Success -> {
                    val documents = answer.data.map(::named)
                    setState {
                        copy(
                            loading = false,
                            mainBudget = documents.firstOrNull { it.type == BudgetType.Main },
                            departmentBudgets = documents.filter { it.type == BudgetType.Department },
                        )
                    }
                    refreshCounts()
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    /** A document wearing the production's own name for its department. */
    private fun named(document: BudgetDocument): BudgetDocument =
        if (document.departmentName.isNotBlank() || document.departmentId.isBlank()) {
            document
        } else {
            departmentName(document.departmentId)
                ?.takeIf { it.isNotBlank() }
                ?.let { document.copy(departmentName = it) }
                ?: document
        }


    /**
     * Refuses a write on this tab, and offers the one thing that changes it.
     *
     * Posting rights are per-tab here — main budget and department budget are
     * separate grants — so the label names the tab that refused, which is what
     * the admin has to find in the rights grid.
     */
    private fun refusesPost(): Boolean {
        if (currentState.canPostHere) return false
        rights?.ask(MODULE_LABEL, RightsKind.Post)
        setState {
            copy(
                error = "You do not have posting rights for the ${tab.label.lowercase()} budget" +
                    if (rights == null) "." else " — asking an administrator.",
            )
        }
        return true
    }

    /**
     * The file goes to storage first, then its descriptor to the service —
     * the same two steps the web takes, and the reason a failed upload never
     * leaves a budget row pointing at nothing.
     */
    private fun upload() {
        val picker = pickFile ?: return
        if (refusesPost()) return
        val type = currentState.tab.type
        val department = if (type == BudgetType.Department) departmentId() else ""
        setState { copy(busy = true, error = null) }
        launch {
            val file = picker()
            if (file == null) {
                setState { copy(busy = false) }
                return@launch
            }
            when (val answer = repository.post(type, department, file)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false, notice = "Budget uploaded.") }
                    load()
                }

                is ZillitResult.Failure ->
                    setState { copy(busy = false, error = answer.error.localised()) }
            }
        }
    }

    private fun delete(documentId: String) {
        if (refusesPost()) return
        setState { copy(busy = true, error = null) }
        launch {
            when (val answer = repository.delete(listOf(documentId))) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false, notice = "Budget removed.") }
                    load()
                }

                is ZillitResult.Failure ->
                    setState { copy(busy = false, error = answer.error.localised()) }
            }
        }
    }

    /**
     * Opening is also a record: the service keeps a last-visited stamp and a
     * per-document view count, which is what the web's "who has seen this"
     * numbers read from.
     */
    private fun openFile(save: Boolean) {
        val document = currentState.selected ?: return
        val file = document.file
        if (file == null || !file.isPresent) {
            setState { copy(error = "There is no file on this budget yet.") }
            return
        }
        if (save && !currentState.viewer.canDownload(document.type)) {
            rights?.ask(MODULE_LABEL, RightsKind.Download)
            setState {
                copy(
                    error = "You do not have download rights for this budget" +
                        if (rights == null) "." else " — asking an administrator.",
                )
            }
            return
        }
        sendEffect(BudgetEffect.Open(document, save))
        launch {
            repository.markVisited(document.id)
            refreshCounts()
        }
    }

    private fun showMembers() {
        setState { copy(membersOpen = true) }
        launch {
            val answer = repository.members(departmentId())
            if (answer is ZillitResult.Success) {
                val people = when (currentState.tab) {
                    BudgetTab.Main -> answer.data.main
                    BudgetTab.Department -> answer.data.department
                }
                setState { copy(members = people) }
            }
        }
    }

    /** Both numbers for whatever is selected; a failure simply leaves them unknown. */
    private fun refreshCounts() {
        val document = currentState.selected ?: return
        launch {
            val views = repository.activityCount(document.id, BudgetActivity.View)
            val downloads = repository.activityCount(document.id, BudgetActivity.Download)
            setState {
                copy(
                    viewCount = (views as? ZillitResult.Success)?.data,
                    downloadCount = (downloads as? ZillitResult.Success)?.data,
                )
            }
        }
    }
}

private const val MODULE_LABEL = "Budget"

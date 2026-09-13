package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.DealListRules
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * All Deals — the web's `DMDealsPage` and the shell's Export / Create /
 * Deal Memo Setup actions beside its search.
 */
internal class DealListActions(private val vm: DealMemoViewModel) {

    private var loadJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per list action.
    fun onEvent(event: DealsEvent) {
        when (event) {
            is DealsEvent.Search -> edit { copy(search = event.query) }
            is DealsEvent.Filter -> edit { copy(filter = event.filter) }
            is DealsEvent.Department -> edit { copy(departmentId = event.departmentId) }
            is DealsEvent.Sort -> edit { copy(sort = event.sort) }
            is DealsEvent.Open -> open(event.deal)
            is DealsEvent.Activate -> activate(event.deal)
            is DealsEvent.Chase -> chase(event.deal)
            is DealsEvent.AskDelete -> askDelete(event.deal)
            DealsEvent.ConfirmDelete -> confirmDelete()
            DealsEvent.CancelDelete -> edit { if (deleting) this else copy(pendingDelete = null) }
            is DealsEvent.Export -> export(event.kind)
            DealsEvent.RequestCreateMenu -> requestCreateMenu()
            DealsEvent.CloseCreateMenu -> edit { copy(createMenuOpen = false) }
            is DealsEvent.CreateFrom -> createFrom(event.group)
            DealsEvent.CloseSetupGate -> edit { copy(setupGate = null) }
            DealsEvent.OpenSetupFromGate -> openSetupFromGate()
        }
    }

    /** Entering the tab: a loader the first time, a silent refresh after. */
    fun enter() = load(silent = vm.ui.deals.loaded)

    fun reload() = load(silent = true)

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true) }
        loadJob = vm.work {
            when (val result = vm.repository.deals()) {
                is ZillitResult.Success -> edit {
                    copy(rows = result.data, loading = false, loaded = true, loadedAt = vm.clock())
                }
                // A failed first load is an empty table, as on the web; a failed
                // background refresh keeps what is on screen rather than wiping it.
                is ZillitResult.Failure -> edit {
                    copy(rows = if (silent) rows else emptyList(), loading = false, loaded = true)
                }
            }
        }
    }

    /** A draft reopens in the builder; anything else in the preview, reading this tab's badge for it. */
    private fun open(deal: DealDoc) {
        val route = if (deal.rawStatus == DealStatus.Draft.wire) {
            DealMemoRoute.EditDeal(deal.id, exitTo = DealMemoRoute.Tab(DealTab.Deals))
        } else {
            DealMemoRoute.Deal(deal.id, from = DealBadgeUnit.AllDeals)
        }
        vm.navigate(route)
    }

    /** Single-flight across the list; the list reloads afterwards, success or not. */
    private fun activate(deal: DealDoc) {
        val state = vm.ui.deals
        if (state.activatingId != null || !DealListRules.canActivate(deal) || !vm.ui.rights.canPost) return
        edit { copy(activatingId = deal.id) }
        vm.work {
            when (val result = vm.repository.activate(deal.id)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_activated")
                    vm.badges?.readDeal(DealBadgeUnit.AllDeals, deal.id)
                }
                is ZillitResult.Failure -> vm.toastError(result.error)
            }
            edit { copy(activatingId = null) }
            reload()
        }
    }

    /** No confirmation and no reload — a nudge changes nothing about the deal. */
    private fun chase(deal: DealDoc) {
        val state = vm.ui
        if (state.deals.chasingId != null) return
        if (!DealListRules.canChase(deal, state.rights.canPost, state.viewer.userId)) return
        edit { copy(chasingId = deal.id) }
        vm.work {
            when (val result = vm.repository.chase(deal.id)) {
                is ZillitResult.Success -> vm.toastSuccess(result.data, "deal_memo_chased")
                is ZillitResult.Failure -> vm.toastError(result.error)
            }
            edit { copy(chasingId = null) }
        }
    }

    private fun askDelete(deal: DealDoc) {
        val state = vm.ui
        if (!DealListRules.canDelete(deal, state.rights.canPost, state.viewer.userId)) return
        edit { copy(pendingDelete = deal) }
    }

    /** A refusal keeps the dialog open; success reads the row's badge and reloads. */
    private fun confirmDelete() {
        val state = vm.ui.deals
        val deal = state.pendingDelete ?: return
        if (state.deleting) return
        edit { copy(deleting = true) }
        vm.work {
            when (val result = vm.repository.delete(deal.id)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_deleted")
                    vm.badges?.readDeal(DealBadgeUnit.AllDeals, deal.id)
                    edit { copy(deleting = false, pendingDelete = null) }
                    reload()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    edit { copy(deleting = false) }
                }
            }
        }
    }

    /**
     * The register or the start forms. No success toast — the file opening is
     * the confirmation; the start forms pin a progress note because the server
     * takes a while to zip them.
     */
    private fun export(kind: DealExport) {
        if (vm.ui.deals.exporting != null) return
        val saver = vm.files ?: return
        edit { copy(exporting = kind) }
        if (kind == DealExport.StartForms) {
            vm.update { copy(progressToast = "Preparing start forms… this can take a minute") }
        }
        vm.work {
            when (val bytes = vm.repository.export(kind)) {
                is ZillitResult.Success -> when (val saved = saver.save(fileName(kind), bytes.data)) {
                    is ZillitResult.Success -> Unit
                    is ZillitResult.Failure -> vm.toastError(saved.error)
                }
                is ZillitResult.Failure -> vm.toastError(bytes.error, "export_failed")
            }
            vm.update { copy(progressToast = null, deals = deals.copy(exporting = null)) }
        }
    }

    /** `deal-memo-register_2026-09-13_1405.pdf` / `crew-start-forms_…zip`, stamped in local time. */
    private fun fileName(kind: DealExport): String {
        val now = Instant.fromEpochMilliseconds(vm.clock()).toLocalDateTime(TimeZone.currentSystemDefault())
        fun pad(n: Int) = n.toString().padStart(2, '0')
        val stamp = "${now.year}-${pad(now.month.ordinal + 1)}-${pad(now.day)}_${pad(now.hour)}${pad(now.minute)}"
        val stem = if (kind == DealExport.StartForms) "crew-start-forms" else "deal-memo-register"
        return "${stem}_$stamp.${kind.extension}"
    }

    /**
     * Create Deal Memo: while the setups sweep is still running, wait for it;
     * with no setup of either kind, the "Set up Deal Memo first" prompt;
     * otherwise the menu.
     */
    private fun requestCreateMenu() {
        val state = vm.ui.deals
        if (state.checkingSetups) return
        if (state.createMenuOpen) {
            edit { copy(createMenuOpen = false) }
            return
        }
        edit { copy(checkingSetups = true) }
        vm.work {
            vm.awaitTemplates()
            val presence = vm.ui.templates.presence
            edit {
                if (presence != null && !presence.has(null)) {
                    copy(checkingSetups = false, setupGate = SetupGateState(group = null))
                } else {
                    copy(checkingSetups = false, createMenuOpen = true)
                }
            }
        }
    }

    private fun createFrom(group: SetupGroup) {
        edit { copy(createMenuOpen = false) }
        vm.work {
            vm.awaitTemplates()
            val presence = vm.ui.templates.presence
            if (presence != null && !presence.has(group)) {
                edit { copy(setupGate = SetupGateState(group)) }
            } else {
                vm.navigate(DealMemoRoute.QuickDeal(group = group, exitTo = DealMemoRoute.Tab(DealTab.Deals)))
            }
        }
    }

    /** "Open Deal Memo Setup" — the setup builder locked to the side that was missing. */
    private fun openSetupFromGate() {
        val group = vm.ui.deals.setupGate?.group
        edit { copy(setupGate = null) }
        vm.navigate(DealMemoRoute.TemplateBuilder(group = group, from = DealMemoRoute.Tab(DealTab.Deals)))
    }

    private fun edit(reducer: DealsState.() -> DealsState) = vm.update { copy(deals = deals.reducer()) }
}

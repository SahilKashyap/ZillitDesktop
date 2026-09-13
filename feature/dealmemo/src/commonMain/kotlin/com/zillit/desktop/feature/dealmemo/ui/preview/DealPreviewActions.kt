package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.ChipAction
import com.zillit.desktop.feature.dealmemo.domain.preview.DealPreviewRules
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoContext
import com.zillit.desktop.feature.dealmemo.domain.preview.PreviewViewer
import com.zillit.desktop.feature.dealmemo.domain.preview.SignSurface
import com.zillit.desktop.feature.dealmemo.ui.CrewFormEvent
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.NominalsEvent
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.SignerEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The deal page (`DMDealPreviewPage.jsx`): loading and refreshing the deal,
 * the header and bar actions, and the small dialogs. Documents, signing and
 * the two editors are their own collaborators.
 */
@Suppress("TooManyFunctions") // One handler per act the deal page performs.
internal class DealPreviewActions(private val vm: DealMemoViewModel) {

    private var loadJob: Job? = null
    private var copiedJob: Job? = null

    val documents = PreviewDocumentActions(vm, this)
    val signing = SigningActions(vm, this)
    val nominals = NominalsActions(vm, this)
    val rules = RulesActions(vm, this)
    val crew = CrewFormActions(vm, this)

    fun onEvent(event: DealMemoEvent) {
        when (event) {
            is PreviewEvent -> onPreview(event)
            is SignerEvent -> signing.onEvent(event)
            is NominalsEvent -> nominals.onEvent(event)
            is RulesEvent -> rules.onEvent(event)
            is CrewFormEvent -> crew.onEvent(event)
            else -> Unit
        }
    }

    // -- entering ------------------------------------------------------------------------

    /** `/deals/:id`: the badge reads of the list it came from, then the deal. */
    fun enter(route: DealMemoRoute.Deal) {
        val showing = vm.ui.preview?.takeIf { !it.embedded && it.deal != null }
        if (showing?.dealId == route.dealId) {
            refresh()
            return
        }
        vm.update {
            copy(preview = DealPreviewState(dealId = route.dealId, embedded = false, from = route.from, loading = true))
        }
        readBadges(route)
        ensureProduction()
        load(route.dealId)
    }

    /** My Deal's own deal: the page embeds it, keeping any dialog open over the same deal. */
    fun embed(deal: DealDoc) {
        vm.update {
            val same = preview?.takeIf { it.embedded && it.dealId == deal.id }
            copy(
                preview = same?.copy(deal = deal, loading = false, failed = false)
                    ?: DealPreviewState(dealId = deal.id, embedded = true, deal = deal),
            )
        }
        ensureProduction()
        loadAgenciesIfNeeded(deal)
    }

    /** The web's `fromUnit` rule: the queue keeps its badge until approval; no origin clears all three. */
    private fun readBadges(route: DealMemoRoute.Deal) {
        val source = vm.badges ?: return
        when (route.from) {
            DealBadgeUnit.ApprovalQueue -> Unit
            null -> listOf(DealBadgeUnit.AllDeals, DealBadgeUnit.MyDeal, DealBadgeUnit.ApprovalQueue)
                .forEach { source.readDeal(it, route.dealId) }
            else -> source.readDeal(route.from, route.dealId)
        }
    }

    private fun load(dealId: String) {
        loadJob?.cancel()
        loadJob = vm.work {
            when (val result = vm.repository.deal(dealId)) {
                is ZillitResult.Success -> {
                    updatePreview(dealId) {
                        copy(deal = result.data, loading = false, failed = false, notFound = false)
                    }
                    afterLoad(result.data)
                }
                is ZillitResult.Failure -> if (result.error is ZillitError.Serialization) {
                    updatePreview(dealId) { copy(loading = false, notFound = true) }
                } else {
                    vm.toastError(result.error, "failed_to_load_deal_memo")
                    updatePreview(dealId) { copy(loading = false, failed = true) }
                }
            }
        }
    }

    private fun afterLoad(deal: DealDoc) {
        loadAgenciesIfNeeded(deal)
        val rules = rulesFor(vm.ui) ?: return
        if (rules.shareLinkEligible && vm.ui.preview?.shareToken == null) {
            vm.work {
                vm.repository.portalLink(deal.id).getOrNull()?.let { token ->
                    updatePreview(deal.id) { copy(shareToken = token) }
                }
            }
        }
    }

    /**
     * `refreshDeal`: a silent re-read — every write ends here, and so does a
     * socket frame for this deal. My Deal's copy follows.
     */
    fun refresh() {
        val preview = vm.ui.preview ?: return
        val dealId = preview.dealId
        vm.work {
            val deal = vm.repository.deal(dealId).getOrNull() ?: return@work
            vm.update {
                val current = this.preview?.takeIf { it.dealId == dealId } ?: return@update this
                copy(
                    preview = current.copy(deal = deal, loading = false, failed = false),
                    myDeal = if (current.embedded) myDeal.copy(deal = deal) else myDeal,
                )
            }
            clearSettledGate()
        }
    }

    suspend fun refreshNow(): DealDoc? {
        val dealId = vm.ui.preview?.dealId ?: return null
        val deal = vm.repository.deal(dealId).getOrNull() ?: return null
        vm.update {
            val current = preview?.takeIf { it.dealId == dealId } ?: return@update this
            copy(
                preview = current.copy(deal = deal),
                myDeal = if (current.embedded) myDeal.copy(deal = deal) else myDeal,
            )
        }
        clearSettledGate()
        return deal
    }

    /** A gate whose last blocker has cleared closes itself. */
    private fun clearSettledGate() {
        val rules = rulesFor(vm.ui) ?: return
        val gate = vm.ui.preview?.gate ?: return
        val blockers = if (gate == GateMode.Send) rules.sendBlockers else rules.fieldBlockers
        if (blockers.isEmpty()) updatePreview { copy(gate = null) }
    }

    // -- production references ---------------------------------------------------------------

    private fun ensureProduction() = vm.ensureProduction()

    /** A production switch: nothing in flight survives it. */
    fun reset() {
        listOf(loadJob, copiedJob).forEach { it?.cancel() }
        loadJob = null
        copiedJob = null
        documents.reset()
    }

    /** The agency vendors are read only when a deal names an agency by id alone. */
    private fun loadAgenciesIfNeeded(deal: DealDoc) {
        val cd = deal.crew
        if (DocRead.text(cd, "agency_id") == null || DocRead.text(cd, "agency_name") != null) return
        if (vm.ui.production.agencies.isNotEmpty()) return
        vm.work {
            vm.reference.agencies().getOrNull()?.let { agencies ->
                vm.update { copy(production = production.copy(agencies = agencies)) }
            }
        }
    }

    // -- events ----------------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod")
    private fun onPreview(event: PreviewEvent) {
        when (event) {
            PreviewEvent.Back -> back()
            PreviewEvent.Retry -> vm.ui.preview?.let { preview ->
                updatePreview { copy(loading = true, failed = false) }
                load(preview.dealId)
            }
            PreviewEvent.ToggleEditMenu -> updatePreview { copy(editMenuOpen = !editMenuOpen) }
            PreviewEvent.CloseEditMenu -> updatePreview { copy(editMenuOpen = false) }
            is PreviewEvent.Edit -> edit(event.action)
            PreviewEvent.Activate -> activate()
            PreviewEvent.ApproveAndSign -> approveAndSign()
            PreviewEvent.CopyShareLink -> copyShareLink()
            PreviewEvent.Acknowledge -> acknowledge()
            PreviewEvent.ToggleMore -> updatePreview { copy(moreMenuOpen = !moreMenuOpen) }
            PreviewEvent.CloseMore -> updatePreview { copy(moreMenuOpen = false) }
            PreviewEvent.ShowChecklist -> updatePreview { copy(moreMenuOpen = false, checklistOpen = true) }
            PreviewEvent.CloseChecklist -> updatePreview { copy(checklistOpen = false) }
            PreviewEvent.ViewPdf -> {
                updatePreview { copy(moreMenuOpen = false) }
                documents.openDealPdf()
            }
            PreviewEvent.History -> {
                updatePreview { copy(moreMenuOpen = false) }
                vm.ui.preview?.deal?.let { vm.onEvent(DealMemoEvent.OpenHistory(it)) }
            }
            is PreviewEvent.Chip -> chip(event.action)
            PreviewEvent.CloseGate -> updatePreview { copy(gate = null) }
            PreviewEvent.OpenReject -> updatePreview { copy(reject = RejectDraft()) }
            is PreviewEvent.RejectReason -> updatePreview { copy(reject = reject?.copy(reason = event.reason)) }
            PreviewEvent.ConfirmReject -> reject()
            PreviewEvent.CancelReject -> updatePreview { if (reject?.busy == true) this else copy(reject = null) }
            PreviewEvent.SendForApproval -> sendForApproval()
            is PreviewEvent.ViewPassport -> documents.openPassport(event.attachment)
            PreviewEvent.CloseViewer -> documents.close()
            PreviewEvent.DownloadViewer -> documents.download()
            PreviewEvent.CloseStartForm -> updatePreview { copy(startFormOpen = false) }
            PreviewEvent.CompleteDetails -> {
                updatePreview { copy(gate = null) }
                crew.open()
            }
        }
    }

    /** Back to the list the deal was opened from — the web's history back. */
    private fun back() {
        val from = vm.ui.preview?.from
        val tab = when (from) {
            DealBadgeUnit.AllDeals, DealBadgeUnit.Notices -> DealTab.Deals
            DealBadgeUnit.ApprovalQueue -> DealTab.ApprovalQueue
            DealBadgeUnit.MyDeal -> DealTab.MyDeal
            null -> DealTab.defaultFor(vm.ui.rights)
        }
        vm.navigate(DealMemoRoute.Tab(tab))
    }

    private fun edit(action: EditAction) {
        updatePreview { copy(editMenuOpen = false) }
        val preview = vm.ui.preview ?: return
        if (!allows { action in editControl.actions }) return
        when (action) {
            EditAction.Rules -> rules.open()
            EditAction.Nominals -> nominals.open()
            // No confirmation, as on the web — saving there rewinds a live deal to issued.
            EditAction.Edit -> vm.navigate(
                DealMemoRoute.EditDeal(preview.dealId, exitTo = vm.ui.route ?: DealMemoRoute.Deal(preview.dealId)),
            )
        }
    }

    private fun activate() {
        val preview = vm.ui.preview ?: return
        if (preview.action != null || !allows { showActivate }) return
        updatePreview { copy(action = PreviewAction.Activate) }
        vm.work {
            when (val result = vm.repository.activate(preview.dealId)) {
                is ZillitResult.Success -> vm.toastSuccess(result.data, "deal_activated")
                is ZillitResult.Failure -> vm.toastError(result.error, "something_went_wrong")
            }
            refreshNow()
            updatePreview { copy(action = null) }
        }
    }

    /** Signs the deal memo first unless this approver's signature is already on the stored copy. */
    private fun approveAndSign() {
        val preview = vm.ui.preview ?: return
        val rules = rulesFor(vm.ui) ?: return
        if (preview.action != null || !rules.showApproverActions) return
        val surface = rules.surfaces.firstOrNull { it.key == SignSurface.DEAL_PDF } ?: return
        if (surface.signedByMe) approve() else signing.open(surface, rules.signUserType)
    }

    /** `runApproveChain`: approve, clear the queue badge, refetch — never activate; the server decides. */
    fun approve() {
        val preview = vm.ui.preview ?: return
        if (preview.action != null || !allows { canApproverSign }) return
        updatePreview { copy(action = PreviewAction.Approve) }
        vm.work {
            when (val result = vm.repository.approve(preview.dealId)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_approved")
                    vm.badges?.readDeal(DealBadgeUnit.ApprovalQueue, preview.dealId)
                }
                is ZillitResult.Failure -> vm.toastError(result.error, "something_went_wrong")
            }
            refreshNow()
            updatePreview { copy(action = null) }
        }
    }

    private fun copyShareLink() {
        val preview = vm.ui.preview ?: return
        val url = shareUrl(vm.ui) ?: return
        runCatching { copyTextToClipboard(url) }.onSuccess {
            updatePreview { copy(linkCopied = true) }
            copiedJob?.cancel()
            copiedJob = vm.work {
                delay(COPIED_MILLIS)
                vm.update {
                    copy(preview = this.preview?.takeIf { it.dealId == preview.dealId }?.copy(linkCopied = false))
                }
            }
        }
    }

    private fun acknowledge() {
        val preview = vm.ui.preview ?: return
        if (preview.acknowledging || !allows { canAcknowledgeAmendment }) return
        updatePreview { copy(acknowledging = true) }
        vm.work {
            when (val result = vm.repository.acknowledgeAmendment()) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_amendment_acknowledged")
                    refreshNow()
                }
                is ZillitResult.Failure -> vm.toastError(result.error, "failed_to_acknowledge_amendment")
            }
            updatePreview { copy(acknowledging = false) }
        }
    }

    private fun chip(action: ChipAction) {
        val rules = rulesFor(vm.ui) ?: return
        when (action) {
            is ChipAction.Sign -> rules.surfaces.firstOrNull { it.key == action.key }?.let {
                signing.open(it, rules.signUserType)
            }
            ChipAction.FieldsGate -> updatePreview { copy(gate = GateMode.Fields) }
            ChipAction.ViewPdf -> documents.openDealPdf()
            is ChipAction.OpenTab -> documents.openTab(action.tab)
            ChipAction.None -> Unit
        }
    }

    /** The crew member's own deal; the modal closes and the deal refetches whatever happens. */
    private fun reject() {
        val draft = vm.ui.preview?.reject ?: return
        val reason = draft.reason.trim()
        if (draft.busy || reason.isEmpty() || !allows { canCrewReject }) return
        updatePreview { copy(reject = reject?.copy(busy = true), action = PreviewAction.Reject) }
        vm.work {
            when (val result = vm.repository.rejectAsCrew(reason)) {
                is ZillitResult.Success -> vm.toastSuccess(result.data, "deal_rejected")
                is ZillitResult.Failure -> vm.toastError(result.error, "something_went_wrong")
            }
            refreshNow()
            updatePreview { copy(reject = null, action = null) }
        }
    }

    /** Blockers open the "Not ready yet" list instead of sending; the button itself is never disabled for them. */
    private fun sendForApproval() {
        val preview = vm.ui.preview ?: return
        val rules = rulesFor(vm.ui) ?: return
        if (preview.action != null || !rules.showSend) return
        if (rules.sendBlockers.isNotEmpty()) {
            updatePreview { copy(gate = GateMode.Send) }
            return
        }
        updatePreview { copy(action = PreviewAction.Send) }
        vm.work {
            when (val result = vm.repository.sendForApproval(preview.dealId)) {
                is ZillitResult.Success -> vm.toastSuccess(result.data, "deal_submitted")
                is ZillitResult.Failure -> vm.toastError(result.error, "something_went_wrong")
            }
            refreshNow()
            updatePreview { copy(action = null) }
        }
    }

    // -- shared -------------------------------------------------------------------------------

    /**
     * Whether the page's rules allow an action right now — checked here as well
     * as on screen, so a stale click or a dialog left open never outruns them.
     */
    fun allows(check: DealPreviewRules.() -> Boolean): Boolean = rulesFor(vm.ui)?.check() == true

    fun updatePreview(dealId: String? = null, reducer: DealPreviewState.() -> DealPreviewState) {
        vm.update {
            val current = preview ?: return@update this
            if (dealId != null && current.dealId != dealId) return@update this
            copy(preview = current.reducer())
        }
    }

    companion object {
        private const val COPIED_MILLIS = 1_800L

        fun viewerOf(state: DealMemoUiState): PreviewViewer = PreviewViewer(
            userId = state.viewer.userId,
            canPost = state.rights.canPost,
            isAccountant = state.viewer.isAccountant,
        )

        /** Every gate on the page, for the deal as it stands — unsaved crew edits included. */
        fun rulesFor(state: DealMemoUiState): DealPreviewRules? {
            val preview = state.preview ?: return null
            val deal = preview.deal ?: return null
            return DealPreviewRules(deal, viewerOf(state), state.metadata, preview.embedded, preview.crewDraft)
        }

        fun memoContext(state: DealMemoUiState): MemoContext = MemoContext(
            labels = state.labels,
            projectName = state.production.project.projectName,
            companies = state.production.companies,
            units = state.production.units,
            countries = state.production.countries,
            agencies = state.production.agencies,
            translate = DealLabels.translation,
        )

        /** `${origin}/deal-memo/crew-portal/${token}`, on the web app's own origin. */
        fun shareUrl(state: DealMemoUiState): String? {
            val token = state.preview?.shareToken ?: return null
            val origin = state.production.webOrigin?.trimEnd('/') ?: return null
            return "$origin/deal-memo/crew-portal/$token"
        }
    }
}

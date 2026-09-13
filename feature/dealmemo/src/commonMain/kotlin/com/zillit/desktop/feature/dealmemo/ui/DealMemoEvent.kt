package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.DealQuickFilter
import com.zillit.desktop.feature.dealmemo.domain.DealSort
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroupKind
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch

/** Everything the deal memo tool can be asked to do. */
sealed interface DealMemoEvent {

    /** The window was pointed at a path under the tool — a deep link or a restored tab. */
    data class OpenPath(val path: String) : DealMemoEvent

    data class Navigate(val route: DealMemoRoute) : DealMemoEvent

    /** The header's back arrow: out of the tool, to Film Tools. */
    data object LeaveTool : DealMemoEvent

    data object DismissToast : DealMemoEvent

    data class OpenHistory(val deal: DealDoc) : DealMemoEvent

    data object CloseHistory : DealMemoEvent
}

sealed interface DealsEvent : DealMemoEvent {
    data class Search(val query: String) : DealsEvent
    data class Filter(val filter: DealQuickFilter) : DealsEvent
    data class Department(val departmentId: String?) : DealsEvent
    data class Sort(val sort: DealSort) : DealsEvent

    /** A row: a draft opens the builder, anything else the preview. */
    data class Open(val deal: DealDoc) : DealsEvent
    data class Activate(val deal: DealDoc) : DealsEvent
    data class Chase(val deal: DealDoc) : DealsEvent
    data class AskDelete(val deal: DealDoc) : DealsEvent
    data object ConfirmDelete : DealsEvent
    data object CancelDelete : DealsEvent
    data class Export(val kind: DealExport) : DealsEvent

    /**
     * The Create Deal Memo button: opens the menu when a setup exists (or
     * cannot be known yet), the "Set up Deal Memo first" prompt otherwise.
     */
    data object RequestCreateMenu : DealsEvent
    data object CloseCreateMenu : DealsEvent

    /** A Create menu item — into the builder, unless that kind has no setup yet. */
    data class CreateFrom(val group: SetupGroup) : DealsEvent
    data object CloseSetupGate : DealsEvent
    data object OpenSetupFromGate : DealsEvent
}

sealed interface OverviewEvent : DealMemoEvent {
    data object Retry : OverviewEvent
}

sealed interface MyDealEvent : DealMemoEvent {
    data object Retry : MyDealEvent
}

sealed interface NoticesEvent : DealMemoEvent {
    data class Search(val query: String) : NoticesEvent
    data class OpenSend(val deal: DealDoc) : NoticesEvent
    data class EditSendDate(val date: String) : NoticesEvent
    data class EditSendBody(val body: String) : NoticesEvent
    data object ConfirmSend : NoticesEvent
    data object CancelSend : NoticesEvent
    data class OpenSendAll(val group: NoticeGroupKind) : NoticesEvent
    data object ConfirmSendAll : NoticesEvent
    data object CancelSendAll : NoticesEvent
    data class OpenDeactivate(val deal: DealDoc) : NoticesEvent
    data class EditDeactivateDate(val date: String) : NoticesEvent
    data object ConfirmDeactivate : NoticesEvent
    data object CancelDeactivate : NoticesEvent
}

sealed interface NoticeTemplateEvent : DealMemoEvent {
    data class Edit(val text: String) : NoticeTemplateEvent
    data class SetMode(val mode: NoticeTemplateMode) : NoticeTemplateEvent
    data object AskReset : NoticeTemplateEvent
    data object ConfirmReset : NoticeTemplateEvent
    data object CancelReset : NoticeTemplateEvent
    data object Save : NoticeTemplateEvent
    data object Close : NoticeTemplateEvent
}

sealed interface RatesEvent : DealMemoEvent {
    data class SidebarSearch(val query: String) : RatesEvent
    data class SelectTerritory(val territoryId: String) : RatesEvent
    data object ViewAgreements : RatesEvent
    data class SelectBranch(val branch: Branch) : RatesEvent
    data class SelectAgreement(val agreement: AgreementSummary, val origin: AgreementOrigin) : RatesEvent
    data class TerritoryFilter(val query: String) : RatesEvent
    data class AgreementsFilter(val query: String) : RatesEvent
    data object BackToWelcome : RatesEvent
    data object BackToTerritory : RatesEvent
    data object BackFromAgreement : RatesEvent
    data object Refresh : RatesEvent
}

sealed interface DealMemoEffect {
    /** Leave the tool for the Film Tools grid. */
    data object LeaveTool : DealMemoEffect
}

package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox

sealed interface TaxFilingEvent {

    // -- where ----------------------------------------------------------------

    /** The window's route changed — the catalogue, or a filing below it. */
    data class RouteChanged(val path: String) : TaxFilingEvent

    data class OpenFiling(val filing: TaxFiling) : TaxFilingEvent

    /** The header's back chip: return → companies → catalogue → Account Hub. */
    data object Back : TaxFilingEvent

    data object ShowCatalog : TaxFilingEvent

    data object LeaveToAccountHub : TaxFilingEvent

    data object Refresh : TaxFilingEvent

    // -- registrations ----------------------------------------------------------

    data class Open(val registration: TaxRegistration) : TaxFilingEvent

    data object BackToRegistrations : TaxFilingEvent

    data object ComposeRegistration : TaxFilingEvent

    data class EditDraft(val draft: RegistrationDraft) : TaxFilingEvent

    data object DismissDraft : TaxFilingEvent

    data object SaveRegistration : TaxFilingEvent

    data class AskRemove(val registration: TaxRegistration) : TaxFilingEvent

    data object DismissRemove : TaxFilingEvent

    data object ConfirmRemove : TaxFilingEvent

    /** Saves everything the service holds for a registration, as JSON. */
    data class ExportData(val registration: TaxRegistration) : TaxFilingEvent

    /** Opens the authority's consent page in the machine's browser. */
    data class Connect(val registration: TaxRegistration) : TaxFilingEvent

    /** Stops waiting for a consent the accountant has abandoned. */
    data object CancelConnect : TaxFilingEvent

    // -- the return -------------------------------------------------------------

    /** A period from the picker; blank clears the choice. */
    data class SelectPeriod(val periodKey: String) : TaxFilingEvent

    data object SyncObligations : TaxFilingEvent

    data class EditMapping(val mapping: BoxMapping) : TaxFilingEvent

    data class ToggleBox(val box: VatBox) : TaxFilingEvent

    data object ToggleAllBoxes : TaxFilingEvent

    data object SaveMapping : TaxFilingEvent

    /** Saves the mapping, then builds the nine boxes from the ledger. */
    data object Calculate : TaxFilingEvent

    /** Writes the ledger lines behind the boxes to a workbook. */
    data object ExportLedger : TaxFilingEvent

    data object AskSubmit : TaxFilingEvent

    data object DismissSubmit : TaxFilingEvent

    data object ConfirmSubmit : TaxFilingEvent
}

/** How a message is worded on screen: done, worth knowing, or went wrong. */
enum class TaxToastTone { Success, Info, Error }

data class TaxToast(val message: String, val tone: TaxToastTone = TaxToastTone.Success)

sealed interface TaxFilingEffect {

    data class Toast(val toast: TaxToast) : TaxFilingEffect

    /** The consent page. Opened in a browser, not in this application. */
    data class OpenInBrowser(val url: String) : TaxFilingEffect

    /** A route within this tool — the catalogue, or a filing. */
    data class Navigate(val path: String) : TaxFilingEffect

    /** Back to the Account Hub the tool was opened from. */
    data object LeaveTool : TaxFilingEffect
}

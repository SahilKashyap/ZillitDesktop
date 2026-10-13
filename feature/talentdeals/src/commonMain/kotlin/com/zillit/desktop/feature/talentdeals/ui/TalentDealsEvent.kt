package com.zillit.desktop.feature.talentdeals.ui

import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentForm

sealed interface TalentDealsEvent {

    // -- register -----------------------------------------------------------------
    data object Retry : TalentDealsEvent
    data class Search(val query: String) : TalentDealsEvent
    data object AddContract : TalentDealsEvent
    data object CancelRolePicker : TalentDealsEvent
    data class PickRole(val role: String) : TalentDealsEvent
    data class OpenContract(val id: String) : TalentDealsEvent
    data class RequestRemove(val contract: TalentContract) : TalentDealsEvent
    data object ConfirmRemove : TalentDealsEvent
    data object CancelRemove : TalentDealsEvent

    // -- navigation ---------------------------------------------------------------
    /** The header's back chip: one step out, and out of the tool from the register. */
    data object Back : TalentDealsEvent
    data class OpenEdit(val id: String) : TalentDealsEvent

    // -- the form -----------------------------------------------------------------
    /** A plain edit of the form's fields. */
    data class FormEdit(val change: TalentForm.() -> TalentForm) : TalentDealsEvent
    data class SelectTerritory(val id: String) : TalentDealsEvent
    data object PickContractFile : TalentDealsEvent
    data object PickCertificateFile : TalentDealsEvent

    /** The Name field lost focus with [name] in it. */
    data class CommitName(val name: String) : TalentDealsEvent
    data class ToggleWeekly(val on: Boolean) : TalentDealsEvent
    data class ToggleExpenses(val on: Boolean) : TalentDealsEvent
    data object AddTranche : TalentDealsEvent
    data object AddExpense : TalentDealsEvent
    data object Save : TalentDealsEvent

    // -- Exhibit G ----------------------------------------------------------------
    data object ToggleExhibit : TalentDealsEvent
    data class PickExhibitRow(val dayKey: String, val rowKey: String) : TalentDealsEvent

    // -- files --------------------------------------------------------------------
    data class OpenAttachment(val attachment: TalentAttachment) : TalentDealsEvent
    data object CloseViewer : TalentDealsEvent
    data object DownloadViewed : TalentDealsEvent
}

sealed interface TalentDealsEffect {
    data class Notice(val text: String, val isError: Boolean) : TalentDealsEffect

    /** Back from the register leaves the tool — the web's `/film-tools`, which also closes an MDI window. */
    data object CloseTool : TalentDealsEffect
}

package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrViewMode
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader

sealed interface WorksheetEvent {
    // -- chrome ------------------------------------------------------------------
    data class SelectPane(val pane: WorksheetPane) : WorksheetEvent
    data class SelectLiveTab(val tab: LiveTab) : WorksheetEvent
    data object Back : WorksheetEvent
    data object OpenAnalytics : WorksheetEvent
    data object RefreshSource : WorksheetEvent
    data object Retry : WorksheetEvent
    data object DismissProgress : WorksheetEvent
    data object DismissExport : WorksheetEvent

    // -- a pane's filters ---------------------------------------------------------
    data class SetCompany(val pane: WorksheetPane, val companyId: String?) : WorksheetEvent
    data class SetBudget(val pane: WorksheetPane, val budgetKey: String?) : WorksheetEvent
    data class SetCurrency(val pane: WorksheetPane, val code: String?) : WorksheetEvent
    data class SetVersion(val pane: WorksheetPane, val versionId: String?) : WorksheetEvent
    data class StepWeek(val pane: WorksheetPane, val forward: Boolean) : WorksheetEvent
    data class GoToCurrentWeek(val pane: WorksheetPane) : WorksheetEvent
    data class Compute(val pane: WorksheetPane) : WorksheetEvent

    // -- a pane's grid --------------------------------------------------------------
    data class Search(val pane: WorksheetPane, val query: String) : WorksheetEvent
    data class ToggleSection(val pane: WorksheetPane, val id: String) : WorksheetEvent
    data class ToggleHeader(val pane: WorksheetPane, val code: String) : WorksheetEvent
    data class ToggleNominal(val pane: WorksheetPane, val identity: String) : WorksheetEvent
    data class OpenLedger(val pane: WorksheetPane, val nominal: CrNominal, val column: CrColumn?) : WorksheetEvent
    data object CloseLedger : WorksheetEvent

    // -- the worksheet's controls ----------------------------------------------------
    data class SetDecimals(val decimals: Int) : WorksheetEvent
    data class SetViewMode(val mode: CrViewMode) : WorksheetEvent
    data object ToggleExpandAll : WorksheetEvent
    data class SetFilter(val filter: CrLineFilter) : WorksheetEvent
    data class SortBy(val column: CrColumn) : WorksheetEvent
    data object ClearSort : WorksheetEvent

    /** The mode strip's Clear: the sort and the filter together. */
    data object ClearFlat : WorksheetEvent

    /** A value typed into an ETC, EFC or VTP cell. */
    data class CommitCell(val nominal: CrNominal, val column: CrColumn, val value: Double) : WorksheetEvent

    // -- the worksheet's actions ------------------------------------------------------
    data object Save : WorksheetEvent
    data object OpenSaveVersion : WorksheetEvent
    data class SetVersionLabel(val label: String) : WorksheetEvent
    data object ConfirmSaveVersion : WorksheetEvent

    data object OpenPublish : WorksheetEvent
    data class EditPublish(val form: PublishForm) : WorksheetEvent
    data object ConfirmPublish : WorksheetEvent

    data object OpenLock : WorksheetEvent
    data class SetLockNote(val note: String) : WorksheetEvent
    data object ConfirmLock : WorksheetEvent

    data object OpenExport : WorksheetEvent
    data class SetExportFormat(val format: ExportFormat) : WorksheetEvent
    data object ConfirmExport : WorksheetEvent

    data object OpenOverages : WorksheetEvent
    data class SetFlagNote(val headerCode: String, val note: String) : WorksheetEvent
    data object GoToLiveCr : WorksheetEvent

    data object OpenHistory : WorksheetEvent
    data object CloseModal : WorksheetEvent

    // -- posted snapshots -----------------------------------------------------------------
    data class SetHistoryFilter(val filter: PostedFilter) : WorksheetEvent
    data object RefreshHistory : WorksheetEvent
    data class OpenSnapshot(val header: SnapshotHeader) : WorksheetEvent
    data object CloseSnapshot : WorksheetEvent
    data class SearchSnapshot(val query: String) : WorksheetEvent
    data class ToggleSnapshotSection(val id: String) : WorksheetEvent
    data class ToggleSnapshotHeader(val code: String) : WorksheetEvent
    data class ExportSnapshot(val format: ExportFormat) : WorksheetEvent
}

sealed interface WorksheetEffect {
    data class Notice(val text: String, val error: Boolean = false) : WorksheetEffect

    /** The header's back arrow: return to the Account Hub. */
    data object Back : WorksheetEffect

    data object OpenAnalytics : WorksheetEffect
}

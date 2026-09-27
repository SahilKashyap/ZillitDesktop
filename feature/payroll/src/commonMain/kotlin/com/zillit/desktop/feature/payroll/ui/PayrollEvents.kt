package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.Employment
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction

/** Everything the payroll screens can ask for. */
sealed interface PayrollEvent {
    /** The window's route changed — a tile, a deep link, the hub. */
    data class Route(val path: String) : PayrollEvent
    data class OpenTile(val tile: PayrollTile) : PayrollEvent
    data object BackToLanding : PayrollEvent
    data object ClearNotice : PayrollEvent

    // -- override approval, from any screen --
    data class AskOverride(val timecard: PayrollTimecard) : PayrollEvent
    data class EditOverrideReason(val reason: String) : PayrollEvent
    data object ConfirmOverride : PayrollEvent
    data object DismissOverride : PayrollEvent

    // -- claims and deductions, from any screen --
    data class OpenAdjustments(val kind: AdjustmentKind, val timecard: PayrollTimecard) : PayrollEvent
    data class EditAdjustment(val name: String? = null, val amount: String? = null, val nominal: String? = null) :
        PayrollEvent
    data object AddAdjustment : PayrollEvent
    data class AttachBatch(val batchId: String) : PayrollEvent
    data class RemoveClaim(val claim: ClaimLine) : PayrollEvent
    data class RemoveDeduction(val deductionId: String) : PayrollEvent
    data object UnlockAdjusted : PayrollEvent
    data object DismissAdjustments : PayrollEvent
}

/** Payroll History. */
sealed interface HistoryEvent : PayrollEvent {
    data class ShiftWeek(val steps: Int) : HistoryEvent
    data object CurrentWeek : HistoryEvent
    data object Refresh : HistoryEvent
    data class Search(val query: String) : HistoryEvent
    data class Select(val timecardId: String) : HistoryEvent
    data class Tab(val tab: HistoryTab) : HistoryEvent
    data object DownloadPayslip : HistoryEvent
}

/** Payroll Run. */
sealed interface RunEvent : PayrollEvent {
    data class ShiftWeek(val steps: Int) : RunEvent
    data object CurrentWeek : RunEvent
    data object Refresh : RunEvent
    data class Search(val query: String) : RunEvent
    data class Status(val filter: RunStatusFilter) : RunEvent
    data class Department(val department: String?) : RunEvent
    data class EmploymentFilter(val employment: Employment?) : RunEvent
    data class ToggleRow(val timecardId: String) : RunEvent
    data object ToggleAllShown : RunEvent
    data object ClearSelection : RunEvent
    data class Ask(val action: RunAction) : RunEvent
    data object Confirm : RunEvent
    data object DismissConfirm : RunEvent
    data class Row(val timecardId: String, val action: RowAction) : RunEvent
    data class OpenDrawer(val timecardId: String?) : RunEvent
    data class Unlock(val timecardId: String) : RunEvent
    data class Export(val format: ExportFormat) : RunEvent
    data object GoToProcessing : RunEvent
    data class Journal(val open: Boolean) : RunEvent
}

/** The Journal Ledger, inside the Run. */
sealed interface JournalEvent : PayrollEvent {
    data class Code(val rowId: String, val code: String) : JournalEvent
    data class Date(val rowId: String, val date: String) : JournalEvent
    data class Credit(val rowId: String, val amount: String) : JournalEvent
    data class Describe(val rowId: String, val description: String) : JournalEvent

    // -- the timecard's tax line --
    data class AddTax(val timecardId: String) : JournalEvent
    data class RemoveTax(val timecardId: String) : JournalEvent
    data class TaxType(val rowId: String, val type: String) : JournalEvent
    data class TaxRate(val rowId: String, val rate: String) : JournalEvent

    // -- layers and tags --
    data class Layers(val rowId: String, val codes: Map<String, String>) : JournalEvent
    data class Tags(val rowId: String, val tags: List<String>) : JournalEvent
    data class SplitLayers(val rowId: String, val childId: String, val codes: Map<String, String>) : JournalEvent
    data class SplitTags(val rowId: String, val childId: String, val tags: List<String>) : JournalEvent

    // -- allocations --
    data class Split(val rowId: String) : JournalEvent
    data class SplitAmount(val rowId: String, val childId: String, val amount: String) : JournalEvent
    data class SplitCode(val rowId: String, val childId: String, val code: String) : JournalEvent
    data class SplitDescribe(val rowId: String, val childId: String, val description: String) : JournalEvent
    data class SplitDate(val rowId: String, val childId: String, val date: String) : JournalEvent
    data class RemoveSplit(val rowId: String, val childId: String) : JournalEvent
    data class HeaderDate(val date: String) : JournalEvent
    data object Save : JournalEvent
    data object Post : JournalEvent
    data class PostDate(val date: String) : JournalEvent
    data object ConfirmPost : JournalEvent
    data object DismissPost : JournalEvent
    data object DismissAlert : JournalEvent
    data object FixAndPost : JournalEvent
}

/** Payroll Processing. */
sealed interface ProcessingEvent : PayrollEvent {
    data class View(val view: ProcessingView) : ProcessingEvent
    data class Nav(val nav: ProcessingNav) : ProcessingEvent
    data class Department(val department: String?) : ProcessingEvent
    data class Search(val query: String) : ProcessingEvent
    data class Day(val index: Int) : ProcessingEvent
    data object Refresh : ProcessingEvent
    data class MarkPaid(val timecardId: String) : ProcessingEvent
    data class MarkUnpaid(val timecardId: String) : ProcessingEvent

    /** The drawer's Mark Paid, whose rule differs from the table's. */
    data class DrawerMarkPaid(val timecardId: String) : ProcessingEvent
    data class OpenDrawer(val timecardId: String?) : ProcessingEvent
    data class OpenOutstanding(val userId: String?) : ProcessingEvent
    data class Export(val open: Boolean) : ProcessingEvent
    data class ExportScope(val outstanding: Boolean) : ProcessingEvent
}

/**
 * Producer Board Payroll Status. The board reads: the web removed its
 * override / approve / submit actions when the producer flow was respecified,
 * so there is nothing here that writes.
 */
sealed interface ProducerBoardEvent : PayrollEvent {
    data class ShiftWeek(val steps: Int) : ProducerBoardEvent
    data object CurrentWeek : ProducerBoardEvent
    data class Select(val timecardId: String) : ProducerBoardEvent
}

/** Production Report Payroll — every fill is local, and nothing is saved. */
sealed interface ProductionReportEvent : PayrollEvent {
    data class ShiftWeek(val steps: Int) : ProductionReportEvent
    data object CurrentWeek : ProductionReportEvent
    data class Select(val userId: String) : ProductionReportEvent
    data object ToggleAutoFill : ProductionReportEvent

    /** One day of the open week, by its index within it. */
    data class FillDay(val index: Int) : ProductionReportEvent
    data class ClearDay(val index: Int) : ProductionReportEvent
    data object FillWeek : ProductionReportEvent
    data object ClearWeek : ProductionReportEvent
}

sealed interface PayrollEffect {
    data class Failed(val message: String) : PayrollEffect

    /** Take the window — or the embedding hub — to a route. */
    data class Navigate(val path: String) : PayrollEffect
}

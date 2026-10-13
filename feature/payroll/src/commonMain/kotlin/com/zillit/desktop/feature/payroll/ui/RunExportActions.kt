package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.RunSelection

/**
 * The Run's exports — the web's `handleExportCsv` / `handleExportSummary`.
 *
 * Export Summary (PDF, Excel) is always direct. Export CSV is the accountant
 * workbook, and when the run still holds weeks this viewer could approve or
 * lock it stands the confirm in front first: "…& Export" moves those weeks on
 * and then downloads, "Export Only" just downloads.
 */
internal class RunExportActions(private val vm: PayrollViewModel, private val actions: RunActions) {

    private val state: RunState get() = vm.ui.run

    fun ask(format: ExportFormat) {
        val ui = vm.ui
        if (!ui.viewer.seesAccountantViews || state.exporting) return
        // The whole run, not a ticked selection: the workbook is a run-level document.
        val everyone = RunSelection.of(ui.runRows(), state.timecards.map { it.id }.toSet())
        val action = everyone.approvalFor(ui.viewer)?.first
        if (format != ExportFormat.Csv || action == null) {
            export(format)
            return
        }
        edit { copy(confirm = actions.confirmFor(action, everyone, runScope = true).copy(thenExport = true)) }
    }

    /** The confirm's secondary: the workbook as the statuses stand, nothing moved. */
    fun only() {
        state.confirm?.takeIf { it.thenExport && !it.working } ?: return
        edit { copy(confirm = null) }
        export(ExportFormat.Csv)
    }

    /** The server renders it; a declined export names why. */
    fun export(format: ExportFormat) {
        val week = state.weekStarting ?: return
        val documents = vm.documents ?: return
        val files = vm.files ?: return
        if (state.exporting || !vm.ui.viewer.seesAccountantViews) return
        edit { copy(exporting = true) }
        vm.launchWork {
            val saved = when (val file = documents.runSummary(week, format)) {
                is ZillitResult.Success ->
                    files.saveAndOpen(
                        "payroll-run-summary_${fileStamp(vm.ui.now)}.${file.data.extension}",
                        file.data.bytes,
                    )
                is ZillitResult.Failure -> file
            }
            edit { copy(exporting = false) }
            if (saved is ZillitResult.Failure) vm.fail(saved.error.localised())
        }
    }

    private fun edit(reducer: RunState.() -> RunState) = vm.update { copy(run = run.reducer()) }
}

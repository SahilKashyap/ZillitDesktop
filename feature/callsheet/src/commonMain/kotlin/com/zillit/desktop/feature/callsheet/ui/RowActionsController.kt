package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetHistory
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.approvalStatusEntries
import com.zillit.desktop.feature.callsheet.domain.deleteQuestion
import com.zillit.desktop.feature.callsheet.domain.latestReminder
import com.zillit.desktop.feature.callsheet.domain.stageForStatus

/**
 * The row actions every list shares: View (the server PDF), Delete, History,
 * Approval status, the reminder popup, and Published's Attach Document.
 * Opening a sheet any of these ways reads its report badge (`readReport`).
 */
internal class RowActionsController(private val ctx: SheetContext) {

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one branch per row action.
    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.View -> {
                ctx.lists.readReport(event.sheet.id)
                view(event.sheet)
            }
            ListEvent.ClosePdf -> ctx.update { copy(pdf = null) }
            ListEvent.DownloadPdf -> download()
            is ListEvent.Edit -> {
                ctx.lists.readReport(event.sheet.id)
                ctx.editor.openExisting(event.sheet)
            }
            is ListEvent.Delete -> if (ctx.state.isPoster && !event.sheet.status.locked) {
                ctx.update {
                    copy(
                        dialog = SheetDialog.Confirm(
                            action = ConfirmAction.DeleteSheet(event.sheet),
                            title = "Delete Call Sheet",
                            message = deleteQuestion(event.sheet),
                            confirmLabel = "Delete",
                            danger = true,
                        ),
                    )
                }
            }
            is ListEvent.OpenHistory -> {
                ctx.lists.readReport(event.sheet.id)
                history(event.sheet, event.title, event.fromDetail)
            }
            is ListEvent.OpenApprovalStatus -> approvalStatus(event.sheet)
            is ListEvent.ViewReminder -> latestReminder(event.sheet, ctx.state.me)?.let { reminder ->
                ctx.lists.readReport(event.sheet.id)
                ctx.update { copy(dialog = SheetDialog.ReminderView(reminder)) }
            }
            is ListEvent.AttachDocument -> attach(event.sheet)
            else -> Unit
        }
    }

    /** "Generating PDF", then the viewer — the web's `viewCallSheet`. */
    private fun view(sheet: CallSheetSummary) {
        ctx.update { copy(pdf = PdfOverlay(sheetId = sheet.id, title = pdfTitle(sheet))) }
        ctx.launchWork {
            when (val bytes = ctx.services.delivery.pdf(sheet.id)) {
                is ZillitResult.Failure -> {
                    ctx.update { copy(pdf = null) }
                    ctx.toast(bytes.error.withPrefix("Failed to generate PDF: "), isError = true)
                }
                is ZillitResult.Success -> when (
                    val pages = ctx.services.delivery.renderPages(bytes.data, PDF_RENDER_WIDTH)
                ) {
                    is ZillitResult.Failure -> {
                        ctx.update { copy(pdf = null) }
                        ctx.toast(pages.error.withPrefix("Failed to generate PDF: "), isError = true)
                    }
                    is ZillitResult.Success -> ctx.update {
                        if (pdf?.sheetId != sheet.id) {
                            this
                        } else {
                            copy(pdf = pdf.copy(loading = false, pages = pages.data, bytes = bytes.data))
                        }
                    }
                }
            }
        }
    }

    private fun pdfTitle(sheet: CallSheetSummary): String =
        "CallSheet_${sheet.serialNo.ifBlank { sheet.id }}"

    private fun download() {
        val overlay = ctx.state.pdf ?: return
        val bytes = overlay.bytes ?: return
        val fileName = overlay.title.let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }
        ctx.launchWork {
            when (val saved = ctx.services.delivery.savePdf(fileName, bytes)) {
                is ZillitResult.Success -> ctx.toast("Saved $fileName to Downloads.")
                is ZillitResult.Failure -> ctx.toast(saved.error.withPrefix("Couldn't save the PDF: "), isError = true)
            }
        }
    }

    /**
     * Deletes after the confirm. The confirm stays open, its buttons busy,
     * until the request settles (the web's `useInFlight`); then the row
     * leaves every list at once and the list on screen reloads.
     */
    fun delete(sheet: CallSheetSummary) {
        if (ctx.state.busy || !ctx.state.isPoster || sheet.status.locked) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.delete(sheet.id)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, dialog = closedConfirm(), lists = lists.without(sheet.id)) }
                    ctx.toast("Deleted!")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false, dialog = closedConfirm()) }
                    ctx.toast(result.error.withPrefix("Delete failed: "), isError = true)
                }
            }
        }
    }

    /** The confirm that held the request closes; anything opened over it since stays. */
    private fun SheetUiState.closedConfirm(): SheetDialog? = if (dialog is SheetDialog.Confirm) null else dialog

    /**
     * History needs the detail — list rows carry neither revisions nor
     * reminders. Received builds from the row, as the web does; a failed
     * detail falls back to the row.
     */
    private fun history(sheet: CallSheetSummary, title: String, fromDetail: Boolean) {
        if (ctx.state.historyLoadingId != null) return
        val emptyText = if (ctx.state.activeTab == SheetTab.Published) {
            "No history found for this call sheet."
        } else {
            "No history found."
        }
        if (!fromDetail) {
            val entries = SheetHistory.entries(CallSheetDetail(sheet, SheetPayload()), ctx.state.members)
            ctx.update { copy(dialog = SheetDialog.History(title, entries, emptyText)) }
            return
        }
        ctx.update { copy(historyLoadingId = sheet.id) }
        ctx.launchWork {
            val detail = (ctx.repository.sheet(sheet.id) as? ZillitResult.Success)?.data
                ?: CallSheetDetail(sheet, SheetPayload())
            val entries = SheetHistory.entries(detail, ctx.state.members)
            ctx.update {
                copy(historyLoadingId = null, dialog = SheetDialog.History(title, entries, emptyText))
            }
        }
    }

    /**
     * "Approval Status": the current round of the stage the sheet is in; a Sent
     * row without requests asks the detail.
     */
    private fun approvalStatus(sheet: CallSheetSummary) {
        if (ctx.state.statusLoadingId != null) return
        val stage = stageForStatus(sheet.status)
        if (sheet.approvalsIncluded && sheet.approvals.isNotEmpty()) {
            val entries = approvalStatusEntries(sheet.approvals, stage, ctx.state.members)
            ctx.update { copy(dialog = SheetDialog.ApprovalStatus("Approval Status", entries)) }
            return
        }
        ctx.update { copy(statusLoadingId = sheet.id) }
        ctx.launchWork {
            val approvals = (ctx.repository.sheet(sheet.id) as? ZillitResult.Success)?.data?.approvals
                ?: sheet.approvals
            val entries = approvalStatusEntries(approvals, stage, ctx.state.members)
            ctx.update {
                copy(statusLoadingId = null, dialog = SheetDialog.ApprovalStatus("Approval Status", entries))
            }
        }
    }

    /** "Attach Document": a PDF picked here, captioned, then posted beside the live call sheet. */
    private fun attach(sheet: CallSheetSummary) {
        if (!ctx.state.isPoster) return
        ctx.launchWork {
            val picked = ctx.services.publishing.pickPdf() ?: return@launchWork
            ctx.update { copy(dialog = SheetDialog.AttachDocument(sheet, picked)) }
        }
    }

    private companion object {
        const val PDF_RENDER_WIDTH = 1100
    }
}

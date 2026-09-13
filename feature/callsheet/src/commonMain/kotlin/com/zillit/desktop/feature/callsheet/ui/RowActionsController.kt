package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetHistory
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.approvalStatusEntries
import com.zillit.desktop.feature.callsheet.domain.chatTargets
import com.zillit.desktop.feature.callsheet.domain.deleteQuestion
import com.zillit.desktop.feature.callsheet.domain.latestReminder
import com.zillit.desktop.feature.callsheet.domain.stageForStatus

/**
 * The row actions every list shares: View (the server PDF), Delete, History,
 * Approval status, Chat, the reminder popup, and Published's Attach Document.
 */
internal class RowActionsController(private val ctx: SheetContext) {

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one branch per row action.
    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.View -> view(event.sheet)
            ListEvent.ClosePdf -> ctx.update { copy(pdf = null) }
            ListEvent.DownloadPdf -> download()
            is ListEvent.Edit -> ctx.editor.openExisting(event.sheet)
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
            is ListEvent.OpenHistory -> history(event.sheet, event.title, event.fromDetail)
            is ListEvent.OpenApprovalStatus -> approvalStatus(event.sheet)
            is ListEvent.ChatWithApprovers -> chatWithApprovers(event.sheet)
            is ListEvent.ChatWithCreator -> chatWithCreator(event.sheet)
            is ListEvent.ChatWith -> chatWith(event.userId)
            is ListEvent.ViewReminder -> latestReminder(event.sheet, ctx.state.me)?.let { reminder ->
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

    /** Deletes after the confirm; the row leaves every list at once, then the list on screen reloads. */
    fun delete(sheet: CallSheetSummary) {
        if (ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.delete(sheet.id)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, lists = lists.without(sheet.id)) }
                    ctx.toast("Deleted!")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(result.error.withPrefix("Delete failed: "), isError = true)
                }
            }
        }
    }

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

    /** Sent's Chat: the sheet's approvers and comment recipients, minus me. */
    private fun chatWithApprovers(sheet: CallSheetSummary) {
        val ids = chatTargets(sheet, ctx.state.me)
        if (ids.isEmpty()) {
            ctx.toast("No approver selected", isError = true)
            return
        }
        ctx.update { copy(dialog = SheetDialog.ChatPicker("Chat with Approver", ids)) }
    }

    private fun chatWithCreator(sheet: CallSheetSummary) {
        val creator = sheet.createdById.trim()
        if (creator.isEmpty() || creator == ctx.state.me) {
            ctx.toast("No creator available to chat with", isError = true)
            return
        }
        ctx.update { copy(dialog = SheetDialog.ChatPicker("Chat with Creator", listOf(creator))) }
    }

    private fun chatWith(userId: String) {
        ctx.update { copy(dialog = null) }
        val name = ctx.state.member(userId)?.fullName.orEmpty()
        if (!ctx.services.chat.openChat(userId, name)) {
            ctx.toast("Unable to open chat — chat integration is not available.", isError = true)
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

package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ReportHistory
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.approverIdsOf
import com.zillit.desktop.feature.productionreport.domain.deleteQuestion

/**
 * The row actions every list shares: View (the server PDF), Delete, History,
 * Chat, and Published's Attach Document.
 */
internal class RowActionsController(private val ctx: ReportContext) {

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.View -> view(event.report)
            ListEvent.ClosePdf -> ctx.update { copy(pdf = null) }
            ListEvent.DownloadPdf -> download()
            is ListEvent.Edit -> ctx.editor.openExisting(event.report)
            is ListEvent.Delete -> ctx.update {
                copy(
                    dialog = ReportDialog.Confirm(
                        action = ConfirmAction.DeleteReport(event.report),
                        title = "Delete Production Report",
                        message = deleteQuestion(event.report),
                        confirmLabel = "Delete",
                        danger = true,
                    ),
                )
            }
            is ListEvent.OpenHistory -> history(event.report, event.title)
            is ListEvent.ChatWithApprovers -> chatWithApprovers(event.report)
            is ListEvent.ChatWithCreator -> chatWithCreator(event.report)
            is ListEvent.ChatWith -> chatWith(event.userId)
            ListEvent.AttachDocument -> attach()
            else -> Unit
        }
    }

    /** "Generating PDF", then the viewer — the web's `viewProductionReport`. */
    private fun view(report: ReportSummary) {
        ctx.update { copy(pdf = PdfOverlay(reportId = report.id, title = report.name.ifBlank { "PDF View" })) }
        ctx.launchWork {
            when (val bytes = ctx.services.delivery.pdf(report.id)) {
                is ZillitResult.Failure -> {
                    ctx.update { copy(pdf = null) }
                    ctx.toast(bytes.error.withPrefix("Failed to generate PDF: "), isError = true)
                }
                is ZillitResult.Success -> when (val pages = ctx.services.delivery.renderPages(
                    bytes.data,
                    PDF_RENDER_WIDTH,
                )) {
                    is ZillitResult.Failure -> {
                        ctx.update { copy(pdf = null) }
                        ctx.toast(pages.error.withPrefix("Failed to generate PDF: "), isError = true)
                    }
                    is ZillitResult.Success -> ctx.update {
                        if (pdf?.reportId != report.id) this else copy(
                            pdf = pdf.copy(loading = false, pages = pages.data, bytes = bytes.data),
                        )
                    }
                }
            }
        }
    }

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

    /** Deletes after the confirm; the row leaves every list at once. */
    fun delete(report: ReportSummary) {
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.delete(report.id)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, lists = lists.without(report.id)) }
                    ctx.toast("Deleted!")
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(result.error.withPrefix("Delete failed: "), isError = true)
                }
            }
        }
    }

    /** History needs the detail — list rows carry neither revisions nor reminders. */
    private fun history(report: ReportSummary, title: String) {
        if (ctx.state.historyLoadingId != null) return
        ctx.update { copy(historyLoadingId = report.id) }
        ctx.launchWork {
            val detail = (ctx.repository.report(report.id) as? ZillitResult.Success)?.data
            val entries = detail?.let { ReportHistory.entries(it) }
                ?: ReportHistory.entries(
                    com.zillit.desktop.feature.productionreport.domain.ReportDetail(
                        report,
                        com.zillit.desktop.feature.productionreport.domain.SheetPayload(),
                    ),
                )
            val publishedTab = ctx.state.tab == com.zillit.desktop.feature.productionreport.domain.ManageTab.Published
            val emptyText = if (title == "History" && publishedTab) {
                "No history found for this production report."
            } else {
                "No history found."
            }
            ctx.update {
                copy(
                    historyLoadingId = null,
                    dialog = ReportDialog.History(title = title, entries = entries, emptyText = emptyText),
                )
            }
        }
    }

    /** Sent's Chat: the report's approvers minus me, always through the picker (ZL-20652). */
    private fun chatWithApprovers(report: ReportSummary) {
        val ids = approverIdsOf(report).filter { it != ctx.state.me }
        if (ids.isEmpty()) {
            ctx.toast("No approver to chat with", isError = true)
            return
        }
        ctx.update { copy(dialog = ReportDialog.ChatPicker("Chat with Approver", ids)) }
    }

    private fun chatWithCreator(report: ReportSummary) {
        val creator = report.createdById.trim()
        if (creator.isEmpty() || creator == ctx.state.me) {
            ctx.toast("No creator available to chat with", isError = true)
            return
        }
        ctx.update { copy(dialog = ReportDialog.ChatPicker("Chat with Creator", listOf(creator))) }
    }

    private fun chatWith(userId: String) {
        ctx.update { copy(dialog = null) }
        val name = ctx.state.member(userId)?.fullName.orEmpty()
        if (!ctx.services.chat.openChat(userId, name)) {
            ctx.toast("Unable to open chat — chat integration is not available.", isError = true)
        }
    }

    /** "Attach Document": PDFs posted into the unit chat alongside the live report. */
    private fun attach() {
        ctx.launchWork {
            when (val result = ctx.services.publishing.attachDocuments(replacePrevious = false)) {
                is ZillitResult.Success -> if (result.data > 0) {
                    ctx.toast("Document attached successfully!")
                    ctx.lists.loadPublished()
                }
                is ZillitResult.Failure -> ctx.toast(
                    "Attach document failed: ${result.error.localised()}",
                    isError = true,
                )
            }
        }
    }

    private companion object {
        const val PDF_RENDER_WIDTH = 1100
    }
}

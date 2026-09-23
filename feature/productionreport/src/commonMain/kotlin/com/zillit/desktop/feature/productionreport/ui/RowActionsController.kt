package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportHistory
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.deleteQuestion

/**
 * The row actions every list shares: View (the server PDF), Delete, History,
 * Send for Chat, and Published's Attach Document. Opening a report reads its
 * REPORT badge on the list it was opened from.
 */
@Suppress("TooManyFunctions") // One function per row action.
internal class RowActionsController(private val ctx: ReportContext) {

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.View -> view(event.report)
            ListEvent.ClosePdf -> ctx.update { copy(pdf = null) }
            ListEvent.DownloadPdf -> download()
            is ListEvent.Edit -> {
                ctx.lists.readRowBadge(event.report.id, BadgeKind.Report)
                ctx.editor.openExisting(event.report)
            }
            is ListEvent.Delete -> ctx.update {
                copy(
                    dialog = ReportDialog.Confirm(
                        action = ConfirmAction.DeleteReport(event.report),
                        title = "Delete Production Report",
                        message = deleteQuestion(event.report),
                        confirmLabel = "Delete",
                        danger = true,
                        reportId = event.report.id,
                    ),
                )
            }
            is ListEvent.OpenHistory -> history(event.report, event.title)
            is ListEvent.SendForChat -> openSendForChat(event.report)
            is ListEvent.SearchChatRecipient -> updateChat { copy(search = event.query) }
            is ListEvent.PickChatRecipient -> updateChat { copy(selected = event.userId) }
            ListEvent.ConfirmSendForChat -> sendForChat()
            ListEvent.AttachDocument -> attach()
            else -> Unit
        }
    }

    /** "Generating PDF", then the viewer — the web's `viewProductionReport`. */
    private fun view(report: ReportSummary) {
        ctx.lists.readRowBadge(report.id, BadgeKind.Report)
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

    /**
     * Deletes after the confirm, which stays up (spinning) until the request
     * settles: closed on success, kept on a refusal so the answer is read
     * against the question. The row leaves every list at once.
     */
    fun delete(report: ReportSummary) {
        ctx.update {
            copy(busy = true, dialog = (dialog as? ReportDialog.Confirm)?.copy(busy = true) ?: dialog)
        }
        ctx.launchWork {
            when (val result = ctx.repository.delete(report.id)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, dialog = null, lists = lists.without(report.id)) }
                    ctx.toast("Deleted!")
                }
                is ZillitResult.Failure -> {
                    ctx.update {
                        copy(busy = false, dialog = (dialog as? ReportDialog.Confirm)?.copy(busy = false) ?: dialog)
                    }
                    ctx.toast(result.error.withPrefix("Delete failed: "), isError = true)
                }
            }
        }
    }

    /** History needs the detail — list rows carry neither revisions nor reminders. */
    private fun history(report: ReportSummary, title: String) {
        if (ctx.state.historyLoadingId != null) return
        ctx.lists.readRowBadge(report.id, BadgeKind.Report)
        ctx.update { copy(historyLoadingId = report.id) }
        ctx.launchWork {
            val detail = (ctx.repository.report(report.id) as? ZillitResult.Success)?.data
            val members = ctx.state.members
            val entries = detail?.let { ReportHistory.entries(it, members) }
                ?: ReportHistory.entries(ReportDetail(report, SheetPayload()), members)
            val publishedTab = ctx.state.tab == ManageTab.Published
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

    // Send for Chat (ZL-21415) --------------------------------------------------------

    /** Anyone who can see a row may share its PDF — never once final-approved or published. */
    private fun openSendForChat(report: ReportSummary) {
        if (report.status.locked) return
        ctx.update { copy(dialog = ReportDialog.SendForChat(report)) }
    }

    /**
     * The saved PDF as a document message in a 1:1 chat with the chosen
     * member. The picker stays up while it sends and on a failure, so the
     * send can be retried; it closes only once the message is handed over.
     */
    private fun sendForChat() {
        val dialog = ctx.state.dialog as? ReportDialog.SendForChat ?: return
        val userId = dialog.selected ?: return
        if (dialog.sending || dialog.report.status.locked || userId == ctx.state.me) return
        updateChat { copy(sending = true) }
        ctx.launchWork {
            val report = dialog.report
            val fileName = "ProductionReport_${report.name.ifBlank { report.id }}.pdf"
            val outcome = when (val pdf = ctx.services.delivery.pdf(report.id)) {
                is ZillitResult.Failure -> pdf
                is ZillitResult.Success -> ctx.services.publishing.sendPdfToChat(userId, pdf.data, fileName)
            }
            when (outcome) {
                is ZillitResult.Success -> {
                    ctx.update { copy(dialog = if (dialog.sameAs(this.dialog)) null else this.dialog) }
                    ctx.toast("Production report PDF sent to chat.")
                }
                is ZillitResult.Failure -> {
                    updateChat { copy(sending = false) }
                    ctx.toast(outcome.error.withPrefix("Couldn't send the PDF to chat: "), isError = true)
                }
            }
        }
    }

    private fun ReportDialog.SendForChat.sameAs(other: ReportDialog?): Boolean =
        (other as? ReportDialog.SendForChat)?.report?.id == report.id

    private fun updateChat(change: ReportDialog.SendForChat.() -> ReportDialog.SendForChat) = ctx.update {
        copy(dialog = (dialog as? ReportDialog.SendForChat)?.change() ?: dialog)
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

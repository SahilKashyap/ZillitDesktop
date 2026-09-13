package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReminderRequest
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReviewAssignee
import com.zillit.desktop.feature.productionreport.domain.actionableRequest
import com.zillit.desktop.feature.productionreport.domain.reminderAssigneeIds
import com.zillit.desktop.feature.productionreport.domain.resolveApproverIdsForSend
import com.zillit.desktop.feature.productionreport.domain.userReminders

/**
 * The review workflow: send for comments and for signature, approve (with or
 * without a signature), reject, remind, publish, and Document Distribution —
 * the web's `handleSubmit*`, `approve/rejectProductionReport`,
 * `handleSendReminder`, `confirmPublish` and `distributeReportToDocDist`.
 * Every action re-checks its gate here, not only on screen.
 */
@Suppress("TooManyFunctions") // One function per step of the web's workflow.
internal class WorkflowController(private val ctx: ReportContext) {

    private val recipients = RecipientPicker(ctx)

    /** The editor's "Send for Approval": the comments / signature chooser first. */
    fun openEditorSend() = recipients.open(reportId = ctx.state.editor?.reportId, fromEditor = true)

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one branch per workflow step.
    fun onEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.SendForSignature -> sendForSignature(event.report.id)
            is WorkflowEvent.SendForComments -> recipients.open(event.report.id, fromEditor = false)
            is WorkflowEvent.SendToDocDist -> askDocDist(event.report, event.fromDraft)
            WorkflowEvent.ConfirmDocDist -> confirmDocDist()
            WorkflowEvent.ChooseComments, WorkflowEvent.ChooseSignature, WorkflowEvent.BackToChooser,
            is WorkflowEvent.SearchRecipients,
            is WorkflowEvent.ToggleRecipient, WorkflowEvent.ToggleAllRecipients, WorkflowEvent.SendRecipients,
            WorkflowEvent.CancelRemoval,
            -> recipients.onEvent(event, onFinalFromEditor = ::sendFromEditorForSignature, onFinish = ::finishSend)
            is WorkflowEvent.FinishSend -> finishSend(event.revokeAccess)
            is WorkflowEvent.OpenApprove -> openApprove(event.report)
            is WorkflowEvent.ApproveWithSignature -> approveWithSignature(event.png)
            WorkflowEvent.ApproveWithoutSignature -> approve(ApprovalDecision.WithoutSignature)
            is WorkflowEvent.OpenReject -> openReject(event.report)
            is WorkflowEvent.EditRejectReason -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.Reject)?.copy(reason = event.reason) ?: dialog)
            }
            WorkflowEvent.ConfirmReject -> reject()
            is WorkflowEvent.OpenReminder -> ctx.update { copy(dialog = ReportDialog.ReminderCompose(event.report)) }
            is WorkflowEvent.EditReminder -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.ReminderCompose)?.copy(message = event.message) ?: dialog)
            }
            WorkflowEvent.ConfirmReminder -> remind()
            is WorkflowEvent.ViewReminders -> ctx.update {
                copy(dialog = ReportDialog.Reminders(userReminders(event.report, me)))
            }
            is WorkflowEvent.OpenPublish, is WorkflowEvent.PickDestination, WorkflowEvent.ContinuePublish,
            is WorkflowEvent.PickContinuation, WorkflowEvent.ConfirmPublish,
            -> onPublishEvent(event)
        }
    }

    // Send for signature ---------------------------------------------------------

    /**
     * Send for Signature: the report's own approvers (payload list, then the
     * Approvers block, then the project default); none → the "No Approvers" prompt.
     */
    fun sendForSignature(reportId: String, afterSend: () -> Unit = {}) {
        if (!ctx.state.isPoster || ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val detail = (ctx.repository.report(reportId) as? ZillitResult.Success)?.data
            val ids = resolveApproverIdsForSend(detail, ctx.state.metadata.finalApproverIds)
            if (ids.isEmpty()) {
                ctx.update { copy(busy = false, dialog = noApproversPrompt()) }
                return@launchWork
            }
            val approvers = ids.map { id ->
                val member = ctx.state.member(id)
                ReviewAssignee(
                    id,
                    member?.fullName?.ifBlank { null } ?: id,
                    member?.designation?.ifBlank { null } ?: "Approver",
                )
            }
            when (val sent = ctx.repository.submitForApproval(reportId, approvers, ctx.viewer().displayName)) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            busy = false,
                            lists = lists.copy(
                                drafts = lists.drafts.copy(rows = lists.drafts.rows.filterNot { it.id == reportId }),
                            ),
                        )
                    }
                    ctx.toast("Sent for approval!")
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                    afterSend()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun noApproversPrompt() = ReportDialog.Confirm(
        action = ConfirmAction.NoApprovers,
        title = "No Approvers",
        message = "No approvers exist for this report. Add approvers before sending a production report for signature.",
        confirmLabel = "OK",
        danger = false,
    )

    /** The editor's "For Signature": approvers checked first, then save, then send, then Approvals → Sent. */
    private fun sendFromEditorForSignature() {
        val editor = ctx.state.editor ?: return
        if (editor.document.shared.approverIds.isEmpty()) {
            ctx.update { copy(dialog = noApproversPrompt()) }
            return
        }
        ctx.editor.saveThen { savedId ->
            sendForSignature(savedId) {
                ctx.update {
                    copy(
                        workspace = Workspace.Manage,
                        tab = ManageTab.Approvals,
                        section = ApprovalSection.Sent,
                    )
                }
                ctx.lists.loadCurrentTab()
            }
        }
    }

    // Send for comments ------------------------------------------------------------

    /**
     * Recipients chosen: write the receiver list (this write IS the removal of
     * anyone dropped), then submit the INTERNAL round with the same ids. The
     * report stays in Drafts, under For Comments.
     */
    private fun finishSend(revokeAccess: Boolean) {
        val picker = ctx.state.dialog as? ReportDialog.SendPicker ?: return
        val ids = recipients.payloadIds(picker)
        ctx.update { copy(dialog = null) }
        if (picker.fromEditor) {
            ctx.editor.saveThen { savedId -> sendForComments(savedId, ids, revokeAccess) }
        } else {
            picker.reportId?.let { sendForComments(it, ids, revokeAccess) }
        }
    }

    private fun sendForComments(reportId: String, ids: List<String>, revokeAccess: Boolean) {
        if (!ctx.state.isPoster) return
        if (ids.isEmpty()) {
            ctx.toast("No recipients selected.", isError = true)
            return
        }
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            ctx.projectId()?.let { project ->
                val saved = ctx.repository.saveMetadata(
                    project,
                    MetadataUpdate(internalReceiverIds = ids, revokeAccessOnRemoval = revokeAccess),
                )
                if (saved is ZillitResult.Success) ctx.update {
                    copy(
                        metadata = metadata.copy(internalReceiverIds = ids),
                    )
                }
            }
            val approvers = ids.map { id ->
                val member = ctx.state.member(id)
                ReviewAssignee(
                    id,
                    member?.fullName?.ifBlank { null } ?: id,
                    member?.designation?.ifBlank { null } ?: "Member",
                )
            }
            when (val sent = ctx.repository.submitForInternalApproval(reportId, approvers, ctx.viewer().displayName)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Sent for comments!")
                    ctx.lists.loadDrafts()
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    // Approve / reject / remind ------------------------------------------------------

    private fun openApprove(report: ReportSummary) {
        val request = actionableRequest(report, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
        ctx.update { copy(dialog = ReportDialog.Approve(report, request)) }
    }

    private fun approveWithSignature(png: ByteArray) {
        val dialog = ctx.state.dialog as? ReportDialog.Approve ?: return
        if (dialog.uploading) return
        ctx.update { copy(dialog = dialog.copy(uploading = true)) }
        ctx.launchWork {
            when (val stored = ctx.services.publishing.uploadSignature(png)) {
                is ZillitResult.Success -> approve(stored.data)
                is ZillitResult.Failure -> {
                    // The web falls back to an unmapped `signatureDataUrl` the
                    // backend may not read; this keeps the dialog so the
                    // approver can retry or approve without the signature.
                    ctx.update {
                        copy(dialog = (this.dialog as? ReportDialog.Approve)?.copy(uploading = false) ?: this.dialog)
                    }
                    ctx.toast("Couldn't upload the signature: ${stored.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun approve(decision: ApprovalDecision) {
        val dialog = ctx.state.dialog as? ReportDialog.Approve ?: return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.approve(dialog.request.id, decision)) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            busy = false,
                            dialog = null,
                            lists = lists.copy(
                                received = lists.received.copy(
                                    rows = lists.received.rows.filterNot { it.id == dialog.report.id },
                                ),
                            ),
                        )
                    }
                    ctx.toast("Approved!")
                    ctx.lists.loadApproverSheets(received = true)
                    ctx.lists.refreshFinalized()
                }
                is ZillitResult.Failure -> {
                    ctx.update {
                        copy(
                            busy = false,
                            dialog = (this.dialog as? ReportDialog.Approve)?.copy(uploading = false) ?: this.dialog,
                        )
                    }
                    ctx.toast("Approve failed: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun openReject(report: ReportSummary) {
        val request = actionableRequest(report, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
        ctx.update { copy(dialog = ReportDialog.Reject(report, request)) }
    }

    private fun reject() {
        val dialog = ctx.state.dialog as? ReportDialog.Reject ?: return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.reject(dialog.request.id, dialog.reason)) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            busy = false,
                            dialog = null,
                            lists = lists.copy(
                                received = lists.received.copy(
                                    rows = lists.received.rows.filterNot { it.id == dialog.report.id },
                                ),
                            ),
                        )
                    }
                    ctx.toast("Rejected.")
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Reject failed: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    /**
     * Reminders go to the current round's PENDING approvers (ZL-20678). A list
     * row without requests asks the detail — its requests first, and only then
     * the payload's approver list the web falls back to.
     */
    private fun remind() {
        val dialog = ctx.state.dialog as? ReportDialog.ReminderCompose ?: return
        val report = dialog.report
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            var assignees = reminderAssigneeIds(report.status, report.approvals)
            if (assignees.isEmpty()) {
                val detail = (ctx.repository.report(report.id) as? ZillitResult.Success)?.data
                assignees = detail?.let { reminderAssigneeIds(it.summary.status, it.approvals) }.orEmpty()
                    .ifEmpty { detail?.payload?.shared?.approverIds.orEmpty() }
            }
            if (assignees.isEmpty()) {
                ctx.update { copy(busy = false, dialog = null) }
                ctx.toast("No pending approvers to remind on this report.", isError = true)
                return@launchWork
            }
            val me = ctx.state.currentMember
            val request = ReminderRequest(
                sentBy = ctx.viewer().displayName,
                sentById = ctx.state.me,
                assigneeIds = assignees,
                sentByRole = me?.designation.orEmpty(),
                message = dialog.message.trim().ifEmpty { DEFAULT_REMINDER },
            )
            when (val sent = ctx.repository.sendReminder(report.id, request)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, dialog = null) }
                    ctx.toast("Reminder sent!")
                    ctx.lists.loadSent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Reminder failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    // Publish ---------------------------------------------------------------------------

    private fun onPublishEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.OpenPublish -> if (ctx.state.isPoster) {
                ctx.update { copy(dialog = ReportDialog.Publish(event.report)) }
            }
            is WorkflowEvent.PickDestination -> ctx.update {
                val publish = dialog as? ReportDialog.Publish
                val blocked = event.destination.needsDocDist && !ctx.services.publishing.canDistribute()
                copy(dialog = if (publish == null || blocked) dialog else publish.copy(destination = event.destination))
            }
            WorkflowEvent.ContinuePublish -> {
                val publish = ctx.state.dialog as? ReportDialog.Publish ?: return
                if (publish.destination == PublishDestination.DocDist) {
                    ctx.update { copy(dialog = null) }
                    distribute(
                        publish.report,
                        fromDraft = false,
                        alreadyPublished = false,
                        afterwards = { ctx.lists.refreshFinalized() },
                    )
                } else {
                    ctx.update { copy(dialog = publish.copy(choosingDestination = false)) }
                }
            }
            is WorkflowEvent.PickContinuation -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.Publish)?.copy(continuation = event.continuation) ?: dialog)
            }
            WorkflowEvent.ConfirmPublish -> publish()
            else -> Unit
        }
    }

    /**
     * Publish in app (and on Both): the publish call, then the reloads, then —
     * after the dialog has closed — the Document Distribution copy and the PDF
     * posted into the unit chat, where New replaces earlier posts and
     * Continuation appends.
     */
    private fun publish() {
        val dialog = ctx.state.dialog as? ReportDialog.Publish ?: return
        val continuation = dialog.continuation ?: return
        if (!ctx.state.isPoster || ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val result = ctx.repository.publish(dialog.report.id, ctx.viewer().displayName, ctx.state.me, continuation)
            if (result is ZillitResult.Failure) {
                ctx.update { copy(busy = false) }
                ctx.toast("Publish failed: ${result.error.localised()}", isError = true)
                return@launchWork
            }
            ctx.update { copy(busy = false, dialog = null) }
            ctx.toast("Published!")
            ctx.lists.refreshFinalized()
            ctx.lists.loadPublished()
            ctx.lists.loadSent()
            if (dialog.destination == PublishDestination.Both) {
                distribute(dialog.report, fromDraft = false, alreadyPublished = true)
            }
            postToChat(dialog.report, replacePrevious = !continuation)
        }
    }

    /** Best-effort, like the web: a failed chat post never unpublishes. */
    private suspend fun postToChat(report: ReportSummary, replacePrevious: Boolean) {
        val pdf = (ctx.services.delivery.pdf(report.id) as? ZillitResult.Success)?.data ?: return
        val fileName = "ProductionReport_${report.name.ifBlank { report.id }}.pdf"
        ctx.services.publishing.postToChat(pdf, fileName, replacePrevious)
    }

    // Document Distribution -----------------------------------------------------------------

    private fun askDocDist(report: ReportSummary, fromDraft: Boolean) {
        if (!ctx.services.publishing.canDistribute()) {
            ctx.toast("You don't have permission to distribute to Document Distribution.", isError = true)
            return
        }
        ctx.update { copy(dialog = ReportDialog.DocDistConfirm(report, pdfName(report), fromDraft)) }
    }

    private fun confirmDocDist() {
        val dialog = ctx.state.dialog as? ReportDialog.DocDistConfirm ?: return
        ctx.update { copy(dialog = null) }
        distribute(dialog.report, dialog.fromDraft, dialog.alreadyPublished)
    }

    /**
     * The report's PDF into the library, filed under `Production Report` (or
     * `Draft Production Report`) and dated by the shoot date from the detail.
     */
    private fun distribute(
        report: ReportSummary,
        fromDraft: Boolean,
        alreadyPublished: Boolean,
        afterwards: () -> Unit = {},
    ) {
        if (!ctx.services.publishing.canDistribute()) {
            ctx.toast("You don't have permission to distribute to Document Distribution.", isError = true)
            return
        }
        ctx.launchWork {
            ctx.toast("Sending to Document Distribution…")
            val detail = (ctx.repository.report(report.id) as? ZillitResult.Success)?.data
            val date = detail?.payload?.shared?.dateYmd?.ifBlank { null } ?: report.shared?.dateYmd?.ifBlank { null }
            val fileName = pdfName(detail?.summary ?: report)
            val outcome = when (val pdf = ctx.services.delivery.pdf(report.id)) {
                is ZillitResult.Failure -> pdf
                is ZillitResult.Success -> ctx.services.publishing.sendToDocumentDistribution(
                    pdf.data,
                    fileName,
                    fromDraft,
                    date,
                )
            }
            when (outcome) {
                is ZillitResult.Success -> ctx.update { copy(dialog = ReportDialog.DocDistDone(fileName)) }
                is ZillitResult.Failure -> ctx.toast(
                    if (alreadyPublished) {
                        "Published, but the Document Distribution copy failed: ${outcome.error.localised()}"
                    } else {
                        outcome.error.localised()
                            .ifBlank { "Couldn't send to Document Distribution — please try again" }
                    },
                    isError = true,
                )
            }
            afterwards()
        }
    }

    private fun pdfName(report: ReportSummary): String {
        val base = report.name.trim()
            .ifEmpty { "Production Report ${report.id}".trim() }.ifEmpty { "Production Report" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    private companion object {
        const val DEFAULT_REMINDER = "Please review and approve this production report."
    }
}

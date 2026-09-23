package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ApprovalDecision
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.MetadataUpdate
import com.zillit.desktop.feature.productionreport.domain.ReminderRequest
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReviewAssignee
import com.zillit.desktop.feature.productionreport.domain.actionableRequest
import com.zillit.desktop.feature.productionreport.domain.hasRequiredCallTimes
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

    /** The editor's "Send for Comments": the recipient picker; the send saves first. */
    fun openEditorComments() = recipients.open(reportId = ctx.state.editor?.reportId, fromEditor = true)

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one branch per workflow step.
    fun onEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.SendForSignature -> sendForSignature(event.report.id)
            is WorkflowEvent.SendForComments -> recipients.open(event.report.id, fromEditor = false)
            is WorkflowEvent.SendToDocDist -> askDocDist(event.report, event.fromDraft)
            WorkflowEvent.ConfirmDocDist -> confirmDocDist()
            is WorkflowEvent.SearchRecipients, is WorkflowEvent.ToggleRecipient, WorkflowEvent.ToggleAllRecipients,
            WorkflowEvent.SendRecipients, WorkflowEvent.CancelRemoval,
            -> recipients.onEvent(event, onFinish = ::finishSend)
            is WorkflowEvent.FinishSend -> finishSend(event.revokeAccess)
            is WorkflowEvent.OpenApprove -> openApprove(event.report)
            WorkflowEvent.ChooseSignedApproval -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.Approve)?.copy(sign = true) ?: dialog)
            }
            is WorkflowEvent.ApproveWithSignature -> approveWithSignature(event.png)
            WorkflowEvent.ApproveWithoutSignature -> approve(ApprovalDecision.WithoutSignature)
            is WorkflowEvent.OpenReject -> openReject(event.report)
            is WorkflowEvent.EditRejectReason -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.Reject)?.copy(reason = event.reason) ?: dialog)
            }
            WorkflowEvent.ConfirmReject -> reject()
            is WorkflowEvent.OpenReminder -> {
                ctx.lists.readRowBadge(event.report.id, BadgeKind.Report)
                ctx.update { copy(dialog = ReportDialog.ReminderCompose(event.report)) }
            }
            is WorkflowEvent.EditReminder -> ctx.update {
                copy(dialog = (dialog as? ReportDialog.ReminderCompose)?.copy(message = event.message) ?: dialog)
            }
            WorkflowEvent.ConfirmReminder -> remind()
            is WorkflowEvent.ViewReminders -> {
                ctx.lists.readRowBadge(event.report.id, BadgeKind.Report)
                ctx.update { copy(dialog = ReportDialog.Reminders(event.report.id, userReminders(event.report, me))) }
            }
            is WorkflowEvent.OpenPublish, is WorkflowEvent.PickDestination, WorkflowEvent.ContinuePublish,
            is WorkflowEvent.PickPublishType, is WorkflowEvent.PickReplaceTarget, WorkflowEvent.ConfirmPublish,
            -> onPublishEvent(event)
        }
    }

    // Send for signature ---------------------------------------------------------

    /**
     * Send for Signature: checked on the SAVED report, here where every
     * signature send routes (editor, Drafts row, Sent row) — the call times
     * first (ZL-21398), then the report's own approvers (payload list, then
     * the Approvers block, then the project default); none → "No Approvers".
     */
    fun sendForSignature(reportId: String, afterSend: () -> Unit = {}) {
        if (!ctx.state.isPoster || ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val detail = (ctx.repository.report(reportId) as? ZillitResult.Success)?.data
            if (detail != null && detail.hasPayload && !hasRequiredCallTimes(detail.payload)) {
                ctx.update { copy(busy = false, dialog = missingCallTimesPrompt()) }
                return@launchWork
            }
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
                    member?.designation?.ifBlank { null } ?: str(S.desktop_approver),
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
                    ctx.toast(str(S.desktop_sent_for_approval))
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                    afterSend()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(str(S.drive_uploads_status_failed_format, sent.error.localised()), isError = true)
                }
            }
        }
    }

    private fun noApproversPrompt() = ReportDialog.Confirm(
        action = ConfirmAction.NoApprovers,
        title = str(S.desktop_no_approvers),
        message = str(S.desktop_pr_no_approvers_message),
        confirmLabel = str(S.ok),
        danger = false,
    )

    /** ZL-21398 — the same modal as No Approvers; the send has not happened. */
    private fun missingCallTimesPrompt() = ReportDialog.Confirm(
        action = ConfirmAction.MissingCallTimes,
        title = str(S.pr_missing_call_times_title),
        message = str(S.pr_missing_call_times_message),
        confirmLabel = str(S.ok),
        danger = false,
    )

    /**
     * The editor's "Send for Signature": the document is checked before the
     * save (call times, approvers), then saved, then sent, then Approvals →
     * Sent. A failed send leaves the editor where it was.
     */
    fun sendFromEditorForSignature() {
        val editor = ctx.state.editor ?: return
        if (!ctx.state.isPoster || editor.saving || ctx.state.busy) return
        if (!hasRequiredCallTimes(editor.document)) {
            ctx.update { copy(dialog = missingCallTimesPrompt()) }
            return
        }
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
            ctx.toast(str(S.desktop_no_recipients_selected), isError = true)
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
                    member?.designation?.ifBlank { null } ?: str(S.member),
                )
            }
            when (val sent = ctx.repository.submitForInternalApproval(reportId, approvers, ctx.viewer().displayName)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(str(S.desktop_sent_for_comments))
                    ctx.lists.loadDrafts()
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(str(S.drive_uploads_status_failed_format, sent.error.localised()), isError = true)
                }
            }
        }
    }

    // Approve / reject / remind ------------------------------------------------------

    /** ZL-21512: the chooser first; "with signature" advances to the signing screen. */
    private fun openApprove(report: ReportSummary) {
        val request = actionableRequest(report, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
        ctx.lists.readRowBadge(report.id, BadgeKind.Report)
        ctx.update { copy(dialog = ReportDialog.Approve(report, request)) }
    }

    private fun approveWithSignature(png: ByteArray) {
        val dialog = ctx.state.dialog as? ReportDialog.Approve ?: return
        if (dialog.uploading || !dialog.sign) return
        ctx.update { copy(dialog = dialog.copy(uploading = true)) }
        ctx.launchWork {
            when (val stored = ctx.services.publishing.uploadSignature(png)) {
                is ZillitResult.Success -> approve(stored.data)
                is ZillitResult.Failure -> {
                    // The web falls back to an unmapped `signatureDataUrl` the
                    // backend may not read; this keeps the dialog so the
                    // approver can retry.
                    ctx.update {
                        copy(dialog = (this.dialog as? ReportDialog.Approve)?.copy(uploading = false) ?: this.dialog)
                    }
                    val reason = stored.error.localised()
                    ctx.toast(str(S.desktop_could_not_upload_signature_reason, reason), isError = true)
                }
            }
        }
    }

    /** Closes only on success — a refused approve leaves the dialog up to retry. */
    private fun approve(decision: ApprovalDecision) {
        val dialog = ctx.state.dialog as? ReportDialog.Approve ?: return
        if (ctx.state.busy) return
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
                    ctx.toast(str(S.desktop_approved_exclaim))
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
                    ctx.toast(str(S.desktop_approve_failed_reason, result.error.localised()), isError = true)
                }
            }
        }
    }

    private fun openReject(report: ReportSummary) {
        val request = actionableRequest(report, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
        ctx.lists.readRowBadge(report.id, BadgeKind.Report)
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
                    ctx.toast(str(S.rejected))
                    ctx.lists.loadSent()
                    ctx.lists.loadApproverSheets(received = true)
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(str(S.desktop_reject_failed_reason, result.error.localised()), isError = true)
                }
            }
        }
    }

    /**
     * Reminders go to the current round's PENDING approvers (ZL-20678). A list
     * row without requests asks the detail — its requests first, and only then
     * the payload's approver list the web falls back to. `sent_by` is the
     * sender's MEMBER ID (the reader resolves it), `sent_by_role` the
     * designation KEY (ZL-20648).
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
                ctx.toast(str(S.desktop_pr_no_pending_approvers_to_remind), isError = true)
                return@launchWork
            }
            val me = ctx.state.currentMember
            val request = ReminderRequest(
                sentBy = ctx.state.me,
                sentById = ctx.state.me,
                assigneeIds = assignees,
                sentByRole = me?.designationKey?.ifBlank { null } ?: me?.designation.orEmpty(),
                message = dialog.message.trim().ifEmpty { DEFAULT_REMINDER },
            )
            when (val sent = ctx.repository.sendReminder(report.id, request)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, dialog = null) }
                    ctx.toast(str(S.desktop_reminder_sent_exclaim))
                    ctx.lists.loadSent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast(str(S.desktop_reminder_failed_reason, sent.error.localised()), isError = true)
                }
            }
        }
    }

    // Publish ---------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One branch per step of the publish dialog, as the web's handlers read.
    private fun onPublishEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.OpenPublish -> if (ctx.state.isPoster) {
                ctx.lists.readRowBadge(event.report.id, BadgeKind.Report)
                ctx.update { copy(dialog = ReportDialog.Publish(event.report)) }
                loadReplaceOptions(event.report.id)
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
            is WorkflowEvent.PickPublishType -> ctx.update {
                val publish = dialog as? ReportDialog.Publish
                val offered = event.type != PublishType.Replace || publish?.replaceOptions?.isNotEmpty() == true
                copy(dialog = if (publish == null || !offered) dialog else publish.copy(type = event.type))
            }
            is WorkflowEvent.PickReplaceTarget -> ctx.update {
                val publish = dialog as? ReportDialog.Publish
                val known = publish?.replaceOptions?.any { it.chatId == event.chatId } == true
                copy(dialog = if (publish == null || !known) dialog else publish.copy(replaceChatId = event.chatId))
            }
            WorkflowEvent.ConfirmPublish -> publish()
            else -> Unit
        }
    }

    /**
     * The unit chat's live documents for the Replace card. Newest first, so
     * the default is "fix the one I just posted"; seeded only while nothing
     * is chosen. A failure is swallowed — no Replace card is a fine outcome.
     */
    private fun loadReplaceOptions(reportId: String) {
        ctx.launchWork {
            val options = (ctx.services.publishing.replaceableDocuments() as? ZillitResult.Success)?.data.orEmpty()
            ctx.update {
                val publish = dialog as? ReportDialog.Publish
                if (publish == null || publish.report.id != reportId) {
                    this
                } else {
                    copy(
                        dialog = publish.copy(
                            replaceOptions = options,
                            replaceChatId = publish.replaceChatId.ifBlank { options.firstOrNull()?.chatId.orEmpty() },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Publish in app (and on Both): the publish call, then the reloads, then —
     * after the dialog has closed — the Document Distribution copy and the PDF
     * posted into the unit chat. The dialog never waits for that tail.
     */
    private fun publish() {
        val dialog = ctx.state.dialog as? ReportDialog.Publish ?: return
        val type = dialog.type ?: return
        if (!ctx.state.isPoster || ctx.state.busy) return
        // The wipe flag follows the CHOICE: a Replace arriving with an empty target appends.
        val replaceChatId = dialog.replaceChatId.takeIf { type == PublishType.Replace && it.isNotBlank() }
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val result = ctx.repository.publish(
                dialog.report.id,
                ctx.viewer().displayName,
                ctx.state.me,
                continuation = type == PublishType.Continuation,
            )
            if (result is ZillitResult.Failure) {
                ctx.update { copy(busy = false) }
                ctx.toast(str(S.desktop_publish_failed_reason, result.error.localised()), isError = true)
                return@launchWork
            }
            ctx.update { copy(busy = false, dialog = null) }
            ctx.toast(str(S.desktop_published_exclaim))
            ctx.lists.refreshFinalized()
            ctx.lists.loadPublished()
            ctx.lists.loadSent()
            if (dialog.destination == PublishDestination.Both) {
                distribute(dialog.report, fromDraft = false, alreadyPublished = true)
            }
            postToChat(dialog.report, replacePrevious = type.wipesChat, replaceChatId = replaceChatId)
        }
    }

    /**
     * Best-effort, like the web: a failed chat post never unpublishes. The one
     * refusal that is reported is a Replace target deleted or archived since
     * the dialog opened — swallowing it would leave the user believing a
     * document had been replaced when the PDF was never posted at all.
     */
    private suspend fun postToChat(report: ReportSummary, replacePrevious: Boolean, replaceChatId: String?) {
        val pdf = (ctx.services.delivery.pdf(report.id) as? ZillitResult.Success)?.data ?: return
        val fileName = "ProductionReport_${report.name.ifBlank { report.id }}.pdf"
        val posted = ctx.services.publishing.postToChat(pdf, fileName, replacePrevious, replaceChatId)
        if (posted is ZillitResult.Failure && posted.error.isReplaceTargetGone()) {
            ctx.toast(
                str(S.desktop_pr_replace_target_gone),
                isError = true,
            )
        }
    }

    private fun ZillitError.isReplaceTargetGone(): Boolean =
        (this as? ZillitError.Http)?.serverMessage == REPLACE_TARGET_NOT_FOUND

    // Document Distribution -----------------------------------------------------------------

    private fun askDocDist(report: ReportSummary, fromDraft: Boolean) {
        if (!ctx.services.publishing.canDistribute()) {
            ctx.toast(str(S.dd_no_distribute_permission), isError = true)
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
            ctx.toast(str(S.dd_no_distribute_permission), isError = true)
            return
        }
        ctx.launchWork {
            ctx.toast(str(S.dd_distribute_loading))
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
                        str(S.desktop_published_but_dd_copy_failed_reason, outcome.error.localised())
                    } else {
                        outcome.error.localised()
                            .ifBlank { str(S.dd_distribute_error) }
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
        val DEFAULT_REMINDER: String get() = str(S.desktop_pr_default_reminder)
        const val REPLACE_TARGET_NOT_FOUND = "unit_chat_replace_target_not_found"
    }
}

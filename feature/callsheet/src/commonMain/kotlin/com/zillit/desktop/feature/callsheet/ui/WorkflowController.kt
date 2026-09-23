package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.ApprovalDecision
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.MetadataUpdate
import com.zillit.desktop.feature.callsheet.domain.ReminderRequest
import com.zillit.desktop.feature.callsheet.domain.ReviewAssignee
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.actionableRequest
import com.zillit.desktop.feature.callsheet.domain.approverIdsFromSheet
import com.zillit.desktop.feature.callsheet.domain.canPublish
import com.zillit.desktop.feature.callsheet.domain.reminderAssigneeIds
import com.zillit.desktop.feature.callsheet.domain.replaceTargets
import com.zillit.desktop.feature.callsheet.domain.sendForChatAllowed

/**
 * The review workflow: Send for Signature and for Comments, Send for Chat,
 * approve (asked first: with or without a signature), reject, remind,
 * publish (Continuation / New / Replace one document), attach and Document
 * Distribution — the web's `submitForApproval`, `submitForInternalApproval`,
 * `sendCallSheetForChat`, `approve/rejectCallSheet`, `sendReminder`,
 * `publishCallSheet`, `attachToPublished` and `distributeCallSheetToDocDist`.
 * Every action re-checks its gate here, not only on screen.
 *
 * Divergences, each fixing a web bug: an editor send lands where the sheet is
 * (Comments → Drafts, B-9) and only after the send succeeded (B-12); reminders
 * go to the pending approvers of the current round, never an empty list
 * (B-28); a signature that cannot be stored keeps the dialog instead of
 * posting an unread `signatureDataUrl`; "Attach a document instead" publishes
 * as a continuation, as its flag says (B-13).
 */
@Suppress("TooManyFunctions") // One function per step of the web's workflow.
internal class WorkflowController(private val ctx: SheetContext) {

    private val recipients = RecipientPicker(ctx)
    private var distributing = false

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one branch per workflow step.
    fun onEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.SendForSignature -> sendForSignature(event.sheet)
            is WorkflowEvent.SendForComments -> recipients.open(event.sheet.id, fromEditor = false)
            is WorkflowEvent.SendToDocDist -> askDocDist(event.sheet, event.fromDraft, alreadyPublished = false)
            WorkflowEvent.ConfirmDocDist -> confirmDocDist()
            is WorkflowEvent.SearchRecipients, is WorkflowEvent.ToggleRecipient, WorkflowEvent.ToggleAllRecipients,
            WorkflowEvent.SendRecipients, WorkflowEvent.CancelRemoval,
            -> recipients.onEvent(event, onFinish = ::finishSend)
            is WorkflowEvent.FinishSend -> finishSend(event.revokeAccess)
            is WorkflowEvent.OpenSendForChat, is WorkflowEvent.SearchChatRecipients,
            is WorkflowEvent.PickChatRecipient, WorkflowEvent.ConfirmSendForChat,
            -> onChatEvent(event)
            is WorkflowEvent.OpenApprove, WorkflowEvent.ChooseSignature, is WorkflowEvent.UseSignature,
            WorkflowEvent.ChangeSignature, WorkflowEvent.ApproveWithSignature, WorkflowEvent.ApproveWithoutSignature,
            -> onApproveEvent(event)
            is WorkflowEvent.OpenReject -> openReject(event.sheet)
            is WorkflowEvent.EditRejectReason -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.Reject)?.copy(reason = event.reason) ?: dialog)
            }
            WorkflowEvent.ConfirmReject -> reject()
            is WorkflowEvent.OpenReminder -> if (ctx.state.isPoster) {
                ctx.update { copy(dialog = SheetDialog.ReminderCompose(event.sheet)) }
            }
            is WorkflowEvent.EditReminder -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.ReminderCompose)?.copy(message = event.message) ?: dialog)
            }
            WorkflowEvent.ConfirmReminder -> remind()
            is WorkflowEvent.OpenPublish, is WorkflowEvent.PickDestination, WorkflowEvent.ContinuePublish,
            WorkflowEvent.BackToDestination, is WorkflowEvent.PickPublishChoice, is WorkflowEvent.PickReplaceTarget,
            is WorkflowEvent.EditPublishNotes, WorkflowEvent.ConfirmPublish, WorkflowEvent.AttachInstead,
            is WorkflowEvent.EditAttachCaption, WorkflowEvent.ConfirmAttach,
            -> onPublishEvent(event)
        }
    }

    // Send for signature ---------------------------------------------------------------------

    /**
     * `submitForApproval`: the sheet's own approver list when it states one
     * (an explicit empty list means nobody signs), else the project's —
     * re-read fresh. None → the "No Approvers" prompt; no request.
     */
    fun sendForSignature(sheet: CallSheetSummary, afterSend: () -> Unit = { ctx.lists.refreshCurrent() }) {
        if (!ctx.state.isPoster || ctx.state.busy || sheet.status.locked) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val ids = approverIdsFromSheet(sheet.shared)
                ?: (ctx.lists.refreshMetadata() ?: ctx.state.metadata).finalApproverIds
            if (ids.isEmpty()) {
                ctx.update { copy(busy = false, dialog = noApproversPrompt()) }
                return@launchWork
            }
            when (val sent = ctx.repository.submitForApproval(sheet.id)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, lists = lists.without(sheet.id)) }
                    ctx.toast("Sent for approval!")
                    afterSend()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun noApproversPrompt() = SheetDialog.Confirm(
        action = ConfirmAction.NoApprovers,
        title = "No Approvers",
        message = "No approvers exist for this project. Add approvers before sending a call sheet for signature.",
        confirmLabel = "OK",
        danger = false,
    )

    /**
     * The editor's Send for Signature: the form's own approvers checked first
     * (nothing is saved when there are none), then save, then send, then
     * Approvals → Sent.
     */
    fun sendFromEditorForSignature() {
        val editor = ctx.state.editor ?: return
        if (!editor.offersSignature || editor.saving || ctx.state.busy) return
        if (editor.document.shared.approverIds.isEmpty()) {
            ctx.update { copy(dialog = noApproversPrompt()) }
            return
        }
        ctx.editor.saveThen { savedId ->
            val saved = CallSheetSummary(
                id = savedId,
                shared = ctx.state.editor?.document?.shared ?: editor.document.shared,
            )
            sendForSignature(saved) {
                ctx.update { copy(editor = null, tab = SheetTab.Approvals, section = ApprovalSection.Sent) }
                ctx.lists.refreshCurrent()
            }
        }
    }

    // Send for comments ----------------------------------------------------------------------

    fun openEditorComments() {
        val editor = ctx.state.editor ?: return
        if (!editor.offersComments || editor.saving) return
        recipients.open(editor.sheetId, fromEditor = true)
    }

    /**
     * Recipients chosen: from the editor, save first and land on Drafts, where
     * a sheet out for comments lives.
     */
    private fun finishSend(revokeAccess: Boolean) {
        val picker = ctx.state.dialog as? SheetDialog.SendPicker ?: return
        val ids = recipients.payloadIds(picker)
        ctx.update { copy(dialog = null) }
        if (picker.fromEditor) {
            ctx.editor.saveThen { savedId ->
                sendForComments(savedId, ids, revokeAccess) {
                    ctx.update { copy(editor = null, tab = SheetTab.Drafts) }
                    ctx.lists.refreshCurrent()
                }
            }
        } else {
            picker.sheetId?.let { sendForComments(it, ids, revokeAccess) { ctx.lists.refreshCurrent() } }
        }
    }

    /**
     * Write the receiver list (this write IS the removal of anyone dropped),
     * then open the comments round with the same people.
     */
    private fun sendForComments(sheetId: String, ids: List<String>, revokeAccess: Boolean, afterSend: () -> Unit) {
        if (!ctx.state.isPoster || ctx.state.busy) return
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
                if (saved is ZillitResult.Success) {
                    ctx.update { copy(metadata = metadata.copy(internalReceiverIds = ids)) }
                }
            }
            val approvers = ids.map { id ->
                val member = ctx.state.member(id)
                ReviewAssignee(
                    assigneeId = id,
                    assigneeName = member?.fullName.orEmpty(),
                    role = member?.designation?.ifBlank { null } ?: "Member",
                )
            }
            when (val sent = ctx.repository.submitForInternalApproval(sheetId, approvers)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Sent for internal distribution!")
                    afterSend()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    // Send for chat ---------------------------------------------------------------------------

    /** ZL-21415: sharing a read-only PDF is not a posting action — anyone who sees the row may, until it locks. */
    private fun onChatEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.OpenSendForChat -> if (sendForChatAllowed(event.sheet.status)) {
                ctx.update { copy(dialog = SheetDialog.ChatSend(event.sheet)) }
            }
            is WorkflowEvent.SearchChatRecipients -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.ChatSend)?.copy(search = event.query) ?: dialog)
            }
            is WorkflowEvent.PickChatRecipient -> ctx.update {
                val chat = dialog as? SheetDialog.ChatSend
                copy(dialog = if (chat == null || chat.sending) dialog else chat.copy(selected = event.userId))
            }
            WorkflowEvent.ConfirmSendForChat -> sendForChat()
            else -> Unit
        }
    }

    /** `sendCallSheetForChat`: the rendered PDF, uploaded and posted one-to-one; the dialog stays on failure. */
    private fun sendForChat() {
        val chat = ctx.state.dialog as? SheetDialog.ChatSend ?: return
        val receiver = chat.selected ?: return
        if (chat.sending) return
        ctx.update { copy(dialog = chat.copy(sending = true)) }
        ctx.launchWork {
            val outcome = when (val pdf = ctx.services.delivery.pdf(chat.sheet.id)) {
                is ZillitResult.Failure -> pdf
                is ZillitResult.Success -> ctx.services.publishing.sendPdfToChat(
                    pdf.data,
                    unitFileName(chat.sheet),
                    receiver,
                )
            }
            when (outcome) {
                is ZillitResult.Success -> {
                    ctx.update { copy(dialog = if (dialog is SheetDialog.ChatSend) null else dialog) }
                    ctx.toast("Call sheet PDF sent to chat.")
                }
                is ZillitResult.Failure -> {
                    ctx.update {
                        copy(dialog = (dialog as? SheetDialog.ChatSend)?.copy(sending = false) ?: dialog)
                    }
                    ctx.toast("Send for Chat failed: ${outcome.error.localised()}", isError = true)
                }
            }
        }
    }

    // Approve / reject / remind ------------------------------------------------------------------

    /**
     * ZL-21512 — Approve asks first. Both call sites (Received, and the
     * creator-approver shortcut on Sent) open the same chooser; "without"
     * approves straight away, "with" advances to the pad, whose one button
     * stays locked to that choice. Opening reads the sheet's report badge.
     */
    private fun onApproveEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.OpenApprove -> {
                val request = actionableRequest(event.sheet, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
                ctx.lists.readReport(event.sheet.id)
                ctx.update { copy(dialog = SheetDialog.Approve(event.sheet, request)) }
            }
            WorkflowEvent.ChooseSignature -> approveDialog {
                if (uploading || ctx.state.busy) this else copy(sign = true)
            }
            is WorkflowEvent.UseSignature -> approveDialog { copy(signature = SignatureImage(event.png)) }
            WorkflowEvent.ChangeSignature -> approveDialog { if (uploading) this else copy(signature = null) }
            WorkflowEvent.ApproveWithSignature -> approveWithSignature()
            WorkflowEvent.ApproveWithoutSignature -> approveWithoutSignature()
            else -> Unit
        }
    }

    private inline fun approveDialog(crossinline change: SheetDialog.Approve.() -> SheetDialog.Approve) = ctx.update {
        copy(dialog = (dialog as? SheetDialog.Approve)?.change() ?: dialog)
    }

    /** Only from the chooser: once the pad is up, the unsigned outcome is no longer on offer. */
    private fun approveWithoutSignature() {
        val dialog = ctx.state.dialog as? SheetDialog.Approve ?: return
        if (dialog.sign) return
        approve(ApprovalDecision.WithoutSignature)
    }

    private fun approveWithSignature() {
        val dialog = ctx.state.dialog as? SheetDialog.Approve ?: return
        val signature = dialog.signature ?: return
        if (!dialog.sign || dialog.uploading || ctx.state.busy) return
        approveDialog { copy(uploading = true) }
        ctx.launchWork {
            when (val stored = ctx.services.publishing.uploadSignature(signature.png)) {
                is ZillitResult.Success -> approve(stored.data)
                is ZillitResult.Failure -> {
                    approveDialog { copy(uploading = false) }
                    ctx.toast("Couldn't upload the signature: ${stored.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun approve(decision: ApprovalDecision) {
        val dialog = ctx.state.dialog as? SheetDialog.Approve ?: return
        if (ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.approve(dialog.request.id, decision)) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            busy = false,
                            dialog = if (this.dialog is SheetDialog.Approve) null else this.dialog,
                            lists = lists.copy(
                                received = lists.received.copy(
                                    rows = lists.received.rows.filterNot { it.id == dialog.sheet.id },
                                ),
                            ),
                        )
                    }
                    ctx.toast("Approved!")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    approveDialog { copy(uploading = false) }
                    ctx.toast("Approve failed: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    private fun openReject(sheet: CallSheetSummary) {
        val request = actionableRequest(sheet, ctx.state.me)?.takeIf { it.isFinalStage } ?: return
        ctx.lists.readReport(sheet.id)
        ctx.update { copy(dialog = SheetDialog.Reject(sheet, request)) }
    }

    private fun reject() {
        val dialog = ctx.state.dialog as? SheetDialog.Reject ?: return
        if (ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            when (val result = ctx.repository.reject(dialog.request.id, dialog.reason)) {
                is ZillitResult.Success -> {
                    ctx.update {
                        copy(
                            busy = false,
                            dialog = if (this.dialog is SheetDialog.Reject) null else this.dialog,
                            lists = lists.copy(
                                received = lists.received.copy(
                                    rows = lists.received.rows.filterNot { it.id == dialog.sheet.id },
                                ),
                            ),
                        )
                    }
                    ctx.toast("Rejected.")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Reject failed: ${result.error.localised()}", isError = true)
                }
            }
        }
    }

    /**
     * Reminders go to the current round's PENDING approvers of the stage the
     * sheet is in. A list row without requests asks the detail first.
     * `sent_by` is the sender's MEMBER ID (the reader resolves who they are
     * today) and `sent_by_role` the designation KEY, never the resolved label
     * (ZL-20648 — that would bake in the sender's locale).
     */
    private fun remind() {
        val dialog = ctx.state.dialog as? SheetDialog.ReminderCompose ?: return
        if (ctx.state.busy || !ctx.state.isPoster) return
        val sheet = dialog.sheet
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            var assignees = reminderAssigneeIds(sheet.status, sheet.approvals)
            if (assignees.isEmpty()) {
                val detail = (ctx.repository.sheet(sheet.id) as? ZillitResult.Success)?.data
                assignees = detail?.let { reminderAssigneeIds(it.summary.status, it.approvals) }.orEmpty()
            }
            if (assignees.isEmpty()) {
                ctx.update { copy(busy = false, dialog = null) }
                ctx.toast("No pending approvers to remind on this call sheet.", isError = true)
                return@launchWork
            }
            val me = ctx.state.currentMember
            val request = ReminderRequest(
                sentBy = ctx.state.me,
                sentById = ctx.state.me,
                sentByRole = me?.designationKey?.ifBlank { null } ?: me?.designation.orEmpty(),
                assigneeIds = assignees,
                message = dialog.message.trim().ifEmpty { DEFAULT_REMINDER },
            )
            when (val sent = ctx.repository.sendReminder(sheet.id, request)) {
                is ZillitResult.Success -> {
                    ctx.update { copy(busy = false, dialog = null) }
                    ctx.toast("Reminder sent!")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    ctx.update { copy(busy = false) }
                    ctx.toast("Reminder failed: ${sent.error.localised()}", isError = true)
                }
            }
        }
    }

    // Publish ---------------------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One branch per step of the publish wizard.
    private fun onPublishEvent(event: WorkflowEvent) {
        when (event) {
            is WorkflowEvent.OpenPublish -> if (canPublish(event.sheet, ctx.state.me)) {
                ctx.lists.readReport(event.sheet.id)
                ctx.update { copy(dialog = SheetDialog.Publish(event.sheet)) }
                loadReplaceTargets(event.sheet.id)
            }
            is WorkflowEvent.PickDestination -> ctx.update {
                val publish = dialog as? SheetDialog.Publish
                val blocked = event.destination.needsDocDist && !ctx.services.publishing.canDistribute()
                copy(dialog = if (publish == null || blocked) dialog else publish.copy(destination = event.destination))
            }
            WorkflowEvent.ContinuePublish -> {
                val publish = ctx.state.dialog as? SheetDialog.Publish ?: return
                if (publish.destination == PublishDestination.DocDist) {
                    ctx.update { copy(dialog = null) }
                    askDocDist(publish.sheet, fromDraft = false, alreadyPublished = false)
                } else {
                    ctx.update { copy(dialog = publish.copy(step = PublishStep.Type)) }
                }
            }
            WorkflowEvent.BackToDestination -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.Publish)?.copy(step = PublishStep.Destination) ?: dialog)
            }
            is WorkflowEvent.PickPublishChoice -> ctx.update {
                val publish = dialog as? SheetDialog.Publish
                // Replace is hidden, not disabled, when there is nothing to swap.
                val blocked = event.choice == PublishChoice.Replace && publish?.replaceTargets.isNullOrEmpty()
                copy(dialog = if (publish == null || blocked) dialog else publish.copy(choice = event.choice))
            }
            is WorkflowEvent.PickReplaceTarget -> ctx.update {
                val publish = dialog as? SheetDialog.Publish
                val known = publish?.replaceTargets?.any { it.chatId == event.chatId } == true
                copy(dialog = if (publish == null || !known) dialog else publish.copy(replaceChatId = event.chatId))
            }
            is WorkflowEvent.EditPublishNotes -> ctx.update {
                copy(dialog = (dialog as? SheetDialog.Publish)?.copy(notes = event.notes) ?: dialog)
            }
            WorkflowEvent.ConfirmPublish -> publish()
            WorkflowEvent.AttachInstead -> attachInstead()
            is WorkflowEvent.EditAttachCaption -> ctx.update {
                val attach = dialog as? SheetDialog.AttachDocument
                copy(dialog = if (attach == null || attach.uploading) dialog else attach.copy(caption = event.caption))
            }
            WorkflowEvent.ConfirmAttach -> confirmAttach()
            else -> Unit
        }
    }

    /**
     * The Replace card's options, read on OPEN rather than on reaching step 2
     * (a third card appearing under the cursor), refetched every open (the
     * unit's list changes with each publish, and a stale id is exactly how a
     * publish earns `unit_chat_replace_target_not_found`). Newest first, and
     * seeded only while nothing is chosen. A failed read hides the card.
     */
    private fun loadReplaceTargets(sheetId: String) {
        ctx.launchWork {
            val targets = when (val messages = ctx.services.publishing.unitMessages()) {
                is ZillitResult.Success -> replaceTargets(messages.data)
                is ZillitResult.Failure -> emptyList()
            }
            ctx.update {
                val publish = dialog as? SheetDialog.Publish
                if (publish == null || publish.sheet.id != sheetId) {
                    this
                } else {
                    copy(
                        dialog = publish.copy(
                            replaceTargets = targets,
                            replaceChatId = publish.replaceChatId ?: targets.firstOrNull()?.chatId,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Publish in app (and on Both): the publish call holds the dialog — busy
     * covers that call ONLY — then, with it closed, the PDF posted into the
     * Home call-sheet unit as a detached tail reporting its own outcome: New
     * replaces every live call sheet, Continuation appends, Replace retires
     * the one picked document; on Both, the Document Distribution copy last,
     * so its failure never un-publishes.
     */
    private fun publish() {
        val dialog = ctx.state.dialog as? SheetDialog.Publish ?: return
        val choice = dialog.choice ?: return
        if (!dialog.canConfirm || !canPublish(dialog.sheet, ctx.state.me) || ctx.state.busy) return
        ctx.update { copy(busy = true) }
        ctx.launchWork {
            val result = publishCall(dialog.sheet, choice.continuation, dialog.notes.trim())
            if (result is ZillitResult.Failure) {
                ctx.update { copy(busy = false) }
                ctx.toast("Publish failed: ${result.error.localised()}", isError = true)
                return@launchWork
            }
            ctx.update { copy(busy = false, dialog = null) }
            ctx.toast("Published!")
            ctx.lists.refreshCurrent()
            // The tail — the dialog and busy are already released above.
            postPdfToUnit(
                dialog.sheet,
                replacePrevious = choice.replacePrevious,
                replaceChatId = dialog.replaceChatId.takeIf { choice == PublishChoice.Replace },
            )
            if (dialog.destination == PublishDestination.Both) {
                askDocDist(dialog.sheet, fromDraft = false, alreadyPublished = true)
            }
        }
    }

    private suspend fun publishCall(sheet: CallSheetSummary, continuation: Boolean, notes: String) =
        ctx.repository.publish(
            id = sheet.id,
            publishedBy = ctx.state.currentMember?.fullName?.ifBlank { null } ?: ctx.viewer().displayName,
            publishedById = ctx.state.me,
            continuation = continuation,
            notes = notes,
        )

    /**
     * Best-effort, like the web: a failed chat post never unpublishes — but it
     * is said, a target archived or deleted since the picker was read included.
     */
    private suspend fun postPdfToUnit(sheet: CallSheetSummary, replacePrevious: Boolean, replaceChatId: String?) {
        val pdf = when (val bytes = ctx.services.delivery.pdf(sheet.id)) {
            is ZillitResult.Success -> bytes.data
            is ZillitResult.Failure -> {
                ctx.toast(
                    "Published, but the PDF couldn't be posted to Home: ${bytes.error.localised()}",
                    isError = true,
                )
                return
            }
        }
        val posted = ctx.services.publishing.postToUnit(
            bytes = pdf,
            fileName = unitFileName(sheet),
            contentType = PDF,
            caption = "",
            replacePrevious = replacePrevious,
            replaceChatId = replaceChatId,
        )
        if (posted is ZillitResult.Failure) {
            // The key as the server spells it — `localised()` humanises it ("Unit Chat Replace Target Not Found").
            val gone = replaceChatId != null &&
                listOfNotNull((posted.error as? ZillitError.Http)?.serverMessage, posted.error.localised())
                    .any { it.lowercase().replace(' ', '_').contains(REPLACE_TARGET_GONE) }
            ctx.toast(
                if (gone) {
                    "That document no longer exists — nothing was replaced."
                } else {
                    "Published, but the PDF couldn't be posted to Home: ${posted.error.localised()}"
                },
                isError = true,
            )
        }
    }

    /** "Attach a document instead": the wizard closes and a PDF is picked. */
    private fun attachInstead() {
        val publish = ctx.state.dialog as? SheetDialog.Publish ?: return
        ctx.update { copy(dialog = null) }
        ctx.launchWork {
            val picked = ctx.services.publishing.pickPdf() ?: return@launchWork
            ctx.update { copy(dialog = SheetDialog.AttachDocument(publish.sheet, picked, withPublish = true)) }
        }
    }

    /**
     * The picked document into the Home call-sheet unit, appended. From the
     * wizard the sheet is first published as a continuation and its own PDF
     * appended too.
     */
    private fun confirmAttach() {
        val attach = ctx.state.dialog as? SheetDialog.AttachDocument ?: return
        if (attach.uploading || ctx.state.busy || !ctx.state.isPoster) return
        ctx.update { copy(dialog = attach.copy(uploading = true)) }
        ctx.launchWork {
            ctx.toast("Uploading document…")
            if (attach.withPublish) {
                val published = publishCall(attach.sheet, continuation = true, notes = "")
                if (published is ZillitResult.Failure) {
                    uploadFailed("Publish failed: ${published.error.localised()}")
                    return@launchWork
                }
                ctx.toast("Published!")
                postPdfToUnit(attach.sheet, replacePrevious = false, replaceChatId = null)
            }
            val posted = ctx.services.publishing.postToUnit(
                bytes = attach.document.bytes,
                fileName = attach.document.name,
                contentType = attach.document.contentType.ifBlank { PDF },
                caption = attach.caption.trim(),
                replacePrevious = false,
            )
            when (posted) {
                is ZillitResult.Success -> {
                    ctx.update { copy(dialog = if (dialog is SheetDialog.AttachDocument) null else dialog) }
                    ctx.toast("Document attached successfully!")
                    ctx.lists.refreshCurrent()
                }
                is ZillitResult.Failure -> {
                    if (attach.withPublish) {
                        ctx.update { copy(dialog = if (dialog is SheetDialog.AttachDocument) null else dialog) }
                        ctx.lists.refreshCurrent()
                    }
                    uploadFailed("Attach document failed: ${posted.error.localised()}")
                }
            }
        }
    }

    private fun uploadFailed(message: String) {
        ctx.update { copy(dialog = (dialog as? SheetDialog.AttachDocument)?.copy(uploading = false) ?: dialog) }
        ctx.toast(message, isError = true)
    }

    // Document Distribution ----------------------------------------------------------------------------

    private fun askDocDist(sheet: CallSheetSummary, fromDraft: Boolean, alreadyPublished: Boolean) {
        if (!ctx.services.publishing.canDistribute()) {
            ctx.toast("You don't have permission to distribute to Document Distribution.", isError = true)
            return
        }
        if (distributing) return
        ctx.update {
            copy(dialog = SheetDialog.DocDistConfirm(sheet, documentName(sheet), fromDraft, alreadyPublished))
        }
    }

    private fun confirmDocDist() {
        val dialog = ctx.state.dialog as? SheetDialog.DocDistConfirm ?: return
        ctx.update { copy(dialog = null) }
        distribute(dialog.sheet, dialog.fromDraft, dialog.alreadyPublished)
    }

    /**
     * The sheet's PDF into the library, filed under `Call Sheet` (or
     * `Draft Call Sheet` from Drafts) and dated by the shoot date.
     */
    private fun distribute(sheet: CallSheetSummary, fromDraft: Boolean, alreadyPublished: Boolean) {
        if (distributing) return
        distributing = true
        ctx.launchWork {
            ctx.toast("Sending to Document Distribution…")
            val detail = (ctx.repository.sheet(sheet.id) as? ZillitResult.Success)?.data
            val dateMs = detail?.payload?.shared?.dateMs ?: sheet.shared?.dateMs ?: sheet.publishedOn
            val fileName = documentName(detail?.summary ?: sheet)
            val outcome = when (val pdf = ctx.services.delivery.pdf(sheet.id)) {
                is ZillitResult.Failure -> pdf
                is ZillitResult.Success -> ctx.services.publishing.sendToDocumentDistribution(
                    pdf.data,
                    fileName,
                    fromDraft,
                    dateMs,
                )
            }
            distributing = false
            when (outcome) {
                is ZillitResult.Success -> ctx.update { copy(dialog = SheetDialog.DocDistDone(fileName)) }
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
        }
    }

    private fun documentName(sheet: CallSheetSummary): String {
        val base = sheet.name.trim().ifEmpty { "Call Sheet ${sheet.serialNo.ifBlank { sheet.id }}".trim() }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    private fun unitFileName(sheet: CallSheetSummary) = "CallSheet_${sheet.serialNo.ifBlank { sheet.id }}.pdf"

    private companion object {
        const val DEFAULT_REMINDER = "Please review and approve this call sheet."
        const val PDF = "application/pdf"

        /** The server's refusal of a replace target already archived or deleted. */
        const val REPLACE_TARGET_GONE = "unit_chat_replace_target_not_found"
    }
}

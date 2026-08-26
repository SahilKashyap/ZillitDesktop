@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.InternalApprover
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.firstUntitledSystemCell
import com.zillit.desktop.feature.callsheet.domain.normalised
import com.zillit.desktop.feature.callsheet.domain.withAddedLine
import com.zillit.desktop.feature.callsheet.domain.withValue
import kotlinx.coroutines.flow.conflate

/**
 * Call sheet creation and review.
 *
 * The lifecycle mirrors the web exactly: compose locally from the server
 * template, save as a draft (create once, revisions after), send for comments
 * or signature, approve by request id, publish, and fan the rendered PDF out
 * to the call-sheet home unit.
 */
class CallSheetViewModel(
    private val repository: CallSheetRepository,
    private val delivery: CallSheetDelivery,
    private val resolveViewer: () -> com.zillit.desktop.feature.callsheet.domain.CallSheetViewer,
    private val projectId: () -> String?,
    private val membersProvider: () -> List<SheetMember>,
    private val companySeed: () -> CompanySeed,
    private val todayMs: () -> Long,
) : ZillitViewModel<CallSheetUiState, CallSheetEvent, CallSheetEffect>(CallSheetUiState()) {

    fun start() {
        val viewer = resolveViewer()
        setState {
            copy(
                viewer = viewer,
                members = membersProvider(),
                destination = if (viewer.canAuthor) destination else CallSheetDestination.Approvals,
            )
        }
        refresh()
        listenOnce()
    }

    /**
     * Reloads the open list when the socket says a sheet moved through the
     * workflow on another client — the web's `handleSocketSheetUpdate`
     * (`CallSheetApp.jsx:1631-1694`) reloads the lists the new status
     * touches; this client's refresh already scopes to the open
     * destination and bucket. Guarded so a second start (the window
     * reopening) does not stack collectors; `conflate()` folds a burst
     * into one reload.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { refresh() }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: CallSheetEvent) {
        when (event) {
            is CallSheetEvent.Open -> {
                setState { copy(destination = event.destination) }
                refresh()
            }
            is CallSheetEvent.OpenBucket -> {
                setState { copy(bucket = event.bucket) }
                refresh()
            }
            CallSheetEvent.Refresh -> refresh()
            CallSheetEvent.NewSheet -> composeNew()
            is CallSheetEvent.EditSheet -> openForEdit(event.id)
            is CallSheetEvent.ViewPdf -> openPdf(event.id, event.title)
            CallSheetEvent.ClosePdf -> setState { copy(pdf = null) }
            is CallSheetEvent.DeleteSheet -> delete(event.id)
            is CallSheetEvent.NameChanged -> updateEditor { copy(name = event.name, dirty = true) }
            is CallSheetEvent.SharedChanged -> updateEditor {
                copy(
                    payload = payload.copy(
                        shared = payload.shared.copy(
                            shootDayNumber = event.shootDayNumber ?: payload.shared.shootDayNumber,
                            totalDays = event.totalDays ?: payload.shared.totalDays,
                            dayType = event.dayType ?: payload.shared.dayType,
                        ),
                    ),
                    dirty = true,
                )
            }
            is CallSheetEvent.ValueChanged -> updateEditor {
                copy(
                    payload = payload.withValue(
                        event.row,
                        event.cell,
                        event.line,
                        event.column,
                        event.value,
                    ),
                    dirty = true,
                )
            }
            is CallSheetEvent.AddLine -> updateEditor {
                copy(payload = payload.withAddedLine(event.row, event.cell), dirty = true)
            }
            CallSheetEvent.SaveDraft -> saveDraft()
            CallSheetEvent.CloseEditor -> setState { copy(editor = null) }
            is CallSheetEvent.OpenSend -> setState {
                copy(
                    send = SendDialog(
                        sheetId = event.id,
                        sheetName = event.name,
                        selectedIds = (metadata.internalReceiverIds + viewer.userId).toSet(),
                    ),
                )
            }
            is CallSheetEvent.SendModeChanged -> setState {
                copy(send = send?.copy(forComments = event.forComments))
            }
            is CallSheetEvent.ToggleReviewer -> setState {
                copy(
                    send = send?.copy(
                        selectedIds = send.selectedIds.toggled(event.userId),
                    ),
                )
            }
            CallSheetEvent.ConfirmSend -> confirmSend()
            CallSheetEvent.DismissSend -> setState { copy(send = null) }
            is CallSheetEvent.Approve -> act(event.sheetId, approve = true, reason = "")
            is CallSheetEvent.Reject -> act(event.sheetId, approve = false, reason = event.reason)
            is CallSheetEvent.OpenPublish -> setState {
                copy(publish = PublishDialog(sheetId = event.id, sheetName = event.name))
            }
            is CallSheetEvent.PublishOptionsChanged -> setState {
                copy(
                    publish = publish?.copy(
                        continuation = event.continuation ?: publish.continuation,
                        notes = event.notes ?: publish.notes,
                    ),
                )
            }
            CallSheetEvent.ConfirmPublish -> confirmPublish()
            CallSheetEvent.DismissPublish -> setState { copy(publish = null) }
            CallSheetEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun updateEditor(change: SheetEditor.() -> SheetEditor) {
        setState { copy(editor = editor?.change()) }
    }

    private fun refresh() {
        val project = projectId() ?: return
        val me = state.value.viewer.userId
        setState { copy(loading = true) }
        launch {
            when (state.value.destination) {
                CallSheetDestination.Drafts -> load {
                    repository.sheets(
                        project,
                        listOf(CallSheetStatus.Draft),
                        createdById = me,
                    )
                        .also { result -> ifOk(result) { setState { copy(drafts = it) } } }
                }
                CallSheetDestination.Published -> load {
                    repository.sheets(project, listOf(CallSheetStatus.Published))
                        .also { result -> ifOk(result) { setState { copy(published = it) } } }
                }
                CallSheetDestination.Approvals -> loadApprovals(project, me)
            }
            setState { copy(loading = false) }
        }
    }

    private suspend fun loadApprovals(project: String, me: String) {
        when (state.value.bucket) {
            ApprovalBucket.Sent -> load {
                repository.sheets(project, IN_REVIEW, createdById = me)
                    .also { result -> ifOk(result) { setState { copy(sent = it) } } }
            }
            ApprovalBucket.Received -> load {
                // No project filter — the backend scopes by approver identity,
                // and the client filters the review states.
                repository.sheets(projectId = null, approverId = me)
                    .also { result ->
                        ifOk(result) { sheets ->
                            setState {
                                copy(received = sheets.filter { it.status.reviewInFlight })
                            }
                        }
                    }
            }
            ApprovalBucket.Finalized -> load {
                repository.sheets(project, listOf(CallSheetStatus.ApprovedForPublish))
                    .also { result -> ifOk(result) { setState { copy(finalized = it) } } }
            }
        }
    }

    private suspend fun <T> load(block: suspend () -> ZillitResult<T>) {
        when (val result = block()) {
            is ZillitResult.Success -> Unit
            is ZillitResult.Failure -> setState { copy(error = result.error.localised()) }
        }
    }

    private inline fun <T> ifOk(result: ZillitResult<T>, onOk: (T) -> Unit) {
        if (result is ZillitResult.Success) onOk(result.data)
    }

    /** The web's `openNewEditor` — template + metadata + crew, no API writes. */
    private fun composeNew() {
        setState { copy(busy = true) }
        launch {
            val meta = when (val result = repository.metadata(projectId().orEmpty())) {
                is ZillitResult.Success -> result.data
                is ZillitResult.Failure -> state.value.metadata
            }
            val template = when (val result = repository.defaultTemplate()) {
                is ZillitResult.Success -> result.data
                is ZillitResult.Failure -> null
            }
            if (template == null) {
                setState { copy(busy = false, error = "The call sheet template is unavailable") }
                return@launch
            }
            val payload = ComposeSheet.newSheet(
                template = template,
                metadata = meta,
                members = membersProvider(),
                company = companySeed(),
                todayMs = todayMs(),
            )
            setState {
                copy(
                    busy = false,
                    metadata = meta,
                    members = membersProvider(),
                    editor = SheetEditor(
                        name = "Call sheet — day ${payload.shared.shootDayNumber}",
                        payload = payload,
                    ),
                )
            }
        }
    }

    private fun openForEdit(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val result = repository.sheet(id)) {
                is ZillitResult.Success -> {
                    val detail = result.data
                    if (detail.summary.status.locked) {
                        setState { copy(busy = false) }
                        sendEffect(CallSheetEffect.Notice("A finalised sheet cannot be edited"))
                    } else {
                        setState {
                            copy(
                                busy = false,
                                editor = SheetEditor(
                                    sheetId = detail.summary.id,
                                    name = detail.summary.name,
                                    status = detail.summary.status,
                                    payload = with(ComposeSheet) {
                                        detail.payload.withoutApproverCells().normalised()
                                    },
                                ),
                            )
                        }
                    }
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = result.error.localised())
                }
            }
        }
    }

    private fun saveDraft() {
        val editor = state.value.editor ?: return
        val untitled = editor.payload.firstUntitledSystemCell()
        if (untitled != null) {
            setState { copy(error = "Every default section needs a title before saving") }
            return
        }
        val project = projectId() ?: return
        val viewer = state.value.viewer
        updateEditor { copy(saving = true) }
        launch {
            // Counters ride along on every save; a failure here never blocks
            // the sheet write — the web behaves the same.
            repository.saveMetadata(
                projectId = project,
                totalDays = editor.payload.shared.totalDays,
                currentShootDay = editor.payload.shared.shootDayNumber.toIntOrNull(),
                finalApproverIds = editor.payload.shared.approverIds,
            )
            val payload = editor.payload.normalised()
            val result = if (editor.sheetId == null) {
                repository.create(project, editor.name, payload, viewer.displayName, viewer.userId)
                    .let { created ->
                        if (created is ZillitResult.Success) {
                            updateEditor { copy(sheetId = created.data.id) }
                        }
                        created
                    }
            } else {
                repository.saveRevision(
                    editor.sheetId,
                    editor.name,
                    payload,
                    viewer.displayName,
                    viewer.userId,
                )
            }
            when (result) {
                is ZillitResult.Success -> {
                    updateEditor { copy(saving = false, dirty = false) }
                    sendEffect(CallSheetEffect.Notice("Saved"))
                    refresh()
                }
                is ZillitResult.Failure -> {
                    updateEditor { copy(saving = false) }
                    setState { copy(error = result.error.localised()) }
                }
            }
        }
    }

    private fun confirmSend() {
        val dialog = state.value.send ?: return
        setState { copy(busy = true) }
        launch {
            val result = if (dialog.forComments) {
                val members = state.value.members
                val approvers = dialog.selectedIds.mapNotNull { id ->
                    members.firstOrNull { it.userId == id }?.let {
                        InternalApprover(it.userId, it.fullName, it.designation.ifBlank { "Member" })
                    }
                }
                repository.saveMetadata(
                    projectId = projectId().orEmpty(),
                    totalDays = null,
                    currentShootDay = null,
                    finalApproverIds = null,
                    internalReceiverIds = dialog.selectedIds.toList(),
                )
                repository.submitForInternalApproval(dialog.sheetId, approvers)
            } else {
                repository.submitForApproval(dialog.sheetId)
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false, send = null) }
                    sendEffect(CallSheetEffect.Notice("Sent for review"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = result.error.localised())
                }
            }
        }
    }

    /**
     * Approve or reject — resolved to MY pending request id from the sheet
     * detail, matched by assignee id. The web matches by display name and
     * desynchronises on shared names; this does not.
     */
    private fun act(sheetId: String, approve: Boolean, reason: String) {
        setState { copy(busy = true) }
        launch {
            val detail = when (val result = repository.sheet(sheetId)) {
                is ZillitResult.Success -> result.data
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, error = result.error.localised()) }
                    return@launch
                }
            }
            val me = state.value.viewer.userId
            val finalStage = detail.summary.status == CallSheetStatus.PendingApproval
            val mine = detail.approvals.firstOrNull { request ->
                request.isPending && request.assigneeId == me &&
                    (!finalStage || request.isFinalStage)
            }
            if (mine == null) {
                setState { copy(busy = false) }
                sendEffect(CallSheetEffect.Notice("No pending review names you on this sheet"))
                return@launch
            }
            val result = if (approve) {
                repository.approve(mine.id, withoutSignature = true)
            } else {
                repository.reject(mine.id, reason)
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(
                        CallSheetEffect.Notice(if (approve) "Approved" else "Rejected"),
                    )
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = result.error.localised())
                }
            }
        }
    }

    private fun confirmPublish() {
        val dialog = state.value.publish ?: return
        val viewer = state.value.viewer
        setState { copy(busy = true) }
        launch {
            val published = repository.publish(
                id = dialog.sheetId,
                publishedBy = viewer.displayName,
                publishedById = viewer.userId,
                continuation = dialog.continuation,
                notes = dialog.notes,
            )
            when (published) {
                is ZillitResult.Success -> {
                    distribute(dialog)
                    setState { copy(busy = false, publish = null) }
                    sendEffect(CallSheetEffect.Notice("Published"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = published.error.localised())
                }
            }
        }
    }

    /** Best-effort, like the web: a failed fan-out never unpublishes. */
    private suspend fun distribute(dialog: PublishDialog) {
        val pdf = repository.sheet(dialog.sheetId).let { detail ->
            if (detail !is ZillitResult.Success) return
            when (val bytes = delivery.pdf(dialog.sheetId)) {
                is ZillitResult.Success -> detail.data.summary.serialNo to bytes.data
                is ZillitResult.Failure -> return
            }
        }
        delivery.distribute(
            sheetId = dialog.sheetId,
            serialNo = pdf.first,
            pdf = pdf.second,
            replacePrevious = !dialog.continuation,
        )
    }

    private fun openPdf(id: String, title: String) {
        setState { copy(pdf = SheetPdfView(sheetId = id, title = title)) }
        launch {
            when (val bytes = delivery.pdf(id)) {
                is ZillitResult.Success -> {
                    val pages = delivery.renderPages(bytes.data, PDF_RENDER_WIDTH)
                    when (pages) {
                        is ZillitResult.Success -> setState {
                            copy(pdf = pdf?.copy(loading = false, pages = pages.data))
                        }
                        is ZillitResult.Failure -> setState {
                            copy(pdf = null, error = pages.error.localised())
                        }
                    }
                }
                is ZillitResult.Failure -> setState {
                    copy(pdf = null, error = bytes.error.localised())
                }
            }
        }
    }

    private fun delete(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val result = repository.delete(id)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(CallSheetEffect.Notice("Deleted"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = result.error.localised())
                }
            }
        }
    }

    private fun Set<String>.toggled(id: String): Set<String> =
        if (id in this) this - id else this + id

    private companion object {
        val IN_REVIEW = listOf(
            CallSheetStatus.PendingInternalApproval,
            CallSheetStatus.InternalApproved,
            CallSheetStatus.PendingApproval,
            CallSheetStatus.ApprovalRejected,
            CallSheetStatus.ApprovedForPublish,
        )
        const val PDF_RENDER_WIDTH = 1000
    }
}

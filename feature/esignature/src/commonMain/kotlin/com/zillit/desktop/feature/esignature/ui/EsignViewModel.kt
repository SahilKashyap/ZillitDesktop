@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import com.zillit.desktop.feature.esignature.ui.flows.BulkFlow
import com.zillit.desktop.feature.esignature.ui.flows.DetailFlow
import com.zillit.desktop.feature.esignature.ui.flows.EditorFlow
import com.zillit.desktop.feature.esignature.ui.flows.ListsFlow
import com.zillit.desktop.feature.esignature.ui.flows.MarksFlow
import com.zillit.desktop.feature.esignature.ui.flows.SigningFlow
import com.zillit.desktop.feature.esignature.ui.flows.TemplatesFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * E-Signature.
 *
 * The one non-obvious flow is signing: the desktop collects every field the
 * envelope placed for this signer — marks from the pad or the saved
 * library, dates and text from what was entered — and sends **values**;
 * the flattened PDF is the backend's job and arrives later as the
 * envelope's signed document. The mirror image of the documents tool, and
 * the reason this module contains no stamping code.
 *
 * The view model only routes: each surface's logic lives in a flow class
 * behind [EsignStore], so the editor, the signer, the template library and
 * the bulk dashboard can each be read on their own.
 */
@Suppress("LongParameterList") // Every seam the host injects.
class EsignViewModel(
    override val repository: EsignRepository,
    override val transfer: EsignFileTransfer,
    override val pdf: EsignPdf,
    private val resolveViewer: () -> EsignViewer,
    private val currentUserId: () -> String,
    override val currentUserName: () -> String,
    override val signerOptions: () -> List<SignerOptionLike>,
    override val newId: () -> String,
    private val currentUserEmail: () -> String = { "" },
    override val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<EsignUiState, EsignEvent, EsignEffect>(EsignUiState()), EsignStore {

    private val lists = ListsFlow(this)
    private val detail = DetailFlow(this)
    private val signing = SigningFlow(this)
    private val editor = EditorFlow(this)
    private val marks = MarksFlow(this)
    private val templates = TemplatesFlow(this)
    private val bulk = BulkFlow(this)

    private var pendingPick: PickPurpose? = null
    private var listening = false

    fun start() {
        val viewer = resolveViewer()
        setState {
            copy(
                viewer = viewer,
                currentUserId = currentUserId(),
                currentUserEmail = currentUserEmail(),
                surface = if (viewer.receiverOnly) EsignSurface.Sign else surface,
            )
        }
        lists.loadBoth()
        marks.load()
        listenOnce()
    }

    /**
     * Refetches the visible lists when the socket announces an envelope
     * change — the web's `DocuSignObservers` upsert, as a targeted reload.
     * Guarded so a second start (the window reopening) does not stack
     * collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                lists.loadBoth()
                if (currentState.page == EsignPageKind.Detail) detail.refresh()
            }
        }
    }

    // ---------------------------------------------------------------- store

    override val current: EsignUiState get() = currentState
    override fun update(reducer: EsignUiState.() -> EsignUiState) = setState(reducer)
    override fun effect(effect: EsignEffect) = sendEffect(effect)
    override fun runTask(block: suspend CoroutineScope.() -> Unit): Job = launch(block)

    /**
     * Refuses a write, and offers the one thing that changes the answer.
     *
     * Every control that reaches this is on screen for everyone — hiding them
     * is what sent people to support rather than to an admin who could grant
     * the right in a few seconds.
     */
    override fun refusesPost(): Boolean {
        if (currentState.viewer.canPost) return false
        rights?.ask(MODULE_LABEL, RightsKind.Post)
        sendEffect(EsignEffect.Failed(rightsRefusalMessage(MODULE_LABEL, RightsKind.Post, rights != null)))
        return true
    }

    override fun requestPick(purpose: PickPurpose) {
        pendingPick = purpose
        sendEffect(
            when (purpose) {
                PickPurpose.Document -> EsignEffect.PickPdf
                PickPurpose.Csv -> EsignEffect.PickCsv
                PickPurpose.PadImage, PickPurpose.FieldUpload -> EsignEffect.PickImage
            },
        )
    }

    /** The host's picker came back empty — nothing chosen. */
    fun pickCancelled() {
        val purpose = pendingPick
        pendingPick = null
        if (purpose == PickPurpose.Document) editor.pickCancelled()
    }

    // ---------------------------------------------------------------- events

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: EsignEvent) {
        when (event) {
            // navigation
            is EsignEvent.SwitchSurface -> switchSurface(event.surface)
            EsignEvent.Refresh -> refresh()
            is EsignEvent.SetLayout -> setState { copy(layout = event.layout) }
            EsignEvent.Back -> back()

            // manage list
            is EsignEvent.SwitchOuterTab -> setState { copy(manage = manage.copy(outer = event.tab)) }
            is EsignEvent.SwitchInnerTab -> setState { copy(manage = manage.copy(inner = event.tab)) }
            is EsignEvent.SetSentFilter -> setState { copy(manage = manage.copy(sentFilter = event.filter)) }
            is EsignEvent.SearchManage -> setState { copy(manage = manage.copy(search = event.query)) }
            is EsignEvent.AskDeleteDraft -> if (event.envelopeId == null || !refusesPost()) {
                setState { copy(manage = manage.copy(confirmDeleteId = event.envelopeId)) }
            }
            EsignEvent.ConfirmDeleteDraft -> lists.deleteDraft()

            // sign list
            is EsignEvent.SwitchSignBucket -> setState { copy(signList = signList.copy(tab = event.bucket)) }
            is EsignEvent.SearchSign -> setState { copy(signList = signList.copy(search = event.query)) }

            // opening
            is EsignEvent.OpenEnvelope -> lists.open(event.envelope, editor, detail)
            is EsignEvent.OpenDetail -> detail.open(event.envelopeId)
            is EsignEvent.OpenSigning -> signing.open(event.envelope, event.mode, event.fromDetail)

            // detail
            EsignEvent.RefreshDetail -> detail.refresh()
            EsignEvent.ToggleAudit -> setState { copy(detail = detail?.copy(showAudit = !detail.showAudit)) }
            EsignEvent.ToggleOrder -> setState { copy(detail = detail?.copy(showOrder = !detail.showOrder)) }
            is EsignEvent.Remind -> detail.remind(event.recipientId)
            EsignEvent.DownloadAuditPdf -> detail.downloadAuditPdf()
            EsignEvent.DownloadSigned -> detail.downloadSigned()
            EsignEvent.StartVoid -> if (!refusesPost()) {
                setState { copy(detail = detail?.copy(voiding = true, voidReason = "")) }
            }
            is EsignEvent.EditVoidReason -> setState { copy(detail = detail?.copy(voidReason = event.reason)) }
            EsignEvent.CancelVoid -> setState { copy(detail = detail?.copy(voiding = false)) }
            EsignEvent.ConfirmVoid -> detail.confirmVoid(lists)

            // signing
            EsignEvent.Consent -> signing.consent()
            is EsignEvent.FocusField -> signing.focus(event.index)
            EsignEvent.NextField -> signing.next()
            EsignEvent.PrevField -> signing.prev()
            is EsignEvent.Answer -> signing.answer(event.fieldId, event.answer)
            is EsignEvent.SetSignOnce -> setState {
                copy(signing = signing?.copy(signOnce = event.on, currentIndex = 0))
            }
            is EsignEvent.SetApplyToAll -> setState { copy(signing = signing?.copy(applyToAll = event.on)) }
            EsignEvent.OpenPad -> signing.openPad()
            EsignEvent.ClosePad -> setState { copy(signing = signing?.copy(pad = null)) }
            is EsignEvent.SetPadMode -> padEdit { it.copy(mode = event.mode) }
            is EsignEvent.AddPadStroke -> padEdit { it.copy(strokes = it.strokes + listOf(event.stroke)) }
            EsignEvent.ClearPad -> padEdit {
                it.copy(strokes = emptyList(), typedName = "", uploadBytes = null, uploadName = "")
            }
            is EsignEvent.EditTypedName -> padEdit { it.copy(typedName = event.name) }
            is EsignEvent.SetPadFont -> padEdit { it.copy(font = event.font) }
            is EsignEvent.SetSaveForLater -> padEdit { it.copy(saveForLater = event.on) }
            EsignEvent.PickPadImage -> requestPick(PickPurpose.PadImage)
            EsignEvent.ApplyPad -> signing.applyPad()
            is EsignEvent.UseSavedMark -> signing.useSavedMark(event.markId)
            EsignEvent.PickUploadForField -> signing.pickUploadForField()
            EsignEvent.FinishSigning -> signing.finish(lists)
            EsignEvent.StartDecline -> setState { copy(signing = signing?.copy(declining = true)) }
            is EsignEvent.EditDeclineReason -> setState { copy(signing = signing?.copy(declineReason = event.reason)) }
            EsignEvent.CancelDecline -> setState { copy(signing = signing?.copy(declining = false)) }
            EsignEvent.ConfirmDecline -> signing.confirmDecline(lists)

            // editor
            EsignEvent.StartCompose -> editor.startCompose()
            EsignEvent.StartTemplate -> editor.startTemplate()
            is EsignEvent.UseTemplate -> editor.useTemplate(event.template)
            is EsignEvent.EditTemplate -> editor.editTemplate(event.template)
            is EsignEvent.EditCompose -> editor.edit(event.transform)
            EsignEvent.PickDocument -> editor.pickDocument()
            EsignEvent.GoToPlace -> editor.goToPlace()
            EsignEvent.GoToPrepare -> editor.goToPrepare()
            is EsignEvent.ChoosePlacementMode -> editor.choosePlacementMode(event.mode)
            is EsignEvent.ToggleSelfSign -> editor.toggleSelfSign(event.on)
            is EsignEvent.AddSigner -> editor.addSigner(event.userId)
            is EsignEvent.AddCc -> editor.addCc(event.userId)
            is EsignEvent.RemoveRecipient -> editor.removeRecipient(event.index)
            is EsignEvent.MoveRecipient -> editor.moveRecipient(event.index, event.up)
            is EsignEvent.OpenExternal -> editor.edit { copy(external = ExternalRecipientDraft(role = event.role)) }
            EsignEvent.CloseExternal -> editor.edit { copy(external = null) }
            is EsignEvent.EditExternal -> editor.edit { copy(external = event.draft) }
            EsignEvent.AddExternal -> editor.addExternal()
            is EsignEvent.EditSettings -> editor.edit { copy(settings = event.settings) }
            is EsignEvent.PageClicked -> editor.pageClicked(event.page, event.xPt, event.yPt)
            is EsignEvent.ArmType -> editor.edit { copy(armedType = event.type) }
            is EsignEvent.ArmSigner -> editor.edit { copy(armedSignerIndex = event.recipientIndex) }
            is EsignEvent.TogglePendingSigner -> editor.edit {
                val current = pending ?: return@edit this
                val next = if (event.recipientIndex in current.signerIndexes) {
                    current.signerIndexes - event.recipientIndex
                } else {
                    current.signerIndexes + event.recipientIndex
                }
                copy(pending = current.copy(signerIndexes = next))
            }
            is EsignEvent.PlacePending -> editor.placePending(event.type)
            EsignEvent.CancelPending -> editor.edit { copy(pending = null) }
            is EsignEvent.SelectField -> editor.edit { copy(selectedField = event.index, pending = null) }
            is EsignEvent.UpdateField -> editor.updateField(event.index, event.transform)
            is EsignEvent.MoveField -> editor.moveField(event.index, event.dxPt, event.dyPt)
            is EsignEvent.ResizeField -> editor.resizeField(event.index, event.dwPt, event.dhPt)
            is EsignEvent.DeleteField -> editor.deleteField(event.index)
            is EsignEvent.DuplicateField -> editor.duplicateField(event.index)
            is EsignEvent.SetInitialsOnAllPages -> editor.setInitialsOnAllPages(event.on)
            is EsignEvent.SetZoom -> editor.edit { copy(zoom = event.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
            EsignEvent.SaveDraft -> editor.saveDraft(lists)
            EsignEvent.RequestSend -> editor.requestSend()
            EsignEvent.CancelSend -> editor.edit { copy(confirmSend = false) }
            EsignEvent.ConfirmSend -> editor.confirmSend(lists)
            EsignEvent.OpenSaveAsTemplate, EsignEvent.SaveTemplate -> editor.openSaveAsTemplate()
            is EsignEvent.EditSaveAsTemplate -> editor.edit { copy(saveAsTemplate = event.draft) }
            EsignEvent.CancelSaveAsTemplate -> editor.edit { copy(saveAsTemplate = null) }
            EsignEvent.ConfirmSaveAsTemplate -> editor.confirmSaveAsTemplate(templates)
            EsignEvent.CancelCompose -> editor.cancel()

            // marks
            EsignEvent.OpenMarks -> marks.open()
            EsignEvent.CloseMarks -> marks.close()
            is EsignEvent.SetMarksKind -> setState { copy(marks = marks.copy(forSignature = event.forSignature)) }
            EsignEvent.SaveMark -> marks.save()
            is EsignEvent.AskDeleteMark -> setState { copy(marks = marks.copy(confirmDeleteId = event.id)) }
            EsignEvent.ConfirmDeleteMark -> marks.confirmDelete()

            // templates
            is EsignEvent.SearchTemplates -> setState { copy(templates = templates.copy(search = event.query)) }
            is EsignEvent.FilterTemplates -> setState { copy(templates = templates.copy(category = event.category)) }
            is EsignEvent.SetTemplatesLayout -> setState { copy(templates = templates.copy(layout = event.layout)) }
            is EsignEvent.ShowTemplate -> setState { copy(templates = templates.copy(detail = event.template)) }
            is EsignEvent.DuplicateTemplate -> templates.duplicate(event.template)
            is EsignEvent.AskDeleteTemplate -> if (event.id == null || !refusesPost()) {
                setState { copy(templates = templates.copy(confirmDeleteId = event.id)) }
            }
            EsignEvent.ConfirmDeleteTemplate -> templates.confirmDelete()

            // bulk
            is EsignEvent.StartBulkSend -> bulk.start(event.template)
            EsignEvent.CancelBulkSend -> bulk.cancel()
            EsignEvent.PickBulkCsv -> bulk.pickCsv()
            is EsignEvent.BulkStep -> bulk.step(event.step)
            is EsignEvent.EditBatchName -> bulk.editBatchName(event.name)
            EsignEvent.ConfirmBulkSend -> bulk.confirm()
            is EsignEvent.OpenBulkJob -> bulk.openJob(event.job)
            is EsignEvent.RetryFailed -> bulk.retryFailed(event.jobId)
            is EsignEvent.RemindOutstanding -> bulk.remindOutstanding(event.jobId)

            // files
            is EsignEvent.FilePicked -> filePicked(event.name, event.bytes)
        }
    }

    private fun switchSurface(surface: EsignSurface) {
        if (currentState.viewer.receiverOnly && surface != EsignSurface.Sign) return
        setState { copy(surface = surface) }
        when (surface) {
            EsignSurface.Manage -> if (currentState.manage.buckets.isEmpty()) lists.loadManage()
            EsignSurface.Sign -> if (currentState.signList.buckets.isEmpty()) lists.loadSignList()
            EsignSurface.Templates -> if (!currentState.templates.loaded) templates.load()
            EsignSurface.Bulk -> bulk.load()
        }
    }

    private fun refresh() {
        when (currentState.page) {
            EsignPageKind.Detail -> detail.refresh()
            else -> when (currentState.surface) {
                EsignSurface.Templates -> templates.load()
                EsignSurface.Bulk -> bulk.load()
                else -> lists.loadBoth()
            }
        }
    }

    /** Back walks the stack: signing → the detail it came from or the list; detail/editor → the list. */
    private fun back() {
        val state = currentState
        when (state.page) {
            EsignPageKind.Signing -> {
                val toDetail = state.signing?.returnToDetail == true && state.detail != null
                setState { copy(signing = null, page = if (toDetail) EsignPageKind.Detail else EsignPageKind.Lists) }
                if (toDetail) detail.refresh() else lists.loadBoth()
            }
            EsignPageKind.Detail -> setState { copy(detail = null, page = EsignPageKind.Lists) }
            EsignPageKind.Editor -> editor.cancel()
            EsignPageKind.Lists -> Unit
        }
    }

    private fun padEdit(transform: (PadState) -> PadState) {
        if (currentState.signing?.pad != null) {
            signing.editPad(transform)
        } else if (currentState.marks.open) {
            marks.editPad(transform)
        }
    }

    private fun filePicked(name: String, bytes: ByteArray) {
        val purpose = pendingPick
        pendingPick = null
        when (purpose) {
            PickPurpose.Document, null -> editor.documentPicked(name, bytes)
            PickPurpose.Csv -> bulk.csvPicked(name, bytes)
            PickPurpose.PadImage -> if (currentState.signing?.pad != null) {
                signing.padImagePicked(name, bytes)
            } else {
                marks.imagePicked(name, bytes)
            }
            PickPurpose.FieldUpload -> signing.fieldUploadPicked(name, bytes)
        }
    }

    private companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 2f
    }
}

private const val MODULE_LABEL = "E-Signature"

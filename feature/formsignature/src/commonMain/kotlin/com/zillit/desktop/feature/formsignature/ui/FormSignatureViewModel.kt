@file:Suppress("TooManyFunctions") // One handler per user act; merging them hides the acts.

package com.zillit.desktop.feature.formsignature.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.FormSignRefresh
import com.zillit.desktop.feature.formsignature.domain.FormSignatureBadges
import com.zillit.desktop.feature.formsignature.domain.FormSignatureHost
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.FormSignatureUnread
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.formsignature.ui.flows.DetailFlow
import com.zillit.desktop.feature.formsignature.ui.flows.MarksFlow
import com.zillit.desktop.feature.formsignature.ui.flows.SendFlow
import kotlinx.coroutines.Job

/**
 * Documents & Signature — the web's `ContractSignatureMain`: one shell, the
 * tile hub, and the screens it pushes. Signing, sending and the saved marks
 * live in their own flows; this class routes events, owns the lists, the
 * discussion room and the confirmations.
 */
class FormSignatureViewModel(
    private val repository: FormSignatureRepository,
    private val transfer: SignFileTransfer,
    pdfWork: PdfWork,
    private val resolveViewer: () -> FormSignatureViewer,
    private val currentUserId: () -> String,
    newId: () -> String,
    private val host: FormSignatureHost = FormSignatureHost.None,
    private val badges: FormSignatureBadges = FormSignatureBadges.None,
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<FormSignatureUiState, FormSignatureEvent, FormSignatureEffect>(
    FormSignatureUiState(),
) {

    private val store = object : FormSignStore {
        override val state: FormSignatureUiState get() = currentState
        override fun update(reducer: FormSignatureUiState.() -> FormSignatureUiState) = setState(reducer)
        override fun effect(effect: FormSignatureEffect) = sendEffect(effect)
        override fun launch(block: suspend () -> Unit): Job = this@FormSignatureViewModel.launch { block() }
    }

    private val marks = MarksFlow(store, repository, transfer, pdfWork, newId)
    private val detail = DetailFlow(store, repository, transfer, pdfWork, host, badges, marks, onSigned = ::refresh)
    private val send = SendFlow(store, repository, transfer, pdfWork, host, marks, onSent = ::loadDocuments)

    private var listening = false

    fun start() {
        setState { copy(viewer = resolveViewer(), currentUserId = currentUserId()) }
        marks.load()
        loadChatUnit()
        listenOnce()
    }

    /**
     * The socket's refetch pulses and the badge ledger, collected once — a
     * second Start (the window reopening) must not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { kind ->
                when (kind) {
                    FormSignRefresh.Forms ->
                        if (currentState.screen == FormSignScreen.StandardDocuments) loadStandardForms(quiet = true)
                    FormSignRefresh.Documents ->
                        if (currentState.screen == FormSignScreen.DocumentsForSignature) loadDocuments(quiet = true)
                }
            }
        }
        launch { badges.leaves.collect { leaves -> setState { copy(unread = FormSignatureUnread(leaves)) } } }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per user act; the fan-out IS the function.
    override fun onEvent(event: FormSignatureEvent) {
        when (event) {
            is FormSignatureEvent.Open -> open(event.screen)
            FormSignatureEvent.Back -> back()
            FormSignatureEvent.Refresh -> refresh()
            FormSignatureEvent.OpenGuide -> host.openGuide()

            is FormSignatureEvent.SearchStandard -> setState { copy(standard = standard.copy(search = event.query)) }
            is FormSignatureEvent.SwitchStandardTab -> {
                setState { copy(standard = standard.copy(tab = event.tab, search = "")) }
                loadStandardForms()
            }
            is FormSignatureEvent.OpenStandardForm -> detail.openStandardForm(event.form)
            is FormSignatureEvent.SelfAssign -> selfAssign(event.formId)
            is FormSignatureEvent.AskDeleteStandardForm ->
                if (!refusesPost()) setState { copy(confirm = ConfirmState.DeleteForm(event.formId)) }
            is FormSignatureEvent.ShowHistory -> setState { copy(history = HistoryState(event.form)) }
            FormSignatureEvent.CloseHistory -> setState { copy(history = null) }
            FormSignatureEvent.StartUploadForm -> if (!refusesPost()) setState { copy(uploadForm = UploadFormState()) }
            is FormSignatureEvent.EditUploadForm -> setState { copy(uploadForm = event.state) }
            FormSignatureEvent.PickUploadFile ->
                sendEffect(FormSignatureEffect.PickFile(PickTarget.StandardForm, PickKind.PdfOrWord))
            FormSignatureEvent.SubmitUploadForm -> submitUploadForm()
            FormSignatureEvent.CancelUploadForm -> setState { copy(uploadForm = null) }

            is FormSignatureEvent.SearchDocuments -> setState { copy(documents = documents.copy(search = event.query)) }
            is FormSignatureEvent.SwitchDocumentsTab -> switchDocumentsTab(event.tab)
            is FormSignatureEvent.OpenDocument -> detail.openDocument(event.document)
            is FormSignatureEvent.AskDeleteDocument ->
                if (!refusesPost()) setState { copy(confirm = ConfirmState.DeleteDocument(event.documentId)) }

            FormSignatureEvent.StartSend -> if (!refusesPost()) send.start()
            is FormSignatureEvent.EditSend -> send.edit(event.state)
            FormSignatureEvent.SendPickFile -> send.pickFile()
            FormSignatureEvent.SendNext -> send.next()
            FormSignatureEvent.SendBack -> send.back()
            is FormSignatureEvent.SendTurnPage -> send.turnPage(event.delta)
            is FormSignatureEvent.SendSelectSigner -> send.selectSigner(event.personId)
            is FormSignatureEvent.SendAddPlaceholder -> send.addPlaceholder(event.kind)
            is FormSignatureEvent.SendMoveDraft -> send.moveDraft(event.x, event.y, event.width, event.height)
            FormSignatureEvent.SendConfirmDraft -> send.confirmDraft()
            FormSignatureEvent.SendCancelDraft -> send.cancelDraft()
            is FormSignatureEvent.SendMoveSpot -> send.moveSpot(event.personId, event.index, event.x, event.y)
            is FormSignatureEvent.SendRemoveSpot -> send.removeSpot(event.personId, event.index)
            is FormSignatureEvent.SendAddOwnMark -> send.addOwnMark(event.kind)
            is FormSignatureEvent.SendMoveOwnMark -> send.moveOwnMark(event.x, event.y, event.width)
            FormSignatureEvent.SendConfirmOwnMark -> send.confirmOwnMark()
            FormSignatureEvent.SendCancelOwnMark -> send.cancelOwnMark()
            FormSignatureEvent.SubmitSend -> send.submit()
            FormSignatureEvent.CancelSend -> send.cancel()

            FormSignatureEvent.CloseDetail -> detail.close()
            is FormSignatureEvent.TurnPage -> detail.turnPage(event.delta)
            is FormSignatureEvent.TapPlaceholder -> detail.tapPlaceholder(event.spot)
            FormSignatureEvent.AddSignature -> detail.addSignature()
            is FormSignatureEvent.MoveFreeMark -> detail.moveFreeMark(event.x, event.y, event.width)
            FormSignatureEvent.ConfirmFreeMark -> detail.confirmFreeMark()
            FormSignatureEvent.CancelFreeMark -> detail.cancelFreeMark()
            FormSignatureEvent.AskSendSigned -> detail.askSend()
            FormSignatureEvent.DownloadDetail -> detail.download()
            FormSignatureEvent.PrintDetail -> detail.print()
            FormSignatureEvent.TransferToDownloads -> detail.transferToDownloads()

            is FormSignatureEvent.PickSignature -> pickSignature(event.block)
            FormSignatureEvent.ClosePicker -> setState { copy(picker = null) }
            is FormSignatureEvent.StartDraw -> marks.start(event.isSignature, event.existingId, event.asPage)
            is FormSignatureEvent.EditDraw -> setState { copy(draw = event.state) }
            is FormSignatureEvent.AddDrawStroke -> marks.addStroke(event.stroke)
            FormSignatureEvent.ClearDraw -> marks.clear()
            FormSignatureEvent.SubmitDraw -> marks.submit()
            FormSignatureEvent.CancelDraw -> marks.cancel()
            is FormSignatureEvent.AskDeleteSignature ->
                setState { copy(confirm = ConfirmState.DeleteSignature(event.blockId)) }

            FormSignatureEvent.OpenChat -> openChat()
            FormSignatureEvent.PickReceiver -> pickReceiver()
            is FormSignatureEvent.ChooseReceiver ->
                setState { copy(chat = chat.copy(receiver = event.option, pickingReceiver = false)) }
            FormSignatureEvent.CloseReceiverPicker -> setState { copy(chat = chat.copy(pickingReceiver = false)) }

            FormSignatureEvent.ConfirmYes -> confirmYes()
            FormSignatureEvent.ConfirmNo -> setState { copy(confirm = null) }

            is FormSignatureEvent.FilePicked -> when (event.target) {
                PickTarget.StandardForm -> setState {
                    copy(
                        uploadForm = uploadForm?.copy(
                            fileName = event.name,
                            fileBytes = event.bytes,
                            name = uploadForm.name.ifBlank { event.name.substringBeforeLast('.') },
                        ),
                    )
                }
                PickTarget.SendDocument -> send.filePicked(event.name, event.bytes)
            }
        }
    }

    // ------------------------------------------------------------ navigation

    private fun open(screen: FormSignScreen) {
        setState { copy(screen = screen, viewer = resolveViewer(), currentUserId = currentUserId()) }
        when (screen) {
            FormSignScreen.StandardDocuments -> {
                loadStandardForms()
                loadChatUnit()
            }
            FormSignScreen.DocumentsForSignature -> {
                loadDocuments()
                readDocumentsTab()
            }
            FormSignScreen.SignatureBlock -> marks.load()
            else -> Unit
        }
    }

    /** The web's `handleBackClick`, screen by screen. */
    private fun back() {
        when (currentState.screen) {
            FormSignScreen.Tiles -> Unit
            FormSignScreen.StandardDocuments, FormSignScreen.DocumentsForSignature, FormSignScreen.SignatureBlock ->
                setState { copy(screen = FormSignScreen.Tiles) }
            FormSignScreen.DrawSignature -> marks.cancel()
            FormSignScreen.Detail -> detail.close()
            FormSignScreen.Chat -> {
                currentState.chat.unit?.let { badges.readChat(it.id) }
                setState { copy(screen = FormSignScreen.StandardDocuments, chat = chat.copy(receiver = null)) }
            }
        }
    }

    private fun refresh() {
        when (currentState.screen) {
            FormSignScreen.StandardDocuments -> loadStandardForms()
            FormSignScreen.DocumentsForSignature -> loadDocuments()
            FormSignScreen.SignatureBlock -> marks.load()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ lists

    private fun loadStandardForms(quiet: Boolean = false) {
        if (!quiet) setState { copy(standard = standard.copy(loading = true)) }
        launchResult(
            block = { repository.standardForms(currentState.standard.tab == StandardTab.Mine) },
            onSuccess = { rows -> setState { copy(standard = standard.copy(rows = rows, loading = false)) } },
            onError = { error ->
                setState { copy(standard = standard.copy(loading = false)) }
                sendEffect(FormSignatureEffect.Failed(error.localised()))
            },
        )
    }

    private fun loadDocuments(quiet: Boolean = false) {
        if (!quiet) setState { copy(documents = documents.copy(loading = true)) }
        launchResult(
            block = { repository.documents(currentState.documents.tab) },
            onSuccess = { rows -> setState { copy(documents = documents.copy(rows = rows, loading = false)) } },
            onError = { error ->
                setState { copy(documents = documents.copy(loading = false)) }
                sendEffect(FormSignatureEffect.Failed(error.localised()))
            },
        )
    }

    private fun switchDocumentsTab(tab: SignDocumentTab) {
        setState { copy(documents = documents.copy(tab = tab, search = "")) }
        loadDocuments()
        readDocumentsTab()
    }

    /** The web reads the tab's badge as it shows (`DocumentsForSignature.jsx:95-108`). */
    private fun readDocumentsTab() {
        val tab = currentState.documents.tab
        val leaves = currentState.unread.tabLeaves(tab)
        if (leaves.isNotEmpty()) badges.readDocumentsTab(leaves)
    }

    private fun selfAssign(formId: String) {
        launchResult(
            block = { repository.selfAssign(formId) },
            onSuccess = { message ->
                sendEffect(FormSignatureEffect.Notice(message.ifBlank { str(S.desktop_fs_added_to_my_downloads) }))
            },
            onError = { sendEffect(FormSignatureEffect.Failed(it.localised())) },
        )
    }

    /**
     * Refuses a write, and offers the one thing that changes the answer.
     *
     * Every control that reaches this is on screen for everyone — hiding them
     * is what sent people to support rather than to an admin who could grant
     * the right in a few seconds.
     */
    private fun refusesPost(): Boolean {
        if (currentState.viewer.canPost) return false
        rights?.ask(FormSignatureUiState.TOOL_TITLE, RightsKind.Post)
        sendEffect(
            FormSignatureEffect.Failed(
                rightsRefusalMessage(FormSignatureUiState.TOOL_TITLE, RightsKind.Post, rights != null),
            ),
        )
        return true
    }

    // --------------------------------------------------------------- uploads

    /** The library upload — the web's `handleUploadForm`, checks in its order. */
    private fun submitUploadForm() {
        val form = currentState.uploadForm ?: return
        val bytes = form.fileBytes
        if (form.name.isBlank()) {
            sendEffect(FormSignatureEffect.Failed(str(S.please_enter_doc_name)))
            return
        }
        if (bytes == null) {
            sendEffect(FormSignatureEffect.Failed(str(S.desktop_fs_please_upload_the_document)))
            return
        }
        if (form.extension !in ALLOWED_UPLOADS) {
            sendEffect(FormSignatureEffect.Failed(str(S.desktop_fs_select_pdf_or_word)))
            return
        }
        setState { copy(uploadForm = form.copy(uploading = true)) }
        launch {
            val stored = transfer.store(UploadPurpose.Document, form.fileName, mimeFor(form.extension), bytes)
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(uploadForm = currentState.uploadForm?.copy(uploading = false)) }
                    sendEffect(FormSignatureEffect.Failed(stored.error.localised()))
                }
                is ZillitResult.Success -> {
                    // The typed name is the document's name; the extension rides as its subtype, as the web sends it.
                    val document = stored.data.copy(name = form.name.trim(), contentSubtype = form.extension)
                    when (val saved = repository.addStandardForm(document, form.type)) {
                        is ZillitResult.Failure -> {
                            setState { copy(uploadForm = currentState.uploadForm?.copy(uploading = false)) }
                            sendEffect(FormSignatureEffect.Failed(saved.error.localised()))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(uploadForm = null) }
                            sendEffect(FormSignatureEffect.Notice(str(S.desktop_fs_document_uploaded)))
                            loadStandardForms()
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- picker

    private fun pickSignature(block: SignatureBlock) {
        when (val purpose = currentState.picker?.purpose ?: return) {
            is PickPurpose.FillPlaceholder -> detail.fillPlaceholder(purpose.spot, block)
            PickPurpose.FreeOnDetail -> detail.placeFreeMark(block)
            is PickPurpose.SenderMark -> send.placeOwnMark(block)
        }
    }

    // ------------------------------------------------------------------ chat

    private fun loadChatUnit() {
        launch {
            val unit = (repository.chatUnit() as? ZillitResult.Success)?.data
            setState { copy(chat = chat.copy(unit = unit)) }
        }
    }

    private fun openChat() {
        val unit = currentState.chat.unit ?: run {
            sendEffect(FormSignatureEffect.Failed(str(S.desktop_fs_no_unit_for_discussion)))
            return
        }
        setState { copy(screen = FormSignScreen.Chat, chat = chat.copy(receiver = null)) }
        // The web reads the room's badge once its messages are on screen.
        if (currentState.unread.chat(unit.id) > 0) badges.readChat(unit.id)
    }

    /** "Select User" — the web's `SelectMembersModal`, single pick, self excluded. */
    private fun pickReceiver() {
        setState { copy(chat = chat.copy(pickingReceiver = true, loadingOptions = chat.options.isEmpty())) }
        if (currentState.chat.options.isNotEmpty()) return
        launch {
            val me = currentState.currentUserId
            val options = (repository.signerOptions() as? ZillitResult.Success)?.data.orEmpty()
                .filter { it.userId != me }
                .sortedBy { it.label.lowercase() }
            setState { copy(chat = chat.copy(options = options, loadingOptions = false)) }
        }
    }

    // ---------------------------------------------------------- confirmations

    private fun confirmYes() {
        val confirm = currentState.confirm ?: return
        setState { copy(confirm = null) }
        when (confirm) {
            is ConfirmState.DeleteForm -> launchResult(
                block = { repository.deleteStandardForm(confirm.formId) },
                onSuccess = {
                    setState {
                        copy(standard = standard.copy(rows = standard.rows.filterNot { it.id == confirm.formId }))
                    }
                    sendEffect(FormSignatureEffect.Notice(str(S.desktop_fs_document_deleted)))
                },
                onError = { sendEffect(FormSignatureEffect.Failed(it.localised())) },
            )
            is ConfirmState.DeleteDocument -> launchResult(
                block = { repository.deleteDocument(confirm.documentId) },
                onSuccess = {
                    sendEffect(FormSignatureEffect.Notice(str(S.desktop_fs_document_deleted)))
                    loadDocuments()
                },
                onError = { sendEffect(FormSignatureEffect.Failed(it.localised())) },
            )
            is ConfirmState.DeleteSignature -> marks.delete(confirm.blockId)
            ConfirmState.LeaveSigned -> detail.close(force = true)
            ConfirmState.SendSigned -> detail.send()
        }
    }

    private fun mimeFor(extension: String): String = when (extension) {
        "pdf" -> PDF_MIME
        "doc" -> "application/msword"
        else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    private companion object {
        val ALLOWED_UPLOADS = setOf(StoredDocument.SUBTYPE_PDF) + StoredDocument.WORD_SUBTYPES
    }
}

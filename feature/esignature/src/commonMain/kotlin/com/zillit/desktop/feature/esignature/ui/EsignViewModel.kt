@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.NewField
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike

/**
 * E-Signature.
 *
 * The one non-obvious flow is signing: the desktop fills every field the
 * envelope placed for this signer — marks resolve to the saved signature or
 * initials, dates to today, typed fields to what was entered — and sends
 * **values**; the flattened PDF is the backend's job and arrives later as
 * the envelope's signed document. The mirror image of the documents tool,
 * and the reason this module contains no stamping code.
 */
class EsignViewModel(
    private val repository: EsignRepository,
    private val transfer: EsignFileTransfer,
    private val pdf: EsignPdf,
    private val resolveViewer: () -> EsignViewer,
    private val currentUserId: () -> String,
    private val currentUserName: () -> String,
    private val signerOptions: () -> List<SignerOptionLike>,
    private val newId: () -> String,
) : ZillitViewModel<EsignUiState, EsignEvent, EsignEffect>(EsignUiState()) {

    fun start() {
        val viewer = resolveViewer()
        setState {
            copy(
                viewer = viewer,
                currentUserId = currentUserId(),
                surface = if (viewer.receiverOnly) EsignSurface.Sign else surface,
            )
        }
        refresh()
        loadMarks()
        listenOnce()
    }

    /**
     * Refetches the visible envelope list when the socket announces an
     * envelope change — the web's `DocuSignObservers` upsert, as a targeted
     * reload. Guarded so a second Start (the window reopening) does not
     * stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { refresh() }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: EsignEvent) {
        when (event) {
            is EsignEvent.SwitchSurface -> {
                setState { copy(surface = event.surface) }
                refresh()
            }
            EsignEvent.Refresh -> refresh()
            is EsignEvent.SwitchManageBucket -> {
                setState { copy(manage = manage.copy(bucket = event.bucket)) }
                loadManage()
            }
            is EsignEvent.SwitchSignBucket -> {
                setState { copy(signList = signList.copy(bucket = event.bucket)) }
                loadSignList()
            }
            is EsignEvent.OpenEnvelope -> openEnvelope(event.envelope)
            EsignEvent.CloseDetail -> setState { copy(detail = null) }
            EsignEvent.Consent -> setState { copy(detail = detail?.copy(consented = true)) }
            is EsignEvent.Answer -> setState {
                copy(
                    detail = detail?.copy(
                        answers = detail.answers + (event.fieldId to event.answer),
                    ),
                )
            }
            EsignEvent.SignEnvelope -> signEnvelope()
            is EsignEvent.EditDeclineReason ->
                setState { copy(detail = detail?.copy(declineReason = event.reason)) }
            EsignEvent.StartDecline -> setState { copy(detail = detail?.copy(declining = true)) }
            EsignEvent.CancelDecline -> setState { copy(detail = detail?.copy(declining = false)) }
            EsignEvent.ConfirmDecline -> confirmDecline()
            is EsignEvent.DeleteDraft -> deleteDraft(event.envelopeId)
            is EsignEvent.Remind -> remind(event.envelopeId, event.recipientId)
            EsignEvent.StartCompose -> {
                sendEffect(EsignEffect.PickPdf)
            }
            is EsignEvent.EditCompose -> setState { copy(compose = event.state) }
            EsignEvent.BeginPlacement -> beginPlacement()
            is EsignEvent.PlaceField -> placeField(event.page, event.xPx, event.yPx)
            is EsignEvent.RemovePlaced -> removePlaced(event.signer, event.index)
            EsignEvent.SubmitCompose -> submitCompose()
            EsignEvent.CancelCompose -> setState { copy(compose = null) }
            EsignEvent.ToggleMarks -> setState { copy(showMarks = !showMarks) }
            is EsignEvent.StartDrawMark -> setState {
                copy(marks = marks.copy(drawing = DrawMarkState(isSignature = event.isSignature)))
            }
            is EsignEvent.AddMarkStroke -> setState {
                copy(
                    marks = marks.copy(
                        drawing = marks.drawing?.copy(
                            strokes = marks.drawing.strokes + listOf(event.stroke),
                        ),
                    ),
                )
            }
            EsignEvent.ClearMarkStrokes -> setState {
                copy(marks = marks.copy(drawing = marks.drawing?.copy(strokes = emptyList())))
            }
            EsignEvent.SaveMark -> saveMark()
            EsignEvent.CancelDrawMark -> setState { copy(marks = marks.copy(drawing = null)) }
            is EsignEvent.DeleteMark -> deleteMark(event.id)
            is EsignEvent.FilePicked -> startComposeWith(event.name, event.bytes)
        }
    }

    private fun refresh() {
        when (currentState.surface) {
            EsignSurface.Manage -> loadManage()
            EsignSurface.Sign -> loadSignList()
        }
    }

    private fun loadManage() {
        setState { copy(manage = manage.copy(loading = true)) }
        launchResult(
            block = {
                repository.envelopes(
                    EnvelopeScope.Sent,
                    currentState.manage.bucket.wire,
                    userId = null,
                )
            },
            onSuccess = { rows -> setState { copy(manage = manage.copy(rows = rows, loading = false)) } },
            onError = { error ->
                setState { copy(manage = manage.copy(loading = false)) }
                sendEffect(EsignEffect.Failed(error.localised()))
            },
        )
    }

    private fun loadSignList() {
        setState { copy(signList = signList.copy(loading = true)) }
        launchResult(
            block = {
                repository.envelopes(
                    EnvelopeScope.Received,
                    currentState.signList.bucket.wire,
                    userId = currentState.currentUserId,
                )
            },
            onSuccess = { rows ->
                setState { copy(signList = signList.copy(rows = rows, loading = false)) }
            },
            onError = { error ->
                setState { copy(signList = signList.copy(loading = false)) }
                sendEffect(EsignEffect.Failed(error.localised()))
            },
        )
    }

    private fun loadMarks() {
        setState { copy(marks = marks.copy(loading = true)) }
        launchResult(
            block = { repository.savedSignatures() },
            onSuccess = { items ->
                setState { copy(marks = marks.copy(items = items, loading = false)) }
                items.forEach { mark ->
                    val image = mark.image ?: return@forEach
                    if (currentState.marks.images.containsKey(mark.id)) return@forEach
                    launch {
                        val bytes = (transfer.fetch(image) as? ZillitResult.Success)?.data
                            ?: return@launch
                        setState {
                            copy(marks = marks.copy(images = marks.images + (mark.id to bytes)))
                        }
                    }
                }
            },
            onError = { setState { copy(marks = marks.copy(loading = false)) } },
        )
    }

    // ---------------------------------------------------------------- detail

    private fun openEnvelope(row: Envelope) {
        setState {
            copy(detail = EnvelopeDetailState(envelope = row, loadingPages = true))
        }
        launch {
            // The list rows are thin; the full envelope carries tabs and
            // recipients. Mark-viewed rides along, as the web does on open.
            when (val full = repository.envelope(row.id)) {
                is ZillitResult.Failure -> {
                    setState { copy(detail = detail?.copy(loadingPages = false)) }
                    sendEffect(EsignEffect.Failed(full.error.localised()))
                }
                is ZillitResult.Success -> {
                    val envelope = full.data
                    val me = envelope.recipientFor(currentState.currentUserId)
                    val waitingOnMe = me != null && !me.signed && !me.declined
                    val open = envelope.status != EnvelopeStatus.Completed
                    val mine = if (waitingOnMe && open && me != null) {
                        envelope.fieldsFor(me)
                    } else {
                        emptyList()
                    }
                    setState {
                        copy(
                            detail = detail?.copy(envelope = envelope, myFields = mine),
                        )
                    }
                    if (mine.isNotEmpty()) {
                        launch { repository.markViewed(envelope.id) }
                    }
                    loadDetailPages(envelope)
                    loadAudit(envelope.id)
                }
            }
        }
    }

    private suspend fun loadDetailPages(envelope: Envelope) {
        // The finished rendition once it exists; the original otherwise.
        val stored = envelope.signedDocument ?: envelope.document
        if (stored == null || !stored.name.endsWith(".pdf", ignoreCase = true) &&
            !stored.contentSubtype.equals("pdf", ignoreCase = true)
        ) {
            setState { copy(detail = detail?.copy(loadingPages = false, notPdf = true)) }
            return
        }
        when (val bytes = transfer.fetch(stored)) {
            is ZillitResult.Failure -> {
                setState { copy(detail = detail?.copy(loadingPages = false)) }
                sendEffect(EsignEffect.Failed(bytes.error.localised()))
            }
            is ZillitResult.Success ->
                when (val pages = pdf.renderPages(bytes.data, PAGE_RENDER_WIDTH)) {
                    is ZillitResult.Failure ->
                        setState {
                            copy(detail = detail?.copy(loadingPages = false, notPdf = true))
                        }
                    is ZillitResult.Success -> setState {
                        copy(detail = detail?.copy(pages = pages.data, loadingPages = false))
                    }
                }
        }
    }

    private fun loadAudit(envelopeId: String) {
        launchResult(
            block = { repository.auditTrail(envelopeId) },
            onSuccess = { entries -> setState { copy(detail = detail?.copy(audit = entries)) } },
            onError = { /* the trail is garnish; the tracker stays useful without it */ },
        )
    }

    // ---------------------------------------------------------------- signing

    private fun signEnvelope() {
        val detail = currentState.detail ?: return
        if (!detail.canSignNow) return
        setState { copy(detail = detail.copy(signing = true)) }
        launch {
            when (val fields = answersFor(detail)) {
                is ZillitResult.Failure -> {
                    setState { copy(detail = currentState.detail?.copy(signing = false)) }
                    sendEffect(EsignEffect.Failed(fields.error.localised()))
                }
                is ZillitResult.Success -> {
                    when (val signed = repository.sign(detail.envelope.id, fields.data)) {
                        is ZillitResult.Failure -> {
                            setState { copy(detail = currentState.detail?.copy(signing = false)) }
                            sendEffect(EsignEffect.Failed(signed.error.localised()))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(detail = null) }
                            sendEffect(EsignEffect.Notice("Signed."))
                            refresh()
                        }
                    }
                }
            }
        }
    }

    /**
     * Every field this signer owns becomes an answer: explicit ones from the
     * form, marks from the saved signature or initials, the rest from the
     * builder's defaults. A missing saved mark is the one refusal.
     */
    private fun answersFor(detail: EnvelopeDetailState): ZillitResult<List<SignedField>> {
        val answers = mutableListOf<SignedField>()
        for (field in detail.myFields) {
            val explicit = detail.answers[field.id]
            val answer: FieldAnswer? = when {
                explicit != null -> explicit
                field.type.isMark -> {
                    val mark = currentState.marks.items.firstOrNull {
                        it.isSignature == (field.type == FieldType.SignHere)
                    }?.image ?: return ZillitResult.Failure(
                        ZillitError.Validation(
                            "Draw your ${field.type.label.lowercase()} first — " +
                                "open Saved marks and set it up.",
                        ),
                    )
                    FieldAnswer.Mark(mark)
                }
                field.type == FieldType.FullName -> FieldAnswer.Typed(currentUserName())
                field.type == FieldType.Text || field.type == FieldType.Email -> {
                    // A required field the signer never filled is not an empty
                    // answer to send — Android refuses the whole submit with
                    // "Please complete every required field." and the wire
                    // marks a tab required unless it says otherwise. This port
                    // sent the default (usually blank) and completed the
                    // envelope regardless.
                    if (field.required && field.defaultValue.isBlank()) {
                        return ZillitResult.Failure(
                            ZillitError.Validation(
                                "Fill in ${field.label.ifBlank { "every required field" }} before signing.",
                            ),
                        )
                    }
                    FieldAnswer.Typed(field.defaultValue)
                }
                else -> null
            }
            answers += SignedField(
                tabId = field.id,
                type = field.type,
                documentIndex = field.documentIndex,
                answer = answer,
            )
        }
        return ZillitResult.Success(answers)
    }

    private fun confirmDecline() {
        val detail = currentState.detail ?: return
        launchResult(
            block = { repository.decline(detail.envelope.id, detail.declineReason) },
            onSuccess = {
                setState { copy(detail = null) }
                sendEffect(EsignEffect.Notice("Declined."))
                refresh()
            },
            onError = { sendEffect(EsignEffect.Failed(it.userMessage)) },
        )
    }

    private fun deleteDraft(envelopeId: String) {
        launchResult(
            block = { repository.deleteDraft(envelopeId) },
            onSuccess = { loadManage() },
            onError = { sendEffect(EsignEffect.Failed(it.userMessage)) },
        )
    }

    private fun remind(envelopeId: String, recipientId: String?) {
        launchResult(
            block = { repository.remind(envelopeId, recipientId) },
            onSuccess = { sendEffect(EsignEffect.Notice("Reminder sent.")) },
            onError = { sendEffect(EsignEffect.Failed(it.userMessage)) },
        )
    }

    // ---------------------------------------------------------------- compose

    private fun startComposeWith(name: String, bytes: ByteArray) {
        val pages = when (val rendered = pdf.renderPages(bytes, PAGE_RENDER_WIDTH)) {
            is ZillitResult.Failure -> {
                sendEffect(EsignEffect.Failed("Only PDF documents can be sent for e-signature."))
                return
            }
            is ZillitResult.Success -> rendered.data
        }
        setState {
            copy(
                compose = ComposeState(
                    fileName = name,
                    fileBytes = bytes,
                    title = name.removeSuffix(".pdf"),
                    pages = pages,
                    options = signerOptions(),
                ),
            )
        }
    }

    private fun beginPlacement() {
        val compose = currentState.compose ?: return
        if (compose.chosen.isEmpty()) {
            sendEffect(EsignEffect.Failed("Choose at least one signer first."))
            return
        }
        if (compose.missingEmails.isNotEmpty()) {
            sendEffect(
                EsignEffect.Failed(
                    "Every signer needs an email address — the service refuses one without.",
                ),
            )
            return
        }
        setState {
            copy(compose = compose.copy(placing = true, activeSigner = compose.chosen.first()))
        }
    }

    private fun placeField(page: Int, xPx: Float, yPx: Float) {
        val compose = currentState.compose ?: return
        val signer = compose.activeSigner ?: return
        val pageImage = compose.pages.firstOrNull { it.page == page } ?: return
        val (xPt, yPt) = pageImage.pointFromTap(xPx, yPx)
        val field = PlacedField(
            type = compose.activeType,
            page = page,
            x = (xPt - FieldType.DEFAULT_WIDTH / 2)
                .coerceIn(0.0, (pageImage.widthPt - FieldType.DEFAULT_WIDTH).coerceAtLeast(0.0)),
            y = (yPt - FieldType.DEFAULT_HEIGHT / 2)
                .coerceIn(0.0, (pageImage.heightPt - FieldType.DEFAULT_HEIGHT).coerceAtLeast(0.0)),
        )
        val mine = compose.placed[signer].orEmpty() + field
        setState { copy(compose = compose.copy(placed = compose.placed + (signer to mine))) }
    }

    private fun removePlaced(signer: String, index: Int) {
        val compose = currentState.compose ?: return
        val mine = compose.placed[signer].orEmpty().filterIndexed { i, _ -> i != index }
        setState { copy(compose = compose.copy(placed = compose.placed + (signer to mine))) }
    }

    private fun submitCompose() {
        val compose = currentState.compose ?: return
        val bytes = compose.fileBytes ?: return
        if (!compose.everySignerCovered) {
            sendEffect(EsignEffect.Failed("Every signer needs at least one field."))
            return
        }
        setState { copy(compose = compose.copy(sending = true)) }
        launch {
            val fileName = compose.title.ifBlank { compose.fileName }.let {
                if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
            }
            val stored = transfer.store(fileName, "application/pdf", bytes)
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(compose = currentState.compose?.copy(sending = false)) }
                    sendEffect(EsignEffect.Failed(stored.error.localised()))
                }
                is ZillitResult.Success -> createAndSend(compose, stored.data, fileName)
            }
        }
    }

    private suspend fun createAndSend(
        compose: ComposeState,
        stored: com.zillit.desktop.feature.esignature.domain.StoredFile,
        fileName: String,
    ) {
        val recipients = compose.chosen.mapIndexed { index, userId ->
            val option = compose.options.firstOrNull { it.userId == userId }
            EnvelopeRecipient(
                userId = userId,
                name = option?.fullName.orEmpty(),
                email = compose.emailFor(userId),
                role = "signer",
                routingOrder = index + 1,
            )
        }
        val fields = compose.chosen.flatMapIndexed { index, userId ->
            compose.placed[userId].orEmpty().map { placed ->
                NewField(
                    type = placed.type,
                    recipientIndex = index,
                    page = placed.page,
                    x = placed.x,
                    y = placed.y,
                )
            }
        }
        val document = stored.copy(
            name = fileName,
            pageCount = compose.pages.size,
        )
        when (
            val created = repository.create(
                title = compose.title.ifBlank { fileName },
                description = compose.description,
                document = document,
                recipients = recipients,
                fields = fields,
            )
        ) {
            is ZillitResult.Failure -> {
                setState { copy(compose = currentState.compose?.copy(sending = false)) }
                sendEffect(EsignEffect.Failed(created.error.localised()))
            }
            is ZillitResult.Success -> when (val sent = repository.send(created.data.id)) {
                is ZillitResult.Failure -> {
                    setState { copy(compose = currentState.compose?.copy(sending = false)) }
                    sendEffect(
                        EsignEffect.Failed(
                            "Created as a draft, but sending failed: ${sent.error.localised()}",
                        ),
                    )
                }
                is ZillitResult.Success -> {
                    setState { copy(compose = null) }
                    sendEffect(EsignEffect.Notice("Envelope sent."))
                    refresh()
                }
            }
        }
    }

    // ------------------------------------------------------------------ marks

    private fun saveMark() {
        val drawing = currentState.marks.drawing ?: return
        if (drawing.strokes.none { it.size > 1 }) {
            sendEffect(EsignEffect.Failed("Draw something first."))
            return
        }
        setState { copy(marks = marks.copy(drawing = drawing.copy(saving = true))) }
        launch {
            val png = when (
                val raster = pdf.rasterizeStrokes(drawing.strokes, DRAW_WIDTH, DRAW_HEIGHT)
            ) {
                is ZillitResult.Failure -> {
                    setState { copy(marks = marks.copy(drawing = marks.drawing?.copy(saving = false))) }
                    sendEffect(EsignEffect.Failed(raster.error.localised()))
                    return@launch
                }
                is ZillitResult.Success -> raster.data
            }
            val stored = transfer.store("${newId()}.png", "image/png", png)
            when (stored) {
                is ZillitResult.Failure -> {
                    setState { copy(marks = marks.copy(drawing = marks.drawing?.copy(saving = false))) }
                    sendEffect(EsignEffect.Failed(stored.error.localised()))
                }
                is ZillitResult.Success -> {
                    when (repository.saveSignature(drawing.isSignature, stored.data)) {
                        is ZillitResult.Failure -> {
                            setState {
                                copy(marks = marks.copy(drawing = marks.drawing?.copy(saving = false)))
                            }
                            sendEffect(EsignEffect.Failed("The mark could not be saved."))
                        }
                        is ZillitResult.Success -> {
                            setState { copy(marks = marks.copy(drawing = null)) }
                            loadMarks()
                        }
                    }
                }
            }
        }
    }

    private fun deleteMark(id: String) {
        launchResult(
            block = { repository.deleteSavedSignature(id) },
            onSuccess = { loadMarks() },
            onError = { sendEffect(EsignEffect.Failed(it.userMessage)) },
        )
    }

    private companion object {
        const val PAGE_RENDER_WIDTH = 800
        const val DRAW_WIDTH = 800
        const val DRAW_HEIGHT = 300
    }
}

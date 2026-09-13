@file:Suppress("TooManyFunctions", "CyclomaticComplexMethod") // One entry per signer act.

package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.FieldValue
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.ui.EMAIL_RE
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.MARK_RASTER_HEIGHT
import com.zillit.desktop.feature.esignature.ui.MARK_RASTER_WIDTH
import com.zillit.desktop.feature.esignature.ui.PAGE_RENDER_WIDTH
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.PadState
import com.zillit.desktop.feature.esignature.ui.PickPurpose
import com.zillit.desktop.feature.esignature.ui.SigningMode
import com.zillit.desktop.feature.esignature.ui.SigningState
import com.zillit.desktop.feature.esignature.ui.orFail

/**
 * The signing surface — the web's `SigningView`.
 *
 * Opening marks the envelope delivered, seeds locked and defaulted answers,
 * and raises the consent gate until the server records acceptance. Marks
 * come from the pad (drawn, typed or uploaded) or the saved library; Finish
 * sends every field this signer owns — the hidden auto-initials included —
 * as values, and the backend flattens.
 */
internal class SigningFlow(private val store: EsignStore) {

    fun open(seed: Envelope, mode: SigningMode, fromDetail: Boolean) {
        store.update {
            copy(
                page = EsignPageKind.Signing,
                signing = SigningState(envelope = seed, mode = mode, loadingPages = true, returnToDetail = fromDetail),
            )
        }
        store.runTask {
            val envelope = store.orFail { store.repository.envelope(seed.id) } ?: run {
                store.update { copy(signing = signing?.copy(loadingPages = false)) }
                return@runTask
            }
            val me = envelope.recipientFor(store.current.currentUserId, store.current.currentUserEmail)
            val live = envelope.status == EnvelopeStatus.Sent || envelope.status == EnvelopeStatus.Delivered ||
                envelope.status == EnvelopeStatus.Signed
            val canSign = mode == SigningMode.Sign && me != null && !me.signed && !me.declined && live
            val mine = if (canSign && me != null) {
                envelope.fieldsFor(me).sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
            } else {
                emptyList()
            }
            val seeded = mine.filter { it.defaultValue.isNotBlank() }.associate { field ->
                field.id to when {
                    field.type == FieldType.Checkbox -> FieldAnswer.Ticked(field.defaultValue == "true")
                    field.type.hasOptions -> FieldAnswer.Chosen(field.defaultValue)
                    else -> FieldAnswer.Typed(field.defaultValue)
                }
            }
            store.update {
                copy(
                    signing = signing?.copy(
                        envelope = envelope,
                        me = me,
                        mode = if (mode == SigningMode.Sign && !canSign) SigningMode.ViewSigned else mode,
                        myFields = mine,
                        answers = seeded,
                        needsConsent = canSign && me?.acceptedTermsOn == null,
                        savedMarks = store.current.marks.items,
                    ),
                )
            }
            if (canSign && me != null && me.status == "sent") {
                // Fire-and-forget: the sender's "Seen by X/Y" — idempotent server-side.
                store.runTask { store.repository.markViewed(envelope.id) }
            }
            loadPages(envelope, mode)
            loadValueImages(envelope)
        }
    }

    private suspend fun loadPages(envelope: Envelope, mode: SigningMode) {
        // The merged PDF once completed and asked for the signed view; the original otherwise.
        val stored = if (mode == SigningMode.ViewSigned && envelope.status == EnvelopeStatus.Completed) {
            envelope.signedDocument ?: envelope.document
        } else {
            envelope.document
        }
        if (stored == null || !stored.isPdf) {
            store.update { copy(signing = signing?.copy(loadingPages = false, notPdf = true)) }
            return
        }
        val bytes = store.orFail { store.transfer.fetch(stored) } ?: run {
            store.update { copy(signing = signing?.copy(loadingPages = false)) }
            return
        }
        when (val pages = store.pdf.renderPages(bytes, PAGE_RENDER_WIDTH)) {
            is ZillitResult.Failure -> store.update {
                copy(signing = signing?.copy(loadingPages = false, notPdf = true))
            }
            is ZillitResult.Success -> store.update {
                copy(signing = signing?.copy(pages = pages.data, loadingPages = false))
            }
        }
    }

    /** Stamped marks and uploads other signers already submitted, for the overlays. */
    private fun loadValueImages(envelope: Envelope) {
        envelope.fields.mapNotNull { (it.value as? FieldValue.File)?.file }.distinctBy { it.media }.forEach { file ->
            fetchImage(file)
        }
    }

    private fun fetchImage(file: StoredFile) {
        if (store.current.signing?.images?.containsKey(file.media) == true) return
        store.runTask {
            val bytes = (store.transfer.fetch(file) as? ZillitResult.Success)?.data ?: return@runTask
            store.update { copy(signing = signing?.copy(images = signing.images + (file.media to bytes))) }
        }
    }

    // ---------------------------------------------------------------- consent

    /**
     * Records the signer's agreement to sign electronically — the one step
     * that must not be silently skipped. The gate stays up when the call
     * fails, and says why.
     */
    fun consent() {
        val signing = store.current.signing ?: return
        store.update { copy(signing = this.signing?.copy(consenting = true)) }
        store.runTask {
            when (store.repository.acceptTerms(signing.envelope.id)) {
                is ZillitResult.Success -> store.update {
                    copy(signing = this.signing?.copy(needsConsent = false, consenting = false))
                }
                is ZillitResult.Failure -> {
                    store.update { copy(signing = this.signing?.copy(consenting = false)) }
                    store.failed("Your acceptance was not recorded — please try again.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- moving about

    fun focus(index: Int) {
        store.update {
            copy(
                signing = signing?.let { s ->
                    s.copy(currentIndex = index.coerceIn(0, (s.visibleFields.size - 1).coerceAtLeast(0)), pad = null)
                },
            )
        }
    }

    fun next() {
        val s = store.current.signing ?: return
        // Advance to the next field still waiting, wrapping round; else the next in order.
        val order = s.visibleFields.indices
        val waiting = order.drop(s.currentIndex + 1).firstOrNull { !s.isDone(s.visibleFields[it]) }
            ?: order.take(s.currentIndex).firstOrNull { !s.isDone(s.visibleFields[it]) }
        focus(waiting ?: (s.currentIndex + 1).coerceAtMost(order.last.coerceAtLeast(0)))
    }

    fun prev() = focus((store.current.signing?.currentIndex ?: 0) - 1)

    fun answer(fieldId: String, answer: FieldAnswer?) {
        store.update {
            copy(
                signing = signing?.let { s ->
                    s.copy(answers = if (answer == null) s.answers - fieldId else s.answers + (fieldId to answer))
                },
            )
        }
    }

    // ---------------------------------------------------------------- the pad

    fun openPad() {
        val s = store.current.signing ?: return
        val field = s.current ?: return
        val saved = s.savedMarks.filter { it.isSignature == (field.type == FieldType.SignHere) }
        store.update {
            val mode = if (saved.isNotEmpty()) PadMode.Saved else PadMode.Draw
            copy(signing = signing?.copy(pad = PadState(mode = mode)))
        }
    }

    fun editPad(transform: (PadState) -> PadState) {
        store.update { copy(signing = signing?.let { s -> s.copy(pad = s.pad?.let(transform)) }) }
    }

    /** A saved mark, applied as it stands. */
    fun useSavedMark(markId: String) {
        val s = store.current.signing ?: return
        val field = s.current ?: return
        val mark = s.savedMarks.firstOrNull { it.id == markId }?.image ?: return
        applyMark(field, mark)
    }

    /** The pad's drawing, typed name or upload — rasterised, stored, and applied. */
    fun applyPad() {
        val s = store.current.signing ?: return
        val field = s.current ?: return
        val pad = s.pad ?: return
        if (!pad.canApply) return
        editPad { it.copy(busy = true) }
        store.runTask {
            val png = when (pad.mode) {
                PadMode.Draw -> store.pdf.rasterizeStrokes(pad.strokes, MARK_RASTER_WIDTH, MARK_RASTER_HEIGHT)
                PadMode.Type -> store.pdf.rasterizeText(
                    pad.typedName.trim(),
                    pad.font.key,
                    MARK_RASTER_WIDTH,
                    MARK_RASTER_HEIGHT,
                )
                PadMode.Upload -> pad.uploadBytes?.let { ZillitResult.Success(it) }
                    ?: ZillitResult.Failure(ZillitError.Validation("Choose an image first."))
                PadMode.Saved -> return@runTask
            }
            val bytes = when (png) {
                is ZillitResult.Failure -> {
                    editPad { it.copy(busy = false) }
                    store.failed(png.error.userMessage)
                    return@runTask
                }
                is ZillitResult.Success -> png.data
            }
            val jpeg = pad.mode == PadMode.Upload && pad.uploadName.endsWith(".jpg", true)
            val contentType = if (jpeg) "image/jpeg" else "image/png"
            val ext = if (contentType == "image/jpeg") "jpg" else "png"
            val stored = store.orFail { store.transfer.store("${store.newId()}.$ext", contentType, bytes) } ?: run {
                editPad { it.copy(busy = false) }
                return@runTask
            }
            val mark = stored.copy(contentType = "image", contentSubtype = ext)
            if (pad.saveForLater && field.type.isMark) {
                store.repository.saveSignature(field.type == FieldType.SignHere, mark)
                store.repository.savedSignatures().getOrNull()?.let { items ->
                    store.update {
                        copy(marks = marks.copy(items = items), signing = signing?.copy(savedMarks = items))
                    }
                }
            }
            store.update { copy(signing = signing?.copy(images = signing.images + (mark.media to bytes))) }
            applyMark(field, mark)
        }
    }

    private fun applyMark(field: EnvelopeField, mark: StoredFile) {
        val s = store.current.signing ?: return
        val targets = buildList {
            add(field)
            if (s.applyToAll) {
                addAll(
                    s.visibleFields.filter {
                        it.type == field.type && it.id != field.id && !s.answers.containsKey(it.id)
                    },
                )
            }
            if (field.autoInitial && s.signOnce) addAll(s.myFields.filter { it.autoInitial && it.id != field.id })
        }
        val added = targets.associate { it.id to FieldAnswer.Mark(mark) as FieldAnswer }
        store.update { copy(signing = signing?.copy(answers = signing.answers + added, pad = null)) }
        fetchImage(mark)
        next()
    }

    fun pickPadImage() = store.requestPick(PickPurpose.PadImage)

    fun padImagePicked(name: String, bytes: ByteArray) {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            store.failed("Images must be under 8 MB.")
            return
        }
        editPad { it.copy(mode = PadMode.Upload, uploadBytes = bytes, uploadName = name) }
    }

    fun pickUploadForField() = store.requestPick(PickPurpose.FieldUpload)

    /** An image or attachment field's file, stored and applied. */
    fun fieldUploadPicked(name: String, bytes: ByteArray) {
        val s = store.current.signing ?: return
        val field = s.current ?: return
        if (bytes.size > MAX_UPLOAD_BYTES) {
            store.failed("Files must be under 8 MB.")
            return
        }
        store.runTask {
            val contentType = when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            val stored = store.orFail { store.transfer.store(name, contentType, bytes) } ?: return@runTask
            val file = stored.copy(
                contentType = if (contentType.startsWith("image")) "image" else "document",
                contentSubtype = name.substringAfterLast('.', "").lowercase(),
            )
            if (contentType.startsWith("image")) {
                store.update { copy(signing = signing?.copy(images = signing.images + (file.media to bytes))) }
            }
            answer(field.id, FieldAnswer.Mark(file))
            next()
        }
    }

    // ---------------------------------------------------------------- finishing

    fun finish(lists: ListsFlow) {
        val s = store.current.signing ?: return
        if (!s.canFinish) return
        val problem = firstProblem(s)
        if (problem != null) {
            store.failed(problem)
            return
        }
        store.update { copy(signing = signing?.copy(submitting = true)) }
        store.runTask {
            val fields = s.myFields.map { field ->
                val explicit = s.answers[field.id]
                    ?: if (field.autoInitial && s.signOnce) {
                        s.myFields.firstOrNull { it.autoInitial }?.let { s.answers[it.id] }
                    } else {
                        null
                    }
                SignedField(
                    tabId = field.id,
                    type = field.type,
                    documentIndex = field.documentIndex,
                    answer = explicit ?: implicitAnswer(field),
                    defaultValue = field.defaultValue,
                )
            }
            when (val signed = store.repository.sign(s.envelope.id, fields)) {
                is ZillitResult.Failure -> {
                    store.update { copy(signing = signing?.copy(submitting = false)) }
                    store.failed(signed.error.userMessage)
                }
                is ZillitResult.Success -> {
                    store.update { copy(signing = signing?.copy(submitting = false, finished = true)) }
                    lists.loadBoth()
                }
            }
        }
    }

    private fun implicitAnswer(field: EnvelopeField): FieldAnswer? = when (field.type) {
        FieldType.FullName -> FieldAnswer.Typed(store.currentUserName())
        else -> null
    }

    /** The web's `validateFieldValue` plus the required check, first failure wins. */
    private fun firstProblem(s: SigningState): String? {
        s.visibleFields.forEach { field ->
            if (field.required && !s.isDone(field)) {
                val name = field.label.ifBlank { field.type.label }
                return "Please complete every required field — $name on page ${field.page} is empty."
            }
            val text = (s.answers[field.id] as? FieldAnswer.Typed)?.text?.trim().orEmpty()
            if (text.isBlank()) return@forEach
            validateText(field.type, text)?.let { return "${field.label.ifBlank { field.type.label }}: $it" }
        }
        return null
    }

    fun confirmDecline(lists: ListsFlow) {
        val s = store.current.signing ?: return
        store.runTask {
            store.orFail { store.repository.decline(s.envelope.id, s.declineReason) } ?: return@runTask
            store.update { copy(signing = null, detail = null, page = EsignPageKind.Lists) }
            store.notice("Declined.")
            lists.loadBoth()
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
        private val PHONE_RE = Regex("^[+()\\-\\s.0-9]+$")
        private const val MIN_PHONE_DIGITS = 7
        private const val MAX_PHONE_DIGITS = 15

        /** The web's `validateFieldValue`: empty is never an error here. */
        fun validateText(type: FieldType, value: String): String? {
            val v = value.trim()
            if (v.isBlank()) return null
            return when (type) {
                FieldType.Email -> if (EMAIL_RE.matches(v)) null else "Please enter a valid email address"
                FieldType.Phone -> when {
                    !PHONE_RE.matches(v) -> "Use digits, spaces, +, - or () only"
                    v.count { it.isDigit() } < MIN_PHONE_DIGITS -> "Phone number is too short"
                    v.count { it.isDigit() } > MAX_PHONE_DIGITS -> "Phone number is too long"
                    else -> null
                }
                FieldType.Number -> {
                    val numeric = v.replace(",", "").replace(" ", "").toDoubleOrNull() != null
                    if (numeric) null else "Numbers only"
                }
                else -> null
            }
        }
    }
}

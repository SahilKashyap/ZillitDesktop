package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeDraft
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import com.zillit.desktop.feature.esignature.ui.EditorRules
import com.zillit.desktop.feature.esignature.ui.EditorState
import com.zillit.desktop.feature.esignature.ui.EditorStep
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.ManageInnerTab
import com.zillit.desktop.feature.esignature.ui.ManageOuterTab
import com.zillit.desktop.feature.esignature.ui.PAGE_RENDER_WIDTH
import com.zillit.desktop.feature.esignature.ui.PendingPlacement
import com.zillit.desktop.feature.esignature.ui.PickPurpose
import com.zillit.desktop.feature.esignature.ui.PlacementMode
import com.zillit.desktop.feature.esignature.ui.SaveTemplateDraft
import com.zillit.desktop.feature.esignature.ui.orFail

/**
 * The envelope editor — the web's `EnvelopeEditorBody` and
 * `DocumentFieldPlacer`, as one flow with three entrances: a fresh
 * envelope, a draft reopened, and template authoring.
 *
 * Saving always uploads the document first if it is still local, then
 * creates or rewrites the draft; sending is that save followed by the
 * separate send call. A template is the same design with its people
 * stripped to role slots.
 */
@Suppress("TooManyFunctions") // One entry per user act on the editor.
internal class EditorFlow(private val store: EsignStore) {

    // ---------------------------------------------------------------- entrances

    fun startCompose() {
        if (store.refusesPost()) return
        store.update { copy(editor = fresh(), page = EsignPageKind.Editor) }
        store.requestPick(PickPurpose.Document)
    }

    /** "+ New Template": pick the PDF, then straight to placement with a Signer 1 slot. */
    fun startTemplate() {
        if (store.refusesPost()) return
        store.update {
            copy(
                editor = fresh().copy(
                    isTemplate = true,
                    recipients = listOf(EditorRules.placeholder(0)),
                    modeAsked = true,
                ),
                page = EsignPageKind.Editor,
            )
        }
        store.requestPick(PickPurpose.Document)
    }

    /** "Use" on a template card: the design becomes a draft the sender adds real people to. */
    fun useTemplate(template: EnvelopeTemplate) {
        if (store.refusesPost()) return
        val slots = template.recipients.ifEmpty { listOf(EditorRules.placeholder(0)) }.mapIndexed { index, slot ->
            slot.copy(
                name = slot.placeholderLabel.ifBlank { slot.name.ifBlank { "Signer ${index + 1}" } },
                email = "",
                userId = "",
                status = "created",
                placeholderLabel = slot.placeholderLabel.ifBlank { "Signer ${index + 1}" },
            )
        }
        store.update {
            copy(
                editor = fresh().copy(
                    fromTemplateName = template.name,
                    document = template.document,
                    fileName = template.document?.name.orEmpty(),
                    title = template.settings.emailSubject.ifBlank { template.name },
                    description = template.settings.emailBody.ifBlank { template.description },
                    recipients = slots,
                    fields = template.fields,
                    settings = template.settings,
                    placementMode = PlacementMode.fromWire(template.settings.placementMode),
                    modeAsked = true,
                ),
                page = EsignPageKind.Editor,
            )
        }
        template.document?.let { renderStored(it) }
    }

    fun editTemplate(template: EnvelopeTemplate) {
        if (store.refusesPost()) return
        val slots = template.recipients.ifEmpty { listOf(EditorRules.placeholder(0)) }.mapIndexed { index, slot ->
            slot.copy(name = slot.placeholderLabel.ifBlank { "Signer ${index + 1}" }, email = "", userId = "")
        }
        store.update {
            copy(
                editor = fresh().copy(
                    isTemplate = true,
                    templateId = template.id,
                    document = template.document,
                    fileName = template.document?.name.orEmpty(),
                    title = template.name,
                    description = template.description,
                    recipients = slots,
                    fields = template.fields,
                    settings = template.settings,
                    placementMode = PlacementMode.fromWire(template.settings.placementMode),
                    modeAsked = true,
                    step = EditorStep.Place,
                ),
                page = EsignPageKind.Editor,
            )
        }
        template.document?.let { renderStored(it) }
    }

    fun openDraft(envelope: Envelope) {
        store.update {
            copy(
                editor = fresh().copy(
                    envelopeId = envelope.id,
                    document = envelope.document,
                    fileName = envelope.document?.name.orEmpty(),
                    title = envelope.title,
                    description = envelope.description,
                    recipients = envelope.recipients,
                    fields = envelope.fields,
                    settings = envelope.settings,
                    placementMode = PlacementMode.fromWire(envelope.settings.placementMode),
                    modeAsked = true,
                    loadingDoc = envelope.document != null,
                ),
                page = EsignPageKind.Editor,
            )
        }
        store.runTask {
            // List rows are thin; the full draft carries its tabs.
            val full = store.orFail { store.repository.envelope(envelope.id) } ?: return@runTask
            store.update {
                copy(
                    editor = editor?.copy(
                        document = full.document,
                        fileName = full.document?.name.orEmpty(),
                        title = full.title,
                        description = full.description,
                        recipients = full.recipients,
                        fields = full.fields,
                        settings = full.settings,
                        placementMode = PlacementMode.fromWire(full.settings.placementMode),
                    ),
                )
            }
            full.document?.let { renderStored(it) }
        }
    }

    private fun fresh() = EditorState(
        options = store.signerOptions(),
        me = store.signerOptions().firstOrNull { it.userId == store.current.currentUserId },
        settings = EnvelopeSettings(reminderCadenceDays = null),
    )

    // ---------------------------------------------------------------- the document

    fun pickDocument() {
        if (store.refusesPost()) return
        store.requestPick(PickPurpose.Document)
    }

    fun documentPicked(name: String, bytes: ByteArray) {
        val editor = store.current.editor ?: return
        val pages = when (val rendered = store.pdf.renderPages(bytes, PAGE_RENDER_WIDTH)) {
            is ZillitResult.Failure -> {
                store.failed("Only PDF documents can be sent for e-signature.")
                return
            }
            is ZillitResult.Success -> rendered.data
        }
        val replacing = editor.hasDocument
        store.update {
            copy(
                editor = this.editor?.copy(
                    fileName = name,
                    pendingBytes = bytes,
                    document = null,
                    pages = pages,
                    loadingDoc = false,
                    title = this.editor.title.ifBlank { name.removeSuffix(".pdf").removeSuffix(".PDF") },
                    // A new document has its own geometry; the old fields no longer land anywhere.
                    fields = if (replacing) emptyList() else this.editor.fields,
                    step = if (this.editor.isTemplate) EditorStep.Place else this.editor.step,
                ),
            )
        }
    }

    /** A cancelled picker on a fresh compose leaves an editor with nothing in it — back out. */
    fun pickCancelled() {
        val editor = store.current.editor ?: return
        val untouched = !editor.hasDocument && editor.fields.isEmpty()
        val unsaved = editor.envelopeId == null && editor.templateId == null
        if (untouched && unsaved) {
            store.update { copy(editor = null, page = EsignPageKind.Lists) }
        }
    }

    private fun renderStored(stored: StoredFile) {
        store.update { copy(editor = editor?.copy(loadingDoc = true)) }
        store.runTask {
            val bytes = store.orFail { store.transfer.fetch(stored) } ?: run {
                store.update { copy(editor = editor?.copy(loadingDoc = false)) }
                return@runTask
            }
            when (val pages = store.pdf.renderPages(bytes, PAGE_RENDER_WIDTH)) {
                is ZillitResult.Failure -> {
                    store.update { copy(editor = editor?.copy(loadingDoc = false)) }
                    store.failed("The document could not be rendered.")
                }
                is ZillitResult.Success -> store.update {
                    copy(editor = editor?.copy(pages = pages.data, loadingDoc = false))
                }
            }
        }
    }

    // ---------------------------------------------------------------- steps

    fun goToPlace() {
        val editor = store.current.editor ?: return
        if (!editor.hasDocument) {
            store.failed("Please add a document to the envelope")
            return
        }
        if (!editor.isTemplate && editor.signers.isEmpty()) {
            store.failed("Please add at least one signer")
            return
        }
        if (editor.loadingDoc) {
            store.notice("The document is still rendering…")
            return
        }
        edit { copy(step = EditorStep.Place, selectedField = null, pending = null) }
    }

    fun goToPrepare() = edit { copy(step = EditorStep.Prepare, selectedField = null, pending = null) }

    fun choosePlacementMode(mode: PlacementMode) = edit {
        copy(
            placementMode = mode,
            modeAsked = true,
            settings = settings.copy(placementMode = mode.wire),
            armedSignerIndex = if (mode == PlacementMode.FastPlace) signerIndexes.firstOrNull() else null,
        )
    }

    // ---------------------------------------------------------------- recipients

    fun toggleSelfSign(on: Boolean) {
        val editor = store.current.editor ?: return
        val me = editor.me ?: return
        if (on) {
            addSigner(me.userId)
        } else {
            editor.recipients.indexOfFirst { it.isSigner && it.userId == me.userId }
                .takeIf { it >= 0 }
                ?.let { removeRecipient(it) }
        }
    }

    /** A crew member as a signer — filling the first placeholder slot when one is waiting. */
    fun addSigner(userId: String) {
        val editor = store.current.editor ?: return
        val option = editor.options.firstOrNull { it.userId == userId } ?: return
        if (editor.recipients.any { it.isSigner && it.userId == userId }) return
        val placeholderAt = editor.recipients.indexOfFirst { it.isSigner && it.isPlaceholder }
        val next = if (placeholderAt >= 0) {
            val slot = editor.recipients[placeholderAt]
            editor.recipients.toMutableList().also {
                it[placeholderAt] = EditorRules.signerFrom(option, slot.routingOrder).copy(placeholderLabel = "")
            }
        } else {
            editor.recipients + EditorRules.signerFrom(option, editor.signers.size + 1)
        }
        edit { copy(recipients = EditorRules.renumber(next), signerPickerOpen = false, pickerSearch = "") }
    }

    fun addCc(userId: String) {
        val editor = store.current.editor ?: return
        val option = editor.options.firstOrNull { it.userId == userId } ?: return
        if (editor.recipients.any { it.isCc && it.userId == userId }) return
        edit { copy(recipients = recipients + EditorRules.ccFrom(option), ccPickerOpen = false, pickerSearch = "") }
    }

    fun addExternal() {
        val editor = store.current.editor ?: return
        val draft = editor.external ?: return
        if (!draft.emailValid) {
            store.failed("Enter a valid email address.")
            return
        }
        if (editor.recipients.any { it.email.equals(draft.email.trim(), true) && it.role == draft.role }) {
            store.failed("That person is already on the envelope.")
            return
        }
        val person = EditorRules.external(draft, editor.signers.size + 1)
        val placeholderAt = if (person.isSigner) {
            editor.recipients.indexOfFirst { it.isSigner && it.isPlaceholder }
        } else {
            -1
        }
        val next = if (placeholderAt >= 0) {
            editor.recipients.toMutableList().also {
                it[placeholderAt] = person.copy(routingOrder = it[placeholderAt].routingOrder)
            }
        } else {
            editor.recipients + person
        }
        edit { copy(recipients = EditorRules.renumber(next), external = null) }
    }

    fun removeRecipient(index: Int) {
        val editor = store.current.editor ?: return
        if (index !in editor.recipients.indices) return
        val next = editor.recipients.toMutableList().also { it.removeAt(index) }
        edit {
            copy(
                recipients = EditorRules.renumber(next),
                fields = EditorRules.fieldsAfterRemoving(fields, index),
                armedSignerIndex = armedSignerIndex?.let { a -> if (a == index) null else if (a > index) a - 1 else a },
                selectedField = null,
            )
        }
    }

    /** Signers reorder among themselves; fields follow their owners. */
    fun moveRecipient(index: Int, up: Boolean) {
        val editor = store.current.editor ?: return
        val signerIndexes = editor.signerIndexes
        val position = signerIndexes.indexOf(index)
        if (position < 0) return
        val other = signerIndexes.getOrNull(if (up) position - 1 else position + 1) ?: return
        val next = editor.recipients.toMutableList().also {
            val tmp = it[index]
            it[index] = it[other]
            it[other] = tmp
        }
        edit {
            copy(recipients = EditorRules.renumber(next), fields = EditorRules.fieldsAfterSwap(fields, index, other))
        }
    }

    // ---------------------------------------------------------------- placement

    fun pageClicked(page: Int, xPt: Double, yPt: Double) {
        val editor = store.current.editor ?: return
        // A click while a popover is up only dismisses it; a click while a
        // field is selected still places — fast place is "drop multiples
        // without re-arming", and the selection simply moves to the new one.
        if (editor.pending != null) {
            edit { copy(pending = null) }
            return
        }
        var signers = editor.signerIndexes
        if (signers.isEmpty()) {
            if (!editor.isTemplate) {
                store.failed("Add a signer to place fields.")
                return
            }
            // Template authoring lazily seeds "Signer 1" on the first click.
            edit { copy(recipients = recipients + EditorRules.placeholder(0)) }
            signers = listOf(store.current.editor?.recipients?.lastIndex ?: 0)
        }
        when (editor.placementMode) {
            PlacementMode.FastPlace -> {
                val owner = editor.armedSignerIndex ?: signers.first()
                place(page, xPt, yPt, editor.armedType, listOf(owner))
            }
            PlacementMode.Manual -> edit {
                copy(pending = PendingPlacement(page, xPt, yPt, setOf(armedSignerIndex ?: signers.first())))
            }
        }
    }

    fun placePending(type: FieldType) {
        val pending = store.current.editor?.pending ?: return
        place(pending.page, pending.xPt, pending.yPt, type, pending.signerIndexes.sorted())
        edit { copy(pending = null, armedType = type) }
    }

    private fun place(page: Int, xPt: Double, yPt: Double, type: FieldType, owners: List<Int>) {
        val editor = store.current.editor ?: return
        val rendered = editor.pages.firstOrNull { it.page == page } ?: return
        val placed = EditorRules.place(type, rendered, xPt, yPt, owners) { store.newId().take(OPTION_ID_LENGTH) }
        edit { copy(fields = fields + placed, selectedField = fields.size, invalidFields = emptySet()) }
    }

    fun updateField(index: Int, transform: (EnvelopeField) -> EnvelopeField) = edit {
        if (index !in fields.indices) return@edit this
        copy(
            fields = fields.toMutableList().also { it[index] = transform(it[index]) },
            invalidFields = invalidFields - index,
        )
    }

    fun moveField(index: Int, dx: Double, dy: Double) {
        val editor = store.current.editor ?: return
        val field = editor.fields.getOrNull(index) ?: return
        val page = editor.pages.firstOrNull { it.page == field.page } ?: return
        updateField(index) { EditorRules.moved(it, page, dx, dy) }
    }

    fun resizeField(index: Int, dw: Double, dh: Double) {
        val editor = store.current.editor ?: return
        val field = editor.fields.getOrNull(index) ?: return
        val page = editor.pages.firstOrNull { it.page == field.page } ?: return
        updateField(index) { EditorRules.resized(it, page, dw, dh) }
    }

    fun deleteField(index: Int) = edit {
        if (index !in fields.indices) return@edit this
        copy(fields = fields.filterIndexed { i, _ -> i != index }, selectedField = null, invalidFields = emptySet())
    }

    fun duplicateField(index: Int) = edit {
        val source = fields.getOrNull(index) ?: return@edit this
        val page = pages.firstOrNull { it.page == source.page } ?: return@edit this
        val copy = EditorRules.moved(
            source.copy(id = "", autoInitial = false),
            page,
            DUPLICATE_OFFSET,
            DUPLICATE_OFFSET,
        )
        copy(fields = fields + copy, selectedField = fields.size)
    }

    fun setInitialsOnAllPages(on: Boolean) = edit {
        copy(
            settings = settings.copy(initialsOnAllPages = on),
            fields = EditorRules.withAutoInitials(fields, pages, signerIndexes, on),
        )
    }

    // ---------------------------------------------------------------- saving

    fun saveDraft(lists: ListsFlow) {
        val editor = store.current.editor ?: return
        if (store.refusesPost()) return
        EditorRules.problemBeforeDraft(editor)?.let { problem ->
            edit { copy(invalidFields = problem.invalidFields) }
            store.failed(problem.text)
            return
        }
        store.runTask {
            val saved = persist(editor) ?: return@runTask
            store.update {
                copy(
                    editor = null,
                    page = EsignPageKind.Lists,
                    surface = EsignSurface.Manage,
                    manage = manage.copy(outer = ManageOuterTab.Active, inner = ManageInnerTab.Draft),
                )
            }
            store.notice(if (saved.status.wire == "draft") "Envelope saved as draft" else "Envelope saved")
            lists.loadManage()
        }
    }

    fun requestSend() {
        val editor = store.current.editor ?: return
        if (store.refusesPost()) return
        EditorRules.problemBeforeSend(editor)?.let { problem ->
            edit { copy(invalidFields = problem.invalidFields) }
            store.failed(problem.text)
            return
        }
        edit { copy(confirmSend = true) }
    }

    fun confirmSend(lists: ListsFlow) {
        val editor = store.current.editor ?: return
        edit { copy(confirmSend = false) }
        store.runTask {
            val saved = persist(editor) ?: return@runTask
            when (val sent = store.repository.send(saved.id)) {
                is ZillitResult.Failure -> {
                    store.update { copy(editor = this.editor?.copy(saving = false, envelopeId = saved.id)) }
                    store.failed("Saved as a draft, but sending failed: ${sent.error.userMessage}")
                }
                is ZillitResult.Success -> {
                    store.update {
                        copy(
                            editor = null,
                            page = EsignPageKind.Lists,
                            surface = EsignSurface.Manage,
                            manage = manage.copy(outer = ManageOuterTab.Active, inner = ManageInnerTab.Sent),
                        )
                    }
                    store.notice(
                        if (editor.selfSigns) {
                            "Envelope sent — you'll counter-sign from Sign Documents once the other signers have signed"
                        } else {
                            "Envelope sent for signing"
                        },
                    )
                    lists.loadBoth()
                }
            }
        }
    }

    /** Uploads a local document if there is one, then creates or rewrites the draft. */
    private suspend fun persist(editor: EditorState): Envelope? {
        edit { copy(saving = true) }
        val document = uploadIfLocal(editor) ?: run {
            edit { copy(saving = false) }
            return null
        }
        // The service refuses a recipient without an address, so a slot nobody
        // has filled stays out of the draft — and the fields pointing at it with it.
        val (people, fields) = stripPlaceholders(editor.recipients, editor.fields)
        val draft = EnvelopeDraft(
            title = editor.title.ifBlank { editor.fileName.removeSuffix(".pdf") },
            description = editor.description,
            document = document,
            recipients = people,
            fields = fields,
            settings = editor.settings.copy(
                emailSubject = editor.settings.emailSubject.ifBlank { editor.title },
                emailBody = editor.settings.emailBody.ifBlank { editor.description },
                placementMode = editor.placementMode.wire,
            ),
        )
        val answer = if (editor.envelopeId != null) {
            store.repository.update(editor.envelopeId, draft)
        } else {
            store.repository.create(draft)
        }
        return when (answer) {
            is ZillitResult.Failure -> {
                edit { copy(saving = false) }
                store.failed(answer.error.userMessage)
                null
            }
            is ZillitResult.Success -> {
                // A second save must rewrite, not duplicate.
                edit { copy(envelopeId = answer.data.id, document = document, pendingBytes = null) }
                answer.data
            }
        }
    }

    private suspend fun uploadIfLocal(editor: EditorState): StoredFile? {
        val bytes = editor.pendingBytes
        val fileName = editor.fileName.ifBlank { "${editor.title.ifBlank { "document" }}.pdf" }.let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }
        if (bytes == null) {
            return editor.document?.copy(pageCount = maxOf(editor.document.pageCount, editor.pages.size))
                ?: run { store.failed("Please add a document to the envelope"); null }
        }
        val stored = store.orFail { store.transfer.store(fileName, "application/pdf", bytes) } ?: return null
        return stored.copy(name = fileName, pageCount = editor.pages.size, sizeBytes = bytes.size.toLong())
    }

    // ---------------------------------------------------------------- templates

    fun openSaveAsTemplate() {
        val editor = store.current.editor ?: return
        if (store.refusesPost()) return
        EditorRules.optionFieldsMissingLabels(editor.fields).takeIf { it.isNotEmpty() }?.let { bad ->
            edit { copy(invalidFields = bad) }
            store.failed("Fill in every option label before saving a template.")
            return
        }
        if (!editor.hasDocument) {
            store.failed("Please add a document first")
            return
        }
        edit {
            copy(
                saveAsTemplate = SaveTemplateDraft(
                    name = if (isTemplate) title else "",
                    description = if (isTemplate) description else "",
                ),
            )
        }
        loadCategories()
    }

    private fun loadCategories() {
        store.runTask {
            val categories = store.repository.templateCategories().getOrNull()
                ?: store.current.templates.categories
            edit { copy(categories = categories.distinct().sorted()) }
        }
    }

    fun confirmSaveAsTemplate(templates: TemplatesFlow) {
        val editor = store.current.editor ?: return
        val sheet = editor.saveAsTemplate ?: return
        if (sheet.name.isBlank()) {
            store.failed("Give the template a name.")
            return
        }
        edit { copy(saveAsTemplate = sheet.copy(saving = true)) }
        store.runTask {
            val document = uploadIfLocal(editor) ?: run {
                edit { copy(saveAsTemplate = sheet.copy(saving = false)) }
                return@runTask
            }
            val draft = TemplateDraft(
                name = sheet.name.trim(),
                description = sheet.description.trim(),
                category = sheet.category.trim(),
                documents = listOf(document),
                slots = editor.recipients.ifEmpty { listOf(EditorRules.placeholder(0)) },
                fields = editor.fields,
                settings = editor.settings.copy(
                    emailSubject = editor.settings.emailSubject.ifBlank { editor.title },
                    emailBody = editor.settings.emailBody.ifBlank { editor.description },
                    placementMode = editor.placementMode.wire,
                ),
                fromEnvelopeId = editor.envelopeId?.takeIf { !editor.isTemplate },
            )
            val answer = editor.templateId?.let { store.repository.updateTemplate(it, draft) }
                ?: store.repository.createTemplate(draft)
            when (answer) {
                is ZillitResult.Failure -> {
                    edit { copy(saveAsTemplate = sheet.copy(saving = false)) }
                    store.failed(answer.error.userMessage)
                }
                is ZillitResult.Success -> {
                    if (editor.isTemplate) {
                        store.update {
                            copy(editor = null, page = EsignPageKind.Lists, surface = EsignSurface.Templates)
                        }
                        store.notice(if (editor.templateId != null) "Template updated" else "Template saved")
                    } else {
                        edit { copy(saveAsTemplate = null, document = document, pendingBytes = null) }
                        store.notice("Saved as template “${sheet.name.trim()}” — find it under Templates.")
                    }
                    templates.load()
                }
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    fun edit(transform: EditorState.() -> EditorState) = store.update { copy(editor = editor?.transform()) }

    fun cancel() = store.update { copy(editor = null, page = EsignPageKind.Lists) }

    private companion object {
        const val OPTION_ID_LENGTH = 8
        const val DUPLICATE_OFFSET = 16.0
    }
}

/**
 * Recipients a real save may carry — a template's slots never leave the
 * editor. Fields are re-pointed at their owners' new positions; a field
 * whose owner was a slot goes with it.
 */
internal fun stripPlaceholders(
    recipients: List<EnvelopeRecipient>,
    fields: List<EnvelopeField>,
): Pair<List<EnvelopeRecipient>, List<EnvelopeField>> {
    val kept = recipients.withIndex().filter { !it.value.isPlaceholder }
    val newIndex = kept.withIndex().associate { (position, indexed) -> indexed.index to position }
    val movedFields = fields.mapNotNull { field ->
        newIndex[field.recipientIndex]?.let { field.copy(recipientIndex = it) }
    }
    return EditorRules.renumber(kept.map { it.value }) to movedFields
}

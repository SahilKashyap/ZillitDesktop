package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DocDistSignature
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.ListUsed
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.MAX_TOTAL_ATTACHMENT_BYTES
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.patchAgainst
import com.zillit.desktop.feature.documentdistribution.domain.withDefaults
import com.zillit.desktop.feature.documentdistribution.domain.summariseFileNames

/**
 * The send dialog's handlers.
 *
 * ## Watermarking is the point
 *
 * Every watermark-capable attachment arrives ticked, and the checkbox is an
 * *opt out*. That is the web's behaviour (ZL-19547) and the reason this tool
 * exists rather than people mailing files from Outlook: a script that leaves
 * the production unstamped cannot be traced when it leaks.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class ComposerSection(private val vm: VmScope, private val library: LibrarySection) {

    // -- opening -------------------------------------------------------------

    fun composeSelection() {
        if (vm.refusesWrite()) return
        val folder = vm.state.singleSelectedFolder()
        if (vm.state.selectedFolderIds.isEmpty()) {
            open(attachments = vm.state.selectedDocuments, folder = null)
            return
        }
        library.prepare(library::resolveSelection) { documents -> open(attachments = documents, folder = folder) }
    }

    fun composeBlank() {
        if (vm.refusesWrite()) return
        open(attachments = emptyList(), folder = null)
    }

    fun distributeDocument(documentId: String) {
        if (vm.refusesWrite()) return
        val document = vm.state.documents.firstOrNull { it.id == documentId }
            ?: vm.state.preview?.document?.takeIf { it.id == documentId }
            ?: return
        vm.update { copy(preview = null) }
        open(attachments = listOf(document), folder = null)
    }

    fun distributeFolder(folderId: String) {
        if (vm.refusesWrite()) return
        val folder = vm.state.folders.firstOrNull { it.id == folderId } ?: return
        library.prepare({ library.resolveFolder(folderId) }) { documents -> open(
            attachments = documents,
            folder = folder,
        ) }
    }

    fun composeTo(email: String) {
        if (vm.refusesWrite()) return
        open(attachments = emptyList(), folder = null, to = listOf(recipientFor(email)))
        vm.update { copy(destination = DocDistDestination.Library) }
    }

    fun composeWithList(listId: String) {
        if (vm.refusesWrite()) return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        open(attachments = emptyList(), folder = null, to = list.recipients, listId = listId)
        vm.update {
            copy(
                destination = DocDistDestination.Library,
                composer = composer.copy(
                    listsUsed = listOf(ListUsed(list.id, list.name, list.recipients.map { it.email })),
                ),
            )
        }
    }

    private fun open(
        attachments: List<LibraryDocument>,
        folder: LibraryFolder?,
        to: List<Recipient> = emptyList(),
        listId: String? = null,
    ) {
        vm.update {
            copy(
                composer = ComposerState(
                    open = true,
                    folder = folder,
                    subject = folder?.let { "Documents — ${it.name}" }.orEmpty(),
                    to = to,
                    listId = listId,
                    attachments = attachments,
                    watermarked = attachments.filter { it.isWatermarkable }.map { it.id }.toSet(),
                    watermark = WatermarkStyle().withDefaults(watermarkDefaults),
                ),
            )
        }
        prime()
    }

    /** Lists, contacts, templates and signatures — none loaded unless their tabs were visited. */
    @Suppress("CyclomaticComplexMethod") // Four independent primes; one per data set.
    private fun prime() {
        vm.run {
            if (vm.state.lists.isEmpty()) {
                (vm.repository.lists() as? ZillitResult.Success)?.let { loaded ->
                    vm.update { copy(lists = loaded.data) }
                }
            }
        }
        vm.run {
            if (vm.state.contacts.isEmpty()) {
                (vm.repository.contacts() as? ZillitResult.Success)?.let { loaded ->
                    vm.update { copy(contacts = loaded.data) }
                }
            }
        }
        vm.run {
            if (vm.state.templates.isEmpty()) {
                (vm.repository.templates() as? ZillitResult.Success)?.let { loaded ->
                    vm.update { copy(templates = loaded.data) }
                }
            }
        }
        vm.run {
            val signatures = vm.host.signatures()
            // The one marked for new mail, else the only one — a person with a
            // single signature meant it to be used; else the platform's own.
            val primary = signatures.firstOrNull { it.useForNew } ?: signatures.singleOrNull() ?: SYSTEM_SIGNATURE
            edit {
                if (!open) this
                else copy(signatures = signatures, signature = if (signatureSuppressed) signature else primary)
            }
        }
    }

    fun close() {
        // Device uploads that were never sent are the composer's to clean up;
        // a reused row belongs to the past send it came from.
        val orphaned = vm.state.composer.attachments.filter { it.isEphemeral && !it.reused }
        vm.update { copy(composer = ComposerState()) }
        if (orphaned.isNotEmpty()) vm.run { orphaned.forEach { vm.repository.deleteEphemeral(it.id) } }
    }

    private inline fun edit(crossinline change: ComposerState.() -> ComposerState) =
        vm.update { copy(composer = composer.change()) }

    // -- addresses ---------------------------------------------------------

    fun addresses(field: AddressField, tokens: List<String>, input: String) = edit {
        val recipients = tokens.map(::recipientFor).distinctBy { it.email.lowercase() }
        when (field) {
            AddressField.To -> copy(to = recipients, toInput = input)
            AddressField.Cc -> copy(cc = recipients, ccInput = input)
            AddressField.Bcc -> copy(bcc = recipients, bccInput = input)
        }
    }

    /** A typed address, named from the address book when it is known there. */
    private fun recipientFor(raw: String): Recipient {
        val email = raw.trim().trim('<', '>')
        val contact = vm.state.contacts.firstOrNull { it.email.equals(email, ignoreCase = true) }
        return Recipient(email = email, name = contact?.name.orEmpty(), jobTitle = contact?.jobTitle.orEmpty())
    }

    fun addList(listId: String) {
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        val existing = vm.state.composer.to.map { it.email.lowercase() }.toSet()
        val additions = list.recipients.filter { it.email.lowercase() !in existing }
        edit {
            val used = listsUsed.toMutableList()
            val at = used.indexOfFirst { it.id == list.id }
            val entry = ListUsed(list.id, list.name, additions.map { it.email })
            if (additions.isNotEmpty()) {
                if (at < 0) used += entry else used[at] = used[at].copy(
                    emails = (used[at].emails + entry.emails).distinct(),
                )
            }
            copy(to = to + additions, listId = listId, listsUsed = used, listMenuOpen = false)
        }
        vm.notice("Added ${additions.size} from \"${list.name}\"")
    }

    // -- message -------------------------------------------------------------

    fun applyTemplate(templateId: String) {
        val template = vm.state.templates.firstOrNull { it.id == templateId } ?: return
        edit { copy(
            subject = template.subject,
            body = HtmlText.toPlainText(template.bodyHtml),
            templateMenuOpen = false,
        ) }
        vm.notice("Loaded \"${template.name}\"")
    }

    fun insertSignature(signatureId: String) {
        val chosen = vm.state.composer.signatures.firstOrNull { it.id == signatureId }
            ?: SYSTEM_SIGNATURE.takeIf { it.id == signatureId }
        edit { copy(signature = chosen, signatureMenuOpen = false) }
        chosen?.let { vm.notice("Inserted \"${it.title}\"") }
    }

    fun saveCurrentAsTemplate() {
        val composer = vm.state.composer
        vm.update {
            copy(
                templateEditor = TemplateEditorState(
                    subject = composer.subject,
                    body = composer.body,
                    fromComposer = true,
                ),
                composer = composer.copy(templateMenuOpen = false),
            )
        }
    }

    // -- attachments -----------------------------------------------------------

    fun toggleWatermark(documentId: String) = edit {
        val document = attachments.firstOrNull { it.id == documentId }
        if (document?.isWatermarkable != true) this else copy(watermarked = watermarked.toggled(documentId))
    }

    fun removeAttachment(documentId: String) {
        val removed = vm.state.composer.attachments.firstOrNull { it.id == documentId } ?: return
        edit { copy(
            attachments = attachments.filterNot { it.id == documentId },
            watermarked = watermarked - documentId,
        ) }
        if (removed.isEphemeral && !removed.reused) vm.run { vm.repository.deleteEphemeral(removed.id) }
    }

    /** Order matters: the mail attaches the files in this sequence. */
    fun moveAttachment(documentId: String, delta: Int) = edit {
        val from = attachments.indexOfFirst { it.id == documentId }
        val to = from + delta
        if (from < 0 || to < 0 || to >= attachments.size) this
        else copy(attachments = attachments.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun addFromPicker(documents: List<LibraryDocument>) = edit {
        val existing = attachments.map { it.id }.toSet()
        val additions = documents.filter { it.id !in existing }
        copy(
            attachments = attachments + additions,
            watermarked = watermarked + additions.filter { it.isWatermarkable }.map { it.id },
        )
    }

    fun pickAndAttach() {
        if (vm.state.composer.uploading != null) return
        vm.run { attachFiles(vm.host.pickFiles()) }
    }

    fun attachDropped(files: List<LocalFile>) {
        if (!vm.state.composer.open) return
        vm.run { attachFiles(files) }
    }

    private suspend fun attachFiles(raw: List<LocalFile>) {
        if (raw.isEmpty()) return
        val (accepted, rejected) = SupportedUploads.partition(raw)
        if (rejected.isNotEmpty()) {
            vm.fail("Skipped ${summariseFileNames(rejected.map { it.name })} — unsupported file type.")
        }
        if (accepted.isEmpty()) return
        val base = vm.state.composer.totalBytes
        val incoming = accepted.sumOf { it.sizeBytes }
        if (base + incoming <= MAX_TOTAL_ATTACHMENT_BYTES) {
            uploadEphemeral(accepted)
            return
        }
        // Over the cap: let the user choose rather than silently skipping,
        // pre-ticking greedily so the dialog opens with a valid subset.
        var running = base
        val preselected = mutableSetOf<Int>()
        accepted.forEachIndexed { index, file ->
            if (running + file.sizeBytes <= MAX_TOTAL_ATTACHMENT_BYTES) {
                running += file.sizeBytes
                preselected += index
            }
        }
        edit { copy(oversize = OversizeChooser(accepted, base, preselected)) }
    }

    fun toggleOversize(index: Int) = edit { copy(
        oversize = oversize?.let { it.copy(selected = it.selected.toggled(index)) },
    ) }

    fun confirmOversize() {
        val chooser = vm.state.composer.oversize ?: return
        if (chooser.over || chooser.selected.isEmpty()) return
        val picked = chooser.files.filterIndexed { index, _ -> index in chooser.selected }
        edit { copy(oversize = null) }
        vm.run { uploadEphemeral(picked) }
    }

    private suspend fun uploadEphemeral(files: List<LocalFile>) {
        var attached = 0
        try {
            files.forEachIndexed { index, file ->
                edit { copy(uploading = UploadProgress(UploadProgress.Stage.Uploading, file.name, index, files.size)) }
                when (val result = vm.repository.uploadEphemeral(file)) {
                    is ZillitResult.Success -> {
                        attached++
                        addFromPicker(listOf(result.data))
                    }
                    is ZillitResult.Failure -> vm.fail("Failed to upload ${file.name}: ${result.error.userMessage}")
                }
            }
        } finally {
            edit { copy(uploading = null) }
        }
        if (attached > 0) vm.notice("Attached ${plural(attached, "file")}")
    }

    // -- watermark wizard --------------------------------------------------------

    fun openWizard() = edit { copy(wizardDraft = watermark) }

    fun saveWizard() {
        val draft = vm.state.composer.wizardDraft ?: return
        if (draft.line1 == WatermarkLine.Custom && draft.line1Custom.isBlank()) {
            return vm.fail("Enter the custom text for line 1")
        }
        if (draft.line2 == WatermarkLine.Custom && draft.line2Custom.isBlank()) {
            return vm.fail("Enter the custom text for line 2")
        }
        edit { copy(watermark = draft, wizardDraft = null) }
        shareAppearance(draft)
    }

    /**
     * The wizard's Size / Colour / Opacity are the production's, not the
     * send's: saving them here makes them every crew member's starting point
     * (`PUT watermark-settings`). Only the fields that changed go, so two
     * people adjusting different controls at once both land. The send itself
     * is not held up — a refusal is reported and the stamp stays as drawn.
     */
    private fun shareAppearance(draft: WatermarkStyle) {
        val patch = draft.patchAgainst(vm.state.watermarkDefaults)
        if (patch.isEmpty) return
        vm.run {
            vm.onSuccess(vm.repository.updateWatermarkSettings(patch)) { saved ->
                vm.update { copy(watermarkDefaults = saved) }
            }
        }
    }

    fun openWatermarkPreview(documentId: String?) {
        val document = documentId?.let { id -> vm.state.composer.attachments.firstOrNull { it.id == id } }
        edit { copy(watermarkPreview = document) }
        if (document == null) return
        vm.update { copy(preview = PreviewState(document)) }
        library.openPreview(document.id)
    }

    // -- send ----------------------------------------------------------------

    fun send() {
        val state = vm.state
        val composer = state.composer
        if (composer.uploading != null) return vm.fail("Please wait for the attachment to finish uploading")
        val draft = composer.draft(
            replyTo = state.viewer.userEmail.takeIf { it.isNotBlank() },
            folderId = composer.folder?.id,
        ).let { built ->
            // The body travels as HTML with the sign-off beneath it.
            val html = HtmlText.withSignature(
                HtmlText.plainToHtml(composer.body),
                composer.signature?.bodyHtml.orEmpty(),
            )
            // Only list-contributed addresses still in the To field count —
            // the user may have removed some — and a list left with none is dropped.
            val sentTo = built.to.map { it.email.lowercase() }.toSet()
            built.copy(
                bodyHtml = html,
                listsUsed = composer.listsUsed
                    .map { used -> used.copy(emails = used.emails.filter { it.lowercase() in sentTo }) }
                    .filter { it.emails.isNotEmpty() },
            )
        }
        draft.validationError()?.let { return vm.fail(it) }
        // Checked here as well as on the button, which gates its own press:
        // the composer can be left open across a rights change.
        if (!state.viewer.canPost) return vm.askForRights(RightsKind.Post)

        val summary = "Sending to ${draft.to.first().name.ifBlank { draft.to.first().email }}" +
            (if (draft.to.size > 1) " +${draft.to.size - 1}" else "")
        // Close at once and keep sending: a slow request must not hold the
        // window. The outcome lands as a notice either way.
        vm.update { copy(composer = ComposerState(), selectedDocumentIds = emptySet(), selectedFolderIds = emptySet()) }
        vm.notice("$summary…")
        vm.run {
            when (val result = vm.repository.send(draft)) {
                is ZillitResult.Success -> {
                    vm.notice("Email sent to ${plural(draft.to.size, "recipient")}")
                    if (vm.state.destination == DocDistDestination.History) vm.reload()
                }
                is ZillitResult.Failure -> vm.fail("Send failed: ${result.error.userMessage}")
            }
        }
    }

    // -- duplicate ---------------------------------------------------------

    /**
     * Re-opens the composer from a past send: same subject, body and
     * attachments, blank recipients. Attachments are re-hydrated by id —
     * a distribution stores only names and ids — and any since deleted or
     * swept are dropped and said so.
     */
    fun duplicate(distributionId: String) {
        if (vm.refusesWrite()) return
        val past = vm.state.historyDetail?.distribution?.takeIf { it.id == distributionId }
            ?: vm.state.history.firstOrNull { it.id == distributionId }
            ?: vm.state.contactDistributions.firstOrNull { it.id == distributionId }
            ?: vm.state.viewingEmail?.takeIf { it.id == distributionId }
            ?: return
        vm.update { copy(busy = "Opening composer…", viewingEmail = null) }
        vm.run {
            val rehydrated = rehydrate(past)
            vm.update { copy(busy = null) }
            open(attachments = rehydrated, folder = null)
            edit {
                copy(
                    subject = past.subject.takeIf { it != "(no subject)" }.orEmpty(),
                    body = HtmlText.toPlainText(past.bodyHtml),
                    // A duplicated body already carries its sign-off.
                    signature = null,
                    signatureSuppressed = true,
                    watermarked = past.attachments.filter { it.watermarked }.map { it.documentId }.toSet()
                        .intersect(rehydrated.filter { it.isWatermarkable }.map { it.id }.toSet()),
                    watermark = past.watermark ?: watermark,
                )
            }
            vm.update { copy(destination = DocDistDestination.Library) }
            val dropped = past.attachments.count { it.documentId.isNotBlank() } - rehydrated.size
            if (dropped > 0) {
                vm.notice(
                    "Copied subject, message and ${plural(rehydrated.size, "attachment")}. " +
                        "${plural(dropped, "attachment")} no longer available and not carried over.",
                )
            }
        }
    }

    private suspend fun rehydrate(past: Distribution): List<LibraryDocument> {
        val all = past.attachments.filter { it.documentId.isNotBlank() }
        val libraryIds = all.filterNot { it.isEphemeral }.map { it.documentId }
        val ephemeralIds = all.filter { it.isEphemeral }.map { it.documentId }
        val library = (vm.repository.documentsByIds(libraryIds) as? ZillitResult.Success)?.data.orEmpty()
            .associateBy { it.id }
        val ephemeral = (vm.repository.ephemeralByIds(ephemeralIds) as? ZillitResult.Success)?.data.orEmpty()
            .associateBy { it.id }
        // Rebuilt in the original send order; a reused device upload must
        // not be deleted when removed here — the bytes are the past send's.
        return all.mapNotNull { sent ->
            if (sent.isEphemeral) ephemeral[sent.documentId]?.copy(isEphemeral = true, reused = true)
            else library[sent.documentId]
        }
    }

    companion object {
        /** The platform's own sign-off, as the mail composer's — the phones append theirs. */
        val SYSTEM_SIGNATURE = DocDistSignature(
            id = "system-default",
            title = "Default",
            bodyHtml = "Sent from Desktop",
        )
    }
}

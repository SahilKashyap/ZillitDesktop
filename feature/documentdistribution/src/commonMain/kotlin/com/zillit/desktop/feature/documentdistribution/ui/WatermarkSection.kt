package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.ZipRecipient
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail
import com.zillit.desktop.feature.documentdistribution.domain.parseAddressList
import com.zillit.desktop.feature.documentdistribution.domain.watermarkedFilename
import com.zillit.desktop.feature.documentdistribution.domain.withDefaults

/**
 * The two download-side watermark flows — one stamped copy, and a zip of
 * personalised copies — plus the library picker both the zip flow and the
 * composer attach from.
 *
 * Both flows are downloads under the rights model and gate on download
 * rights; stamping happens on the server, which fetches the source and
 * streams the result back.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class WatermarkSection(
    private val vm: VmScope,
    private val library: LibrarySection,
    private val composer: ComposerSection,
) {

    // -- single download -----------------------------------------------------

    fun openDownload(documentId: String) {
        if (vm.refusesDownload()) return
        val document = vm.state.documents.firstOrNull { it.id == documentId }
            ?: vm.state.preview?.document?.takeIf { it.id == documentId }
            ?: return
        vm.update {
            copy(
                preview = null,
                watermarkDownload = WatermarkDownloadState(
                    document,
                    style = WatermarkStyle().withDefaults(watermarkDefaults),
                    previewLoading = document.isWatermarkable,
                ),
            )
        }
        if (!document.isWatermarkable) return
        vm.run {
            // The original, once, behind the stamp: a PDF's first page or the
            // image itself. Any failure leaves a text-only preview.
            val image = (vm.repository.documentBytes(document) as? ZillitResult.Success)?.data?.let { bytes ->
                if (fileKindOf(document.contentType, document.name) == FileKind.Pdf) {
                    (vm.host.renderPdfPages(bytes, PREVIEW_WIDTH) as? ZillitResult.Success)?.data?.firstOrNull()
                } else {
                    bytes
                }
            }
            vm.update {
                if (watermarkDownload?.document?.id != document.id) this
                else copy(watermarkDownload = watermarkDownload.copy(previewImage = image, previewLoading = false))
            }
        }
    }

    fun editDownload(change: WatermarkDownloadState.() -> WatermarkDownloadState) =
        vm.update { copy(watermarkDownload = watermarkDownload?.change()) }

    fun confirmDownload() {
        val open = vm.state.watermarkDownload ?: return
        if (!open.document.isWatermarkable || open.downloading) return
        if (vm.refusesDownload()) return
        editDownload { copy(downloading = true) }
        vm.run {
            when (val stamped = vm.repository.watermarkedCopy(open.document.id, open.stampText, open.style)) {
                is ZillitResult.Success -> {
                    vm.update { copy(watermarkDownload = null) }
                    library.saveAndOpen(watermarkedFilename(open.document.name), stamped.data)
                }
                is ZillitResult.Failure -> {
                    editDownload { copy(downloading = false) }
                    vm.report(stamped.error)
                }
            }
        }
    }

    // -- batch zip ---------------------------------------------------------------

    fun openBatch() {
        if (vm.refusesDownload()) return
        library.prepare(library::resolveSelection) { documents ->
            val stampable = documents.filter { it.isWatermarkable }
            if (stampable.isEmpty()) return@prepare vm.fail("No watermarkable files in the selection")
            vm.update {
                copy(
                    watermarkBatch = WatermarkBatchState(
                        documents = stampable,
                        style = WatermarkStyle().withDefaults(watermarkDefaults),
                    ),
                )
            }
            primeLists()
        }
    }

    private fun primeLists() {
        vm.run {
            if (vm.state.lists.isEmpty()) {
                (vm.repository.lists() as? ZillitResult.Success)?.let { l -> vm.update { copy(lists = l.data) } }
            }
            if (vm.state.contacts.isEmpty()) {
                (vm.repository.contacts() as? ZillitResult.Success)?.let { c -> vm.update { copy(contacts = c.data) } }
            }
        }
    }

    private inline fun editBatch(crossinline change: WatermarkBatchState.() -> WatermarkBatchState) =
        vm.update { copy(watermarkBatch = watermarkBatch?.change()) }

    fun removeBatchDocument(documentId: String) = editBatch { copy(
        documents = documents.filterNot { it.id == documentId },
    ) }

    fun editBatchStyle(style: WatermarkStyle) = editBatch { copy(style = style) }

    fun editBatchInput(text: String) = editBatch { copy(recipientInput = text) }

    fun addBatchRecipient() {
        val batch = vm.state.watermarkBatch ?: return
        val typed = parseAddressList(batch.recipientInput).firstOrNull() ?: return
        // `a@b.com, Name` is accepted too — the web's second form.
        val (email, name) = if (isValidEmail(typed.email)) typed.email to typed.name else {
            val parts = batch.recipientInput.split(',').map { it.trim() }
            val address = parts.firstOrNull { isValidEmail(it) }
            if (address == null) return vm.fail("\"${batch.recipientInput.trim()}\" is not a valid email")
            address to parts.firstOrNull { it != address }.orEmpty()
        }
        addRecipient(Recipient(email = email, name = name.ifBlank { email.substringBefore('@') }))
        editBatch { copy(recipientInput = "") }
    }

    fun addBatchContact(email: String) {
        val contact = vm.state.contacts.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        addRecipient(Recipient(contact.email, contact.name.ifBlank { contact.email.substringBefore('@') }))
        editBatch { copy(recipientInput = "") }
    }

    private fun addRecipient(recipient: Recipient) {
        val batch = vm.state.watermarkBatch ?: return
        if (batch.recipients.any { it.email.equals(recipient.email, ignoreCase = true) }) return vm.notice(
            "Already in the list",
        )
        editBatch { copy(recipients = recipients + recipient) }
    }

    fun removeBatchRecipient(email: String) =
        editBatch { copy(recipients = recipients.filterNot { it.email.equals(email, ignoreCase = true) }) }

    fun addBatchList(listId: String) {
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        val batch = vm.state.watermarkBatch ?: return
        val taken = batch.recipients.map { it.email.lowercase() }.toSet()
        val additions = list.recipients
            .filter { isValidEmail(it.email) && it.email.lowercase() !in taken }
            .map { it.copy(name = it.name.ifBlank { it.email.substringBefore('@') }) }
        editBatch { copy(listMenuOpen = false) }
        if (additions.isEmpty()) return vm.notice("Everyone in \"${list.name}\" is already added")
        editBatch { copy(recipients = recipients + additions) }
        vm.notice("Added ${additions.size} from \"${list.name}\"")
    }

    fun confirmBatch() {
        val batch = vm.state.watermarkBatch ?: return
        if (!batch.canDownload) return
        if (vm.refusesDownload()) return
        editBatch { copy(downloading = true) }
        vm.run {
            // Per-recipient text is rendered here so the server only needs the
            // final string; the appearance rides along once for the batch.
            val recipients = batch.recipients.map { r ->
                ZipRecipient(r.name, r.email, batch.style.render(listOf(r)).ifBlank { "CONFIDENTIAL" })
            }
            when (val zip = vm.repository.watermarkedZip(batch.documents.map { it.id }, recipients, batch.style)) {
                is ZillitResult.Success -> {
                    vm.update { copy(watermarkBatch = null) }
                    library.saveAndOpen("watermarked_${vm.today()}.zip", zip.data)
                    vm.notice("Downloaded ${plural(recipients.size, "watermarked bundle")}")
                }
                is ZillitResult.Failure -> {
                    editBatch { copy(downloading = false) }
                    vm.report(zip.error)
                }
            }
        }
    }

    // -- the library picker ----------------------------------------------------

    fun openPicker(purpose: PickerPurpose) {
        val preselected = when (purpose) {
            PickerPurpose.Composer -> vm.state.composer.attachments.filterNot { it.isEphemeral }.map { it.id }
            PickerPurpose.Batch -> vm.state.watermarkBatch?.documents.orEmpty().map { it.id }
        }.toSet()
        vm.update { copy(picker = DocumentPickerState(purpose = purpose, selected = preselected)) }
        vm.run {
            val loaded = vm.repository.allDocuments()
            vm.update {
                val open = picker ?: return@update this
                copy(
                    picker = open.copy(
                        loading = false,
                        documents = (loaded as? ZillitResult.Success)?.data.orEmpty()
                            .sortedByDescending { it.createdAt ?: 0 },
                    ),
                )
            }
            if (loaded is ZillitResult.Failure) vm.report(loaded.error)
        }
    }

    private inline fun editPicker(crossinline change: DocumentPickerState.() -> DocumentPickerState) =
        vm.update { copy(picker = picker?.change()) }

    fun closePicker() = vm.update { copy(picker = null) }

    fun pickerFolder(folderId: String?) = editPicker { copy(folderId = folderId) }

    fun pickerSearch(text: String) = editPicker { copy(search = text) }

    fun pickerToggle(documentId: String) = editPicker { copy(selected = selected.toggled(documentId)) }

    fun pickerToggleAllVisible() {
        val open = vm.state.picker ?: return
        val visible = pickerVisible(vm.state).map { it.id }
        val all = visible.isNotEmpty() && visible.all { it in open.selected }
        editPicker { copy(selected = if (all) selected - visible.toSet() else selected + visible) }
    }

    fun pickerClear() = editPicker { copy(selected = emptySet()) }

    fun pickerConfirm() {
        val open = vm.state.picker ?: return
        val chosen = open.documents.filter { it.id in open.selected }
        vm.update { copy(picker = null) }
        when (open.purpose) {
            PickerPurpose.Composer -> composer.addFromPicker(chosen)
            PickerPurpose.Batch -> editBatch { copy(documents = chosen.filter { it.isWatermarkable }) }
        }
    }

    private companion object {
        const val PREVIEW_WIDTH = 600
    }
}

/** The picker's rows after its folder and search filters. */
internal fun pickerVisible(state: DocDistUiState): List<LibraryDocument> {
    val open = state.picker ?: return emptyList()
    val inFolder = open.folderId?.let { state.folderWithDescendants(it) }
    val q = open.search.trim().lowercase()
    return open.documents.filter { document ->
        (inFolder == null || document.folderId in inFolder) &&
            (q.isEmpty() || document.name.lowercase().contains(q))
    }
}

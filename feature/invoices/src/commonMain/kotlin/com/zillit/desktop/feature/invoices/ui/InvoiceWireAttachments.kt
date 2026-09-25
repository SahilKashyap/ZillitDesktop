package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.WireAttachment
import com.zillit.desktop.feature.invoices.domain.WireAttachmentsChange

/**
 * "Wire Confirmation — {ref}": filing, viewing and removing the bank
 * confirmations on a paid wire — the web's `WireAttachmentsModal`
 * (`PaymentsPage.jsx:704-867`).
 *
 * The file goes to S3 first, exactly as every other invoice attachment does,
 * then its model is handed to the invoice. Each answer replaces the whole
 * list, so removals run one at a time: overlapping deletes would let the last
 * answer write a list back that still holds the first file (ZL-20536).
 */
internal class InvoiceWireAttachments(private val vm: InvoicesViewModel) {

    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is PaymentsEvent.OpenWireAttachments ->
                vm.update { copy(pay = pay.copy(wireAttachments = WireAttachmentsView(event.invoice))) }
            // Closes whatever is in flight; a late answer still reaches the paid row.
            PaymentsEvent.CloseWireAttachments -> vm.update { copy(pay = pay.copy(wireAttachments = null)) }
            PaymentsEvent.AddWireAttachment -> add()
            is PaymentsEvent.RemoveWireAttachment -> remove(event.key)
            is PaymentsEvent.ViewWireAttachment -> view(event.attachment)
            PaymentsEvent.CloseWireAttachmentViewer -> edit { copy(viewing = null) }
            PaymentsEvent.DownloadWireAttachment -> download()
            else -> return false
        }
        return true
    }

    private fun edit(change: WireAttachmentsView.() -> WireAttachmentsView) = vm.update {
        copy(pay = pay.copy(wireAttachments = pay.wireAttachments?.change()))
    }

    /** `.pdf,.jpg,.jpeg,.png`, the input's own `accept`. */
    private fun add() {
        val open = vm.state.value.pay.wireAttachments ?: return
        if (open.uploading) return
        vm.run {
            val file = vm.pickOne() ?: return@run
            if (file.extension !in InvoicesViewModel.UPLOAD_EXTENSIONS) {
                vm.fail(str(S.desktop_inv_file_wrong_type))
                return@run
            }
            edit { copy(uploading = true) }
            val result: ZillitResult<WireAttachmentsChange> = when (val stored = vm.upload(file)) {
                is ZillitResult.Failure -> ZillitResult.Failure(stored.error)
                is ZillitResult.Success -> vm.repo.uploadWireAttachment(
                    id = open.invoice.id,
                    attachment = stored.data,
                    mimeType = file.contentType,
                    size = file.bytes.size.toLong(),
                )
            }
            landed(open.invoice.id, result)
            edit { copy(uploading = false) }
        }
    }

    /** Removed by its S3 key; every Remove waits while one is in flight. */
    private fun remove(key: String) {
        val open = vm.state.value.pay.wireAttachments ?: return
        if (open.removing != null || key.isBlank()) return
        edit { copy(removing = key) }
        vm.run {
            landed(open.invoice.id, vm.repo.removeWireAttachment(open.invoice.id, key))
            edit { copy(removing = null) }
        }
    }

    /**
     * The server's list replaces the dialog's and the paid row's
     * (`onUpdate`), and its `message` is toasted when it sent one.
     */
    private fun landed(invoiceId: String, result: ZillitResult<WireAttachmentsChange>) {
        when (result) {
            is ZillitResult.Failure -> vm.fail(result.error.localised())
            is ZillitResult.Success -> {
                val list = result.data.attachments
                vm.update {
                    copy(
                        pay = pay.copy(
                            wireAttachments = pay.wireAttachments
                                ?.takeIf { it.invoice.id == invoiceId }
                                ?.copy(attachments = list)
                                ?: pay.wireAttachments,
                            recentlyPaid = pay.recentlyPaid.map {
                                if (it.id == invoiceId) it.copy(wireAttachments = list) else it
                            },
                        ),
                    )
                }
                result.data.message?.let { vm.notice(it.localisedMessage()) }
            }
        }
    }

    /** The viewer: "Loading attachment…", then the file, or "Failed to load attachment". */
    private fun view(attachment: WireAttachment) {
        edit { copy(viewing = WireAttachmentPreview(attachment)) }
        vm.run {
            val bytes = (vm.fetchAttachment(attachment.attachment) as? ZillitResult.Success)?.data
            edit {
                val shown = viewing?.takeIf { it.attachment.key == attachment.key } ?: return@edit this
                copy(viewing = shown.copy(loading = false, bytes = bytes?.let(::AttachmentBytes)))
            }
        }
    }

    /** The viewer's Download: the fetched file, saved and opened. */
    private fun download() {
        val shown = vm.state.value.pay.wireAttachments?.viewing ?: return
        val bytes = shown.bytes?.bytes ?: return
        vm.run {
            val saved = vm.saveAndOpen(shown.attachment.name, bytes)
            if (saved is ZillitResult.Failure) vm.fail(saved.error.localised())
        }
    }
}

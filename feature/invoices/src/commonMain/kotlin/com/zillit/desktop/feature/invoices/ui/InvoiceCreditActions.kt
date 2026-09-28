package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.CreditNoteWrite
import com.zillit.desktop.feature.invoices.domain.CreditNotes
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.LineCheck
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineItems

/**
 * Credit Notes & Disputes — the web's `CreditsPage` handlers: the list,
 * Apply / Resolve, the create/edit form, the preview, history and delete.
 *
 * Every write holds the web's lines here as well as on screen: a note dated
 * in a closed cost-report period is neither changed, applied nor deleted,
 * and only a pending or disputed one is open to any of it.
 */
internal class InvoiceCreditActions(private val vm: InvoicesViewModel) {

    private var nextLine = 0

    private fun newId(): String = "li-${vm.now()}-${++nextLine}"

    /** True when [event] was this page's own. */
    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is InvoicesEvent.ActOnCreditNote -> apply(event.note)
            is CreditEvent.New -> startNew(event.type)
            is CreditEvent.Edit -> startEdit(event.note)
            is CreditEvent.Change -> editForm { old ->
                // Typing in the Against box opens its list, as the web's onChange does.
                val typed = old.invoiceQuery != event.form.invoiceQuery
                event.form.copy(errors = old.errors - fixed(old, event.form), pickerOpen = event.form.pickerOpen || typed)
            }
            is CreditEvent.Lines -> editForm { it.copy(lines = LineItems.apply(it.lines, event.edit, ::newId)) }
            is CreditEvent.PickInvoice -> editForm { pickInvoice(it, event.invoice) }
            CreditEvent.ClearInvoice -> editForm { it.copy(invoiceRef = "", invoiceId = "", invoiceQuery = "") }
            CreditEvent.AddAttachment -> addAttachment()
            is CreditEvent.RemoveAttachment -> editForm {
                it.copy(attachments = it.attachments.filterIndexed { index, _ -> index != event.index })
            }
            is CreditEvent.OpenAttachment -> openAttachment(event.attachment)
            CreditEvent.CloseAttachment -> ui { copy(viewing = null) }
            CreditEvent.DownloadAttachment -> downloadAttachment()
            CreditEvent.OpenInvoicePicker -> editForm { it.copy(pickerOpen = true) }
            CreditEvent.CloseInvoicePicker -> editForm { it.copy(pickerOpen = false) }
            CreditEvent.Save -> save()
            CreditEvent.CloseForm -> ui { copy(form = form?.takeIf { it.saving }) }
            is CreditEvent.Preview -> {
                if (event.fromRow) vm.readPageRow(AccountantPage.Credits, event.note.id)
                val audit = listOf(event.note.createdBy, event.note.updatedBy).filter { it.isNotBlank() }
                vm.rememberNames(audit)
                val people = vm.people().filter { it.id in audit }.associateBy { it.id }
                ui { copy(preview = event.note, people = people) }
            }
            CreditEvent.ClosePreview -> ui { copy(preview = null) }
            CreditEvent.ShowHistory -> showHistory()
            CreditEvent.HideHistory -> ui { copy(history = null) }
            CreditEvent.RequestDelete -> ui { copy(confirmDelete = preview?.takeIf { deletable(it) }) }
            CreditEvent.ConfirmDelete -> delete()
            CreditEvent.CancelDelete -> ui { copy(confirmDelete = null) }
            is CreditEvent.SelectDate -> ui { copy(date = event.date) }
            is CreditEvent.SelectSort -> ui { copy(sort = event.sort) }
            else -> return false
        }
        return true
    }

    // -- the list ---------------------------------------------------------------------

    fun load() {
        vm.update { copy(loading = false, creditNotesLoading = true) }
        vm.run {
            when (val result = vm.repo.creditNotes()) {
                is ZillitResult.Success -> vm.update { copy(creditNotesLoading = false, creditNotes = result.data) }
                is ZillitResult.Failure ->
                    vm.update { copy(creditNotesLoading = false, error = result.error.localised()) }
            }
        }
    }

    /**
     * Apply on a pending note and Resolve on a disputed one are the same
     * write: `POST /credit-notes/:id/apply` (`CreditsPage.jsx`). Sending
     * `/dispute` for Resolve raised the dispute a second time instead of
     * settling it. The rest only open, so nothing is sent.
     */
    private fun apply(note: CreditNote) {
        val state = vm.state.value
        if (!note.status.isActionable || state.credit.applyingId != null) return
        // A note dated in a closed period cannot be applied or resolved.
        if (state.periodLock.isLocked(note.effectiveDateMs)) return
        ui { copy(applyingId = note.id) }
        vm.run {
            val result = vm.repo.applyCreditNoteWithMessage(note.id)
            vm.update { copy(error = (result as? ZillitResult.Failure)?.error?.localised()) }
            ui { copy(applyingId = null, preview = preview?.takeUnless { result is ZillitResult.Success }) }
            if (result is ZillitResult.Success) {
                vm.notice(
                    result.data?.localisedMessage() ?: if (note.status == CreditNoteStatus.Disputed) {
                        str(S.desktop_inv_dispute_resolved)
                    } else {
                        str(S.desktop_credit_note_applied)
                    },
                )
                load()
            }
        }
    }

    // -- the form ---------------------------------------------------------------------

    private fun startNew(type: CreditNoteType) {
        if (!vm.state.value.isAccountant) return
        val lines = if (type == CreditNoteType.Dispute) LineDraft() else LineDraft(listOf(CodedLine(newId())))
        ui { copy(form = CreditNoteForm(type = type, lines = lines), preview = null) }
        loadInvoices()
        vm.loadLineReference()
    }

    /** Edit — or, in a closed period, View: the same form, frozen. Only an open note has it. */
    private fun startEdit(note: CreditNote) {
        if (!vm.state.value.isAccountant || !note.isOpen) return
        val form = CreditNoteForm(
            editingId = note.id,
            type = note.type,
            invoiceRef = note.againstInvoice,
            invoiceId = note.invoiceId,
            invoiceQuery = note.againstInvoice,
            vendorId = note.vendorId,
            reason = note.reason,
            effectiveDate = InvoiceFormat.toDateInput(note.effectiveDateMs),
            currency = note.currency,
            disputeAmount = if (note.type == CreditNoteType.Dispute) InvoiceFormat.plain(note.grossAmount) else "",
            notes = note.notes,
            // An empty saved list stays empty; the form never invents a line on load.
            lines = LineDraft(note.lineItems),
            attachments = note.attachments,
            locked = vm.state.value.periodLock.isLocked(note.effectiveDateMs),
            savedLinesJson = note.lineItemsJson,
        )
        ui { copy(form = form, preview = null) }
        loadInvoices()
        vm.loadLineReference()
    }

    private fun loadInvoices() {
        vm.run {
            // The web reads 500 for this picker, against the list's 200.
            val rows = (vm.repo.list(InvoiceQuery(perPage = PICKER_PAGE)) as? ZillitResult.Success)?.data
            if (rows != null) ui { copy(invoices = rows) }
        }
    }

    /** Changes the form unless it is frozen — saving, or a closed-period note being viewed. */
    private fun editForm(change: (CreditNoteForm) -> CreditNoteForm) {
        val form = vm.state.value.credit.form ?: return
        if (form.frozen) return
        ui { copy(form = this.form?.let(change)) }
    }

    /**
     * Picking an invoice brings its currency and its vendor when it has one on
     * file; an invoice naming only a supplier clears the vendor, as the web's
     * `setSelectedVendor(null)` does (`CreditsPage.jsx:540-542`). The list shuts.
     */
    private fun pickInvoice(form: CreditNoteForm, invoice: Invoice): CreditNoteForm {
        val vendor = invoice.vendorId.takeIf { it.isNotBlank() && it in vm.state.value.vendors }
        val vendorId = when {
            vendor != null -> vendor
            invoice.vendorId.isBlank() && invoice.supplierName.isNotBlank() -> ""
            else -> form.vendorId
        }
        return form.copy(
            invoiceRef = invoice.invoiceNumber,
            invoiceId = invoice.id,
            invoiceQuery = invoice.invoiceNumber,
            currency = invoice.currency,
            vendorId = vendorId,
            pickerOpen = false,
            errors = form.errors - setOfNotNull(CreditField.InvoiceRef, CreditField.Vendor.takeIf { vendor != null }),
        )
    }

    /** Images, PDFs, Word, CSV or Excel; uploaded when the form is saved, as the web does. */
    private fun addAttachment() {
        val form = vm.state.value.credit.form ?: return
        if (form.frozen) return
        vm.run {
            val picked = vm.pickMany()
            val refused = picked.filter { it.extension !in CreditNotes.ATTACHMENT_EXTENSIONS }
            if (refused.isNotEmpty()) vm.fail(str(S.desktop_inv_attachment_types))
            val accepted = (picked - refused.toSet()).map {
                CreditAttachment(name = it.name, sizeBytes = it.bytes.size.toLong(), file = it)
            }
            if (accepted.isNotEmpty()) editForm { it.copy(attachments = it.attachments + accepted) }
        }
    }

    /**
     * View — the web's in-app `CreditAttachmentViewer`: "Loading attachment…",
     * then the picture or the PDF's pages, or "Failed to load attachment".
     */
    private fun openAttachment(attachment: CreditAttachment) {
        val stored = attachment.stored ?: return
        ui { copy(viewing = AttachmentView(attachment)) }
        vm.run {
            val bytes = vm.fetchAttachment(stored)
            ui {
                val open = viewing?.takeIf { it.attachment == attachment } ?: return@ui this
                copy(
                    viewing = when (bytes) {
                        is ZillitResult.Success -> open.copy(bytes = AttachmentBytes(bytes.data), loading = false)
                        is ZillitResult.Failure -> open.copy(loading = false, failed = true)
                    },
                )
            }
        }
    }

    /** The viewer's Download: the fetched file to Downloads, opened. */
    private fun downloadAttachment() {
        val view = vm.state.value.credit.viewing ?: return
        val bytes = view.bytes?.bytes ?: return
        val name = view.attachment.name.ifBlank { view.attachment.stored?.name.orEmpty() }
        vm.run {
            val opened = vm.saveAndOpen(name, bytes)
            if (opened is ZillitResult.Failure) vm.fail(opened.error.localised())
        }
    }

    /**
     * Create or Update — `handleCreate`: the web's checks in its order, then
     * any new files to storage, then one write. A dispute needs the invoice
     * and a reason; a credit note needs its lines.
     */
    private fun save() {
        val state = vm.state.value
        val form = state.credit.form ?: return
        if (form.frozen || !state.isAccountant) return
        val errors = validate(form)
        if (errors.isNotEmpty()) {
            ui { copy(form = this.form?.copy(errors = errors, lines = flagged(form))) }
            return
        }
        ui { copy(form = this.form?.copy(errors = emptyMap(), saving = true)) }
        vm.run {
            val attachments = upload(form.attachments)
            val result = if (attachments == null) {
                null
            } else {
                val write = write(form, attachments)
                val id = form.editingId
                if (id == null) vm.repo.createCreditNoteWithMessage(write) else vm.repo.updateCreditNoteWithMessage(id, write)
            }
            if (result is ZillitResult.Success) {
                ui { copy(form = null) }
                vm.notice(
                    result.data?.localisedMessage()
                        ?: str(if (form.editingId == null) S.desktop_inv_credit_saved else S.desktop_inv_credit_updated),
                )
                load()
            } else {
                ui { copy(form = this.form?.copy(saving = false)) }
                (result as? ZillitResult.Failure)?.let { vm.fail(it.error.localised()) }
            }
        }
    }

    private fun validate(form: CreditNoteForm): Map<CreditField, String> {
        val lock = vm.state.value.periodLock
        return buildMap {
            if (form.vendorId.isBlank()) put(CreditField.Vendor, str(S.ah_err_vendor_required))
            when {
                form.effectiveDate.isBlank() ->
                    put(CreditField.EffectiveDate, str(S.desktop_inv_effective_date_required))
                InvoiceFormat.parseDateInput(form.effectiveDate) == null ->
                    put(CreditField.EffectiveDate, str(S.desktop_that_is_not_a_date))
                lock.isLocked(form.effectiveDate) ->
                    put(CreditField.EffectiveDate, str(S.desktop_inv_date_in_locked_period, lock.lockedThrough))
            }
            if (form.isDispute && form.invoiceRef.isBlank()) {
                put(CreditField.InvoiceRef, str(S.desktop_inv_dispute_needs_invoice))
            }
            if (form.isDispute && form.reason.isBlank()) {
                put(CreditField.Reason, str(S.desktop_inv_dispute_needs_reason))
            }
            if (!form.isDispute) lineMessage(LineItems.check(form.lines.lines))?.let { put(CreditField.Lines, it) }
        }
    }

    /** Stored files go back as they are; picked ones go to storage first. Null when an upload fails. */
    private suspend fun upload(attachments: List<CreditAttachment>): List<CreditAttachment>? =
        attachments.map { attachment ->
            val file = attachment.file ?: return@map attachment
            when (val up = vm.upload(file)) {
                is ZillitResult.Failure -> {
                    vm.fail(up.error.localised())
                    return null
                }
                is ZillitResult.Success -> attachment.copy(stored = up.data, file = null)
            }
        }

    private fun write(form: CreditNoteForm, attachments: List<CreditAttachment>): CreditNoteWrite {
        val state = vm.state.value
        val vendor = state.vendors[form.vendorId]
        return CreditNoteWrite(
            type = form.type,
            vendorId = form.vendorId,
            vendorName = vendor?.name.orEmpty(),
            invoiceReference = form.invoiceRef,
            invoiceId = form.invoiceId.ifBlank {
                state.credit.invoices.firstOrNull { it.invoiceNumber == form.invoiceRef }?.id.orEmpty()
            },
            reason = form.reason,
            effectiveDate = form.effectiveDate,
            currency = form.currency.ifBlank { state.projectCurrency },
            disputeAmount = form.disputeAmount.trim().replace(",", "").toDoubleOrNull() ?: 0.0,
            notes = form.notes,
            lines = form.lines.lines,
            attachments = attachments,
            savedLinesJson = form.savedLinesJson,
        )
    }

    // -- preview, history, delete ------------------------------------------------------

    private fun deletable(note: CreditNote): Boolean =
        note.isOpen && !vm.state.value.periodLock.isLocked(note.effectiveDateMs)

    private fun showHistory() {
        val note = vm.state.value.credit.preview ?: return
        ui { copy(history = CreditHistory(note)) }
        vm.run {
            val result = vm.repo.creditNoteHistory(note.id)
            val rows = (result as? ZillitResult.Success)?.data.orEmpty()
            vm.rememberNames(rows.map { it.actionBy })
            ui { copy(history = history?.takeIf { it.note.id == note.id }?.copy(rows = rows, loading = false)) }
            (result as? ZillitResult.Failure)?.let { vm.fail(it.error.localised()) }
        }
    }

    private fun delete() {
        val note = vm.state.value.credit.confirmDelete ?: return
        if (!vm.state.value.isAccountant || !deletable(note)) return
        ui { copy(confirmDelete = null, preview = null, deletingId = note.id) }
        vm.run {
            val result = vm.repo.deleteCreditNoteWithMessage(note.id)
            ui { copy(deletingId = null) }
            when (result) {
                is ZillitResult.Failure -> vm.fail(result.error.localised())
                is ZillitResult.Success -> {
                    vm.notice(result.data?.localisedMessage() ?: str(S.desktop_inv_credit_deleted))
                    load()
                }
            }
        }
    }

    private fun ui(change: CreditNotesUi.() -> CreditNotesUi) = vm.update { copy(credit = credit.change()) }

    private companion object {
        const val PICKER_PAGE = 500
    }
}

/** The web's line message: one sentence for no description, else `Line N: account, amount > 0; …`. */
internal fun lineMessage(check: LineCheck): String? = when {
    check.ok -> null
    check.noDescription -> str(S.desktop_inv_line_description_required)
    else -> check.problems.joinToString("; ") { problem ->
        val wants = listOfNotNull(
            str(S.desktop_inv_line_wants_account).takeIf { problem.needsAccount },
            str(S.desktop_inv_line_wants_amount).takeIf { problem.needsAmount },
        )
        str(S.desktop_inv_line_n_wants, problem.position, wants.joinToString(", "))
    }
}

/** The errors an edit has answered — each clears as its field is filled in. */
private fun fixed(old: CreditNoteForm, new: CreditNoteForm): Set<CreditField> = buildSet {
    if (old.vendorId != new.vendorId) add(CreditField.Vendor)
    if (old.effectiveDate != new.effectiveDate) add(CreditField.EffectiveDate)
    if (old.reason != new.reason) add(CreditField.Reason)
    if (old.invoiceRef != new.invoiceRef) add(CreditField.InvoiceRef)
}

/** The lines the check refused, tinted until each is edited. */
private fun flagged(form: CreditNoteForm): LineDraft {
    if (form.isDispute) return form.lines
    val ids = LineItems.check(form.lines.lines).problems.map { it.lineId }.toSet()
    return form.lines.copy(flagged = ids)
}

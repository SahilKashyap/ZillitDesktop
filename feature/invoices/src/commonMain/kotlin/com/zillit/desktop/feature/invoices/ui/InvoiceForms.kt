package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile

/**
 * The accountant's Enter Invoice dialog — its Manual tab: attach the
 * document, fill the form, `POST /`. Its Upload tab is the bulk upload
 * (`InvoiceInboxActions`), as the web's is; extraction is the server's job
 * there, so nothing here calls `/upload` any more.
 */
@Suppress("TooManyFunctions") // One handler per user act.
internal class InvoiceForms(private val vm: InvoicesViewModel) {

    /** The form's vendor quick-add. */
    private val vendors = PendingVendors(vm)

    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is EnterEvent.CreateVendor -> {
                val form = vm.state.value.enter ?: return true
                val name = event.name.trim().takeIf { it.isNotEmpty() } ?: return true
                changed(form.copy(vendorId = "", pendingVendorName = name))
            }
            is EnterEvent.Amount -> amountChanged(event.field, event.value)
            EnterEvent.ConfirmSplit -> {
                vm.update { copy(enter = enter?.copy(confirmSplit = false)) }
                create()
            }
            EnterEvent.CancelSplit -> vm.update { copy(enter = enter?.copy(confirmSplit = false)) }
            EnterEvent.ClearFile -> vm.update {
                copy(enter = enter?.takeIf { !it.saving }?.copy(file = null, attachment = null, uploading = false) ?: enter)
            }
            else -> return false
        }
        return true
    }

    // -- accountant enter ------------------------------------------------------

    /**
     * A blank form: the dates start empty, as the web's do — they are
     * required, so the accountant enters them rather than accepting today.
     * The bank and company fill where there is only one answer.
     */
    fun openEnter() {
        val s = vm.state.value
        vm.update { copy(enter = autoFilled(EnterInvoiceForm(currency = s.projectCurrency))) }
    }

    /**
     * A field changed: a newly picked company brings its country's currency
     * (`applyCompany`), the bank and company fill again where they are empty
     * (`resolveAutoFill`), and the errors of fields now filled clear.
     */
    fun changed(next: EnterInvoiceForm) {
        val old = vm.state.value.enter ?: return
        val s = vm.state.value
        val withCurrency = if (next.companyId != old.companyId && next.companyId.isNotBlank()) {
            next.copy(currency = s.currencyFor(next.companyId) ?: next.currency)
        } else {
            next
        }
        val form = autoFilled(withCurrency)
        vm.update { copy(enter = form.copy(error = null, errors = form.errors - satisfied(form))) }
    }

    private fun autoFilled(form: EnterInvoiceForm): EnterInvoiceForm {
        val s = vm.state.value
        var out = form
        repeat(AUTO_FILL_PASSES) {
            val (bank, company) = InboxTriage.autoFill(out.bankId, out.companyId, s.banks, s.companies)
            if (bank != null) out = out.copy(bankId = bank)
            if (company != null) out = out.copy(companyId = company, currency = s.currencyFor(company) ?: out.currency)
        }
        return out
    }

    /**
     * Attaches the document: PDF, JPG or PNG up to 10 MB (`validateInvoiceFile`),
     * uploaded as a plain attachment. A late answer for a file since removed
     * or replaced is dropped — it no longer owns the row.
     */
    fun enterPickFile() {
        vm.state.value.enter ?: return
        vm.run {
            val file = vm.pickOne() ?: return@run
            val problem = validate(file)
            if (problem != null) {
                vm.update { copy(enter = enter?.copy(errors = enter.errors + (EnterField.Attachment to problem))) }
                return@run
            }
            vm.update {
                copy(
                    enter = enter?.copy(
                        file = file,
                        attachment = null,
                        uploading = true,
                        error = null,
                        errors = enter.errors - EnterField.Attachment,
                    ),
                )
            }
            val up = vm.upload(file)
            vm.update {
                val form = enter?.takeIf { it.file === file } ?: return@update this
                copy(
                    enter = when (up) {
                        is ZillitResult.Failure -> form.copy(
                            file = null,
                            uploading = false,
                            errors = form.errors + (EnterField.Attachment to str(S.desktop_inv_upload_failed_try_again)),
                        )
                        is ZillitResult.Success -> form.copy(uploading = false, attachment = up.data)
                    },
                )
            }
        }
    }

    /** Net, Tax or Gross committed — `applyAmountEdit`, the same rules the Inbox review keeps. */
    fun amountChanged(field: AmountField, value: String) = vm.update {
        val f = enter ?: return@update this
        val split = InboxTriage.applyAmountEdit(f.amounts, field, value)
        copy(
            enter = f.copy(
                net = split.net,
                tax = split.tax,
                gross = split.gross,
                grossEdited = split.grossAnchored,
                error = null,
                errors = if (field == AmountField.Gross) f.errors - EnterField.GrossAmount else f.errors,
            ),
        )
    }

    fun netChanged(value: String) = amountChanged(AmountField.Net, value)

    fun taxChanged(value: String) = amountChanged(AmountField.Tax, value)

    fun grossChanged(value: String) = amountChanged(AmountField.Gross, value)

    /**
     * Create Invoice: every missing field at once (`validateManual`), then —
     * when Net and Tax do not add up to Gross — "Amounts don't match" to
     * confirm or go back; never created silently.
     */
    fun submitEnter() {
        val form = vm.state.value.enter ?: return
        if (form.saving) return
        val errors = validateEnter(form)
        if (errors.isNotEmpty()) {
            vm.update { copy(enter = enter?.copy(errors = errors, error = null)) }
            return
        }
        if (form.amountsMismatch) {
            vm.update { copy(enter = enter?.copy(errors = emptyMap(), confirmSplit = true)) }
            return
        }
        vm.update { copy(enter = enter?.copy(errors = emptyMap())) }
        create()
    }

    /**
     * A pending vendor is created first — a refusal stops here, the pick
     * intact for a retry — then the invoice, with the vendor's real id.
     */
    private fun create() {
        val form = vm.state.value.enter ?: return
        if (form.saving) return
        vm.update { copy(enter = enter?.copy(saving = true, error = null)) }
        vm.run {
            val vendorId = vendors.resolve(form.vendorId, form.pendingVendorName) { message ->
                vm.update { copy(enter = enter?.copy(error = message)) }
            }
            if (vendorId == null) {
                vm.update { copy(enter = enter?.copy(saving = false)) }
                return@run
            }
            if (vendorId != form.vendorId) {
                vm.update { copy(enter = enter?.copy(vendorId = vendorId, pendingVendorName = null)) }
            }
            val entered = vm.entered(form.copy(vendorId = vendorId, pendingVendorName = null))
            if (entered == null) {
                vm.update { copy(enter = enter?.copy(saving = false)) }
                return@run
            }
            when (val r = vm.repo.createEnteredWithMessage(entered)) {
                is ZillitResult.Failure -> vm.update {
                    copy(enter = enter?.copy(saving = false, error = r.error.localised()))
                }
                is ZillitResult.Success -> {
                    vm.update { copy(enter = null, bulkPick = null) }
                    val said = r.data?.takeIf { it.isNotBlank() }?.localisedMessage()
                    vm.notice(said ?: str(if (form.paid) S.desktop_inv_invoice_created else S.desktop_inv_created_in_inbox))
                    vm.refresh()
                }
            }
        }
    }

    /** `validateManual` — every missing field, in the web's order and words. */
    private fun validateEnter(form: EnterInvoiceForm): Map<EnterField, String> = buildMap {
        if (form.vendorId.isBlank() && form.pendingVendorName.isNullOrBlank()) {
            put(EnterField.Vendor, str(S.ah_err_vendor_required))
        }
        if (form.invoiceNumber.isBlank()) put(EnterField.InvoiceNumber, str(S.desktop_inv_invoice_number_required))
        if (InvoiceFormat.parseDateInput(form.invoiceDate) == null) {
            put(EnterField.InvoiceDate, str(S.desktop_inv_invoice_date_required))
        }
        if (InvoiceFormat.parseDateInput(form.effectiveDate) == null) {
            put(EnterField.EffectiveDate, str(S.desktop_inv_effective_date_required))
        }
        if (form.departmentId.isBlank()) put(EnterField.Department, str(S.ah_err_department_required))
        if ((form.grossValue ?: 0.0) <= 0.0) put(EnterField.GrossAmount, str(S.desktop_inv_gross_required))
        if (form.attachment == null || form.uploading) {
            put(EnterField.Attachment, str(S.desktop_ce_crew_err_edit_attachment))
        }
    }

    /** The fields a form now satisfies — their errors clear as they are filled, as the web's `update` clears them. */
    private fun satisfied(form: EnterInvoiceForm): Set<EnterField> =
        EnterField.entries.toSet() - validateEnter(form).keys - EnterField.Attachment

    /** `validateInvoiceFile`, without the page check the manual tab never ran. */
    private fun validate(file: PickedInvoiceFile): String? = when {
        file.extension !in InvoicesViewModel.ENTER_EXTENSIONS -> str(S.desktop_inv_file_wrong_type)
        file.bytes.size > InvoicesViewModel.MAX_FILE_BYTES -> str(S.desktop_hub_file_must_be_10mb_or_smaller)
        else -> null
    }

    private companion object {
        /** A lone bank fills first; its account holder can follow only on the next pass. */
        const val AUTO_FILL_PASSES = 2
    }
}

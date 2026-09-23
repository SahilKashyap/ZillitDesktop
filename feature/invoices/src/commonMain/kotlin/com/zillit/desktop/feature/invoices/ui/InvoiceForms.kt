package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The two create flows: the department's Upload Invoice sheet and the
 * accountant's Enter Invoice dialog. Both pick a file, PUT it to S3 through
 * the host seam, optionally run extraction, then `POST /`.
 */
internal class InvoiceForms(private val vm: InvoicesViewModel) {

    // -- department upload -----------------------------------------------------

    fun uploadInvoice() {
        if (!vm.state.value.viewer.mayPost) {
            vm.update { copy(error = str(S.desktop_inv_no_posting_rights)) }
            return
        }
        vm.run {
            val file = vm.pickOne() ?: return@run
            val problem = validate(file, InvoicesViewModel.UPLOAD_EXTENSIONS)
            if (problem != null) {
                vm.update { copy(error = problem) }
                return@run
            }
            vm.update { copy(upload = UploadFlow(file)) }
            when (val up = vm.upload(file)) {
                is ZillitResult.Failure -> vm.update {
                    copy(upload = null, error = str(S.drive_notification_upload_failed_format, up.error.localised()))
                }
                is ZillitResult.Success -> {
                    vm.update { copy(upload = upload?.copy(stage = UploadStage.Extracting, attachment = up.data)) }
                    val extracted = vm.repo.extract(up.data)
                    vm.update {
                        val flow = upload ?: return@update this
                        copy(
                            upload = flow.copy(
                                stage = UploadStage.Ready,
                                extraction = extracted.getOrNull(),
                                extractionFailed = extracted is ZillitResult.Failure,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun sendUpload() {
        val flow = vm.state.value.upload ?: return
        if (flow.type == null || flow.attachment == null || flow.stage != UploadStage.Ready) return
        vm.update { copy(upload = upload?.copy(sending = true)) }
        vm.run {
            when (val r = vm.repo.createFromUpload(vm.departmentUpload(flow))) {
                is ZillitResult.Failure -> vm.update {
                    copy(upload = upload?.copy(sending = false), error = r.error.localised())
                }
                is ZillitResult.Success -> {
                    vm.update { copy(upload = null) }
                    vm.notice(str(S.desktop_inv_sent_to_accounts))
                    vm.refresh()
                }
            }
        }
    }

    // -- accountant enter ------------------------------------------------------

    fun openEnter() {
        val s = vm.state.value
        val today = InvoiceFormat.today(vm.now())
        vm.update {
            copy(
                enter = EnterInvoiceForm(
                    currency = s.projectCurrency,
                    invoiceDate = today,
                    effectiveDate = today,
                    // A single production bank is the bank.
                    bankId = s.banks.singleOrNull()?.id.orEmpty(),
                ),
            )
        }
    }

    fun enterPickFile() {
        val tab = vm.state.value.enter?.tab ?: return
        vm.run {
            val file = vm.pickOne() ?: return@run
            val problem = validate(file, InvoicesViewModel.ENTER_EXTENSIONS)
            if (problem != null) {
                vm.update { copy(enter = enter?.copy(error = problem)) }
                return@run
            }
            vm.update { copy(enter = enter?.copy(file = file, attachment = null, uploading = true, error = null)) }
            when (val up = vm.upload(file)) {
                is ZillitResult.Failure -> vm.update {
                    copy(enter = enter?.copy(uploading = false, error = up.error.localised()))
                }
                is ZillitResult.Success -> {
                    vm.update { copy(enter = enter?.copy(uploading = false, attachment = up.data)) }
                    if (tab == EnterTab.Upload) extractInto(up.data)
                }
            }
        }
    }

    /** Extraction is best-effort: a failure leaves the fields for typing. */
    private suspend fun extractInto(attachment: InvoiceAttachment) {
        vm.update { copy(enter = enter?.copy(extracting = true)) }
        val result = vm.repo.extract(attachment)
        vm.update {
            val current = enter ?: return@update this
            copy(
                enter = when (result) {
                    is ZillitResult.Failure -> current.copy(extracting = false, extractionFailed = true)
                    is ZillitResult.Success -> prefill(current, result.data)
                },
            )
        }
    }

    /** Extraction → fields, the way the web anchors gross and defaults the date to today. */
    private fun prefill(form: EnterInvoiceForm, x: InvoiceExtraction): EnterInvoiceForm = form.copy(
        extracting = false,
        extraction = x,
        vendorId = vm.matchVendor(x.supplierName) ?: form.vendorId,
        vendorQuery = if (form.vendorId.isBlank()) x.supplierName else form.vendorQuery,
        invoiceNumber = x.invoiceNumber.ifBlank { form.invoiceNumber },
        invoiceDate = x.invoiceDate.ifBlank { form.invoiceDate.ifBlank { InvoiceFormat.today(vm.now()) } },
        dueDate = x.dueDate.ifBlank { form.dueDate },
        gross = x.gross?.let(InvoiceFormat::plain) ?: form.gross,
        grossEdited = x.gross != null || form.grossEdited,
        currency = x.currency.ifBlank { form.currency },
        poNumber = x.poNumber.ifBlank { form.poNumber },
    )

    /** Gross follows net + tax until the user has typed a gross; then tax follows gross − net. */
    fun netChanged(value: String) = vm.update {
        val f = enter ?: return@update this
        val next = f.copy(net = value, error = null)
        copy(
            enter = if (f.grossEdited) {
                next.copy(tax = derive(f.grossValue, next.netValue))
            } else {
                next.copy(gross = sum(next.netValue, next.taxValue))
            },
        )
    }

    fun taxChanged(value: String) = vm.update {
        val f = enter ?: return@update this
        val next = f.copy(tax = value, error = null)
        copy(
            enter = if (f.grossEdited) {
                next.copy(net = derive(f.grossValue, next.taxValue))
            } else {
                next.copy(gross = sum(next.netValue, next.taxValue))
            },
        )
    }

    fun grossChanged(value: String) = vm.update {
        copy(enter = enter?.copy(gross = value, grossEdited = true, error = null))
    }

    fun submitEnter() {
        val form = vm.state.value.enter ?: return
        val problem = validateEnter(form)
        if (problem != null) {
            vm.update {
                val acknowledged = enter?.mismatchAcknowledged == true || problem == MISMATCH
                copy(enter = enter?.copy(error = problem, mismatchAcknowledged = acknowledged))
            }
            return
        }
        val entered = vm.entered(form) ?: return
        vm.update { copy(enter = enter?.copy(saving = true, error = null)) }
        vm.run {
            when (val r = vm.repo.createEntered(entered)) {
                is ZillitResult.Failure -> vm.update {
                    copy(enter = enter?.copy(saving = false, error = r.error.localised()))
                }
                is ZillitResult.Success -> {
                    vm.update { copy(enter = null) }
                    vm.notice(str(S.desktop_inv_created_in_inbox))
                    vm.refresh()
                }
            }
        }
    }

    private fun validateEnter(form: EnterInvoiceForm): String? = when {
        form.attachment == null -> str(S.desktop_inv_attach_document_first)
        form.vendorId.isBlank() -> str(S.desktop_pick_a_vendor)
        form.invoiceNumber.isBlank() -> str(S.desktop_inv_number_required)
        InvoiceFormat.parseDateInput(form.invoiceDate) == null -> str(S.desktop_inv_invoice_date_format)
        InvoiceFormat.parseDateInput(form.effectiveDate) == null -> str(S.desktop_inv_effective_date_format)
        form.dueDate.isNotBlank() && InvoiceFormat.parseDateInput(form.dueDate) == null ->
            str(S.desktop_inv_due_date_format)
        form.departmentId.isBlank() -> str(S.desktop_pick_a_department)
        (form.grossValue ?: 0.0) <= 0.0 -> str(S.desktop_inv_gross_greater_than_zero)
        form.amountsMismatch && !form.mismatchAcknowledged -> MISMATCH
        else -> null
    }

    private fun validate(file: PickedInvoiceFile, allowed: Set<String>): String? = when {
        file.extension !in allowed -> str(
            S.desktop_only_files_can_be_uploaded,
            allowed.joinToString(", ") { it.uppercase() },
        )
        file.bytes.size > InvoicesViewModel.MAX_FILE_BYTES -> str(S.desktop_file_larger_than_10mb)
        else -> null
    }

    private fun sum(net: Double?, tax: Double?): String = when {
        net == null && tax == null -> ""
        else -> InvoiceFormat.plain((net ?: 0.0) + (tax ?: 0.0))
    }

    private fun derive(gross: Double?, other: Double?): String =
        if (gross == null || other == null) "" else InvoiceFormat.plain(gross - other)

    private companion object {
        val MISMATCH: String get() = str(S.desktop_inv_mismatch_submit_again)
    }
}

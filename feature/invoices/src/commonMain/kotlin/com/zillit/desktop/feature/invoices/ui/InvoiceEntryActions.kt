package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryBlock
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryWrite
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.TaxLine

/**
 * Invoice Entry's coding screen — the web's
 * `EntryDetailModal` and `EntryPage` handlers, split out of the view model.
 *
 * Every write is refused here as well as hidden on screen: a row the reader
 * may not open, an invoice in a closed cost-report period, a post without the
 * right to post, a Submit for Review by a senior (they are the reviewers).
 */
internal class InvoiceEntryActions(private val vm: InvoicesViewModel) {

    private var nextLine = 0

    /** A line id unique to this session — the web's `li-<time>-<n>`. */
    private fun newId(): String = "li-${vm.now()}-${++nextLine}"

    /** True when [event] was the coding screen's own. */
    @Suppress("CyclomaticComplexMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is EntryEvent.Open -> open(event.invoice, event.readOnly)
            EntryEvent.Close -> vm.update { copy(ledger = null) }
            is EntryEvent.EditHeader -> editable { copy(header = event.header, problem = null) }
            is EntryEvent.SelectLine -> vm.update { copy(ledger = ledger?.copy(selectedLineId = event.id)) }
            is EntryEvent.EditLine -> editable {
                copy(lines = EntryCoding.update(lines, event.line.id) { event.line }, problem = null)
            }
            is EntryEvent.EditSplitAmount -> editable {
                copy(lines = EntryCoding.redistribute(lines, event.id, event.amount), problem = null)
            }
            EntryEvent.AddLine -> editable {
                val (next, id) = EntryCoding.add(lines, ::newId)
                copy(lines = next, selectedLineId = id)
            }
            EntryEvent.SplitLine -> editable {
                if (!canSplit) return@editable this
                val (next, id) = EntryCoding.split(lines, selectedLineId, ::newId)
                copy(lines = next, selectedLineId = id)
            }
            is EntryEvent.RemoveLine -> editable {
                copy(
                    lines = EntryCoding.remove(lines, event.id),
                    selectedLineId = selectedLineId.takeIf { it != event.id },
                )
            }
            is EntryEvent.EditTaxAccount -> editable { copy(tax = tax.copy(account = event.account)) }
            is EntryEvent.EditTaxAmount -> editable {
                copy(tax = tax.copy(amount = event.amount.coerceAtLeast(0.0), overridden = true))
            }
            EntryEvent.ResetTaxAmount -> editable { copy(tax = tax.copy(amount = null, overridden = false)) }
            EntryEvent.Save -> save()
            EntryEvent.Post -> post()
            EntryEvent.SubmitForReview -> submitForReview()
            EntryEvent.ReturnToApproval -> returnToApproval()
            EntryEvent.Assign -> assign()
            EntryEvent.ShowHistory -> showHistory()
            EntryEvent.HideHistory -> vm.update { copy(ledger = ledger?.copy(historyOpen = false)) }
            EntryEvent.DismissProblem -> vm.update { copy(ledger = ledger?.copy(problem = null)) }
            else -> return false
        }
        return true
    }

    // -- opening -----------------------------------------------------------------

    /**
     * Opens the coding screen: the row at once, then the full record, the
     * orders it is linked to (when it has no coding of its own yet) and its
     * document. A row the reader may not open is refused.
     */
    private fun open(row: Invoice, readOnly: Boolean) {
        val state = vm.state.value
        if (!readOnly && !state.canAccessEntry(row)) return
        vm.update { copy(ledger = EntryLedger(invoice = row, readOnly = readOnly)) }
        loadChart()
        vm.run {
            val invoice = (vm.repo.invoice(row.id) as? ZillitResult.Success)?.data ?: row
            val orders = if (invoice.linkedPos.isNotEmpty()) {
                vm.repo.linkedPos(invoice.id).getOrNull().orEmpty()
            } else {
                emptyList()
            }
            val lines = EntryCoding.seedLines(invoice, EntryCoding.poLines(orders, ::newId), ::newId)
            vm.update {
                val open = ledger?.takeIf { it.invoice.id == row.id } ?: return@update this
                copy(
                    ledger = open.copy(
                        invoice = invoice,
                        loading = false,
                        header = seededHeader(invoice),
                        lines = lines,
                        tax = invoice.taxLine ?: TaxLine(),
                        orders = orders,
                    ),
                )
            }
            loadPreview(invoice)
        }
    }

    /**
     * The header as the record has it, with the web's auto-fill: a lone bank
     * is picked, and a company follows the picked bank's entity — or the only
     * company there is.
     */
    private fun seededHeader(invoice: Invoice): EntryHeader {
        val state = vm.state.value
        val base = EntryHeader.of(invoice)
        val bank = base.bankId.ifBlank { state.banks.singleOrNull()?.id.orEmpty() }
        val company = base.companyId.ifBlank {
            state.banks.firstOrNull { it.id == bank }?.entityId?.takeIf { it.isNotBlank() }
                ?: state.companies.singleOrNull()?.id.orEmpty()
        }
        return base.copy(bankId = bank, companyId = company)
    }

    private fun loadPreview(invoice: Invoice) {
        val attachment = invoice.firstAttachment?.takeIf { it.isImage || it.isPdf } ?: return
        vm.update { copy(ledger = ledger?.copy(previewLoading = true)) }
        vm.run {
            val result = vm.fetchAttachment(attachment)
            vm.update {
                val open = ledger?.takeIf { it.invoice.id == invoice.id } ?: return@update this
                copy(
                    ledger = when (result) {
                        is ZillitResult.Success -> open.copy(
                            preview = AttachmentBytes(result.data),
                            previewLoading = false,
                        )
                        is ZillitResult.Failure -> open.copy(previewLoading = false, previewFailed = true)
                    },
                )
            }
        }
    }

    /** The chart, once — it decides which typed nominals go as new. */
    private fun loadChart() {
        if (vm.state.value.chart.isNotEmpty()) return
        vm.run { vm.repo.chartCodes().getOrNull()?.let { codes -> vm.update { copy(chart = codes) } } }
    }

    // -- writes ------------------------------------------------------------------

    /** Changes the open ledger unless it is frozen — posted, or in a closed period. */
    private fun editable(change: EntryLedger.() -> EntryLedger) {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy) return
        vm.update { copy(ledger = this.ledger?.change()) }
    }

    private fun frozen(ledger: EntryLedger): Boolean = ledger.readOnly || vm.state.value.isLocked(ledger.invoice)

    private fun write(ledger: EntryLedger, status: String? = null): EntryWrite {
        val state = vm.state.value
        val sendsTax = EntryCoding.sendsTaxLine(ledger.tax, ledger.lines, state.taxTypes)
        return EntryWrite(
            header = ledger.header,
            lines = ledger.lines,
            taxLine = ledger.tax.takeIf { sendsTax },
            taxAmount = EntryCoding.effectiveTax(ledger.tax, ledger.lines, state.taxTypes),
            savedLinesJson = ledger.invoice.lineItemsJson,
            chart = state.chart,
            status = status,
        )
    }

    /** Save keeps the screen open and changes no status; the lines must reconcile first. */
    private fun save() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy) return
        if (ledger.mismatch(vm.state.value)) {
            problem(str(S.desktop_inv_lines_must_match_save))
            return
        }
        act(EntryAction.Saving) {
            val result = vm.repo.saveEntry(ledger.invoice.id, write(ledger))
            finish(result, closes = false, done = str(S.desktop_inv_invoice_saved))
        }
    }

    /**
     * Post to Ledger — the whole header and coding saved first, then
     * `POST /:id/post`, so what posts is exactly what Save would store
     * (ZL-20476). Refused, in the web's order, without a bank, in a closed
     * period, with lines that do not reconcile, without an effective date, or
     * with a line that has no nominal.
     */
    private fun post() {
        val state = vm.state.value
        val ledger = state.ledger ?: return
        if (ledger.readOnly || ledger.busy || !state.viewer.canPostToLedger) return
        val block = EntryCoding.postBlock(
            header = ledger.header,
            lines = ledger.lines,
            tax = ledger.tax,
            taxTypes = state.taxTypes,
            taxTypesKnown = state.taxTypesKnown,
            invoiceGross = ledger.invoice.grossAmount,
            locked = state.isLocked(ledger.invoice),
        )
        if (block != null) {
            problem(block.alert() ?: return)
            return
        }
        act(EntryAction.Posting) {
            val saved = vm.repo.saveEntry(ledger.invoice.id, write(ledger))
            val result = if (saved is ZillitResult.Failure) saved else vm.repo.postInvoice(ledger.invoice.id)
            finish(result, closes = true, done = str(S.ah_posted_to_ledger_toast))
        }
    }

    /** A junior's hand-off: the header and coding saved with `under_review`. Seniors are the reviewers. */
    private fun submitForReview() {
        val state = vm.state.value
        val ledger = state.ledger ?: return
        if (frozen(ledger) || ledger.busy || state.viewer.isSenior) return
        act(EntryAction.Reviewing) {
            val result = vm.repo.saveEntry(ledger.invoice.id, write(ledger, status = UNDER_REVIEW))
            finish(result, closes = true, done = str(S.ah_submitted_for_review_toast))
        }
    }

    /** `POST /:id/return-to-approval` — the server resets the chain itself. */
    private fun returnToApproval() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy) return
        act(EntryAction.Returning) {
            val result = vm.repo.returnToApproval(ledger.invoice.id)
            finish(result, closes = true, done = str(S.desktop_inv_sent_back_for_approval))
        }
    }

    /** The assign sheet, over this one invoice. */
    private fun assign() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger)) return
        vm.update { copy(assignFor = AssignRequest(invoiceIds = listOf(ledger.invoice.id)), assignees = vm.team()) }
    }

    private fun showHistory() {
        val ledger = vm.state.value.ledger ?: return
        vm.update { copy(ledger = this.ledger?.copy(historyOpen = true, historyLoading = ledger.history == null)) }
        if (ledger.history != null) return
        vm.run {
            val result = vm.repo.history(ledger.invoice.id)
            (result as? ZillitResult.Success)?.data?.let { rows -> vm.rememberNames(rows.map { it.actionBy }) }
            vm.update {
                val open = this.ledger?.takeIf { it.invoice.id == ledger.invoice.id } ?: return@update this
                when (result) {
                    is ZillitResult.Success -> copy(ledger = open.copy(history = result.data, historyLoading = false))
                    is ZillitResult.Failure -> copy(
                        ledger = open.copy(historyLoading = false),
                        error = result.error.localised(),
                    )
                }
            }
        }
    }

    /** Re-reads the open invoice after an assignment, so its new owner shows. */
    fun reloadOpen() {
        val id = vm.state.value.ledger?.invoice?.id ?: return
        vm.run {
            vm.repo.invoice(id).getOrNull()?.let { fresh ->
                vm.update { copy(ledger = ledger?.takeIf { it.invoice.id == id }?.copy(invoice = fresh) ?: ledger) }
            }
        }
    }

    // -- plumbing ------------------------------------------------------------------

    private fun problem(text: String) = vm.update { copy(ledger = ledger?.copy(problem = text)) }

    private fun act(action: EntryAction, block: suspend () -> Unit) {
        vm.update { copy(ledger = ledger?.copy(action = action, problem = null)) }
        vm.run { block() }
    }

    /** A landed write closes the screen or re-reads it; a refused one leaves everything as typed. */
    private fun finish(result: ZillitResult<Unit>, closes: Boolean, done: String) {
        when (result) {
            is ZillitResult.Failure -> vm.update {
                copy(ledger = ledger?.copy(action = null), error = result.error.localised())
            }
            is ZillitResult.Success -> {
                vm.update { copy(ledger = if (closes) null else ledger?.copy(action = null)) }
                vm.notice(done)
                if (!closes) reloadOpen()
                vm.refresh()
            }
        }
    }

    private companion object {
        const val UNDER_REVIEW = "under_review"
    }
}

/** The alert the web raises for each block; the silent ones (bank, lock) have their own on-screen hint. */
private fun EntryBlock.alert(): String? = when (this) {
    EntryBlock.NoBank -> str(S.desktop_inv_bank_before_post)
    EntryBlock.Locked -> null
    EntryBlock.Mismatch -> str(S.desktop_inv_lines_must_match_post)
    EntryBlock.NoEffectiveDate -> str(S.desktop_po_effective_date_before_posting)
    is EntryBlock.MissingNominal -> if (rows.size == 1) {
        str(S.desktop_inv_line_needs_nominal, rows.single())
    } else {
        str(S.desktop_inv_lines_need_nominal, rows.joinToString(", "))
    }
}

/** The coded gross against the invoice's own, to the penny. */
private fun EntryLedger.mismatch(state: InvoicesUiState): Boolean =
    EntryCoding.amountMismatch(totals(state.taxTypes, state.taxTypesKnown).gross, invoice.grossAmount)

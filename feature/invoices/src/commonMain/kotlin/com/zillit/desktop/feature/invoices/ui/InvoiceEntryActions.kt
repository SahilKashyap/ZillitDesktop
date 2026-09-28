package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryBlock
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryWrite
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
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
    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            is EntryEvent.Open -> open(event.invoice, event.readOnly)
            EntryEvent.Close -> vm.update { copy(ledger = null) }
            is EntryEvent.EditHeader -> editHeader(event.header)
            EntryEvent.AutoFill -> editable {
                val state = vm.state.value
                val filled = EntryCoding.autoFill(header, state.banks, state.companies)
                if (filled == header) this else copy(header = filled)
            }
            // A second click on the picked line lets it go — the web's row toggle.
            is EntryEvent.SelectLine -> vm.update {
                copy(
                    ledger = ledger?.let { open ->
                        open.copy(selectedLineId = event.id?.takeUnless { it == open.selectedLineId })
                    },
                )
            }
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
            is EntryEvent.EditTaxLayers -> editable { copy(tax = tax.copy(trackingCodes = event.codes)) }
            is EntryEvent.EditTaxTags -> editable { copy(tax = tax.copy(tags = event.tags)) }
            is EntryEvent.EditTaxAmount -> editable {
                copy(tax = tax.copy(amount = event.amount.coerceAtLeast(0.0), overridden = true))
            }
            EntryEvent.ResetTaxAmount -> editable { copy(tax = tax.copy(amount = null, overridden = false)) }
            is EntryEvent.SelectPo -> vm.update { copy(ledger = ledger?.copy(poIndex = event.index)) }
            EntryEvent.ShowFullscreen -> vm.update {
                copy(ledger = ledger?.let { open -> open.copy(fullscreen = open.preview != null) })
            }
            EntryEvent.HideFullscreen -> vm.update { copy(ledger = ledger?.copy(fullscreen = false)) }
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
     * Opens the coding screen: the row at once, then the full record, its
     * document and — when it has no coding of its own yet — the orders it is
     * linked to, whose lines it starts from. A row the reader may not open is
     * refused. Opened from the Entry queue it reads the invoice's unread, when
     * it has any ([InvoicesViewModel.readOpenEntry]); read-only, it reads nothing.
     */
    private fun open(row: Invoice, readOnly: Boolean) {
        val state = vm.state.value
        if (!readOnly && !state.canAccessEntry(row)) return
        vm.update { copy(ledger = EntryLedger(invoice = row, readOnly = readOnly)) }
        vm.readOpenEntry()
        loadChart()
        vm.loadEntryRefs()
        loadTrackingSets()
        loadCurrencyCatalogue()
        vm.run {
            val invoice = (vm.repo.invoice(row.id) as? ZillitResult.Success)?.data ?: row
            val hasOrders = invoice.linkedPos.isNotEmpty()
            vm.update {
                val open = ledger?.takeIf { it.invoice.id == row.id } ?: return@update this
                copy(
                    ledger = open.copy(
                        invoice = invoice,
                        loading = false,
                        header = seededHeader(invoice),
                        // Saved lines at once; an uncoded invoice waits for its orders' lines.
                        lines = if (invoice.lineItems.isNotEmpty() || !hasOrders) {
                            EntryCoding.seedLines(invoice, emptyList(), ::newId)
                        } else {
                            emptyList()
                        },
                        tax = invoice.taxLine ?: TaxLine(),
                        ordersLoading = hasOrders,
                    ),
                )
            }
            loadPreview(invoice)
            if (hasOrders) loadOrders(invoice)
        }
    }

    /** The linked orders, for the details card — and the lines of an invoice not coded yet. */
    private suspend fun loadOrders(invoice: Invoice) {
        val orders = vm.repo.linkedPos(invoice.id).getOrNull().orEmpty()
        vm.update {
            val open = ledger?.takeIf { it.invoice.id == invoice.id } ?: return@update this
            val seeded = if (open.lines.isEmpty()) {
                EntryCoding.seedLines(invoice, EntryCoding.poLines(orders, ::newId), ::newId)
            } else {
                open.lines
            }
            copy(ledger = open.copy(orders = orders, lines = seeded, ordersLoading = false))
        }
    }

    /**
     * The header as the record has it, with the web's auto-fill: a lone bank
     * is picked, and a company follows the picked bank's entity — or the only
     * company there is.
     */
    private fun seededHeader(invoice: Invoice): EntryHeader {
        val state = vm.state.value
        return EntryCoding.autoFill(EntryHeader.of(invoice), state.banks, state.companies)
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

    /** The Layers sets the grid's picker offers (`trackingSetsApi.listSets`). */
    private fun loadTrackingSets() {
        if (vm.state.value.trackingSets.isNotEmpty()) return
        vm.run { vm.repo.trackingSets().getOrNull()?.let { sets -> vm.update { copy(trackingSets = sets) } } }
    }

    /** The currency catalogue — what a picked company's country resolves its currency through. */
    private fun loadCurrencyCatalogue() {
        if (vm.state.value.currencyCatalogue.isNotEmpty()) return
        vm.run {
            vm.repo.currencyCatalogue().getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { rows -> vm.update { copy(currencyCatalogue = rows) } }
        }
    }

    // -- writes ------------------------------------------------------------------

    /** Changes the open ledger unless it is frozen — posted, or in a closed period. */
    private fun editable(change: EntryLedger.() -> EntryLedger) {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy) return
        vm.update { copy(ledger = this.ledger?.change()) }
    }

    /**
     * A header edit, with the auto-fill re-run (`EntryDetailModal.jsx:512-517`)
     * and an effective date inside the closed period refused — the web's
     * picker bars it with `min` (`:1396`). A date still being typed is let be.
     */
    private fun editHeader(header: EntryHeader) = editable {
        val state = vm.state.value
        val date = header.effectiveDate
        val locked = date != this.header.effectiveDate &&
            InvoiceFormat.parseDateInput(date) != null &&
            state.periodLock.isLocked(date)
        val kept = if (locked) header.copy(effectiveDate = this.header.effectiveDate) else header
        copy(header = EntryCoding.autoFill(kept, state.banks, state.companies), problem = null)
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
            // Recoded in another currency: the coded totals become the invoice's own.
            amounts = ledger.totals(state.taxTypes, state.taxTypesKnown)
                .takeIf { ledger.currencyChanged(state.projectCurrency) },
        )
    }

    /**
     * Save keeps the screen open and changes no status; the lines must
     * reconcile first — unless the currency changed, when there is nothing
     * left to reconcile to.
     */
    private fun save() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy || ledger.ordersLoading) return
        if (ledger.mismatch(vm.state.value)) {
            problem(str(S.desktop_inv_lines_must_match_save))
            return
        }
        act(EntryAction.Saving) {
            val result = vm.repo.saveEntryWithMessage(ledger.invoice.id, write(ledger))
            finish(result, closes = false, done = str(S.desktop_inv_invoice_saved))
        }
    }

    /**
     * Post to Ledger — the whole header and coding saved first, then
     * `POST /:id/post`, so what posts is exactly what Save would store
     * (ZL-20476). Refused, in the web's order, without a bank, in a closed
     * period, with lines that do not reconcile, without an effective date,
     * with a line that has no nominal, or with a described line left at zero.
     */
    private fun post() {
        val state = vm.state.value
        val ledger = state.ledger ?: return
        if (ledger.readOnly || ledger.busy || ledger.ordersLoading || !state.viewer.canPostToLedger) return
        val block = EntryCoding.postBlock(
            header = ledger.header,
            lines = ledger.lines,
            tax = ledger.tax,
            taxTypes = state.taxTypes,
            taxTypesKnown = state.taxTypesKnown,
            invoiceGross = ledger.invoice.grossAmount,
            locked = state.isLocked(ledger.invoice),
            currencyChanged = ledger.currencyChanged(state.projectCurrency),
        )
        if (block != null) {
            problem(block.alert() ?: return)
            return
        }
        act(EntryAction.Posting) {
            val saved = vm.repo.saveEntryWithMessage(ledger.invoice.id, write(ledger))
            val result = if (saved is ZillitResult.Failure) saved else vm.repo.postInvoiceWithMessage(ledger.invoice.id)
            finish(result, closes = true, done = str(S.ah_posted_to_ledger_toast))
        }
    }

    /** A junior's hand-off: the header and coding saved with `under_review`. Seniors are the reviewers. */
    private fun submitForReview() {
        val state = vm.state.value
        val ledger = state.ledger ?: return
        if (frozen(ledger) || ledger.busy || ledger.ordersLoading || state.viewer.isSenior) return
        act(EntryAction.Reviewing) {
            val result = vm.repo.saveEntryWithMessage(ledger.invoice.id, write(ledger, status = UNDER_REVIEW))
            finish(result, closes = true, done = str(S.ah_submitted_for_review_toast))
        }
    }

    /** `POST /:id/return-to-approval` — the server resets the chain itself. */
    private fun returnToApproval() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger) || ledger.busy) return
        act(EntryAction.Returning) {
            val result = vm.repo.returnToApprovalWithMessage(ledger.invoice.id)
            finish(result, closes = true, done = str(S.desktop_inv_sent_back_for_approval))
        }
    }

    /** The assign sheet, over this one invoice. */
    private fun assign() {
        val ledger = vm.state.value.ledger ?: return
        if (frozen(ledger)) return
        vm.update { copy(assignFor = AssignRequest(invoiceIds = listOf(ledger.invoice.id)), assignees = vm.team()) }
    }

    /** The history, read afresh every time it opens — the web's `HistoryPanel` fetches on open. */
    private fun showHistory() {
        val ledger = vm.state.value.ledger ?: return
        vm.update { copy(ledger = this.ledger?.copy(historyOpen = true, historyLoading = true)) }
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

    /** Re-reads the open invoice after an assignment or a save, so its new owner and amounts show. */
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

    /**
     * A landed write closes the screen or re-reads it; a refused one leaves
     * everything as typed. The toast is the server's own word when it sent
     * one (`showApiSuccess(res)`), else [done].
     */
    private fun finish(result: ZillitResult<String?>, closes: Boolean, done: String) {
        when (result) {
            is ZillitResult.Failure -> vm.update {
                copy(ledger = ledger?.copy(action = null), error = result.error.localised())
            }
            is ZillitResult.Success -> {
                vm.update { copy(ledger = if (closes) null else ledger?.copy(action = null)) }
                vm.notice(result.data?.localisedMessage()?.takeIf { it.isNotBlank() } ?: done)
                if (!closes) reloadOpen()
                vm.refresh()
            }
        }
    }

    private companion object {
        const val UNDER_REVIEW = "under_review"
    }
}

/**
 * The ledger's and Quick Entry's pickers' lists — the chart with its names and
 * the account tags — read the first time either opens.
 */
internal fun InvoicesViewModel.loadEntryRefs() {
    if (state.value.entryRefs.loaded) return
    update { copy(entryRefs = entryRefs.copy(loaded = true)) }
    this.run {
        val accounts = repo.nominalCodes().getOrNull()
        val tags = repo.projectSettings().getOrNull()?.assetTags
        update {
            copy(
                entryRefs = entryRefs.copy(
                    accounts = accounts ?: entryRefs.accounts,
                    assetTags = tags ?: entryRefs.assetTags,
                ),
            )
        }
    }
}

/**
 * The alert the web raises for each block; the silent ones — no bank, a
 * closed period — have their own hint on screen (the disabled Post and its
 * tooltip, the lock banner), as the web's `handlePost` returns without one.
 * The web says "need" for one line and for several.
 */
private fun EntryBlock.alert(): String? = when (this) {
    EntryBlock.NoBank -> null
    EntryBlock.Locked -> null
    EntryBlock.Mismatch -> str(S.desktop_inv_lines_must_match_post)
    EntryBlock.NoEffectiveDate -> str(S.desktop_po_effective_date_before_posting)
    is EntryBlock.MissingNominal -> if (rows.size == 1) {
        str(S.desktop_inv_line_need_nominal, rows.single().toString())
    } else {
        str(S.desktop_inv_lines_need_nominal, rows.joinToString(", "))
    }
    is EntryBlock.MissingAmount -> if (rows.size == 1) {
        str(S.desktop_inv_line_need_amount, rows.single().toString())
    } else {
        str(S.desktop_inv_lines_need_amount, rows.joinToString(", "))
    }
}

/** The coded gross against the invoice's own, to the penny — never once the currency changed. */
private fun EntryLedger.mismatch(state: InvoicesUiState): Boolean =
    !currencyChanged(state.projectCurrency) &&
        EntryCoding.amountMismatch(totals(state.taxTypes, state.taxTypesKnown).gross, invoice.grossAmount)

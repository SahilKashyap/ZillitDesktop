package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryTotals
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.LinkedPoDetail
import com.zillit.desktop.feature.invoices.domain.QueryThread
import com.zillit.desktop.feature.invoices.domain.TaxLine
import com.zillit.desktop.feature.invoices.domain.TaxType

/** Which of the ledger view's writes is in flight — only its button spins. */
enum class EntryAction { Saving, Posting, Returning, Reviewing }

/**
 * Invoice Entry's coding screen — the web's full-page `EntryDetailModal`.
 *
 * Opened from an entry row, it replaces the queue in the content column: the
 * header fields, the coded lines with their split and tax, the checks, and the
 * top bar that saves, posts, submits, assigns, returns or queries.
 */
data class EntryLedger(
    val invoice: Invoice,
    val loading: Boolean = true,
    val header: EntryHeader = EntryHeader.of(invoice),
    val lines: List<CodedLine> = emptyList(),
    val tax: TaxLine = TaxLine(),
    val selectedLineId: String? = null,
    /** The linked orders, for the details card and for seeding uncoded lines. */
    val orders: List<LinkedPoDetail> = emptyList(),
    val preview: AttachmentBytes? = null,
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
    val action: EntryAction? = null,
    /** The web's alert: why the last Save or Post was refused. */
    val problem: String? = null,
    val history: List<HistoryEntry>? = null,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    /** Opened from Posted: nothing on it may change. */
    val readOnly: Boolean = false,
    /** The linked orders are still being read — the grid's "Loading PO line items...". */
    val ordersLoading: Boolean = false,
    /** Which linked order the PO details card shows (`selectedPoIdx`). */
    val poIndex: Int = 0,
    /** The document opened over everything — the web's Fullscreen viewer. */
    val fullscreen: Boolean = false,
) {
    val busy: Boolean get() = action != null

    fun totals(taxTypes: List<TaxType>, taxTypesKnown: Boolean): EntryTotals =
        EntryCoding.totals(lines, tax, taxTypes, taxTypesKnown)

    val selectedLine: CodedLine? get() = lines.firstOrNull { it.id == selectedLineId }

    /** Only a parent can be split — the web's `canSplit`. */
    val canSplit: Boolean get() = selectedLine?.isSplit == false

    fun hasSplits(id: String): Boolean = lines.any { it.splitParentId == id }

    /**
     * Recoded in another currency than the invoice was entered in: the stored
     * amounts no longer apply, nothing is matched to them, and a save writes
     * the coded totals as the new ones (`currencyChanged`).
     */
    fun currencyChanged(defaultCurrency: String): Boolean =
        EntryCoding.isCurrencyChanged(header.currency, invoice.currency, defaultCurrency)

    /** The order the PO details card shows. */
    val shownOrder: LinkedPoDetail? get() = orders.getOrNull(poIndex) ?: orders.firstOrNull()
}

/**
 * What the ledger's and Quick Entry's pickers offer beyond Production Setup's
 * companies and tax types: the chart with its names (`CoaCodeInput`) and the
 * account tags (`TagMultiSelect`, project settings' `asset_tags`). Read the
 * first time either opens.
 */
data class EntryRefs(
    val accounts: List<InvoiceNominal> = emptyList(),
    val assetTags: List<String> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * A record's query thread, open in its side panel — the hub's `QueryPanel`.
 * [subtitle] names the record, as the web's panel header does.
 */
data class QueryView(
    val invoiceId: String,
    val subtitle: String,
    val thread: QueryThread = QueryThread(),
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
    /** User id → designation, shown beside an author's name as the web's panel does. */
    val roles: Map<String, String> = emptyMap(),
)

/**
 * Quick Entry's form — a single-code invoice that goes straight to
 * ready-to-pay. Amounts are kept as typed.
 */
data class QuickEntryDraft(
    val reference: String = "",
    val vendorId: String = "",
    val nominal: String = "",
    val costCentre: String = "",
    val net: String = "",
    /** A tax type's identifier; blank = no tax. */
    val taxType: String = "",
    /** `YYYY-MM-DD`; blank = none. */
    val effectiveDate: String = "",
    val busy: Boolean = false,
    /** The classification tags (`quickTags`). */
    val tags: List<String> = emptyList(),
    /**
     * A vendor typed that does not exist yet — `usePendingVendor`: created on
     * Post, just before the invoice, so an abandoned entry leaves none behind.
     */
    val pendingVendorName: String? = null,
) {
    val netValue: Double? get() = net.trim().replace(",", "").toDoubleOrNull()

    /** The web's gate: a reference and a net, nothing more. */
    val isReady: Boolean get() = reference.isNotBlank() && net.isNotBlank()
}

/** The coding screen's own events, routed to [InvoiceEntryActions]. */
sealed interface EntryEvent : InvoicesEvent {
    data class Open(val invoice: Invoice, val readOnly: Boolean = false) : EntryEvent
    data object Close : EntryEvent
    data class EditHeader(val header: EntryHeader) : EntryEvent

    /** Banks or companies landed after the view opened: the auto-fill runs again (`:512-517`). */
    data object AutoFill : EntryEvent
    data class SelectLine(val id: String?) : EntryEvent

    /** A parent line's fields; an amount change re-cuts its split. */
    data class EditLine(val line: CodedLine) : EntryEvent

    /** A split child's amount; its siblings share what is left of the parent. */
    data class EditSplitAmount(val id: String, val amount: Double) : EntryEvent

    data object AddLine : EntryEvent
    data object SplitLine : EntryEvent
    data class RemoveLine(val id: String) : EntryEvent
    data class EditTaxAccount(val account: String) : EntryEvent

    /** The tax row's own Layers and tags. */
    data class EditTaxLayers(val codes: Map<String, String>) : EntryEvent
    data class EditTaxTags(val tags: List<String>) : EntryEvent

    /** Which linked order the PO details card shows. */
    data class SelectPo(val index: Int) : EntryEvent

    /** The document, fullscreen over everything; and back. */
    data object ShowFullscreen : EntryEvent
    data object HideFullscreen : EntryEvent

    /** A typed tax amount overrides the derived one until reset. */
    data class EditTaxAmount(val amount: Double) : EntryEvent
    data object ResetTaxAmount : EntryEvent
    data object Save : EntryEvent
    data object Post : EntryEvent
    data object SubmitForReview : EntryEvent
    data object ReturnToApproval : EntryEvent
    data object Assign : EntryEvent
    data object ShowHistory : EntryEvent
    data object HideHistory : EntryEvent
    data object DismissProblem : EntryEvent

    // -- Quick Entry ---------------------------------------------------------

    data object StartQuick : EntryEvent
    data class EditQuick(val draft: QuickEntryDraft) : EntryEvent
    data object PostQuick : EntryEvent
    data object CancelQuick : EntryEvent
}

/** A record's query thread: opened, typed in, sent, closed. */
sealed interface QueryEvent : InvoicesEvent {
    data class Open(val invoice: Invoice) : QueryEvent
    data class Draft(val text: String) : QueryEvent
    data object Send : QueryEvent
    data object Close : QueryEvent
}

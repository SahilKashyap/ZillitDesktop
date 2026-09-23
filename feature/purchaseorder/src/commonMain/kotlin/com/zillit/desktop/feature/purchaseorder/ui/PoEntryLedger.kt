package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.feature.purchaseorder.domain.PoEntryHeader
import com.zillit.desktop.feature.purchaseorder.domain.PoEntryUpdate
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoPostRequest
import com.zillit.desktop.feature.purchaseorder.domain.PoTotals
import com.zillit.desktop.feature.purchaseorder.domain.PoVat
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import kotlin.math.abs
import kotlin.math.round

/**
 * The processing page's figures, the web's `POEntry` arithmetic in one place.
 *
 * The coded lines carry their own tax, and the page adds **one consolidated
 * tax line** — the reclaimable tax, posted to the reclaim nominal. It is not an
 * editable ledger line (it is dropped from [PoEntryState.lines] on open) but it
 * is part of what is saved and posted, and it is rebuilt from the persisted row
 * so its id, code, tags and tracking survive: dropping it on open and never
 * appending it again is how a save deleted the order's tax row.
 *
 * [recoverable] is the tax types whose tax the production reclaims; [ready] is
 * whether that list has loaded. Until it has, the reclaimable sum reads 0 and
 * substituting a hydrated amount would overshoot, so the substitution base is
 * the effective amount and the totals are the raw ones — the web's
 * `taxSubBase`.
 */
internal class PoEntryLedger(
    private val entry: PoEntryState,
    recoverable: Set<String>,
    ready: Boolean,
) {
    private val raw = PoTotals.of(entry.lines)

    /** The reclaimable tax the lines derive — the web's `computeReclaimableTax`. */
    val reclaimable: Double = round2(raw.reclaimable(entry.lines, recoverable))

    /** What the tax line posts: a typed figure when there is one, else the derived one. */
    val taxAmount: Double = entry.taxOverride?.let(::round2) ?: reclaimable

    private val base: Double = if (ready) reclaimable else taxAmount

    /** The coded net — `calcLedgerTotal`, parent lines only. */
    val net: Double = raw.net
    val tax: Double = raw.tax - base + taxAmount
    val gross: Double = raw.gross - base + taxAmount

    /** Whether the tax line is sent: an amount, or an explicit override — a typed 0 must persist. */
    val sendsTaxLine: Boolean = taxAmount > 0 || entry.taxOverride != null

    /** The web's `calcVat(ledgerTotal, vatTreatment)`. */
    val vat: PoVat = PoVat.of(net, entry.vatTreatment)

    /** Exactly what Save and Post send: the coded lines, then the tax line when it is sent. */
    val linesWithTax: List<PoLine>
        get() = if (sendsTaxLine) entry.lines + taxLine() else entry.lines

    /**
     * The consolidated tax line — the persisted row's id reused so the server
     * updates it in place rather than adding a second one.
     */
    private fun taxLine(): PoLine = PoLine(
        id = entry.taxLine?.id ?: "tax-${entry.orderId}",
        description = "",
        quantity = 1.0,
        unitPrice = taxAmount,
        nominalCode = entry.taxCode.trim().takeIf { it.isNotEmpty() },
        vatRate = null,
        amount = taxAmount,
        tags = entry.taxLine?.tags.orEmpty(),
        isTax = true,
        trackingCodes = entry.taxLine?.trackingCodes,
        customFields = entry.taxLine?.customFields,
    )

    /**
     * Whether the coded gross reconciles to the order's — gross to gross, to the
     * penny, over *or* under. An order with no gross never blocks: there is
     * nothing authoritative to match (the web's `isAmountMismatch`).
     */
    fun balances(order: PurchaseOrder): Boolean = order.gross <= 0 || abs(gross - order.gross) < PENNY

    /**
     * The 1-based rows (of [linesWithTax], which is what Post sends) that would
     * reach the ledger with no nominal — the web's `missingNominalLines`. An
     * untouched placeholder (no description, no amount) never counts.
     */
    val missingCodes: List<Int>
        get() = linesWithTax.mapIndexedNotNull { index, line ->
            val placeholder = line.description.isBlank() && line.total == 0.0
            (index + 1).takeIf { !placeholder && line.nominalCode.isNullOrBlank() }
        }

    fun update(header: PoEntryHeader): PoEntryUpdate =
        PoEntryUpdate(header = header, lines = linesWithTax, vat = vat, effectiveDate = entry.effectiveDate)

    fun post(order: PurchaseOrder, header: PoEntryHeader): PoPostRequest = PoPostRequest(
        header = header,
        lines = linesWithTax,
        vat = vat,
        effectiveDate = entry.effectiveDate,
        description = order.description,
        notes = order.notes,
        episode = order.episode,
        deliveryAddress = order.deliveryAddressRaw,
        deliveryAddressId = order.deliveryAddressId,
    )

    private companion object {
        const val PENNY = 0.01
        const val CENTS = 100.0

        fun round2(value: Double): Double = round(value * CENTS) / CENTS
    }
}

/** The page's figures for this state — the tax types' reclaimability read off [state]. */
internal fun PoEntryState.ledger(state: PoUiState): PoEntryLedger = PoEntryLedger(
    entry = this,
    recoverable = state.taxTypes.filter { it.recoverable }.map { it.id }.toSet(),
    ready = state.taxTypes.isNotEmpty(),
)

/** The header the page edits, as both writes send it. */
internal fun PoEntryState.header(defaultCurrency: String): PoEntryHeader = PoEntryHeader(
    vendorId = vendorId,
    companyId = companyId,
    departmentId = departmentId,
    nominalCode = nominalCode.trim().takeIf { it.isNotEmpty() },
    currency = currency?.takeIf { it.isNotBlank() } ?: defaultCurrency,
    deliveryDate = deliveryDate,
)

/**
 * The page opened on [order]: its lines minus the persisted tax row, which is
 * kept apart as the consolidated line's metadata and its amount hydrated as a
 * sticky override — what was saved is what shows, however the tax types load.
 *
 * A line with no code of its own starts on the order's header code, as the web
 * seeds its ledger lines (`item.account || po.nominalCode`); the web's final
 * `'2100'` fallback is not copied, because a made-up nominal is worse than an
 * empty one the post gate will catch.
 */
internal fun PurchaseOrder.toEntry(): PoEntryState {
    val taxRow = lines.firstOrNull { it.isTax }
    val header = nominalCode?.trim()?.takeIf { it.isNotEmpty() }
    return PoEntryState(
        orderId = id,
        lines = lines.filterNot { it.isTax }.map { line ->
            if (line.nominalCode.isNullOrBlank() && header != null) line.copy(nominalCode = header) else line
        },
        effectiveDate = effectiveDate,
        nominalCode = nominalCode.orEmpty(),
        vendorId = vendorId,
        companyId = companyId,
        departmentId = departmentId,
        currency = currency,
        deliveryDate = deliveryDate,
        vatTreatment = vatTreatmentOrPending,
        taxLine = taxRow,
        taxOverride = taxRow?.total,
        taxCode = taxRow?.nominalCode.orEmpty(),
    )
}

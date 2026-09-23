package com.zillit.desktop.feature.invoices.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * One coded line of an invoice on the ledger — the web's `EntryDetailModal`
 * line shape.
 *
 * [amount] is the line's net. A split child carries [splitParentId]; the
 * parent keeps its own amount and its children must add up to it. Anything
 * the desktop does not edit (layers, tags, custom fields, rental dates) rides
 * along untouched on the wire, keyed by [id].
 */
data class CodedLine(
    val id: String,
    val description: String = "",
    /** The nominal code, as typed. */
    val account: String = "",
    val amount: Double = 0.0,
    val quantity: Double = 1.0,
    val unitPrice: Double = amount,
    /** Percent — `20.0` for 20%; null when no tax is picked. */
    val taxRate: Double? = null,
    /** The Production Setup tax type's identifier, when one was picked. */
    val taxType: String = "",
    val expenditureType: String = "",
    val splitParentId: String? = null,
    /** The order this line was seeded from, when it came off a linked PO. */
    val sourcePo: String = "",
) {
    val isSplit: Boolean get() = splitParentId != null

    /** An untouched placeholder — no words and no money; never blocks a post. */
    val isBlank: Boolean get() = description.isBlank() && amount == 0.0

    /** Amount entered directly: the web pins quantity 1 and unit price = amount. */
    fun withAmount(value: Double): CodedLine = copy(amount = value, quantity = 1.0, unitPrice = value)
}

/**
 * The persisted reclaimable-tax line (`is_tax`) — kept apart from the coded
 * lines, as the web does, and appended on save.
 *
 * [overridden] means a typed amount wins over the derived one; a tax line that
 * was saved before hydrates as overridden, so reopening an invoice never moves
 * its tax.
 */
data class TaxLine(
    val account: String = "",
    val amount: Double? = null,
    val overridden: Boolean = false,
)

/** A tax type from Production Setup — what the line's Tax select offers. */
data class TaxType(
    val identifier: String,
    val label: String,
    /** Percent; null when the type carries no rate. */
    val rate: Double? = null,
    val isRecoverable: Boolean = false,
    val country: String = "",
) {
    /** `VAT · 20%`, the web's option label. */
    val optionLabel: String get() = rate?.let { "$label · ${trimRate(it)}%" } ?: label

    private fun trimRate(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/** What the coded lines add up to — parents only; a split's children re-cut its parent. */
data class EntryTotals(val net: Double, val tax: Double, val gross: Double)

/** The header fields of the ledger view, as typed. Dates are `YYYY-MM-DD`. */
data class EntryHeader(
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val effectiveDate: String = "",
    val payMethod: PayMethod = PayMethod.Bacs,
    /** Blank = the project default; left off the wire so it cannot wipe what is stored. */
    val currency: String = "",
    val companyId: String = "",
    val bankId: String = "",
    val episode: String = "",
) {
    companion object {
        fun of(invoice: Invoice): EntryHeader = EntryHeader(
            invoiceNumber = invoice.invoiceNumber,
            invoiceDate = InvoiceFormat.toDateInput(invoice.invoiceDateMs),
            dueDate = InvoiceFormat.toDateInput(invoice.dueDateMs),
            effectiveDate = InvoiceFormat.toDateInput(invoice.effectiveDateMs),
            payMethod = invoice.payMethod,
            currency = invoice.currency,
            companyId = invoice.companyId,
            bankId = invoice.bankId,
            episode = invoice.episode,
        )
    }
}

/**
 * Why Post to Ledger is refused, in the web's order: the bank first (the
 * server rejects a post without one), then the period lock, the coded total,
 * the effective date and finally the nominal on every line.
 */
sealed interface EntryBlock {
    data object NoBank : EntryBlock
    data object Locked : EntryBlock
    data object Mismatch : EntryBlock
    data object NoEffectiveDate : EntryBlock

    /** 1-based line numbers, as the web's alert names them. */
    data class MissingNominal(val rows: List<Int>) : EntryBlock
}

/**
 * One save of the ledger view — the header always, the lines when the action
 * writes them, the reclaimable-tax line when it is sent, and a status for
 * Submit for Review. [savedLinesJson] and [chart] let the wire keep what this
 * client does not edit and wrap a nominal the chart has not got.
 */
data class EntryWrite(
    val header: EntryHeader,
    val lines: List<CodedLine>? = null,
    val taxLine: TaxLine? = null,
    val taxAmount: Double = 0.0,
    val savedLinesJson: String = "",
    val chart: Set<String> = emptySet(),
    val status: String? = null,
)

/** A single-code invoice posted straight to ready-to-pay — the web's Quick Entry. */
data class QuickEntry(
    val reference: String,
    val vendorId: String,
    val nominal: String,
    val costCentre: String,
    val net: Double,
    val taxRate: Double?,
    /** `YYYY-MM-DD`; blank = none. */
    val effectiveDate: String,
    /** Today, `YYYY-MM-DD` — the invoice and due date. */
    val today: String,
)

/** One line of the data-validation checklist. */
enum class EntryCheck { VendorExists, DateInPeriod, NoDuplicate, ApprovalComplete, BankVerified, NominalsValid }

/**
 * The ledger view's rules — `EntryDetailModal.jsx` and `lib/lineItemSplit.js`,
 * kept free of the screen so each can be tested on its own.
 */
object EntryCoding {

    /**
     * The lines the view opens with, in the web's order of preference: the
     * invoice's own saved lines, else the linked orders' lines, else one line
     * for the whole invoice.
     */
    fun seedLines(invoice: Invoice, poLines: List<CodedLine>, newId: () -> String): List<CodedLine> = when {
        invoice.lineItems.isNotEmpty() -> invoice.lineItems
        poLines.isNotEmpty() -> poLines
        else -> listOf(
            CodedLine(
                id = newId(),
                description = invoice.description.ifBlank { DEFAULT_DESCRIPTION },
                amount = invoice.grossAmount,
            ),
        )
    }

    /**
     * The linked orders' lines as coded lines — what an uncoded invoice with
     * orders opens with. Each remembers the order it came from.
     */
    fun poLines(orders: List<LinkedPoDetail>, newId: () -> String): List<CodedLine> = orders.flatMap { order ->
        order.lines.map { line ->
            val amount = line.total ?: ((line.quantity ?: 1.0) * (line.unitPrice ?: 0.0))
            CodedLine(
                id = line.id.ifBlank { newId() },
                description = line.description,
                account = line.account,
                amount = amount,
                quantity = line.quantity ?: 1.0,
                unitPrice = line.unitPrice ?: amount,
                taxRate = line.taxRate,
                taxType = line.taxType,
                expenditureType = line.expenditureType,
                splitParentId = line.splitParentId,
                sourcePo = order.poNumber,
            )
        }
    }

    /** Derived reclaimable tax: recoverable tax on parent lines only. */
    fun reclaimableTax(lines: List<CodedLine>, taxTypes: List<TaxType>): Double {
        val recoverable = taxTypes.filter { it.isRecoverable }.map { it.identifier }.toSet()
        return round2(
            lines.filter { !it.isSplit && it.taxType in recoverable }
                .sumOf { it.amount * (it.taxRate ?: 0.0) / PERCENT },
        )
    }

    /** The tax line's amount: the typed override, else the derived sum. */
    fun effectiveTax(tax: TaxLine, lines: List<CodedLine>, taxTypes: List<TaxType>): Double =
        if (tax.overridden) round2(tax.amount ?: 0.0) else reclaimableTax(lines, taxTypes)

    /**
     * Net, tax and gross of the coding.
     *
     * The tax is the raw sum of every parent line's rate, with the reclaimable
     * part swapped for the tax line's effective amount — identical to the raw
     * sum until someone overrides the tax line. Until the tax types have
     * loaded ([taxTypesKnown] false) nothing is swapped.
     */
    fun totals(
        lines: List<CodedLine>,
        tax: TaxLine = TaxLine(),
        taxTypes: List<TaxType> = emptyList(),
        taxTypesKnown: Boolean = true,
    ): EntryTotals {
        val parents = lines.filter { !it.isSplit }
        val net = parents.sumOf { it.amount }
        val rawTax = parents.sumOf { it.amount * (it.taxRate ?: 0.0) / PERCENT }
        val effective = effectiveTax(tax, lines, taxTypes)
        val base = if (taxTypesKnown) reclaimableTax(lines, taxTypes) else effective
        val vat = rawTax - base + effective
        return EntryTotals(net = net, tax = vat, gross = net + vat)
    }

    /**
     * The coded gross must reconcile to the invoice's own gross, to the
     * penny, before Save or Post. A missing or zero invoice gross never
     * blocks — there is nothing authoritative to match (`isAmountMismatch`).
     */
    fun amountMismatch(codedGross: Double, invoiceGross: Double): Boolean {
        if (invoiceGross <= 0.0 || codedGross.isNaN()) return false
        return abs(round2(codedGross - invoiceGross)) > PENNY
    }

    /**
     * Lines that would post without a nominal — 1-based, over every line that
     * is posted. A blank placeholder line never counts.
     */
    fun missingNominalRows(lines: List<CodedLine>): List<Int> =
        lines.mapIndexedNotNull { index, line ->
            (index + 1).takeIf { !line.isBlank && line.account.trim().isEmpty() }
        }

    /**
     * The first reason Post to Ledger is refused, or null when it may go.
     * The tax line is checked only when it is actually sent.
     */
    fun postBlock(
        header: EntryHeader,
        lines: List<CodedLine>,
        tax: TaxLine,
        taxTypes: List<TaxType>,
        taxTypesKnown: Boolean,
        invoiceGross: Double,
        locked: Boolean,
    ): EntryBlock? {
        val totals = totals(lines, tax, taxTypes, taxTypesKnown)
        val sendsTax = effectiveTax(tax, lines, taxTypes) > 0.0 || tax.overridden
        val checked = if (sendsTax) lines + CodedLine(id = TAX_LINE_ID, account = tax.account, amount = 1.0) else lines
        val missing = missingNominalRows(checked)
        return when {
            header.bankId.isBlank() -> EntryBlock.NoBank
            locked -> EntryBlock.Locked
            amountMismatch(totals.gross, invoiceGross) -> EntryBlock.Mismatch
            header.effectiveDate.isBlank() -> EntryBlock.NoEffectiveDate
            missing.isNotEmpty() -> EntryBlock.MissingNominal(missing)
            else -> null
        }
    }

    /** Whether the tax line goes on the wire: a positive amount, or any typed override. */
    fun sendsTaxLine(tax: TaxLine, lines: List<CodedLine>, taxTypes: List<TaxType>): Boolean =
        effectiveTax(tax, lines, taxTypes) > 0.0 || tax.overridden

    /**
     * The data-validation checklist — `buildValidationItems`. Three checks the
     * web cannot verify yet pass on purpose, exactly as it passes them.
     */
    fun validation(invoice: Invoice, vendor: Vendor?): List<Pair<EntryCheck, Boolean>> = listOf(
        EntryCheck.VendorExists to (vendor != null),
        EntryCheck.DateInPeriod to true,
        EntryCheck.NoDuplicate to true,
        EntryCheck.ApprovalComplete to (
            invoice.approvalStatus == ApprovalStatus.Approved ||
                invoice.status !in setOf(InvoiceStatus.Approval, InvoiceStatus.Inbox, InvoiceStatus.Matching)
            ),
        EntryCheck.BankVerified to (vendor?.bankId?.isNotBlank() == true),
        EntryCheck.NominalsValid to true,
    )

    // -- editing ---------------------------------------------------------------

    /**
     * Changes one line.
     *
     * A parent whose amount moved has its children rescaled so they still add
     * up to it; a parent whose tax changed passes the tax down, because the
     * children inherited it when they were cut.
     */
    fun update(lines: List<CodedLine>, id: String, change: (CodedLine) -> CodedLine): List<CodedLine> {
        val before = lines.firstOrNull { it.id == id } ?: return lines
        val after = change(before)
        var next = lines.map { if (it.id == id) after else it }
        if (!after.isSplit) {
            if (after.taxRate != before.taxRate || after.taxType != before.taxType) {
                next = next.map {
                    if (it.splitParentId == id) it.copy(taxRate = after.taxRate, taxType = after.taxType) else it
                }
            }
            if (after.amount != before.amount) next = rescale(next, id)
        }
        return next
    }

    /** A child's own amount changed: the rest of its siblings share what is left. */
    fun redistribute(lines: List<CodedLine>, id: String, amount: Double): List<CodedLine> {
        val target = lines.firstOrNull { it.id == id } ?: return lines
        val parentId = target.splitParentId ?: return lines
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines
        val siblings = lines.filter { it.splitParentId == parentId && it.id != id }
        val remaining = parent.amount - amount
        val per = if (siblings.isEmpty()) 0.0 else round2(remaining / siblings.size)
        val last = if (siblings.isEmpty()) 0.0 else round2(remaining - per * (siblings.size - 1))
        var index = 0
        return lines.map { line ->
            when {
                line.id == id -> line.withAmount(amount)
                line.splitParentId == parentId -> {
                    val isLast = index == siblings.size - 1
                    index++
                    line.withAmount(if (isLast) last else per)
                }
                else -> line
            }
        }
    }

    /** Re-cuts a parent's children to its new amount, keeping their shares. */
    fun rescale(lines: List<CodedLine>, parentId: String): List<CodedLine> {
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines
        val children = lines.filter { it.splitParentId == parentId }
        if (children.isEmpty()) return lines
        val current = children.sumOf { it.amount }
        val shares = if (current > 0.0) children.map { it.amount / current } else children.map { 1.0 / children.size }
        val amounts = shares.map { round2(parent.amount * it) }.toMutableList()
        amounts[amounts.lastIndex] = round2(parent.amount - amounts.dropLast(1).sum())
        var index = 0
        return lines.map { if (it.splitParentId == parentId) it.withAmount(amounts[index++]) else it }
    }

    /**
     * Splits the selected line — or its parent, when a child is selected.
     *
     * The first split cuts the parent in two halves; each later one adds a
     * sibling and evens them all out, the last taking the rounding. Returns the
     * lines and the new selection.
     */
    fun split(lines: List<CodedLine>, selectedId: String?, newId: () -> String): Pair<List<CodedLine>, String?> {
        val source = lines.firstOrNull { it.id == selectedId } ?: return lines to selectedId
        val parentId = source.splitParentId ?: source.id
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines to selectedId
        val existing = lines.filter { it.splitParentId == parentId }
        val total = parent.amount
        fun child(amount: Double) = CodedLine(
            id = newId(),
            description = parent.description,
            account = parent.account,
            taxRate = parent.taxRate,
            taxType = parent.taxType,
            expenditureType = parent.expenditureType,
            splitParentId = parentId,
        ).withAmount(amount)
        val at = lines.indexOfFirst { it.id == source.id } + 1
        if (existing.isEmpty()) {
            val half = round2(total / 2)
            val first = child(half)
            val second = child(round2(total - half))
            return lines.toMutableList().apply { addAll(at, listOf(first, second)) } to first.id
        }
        val count = existing.size + 1
        val per = round2(total / count)
        val last = round2(total - per * (count - 1))
        var index = 0
        val evened = lines.map { line ->
            if (line.splitParentId == parentId) {
                val isLast = index == existing.size - 1
                index++
                line.withAmount(if (isLast) last else per)
            } else {
                line
            }
        }
        val added = child(per)
        return evened.toMutableList().apply { add(at, added) } to added.id
    }

    /** A new empty line at the end. */
    fun add(lines: List<CodedLine>, newId: () -> String): Pair<List<CodedLine>, String> {
        val line = CodedLine(id = newId())
        return lines + line to line.id
    }

    /**
     * Removes a line. The last parent line stays; removing a parent takes its
     * children with it; removing one of two children folds the split back into
     * its parent, and otherwise the siblings share the amount again.
     */
    fun remove(lines: List<CodedLine>, id: String): List<CodedLine> {
        val line = lines.firstOrNull { it.id == id } ?: return lines
        if (!line.isSplit) {
            val lastParent = lines.count { !it.isSplit } <= 1
            return if (lastParent) lines else lines.filter { it.id != id && it.splitParentId != id }
        }
        return removeChild(lines.filter { it.id != id }, line.splitParentId)
    }

    /** What is left of a split once one child goes: one child folds back, more share the parent. */
    private fun removeChild(remaining: List<CodedLine>, parentId: String?): List<CodedLine> {
        val siblings = remaining.filter { it.splitParentId == parentId }
        if (siblings.size == 1) return remaining.filter { it.id != siblings.single().id }
        val parent = remaining.firstOrNull { it.id == parentId } ?: return remaining
        val each = parent.amount / siblings.size
        return remaining.map { if (it.splitParentId == parentId) it.withAmount(each) else it }
    }

    /**
     * A nominal the chart does not have goes as `[[code]]`, so the server
     * creates it — `wrapNominal`. With no chart to check against nothing is
     * wrapped, and a case-only difference is the same code.
     */
    fun wrapNominal(code: String, chart: Set<String>): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || chart.isEmpty()) return trimmed
        if (trimmed in chart || chart.any { it.equals(trimmed, ignoreCase = true) }) return trimmed
        return "[[$trimmed]]"
    }

    /** To the penny, halves away from zero on the positive side — JavaScript's `Math.round`, not banker's. */
    fun round2(value: Double): Double = floor(value * CENTS + HALF) / CENTS

    const val TAX_LINE_ID = "__tax"
    private const val DEFAULT_DESCRIPTION = "Invoice"
    private const val PERCENT = 100.0
    private const val CENTS = 100.0
    private const val PENNY = 0.01
    private const val HALF = 0.5
}

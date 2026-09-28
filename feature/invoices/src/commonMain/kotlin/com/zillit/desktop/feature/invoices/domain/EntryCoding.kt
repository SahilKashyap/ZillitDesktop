package com.zillit.desktop.feature.invoices.domain

import kotlin.math.abs
import kotlin.math.floor

/**
 * One coded line of an invoice on the ledger — the web's `EntryDetailModal`
 * line shape.
 *
 * [amount] is the line's net. A split child carries [splitParentId]; the
 * parent keeps its own amount and its children must add up to it. Layers
 * ([trackingCodes]) and [tags] are edited here; what is only shown — custom
 * fields — or not shown at all — rental dates — rides along in [carried],
 * exactly as it was read (`EntryDetailModal.jsx:630-647`, `:674-692`).
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
    /** Layers — Layers set id → the picked code (`tracking_codes`). */
    val trackingCodes: Map<String, String> = emptyMap(),
    /** Account tags (`tags`). */
    val tags: List<String> = emptyList(),
    /**
     * What this client carries but never edits, as read off the saved row or
     * the PO line it came from. Null only for a line built without one (the
     * wire then falls back to the saved row with the same id).
     */
    val carried: CarriedFields? = null,
) {
    val isSplit: Boolean get() = splitParentId != null

    /** An untouched placeholder — no words and no money; never blocks a post. */
    val isBlank: Boolean get() = description.isBlank() && amount == 0.0

    /** Amount entered directly: the web pins quantity 1 and unit price = amount. */
    fun withAmount(value: Double): CodedLine = copy(amount = value, quantity = 1.0, unitPrice = value)
}

/**
 * A line's fields this client never edits — `custom_fields` and the rental
 * dates — as raw JSON text, so a save writes back exactly what was read.
 * [customFields] is the name → value view of `custom_fields` for the grid's
 * read-only columns.
 */
data class CarriedFields(
    val customFieldsJson: String = "[]",
    val rentalStartJson: String = "null",
    val rentalEndJson: String = "null",
    val customFields: List<Pair<String, String>> = emptyList(),
)

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
    /** The tax row's own Layers and tags (`taxLineMeta.trackingCodes` / `tags`). */
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
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
    /**
     * A stored pay method this client has no entry for, kept as its code so a
     * save writes it back rather than BACS (`payMethodCode`, `payMethod.js:147-150`).
     * Blank once a method is picked.
     */
    val payMethodCode: String = "",
) {
    /** What goes on the wire as `pay_method`. */
    val payWire: String get() = payMethodCode.ifBlank { payMethod.wire }

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
            payMethodCode = unknownPayCode(invoice.payMethodRaw),
        )

        /** The stored code when no [PayMethod] carries it; blank for a known one. */
        fun unknownPayCode(raw: String): String {
            val code = PayMethod.normalise(raw)
            return code.takeIf { known -> PayMethod.entries.none { it.wire == known } }.orEmpty()
        }
    }
}

/**
 * Why Post to Ledger is refused, in the web's order: the bank first (the
 * server rejects a post without one), then the period lock, the coded total,
 * the effective date, the nominal on every line and finally an amount on
 * every described line (`EntryDetailModal.jsx:950-976`).
 */
sealed interface EntryBlock {
    data object NoBank : EntryBlock
    data object Locked : EntryBlock
    data object Mismatch : EntryBlock
    data object NoEffectiveDate : EntryBlock

    /** 1-based line numbers, as the web's alert names them. */
    data class MissingNominal(val rows: List<Int>) : EntryBlock

    /** 1-based line numbers of described parent lines left at zero (`missingAmountLines`). */
    data class MissingAmount(val rows: List<Int>) : EntryBlock
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
    /**
     * The coded totals, written as the invoice's own net / tax / gross — only
     * when the currency was changed, and so the old figures no longer apply
     * (`amountsPayload`, `EntryDetailModal.jsx:1104-1110`).
     */
    val amounts: EntryTotals? = null,
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
    /** The classification tags picked (`quickTags`). */
    val tags: List<String> = emptyList(),
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
                // The PO's own coding comes across whole, or the first save wipes it (ZL parity H2).
                trackingCodes = line.trackingCodes,
                tags = line.tags,
                carried = line.carried ?: CarriedFields(),
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
     * The invoice is being recoded in another currency — `isCurrencyChanged`
     * (`lib/invoicePayload.js:56-58`): a picked currency that is not the
     * stored one (or, with none stored, the project's). A blank pick is never
     * a change; nor is a pick that only differs in case.
     */
    fun isCurrencyChanged(formCurrency: String, invoiceCurrency: String, defaultCurrency: String): Boolean {
        val picked = formCurrency.trim()
        if (picked.isEmpty()) return false
        val stored = invoiceCurrency.trim().ifEmpty { defaultCurrency.trim() }
        return !picked.equals(stored, ignoreCase = true)
    }

    /**
     * Described parent lines with no figure — 1-based over every line, as
     * `missingAmountLines` (`lib/coa.js:665-676`) counts them. Split children
     * are skipped (they never move the total) and a negative is real money.
     */
    fun missingAmountRows(lines: List<CodedLine>): List<Int> =
        lines.mapIndexedNotNull { index, line ->
            (index + 1).takeIf { !line.isBlank && !line.isSplit && line.amount == 0.0 }
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
    @Suppress("LongParameterList") // The web's handlePost reads each of these.
    fun postBlock(
        header: EntryHeader,
        lines: List<CodedLine>,
        tax: TaxLine,
        taxTypes: List<TaxType>,
        taxTypesKnown: Boolean,
        invoiceGross: Double,
        locked: Boolean,
        /** Recoded in another currency: the old gross no longer applies, so nothing is matched to it. */
        currencyChanged: Boolean = false,
    ): EntryBlock? {
        val totals = totals(lines, tax, taxTypes, taxTypesKnown)
        val sendsTax = effectiveTax(tax, lines, taxTypes) > 0.0 || tax.overridden
        // The web appends `{ id: "__tax", account }` — no description, no amount —
        // which its own `isBlankLine` then skips, so the tax row never blocks.
        val checked = if (sendsTax) lines + CodedLine(id = TAX_LINE_ID, account = tax.account, amount = 0.0) else lines
        val missing = missingNominalRows(checked)
        val unpriced = missingAmountRows(lines)
        return when {
            header.bankId.isBlank() -> EntryBlock.NoBank
            locked -> EntryBlock.Locked
            !currencyChanged && amountMismatch(totals.gross, invoiceGross) -> EntryBlock.Mismatch
            header.effectiveDate.isBlank() -> EntryBlock.NoEffectiveDate
            missing.isNotEmpty() -> EntryBlock.MissingNominal(missing)
            unpriced.isNotEmpty() -> EntryBlock.MissingAmount(unpriced)
            else -> null
        }
    }

    /**
     * The bank / company auto-fill (`resolveAutoFill`, `lib/companyBankAutoFill.js`):
     * a lone bank is picked; an empty company follows the picked bank's entity,
     * else the only company there is. Each rule only fills a blank, so a pick
     * is never overwritten, and a field cleared by hand is filled again. Run
     * to its fixed point — the web re-runs it on the next render, once the
     * bank it just picked has landed.
     */
    fun autoFill(header: EntryHeader, banks: List<BankAccount>, companies: List<Company>): EntryHeader {
        var next = header
        repeat(AUTO_FILL_PASSES) {
            val bank = next.bankId.ifBlank { banks.singleOrNull()?.id.orEmpty() }
            val company = next.companyId.ifBlank {
                val picked = next.bankId.takeIf { it.isNotBlank() }?.let { id -> banks.firstOrNull { it.id == id } }
                picked?.entityId?.takeIf { it.isNotBlank() } ?: companies.singleOrNull()?.id.orEmpty()
            }
            next = next.copy(bankId = bank, companyId = company)
        }
        return next
    }

    /**
     * A line after its Tax select moved to [picked]: none, a Production Setup
     * type (its rate comes with it), or Other.
     *
     * On the ledger ([keepRateOnOther] false) Other keeps the rate only when
     * the line was already Other — a 20% VAT line switched to Other has no
     * rate until one is typed (`EntryDetailModal.jsx:2076-2089`, ZL-20656).
     * The Credits / Sales editor keeps whatever rate the line had
     * (`LineItemsEditor.jsx:458-464`).
     */
    fun pickTax(line: CodedLine, picked: String, taxTypes: List<TaxType>, keepRateOnOther: Boolean): CodedLine {
        val type = taxTypes.firstOrNull { it.identifier == picked }
        return when {
            picked.isBlank() -> line.copy(taxType = "", taxRate = null)
            type != null -> line.copy(taxType = type.identifier, taxRate = type.rate)
            keepRateOnOther -> line.copy(taxType = OTHER_TAX, taxRate = line.taxRate ?: 0.0)
            else -> line.copy(taxType = OTHER_TAX, taxRate = line.taxRate.takeIf { line.taxType == OTHER_TAX })
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
     *
     * With [cascadeCoding] — the Credits / Sales editor (`LineItemsEditor.jsx:160-199`)
     * — the nominal, expenditure type, Layers and tags pass down too, and
     * every one of them (tax included) only reaches a child still holding the
     * parent's old value or nothing: a child coded on its own keeps its own.
     */
    fun update(
        lines: List<CodedLine>,
        id: String,
        cascadeCoding: Boolean = false,
        change: (CodedLine) -> CodedLine,
    ): List<CodedLine> {
        val before = lines.firstOrNull { it.id == id } ?: return lines
        val after = change(before)
        var next = lines.map { if (it.id == id) after else it }
        if (!after.isSplit) {
            next = next.map { child ->
                if (child.splitParentId != id) child else cascade(child, before, after, cascadeCoding)
            }
            if (after.amount != before.amount) next = rescale(next, id)
        }
        return next
    }

    /** What a parent's edit passes to one of its children. */
    private fun cascade(child: CodedLine, before: CodedLine, after: CodedLine, coding: Boolean): CodedLine {
        if (!coding) {
            // The ledger (`EntryDetailModal.jsx:778-786`): tax always follows the parent.
            val taxMoved = after.taxRate != before.taxRate || after.taxType != before.taxType
            return if (taxMoved) child.copy(taxRate = after.taxRate, taxType = after.taxType) else child
        }
        fun <T> follow(mine: T, old: T, new: T, empty: (T) -> Boolean): T =
            if (old != new && (empty(mine) || mine == old)) new else mine
        return child.copy(
            account = follow(child.account, before.account, after.account) { it.isBlank() },
            expenditureType = follow(child.expenditureType, before.expenditureType, after.expenditureType) {
                it.isBlank()
            },
            trackingCodes = follow(child.trackingCodes, before.trackingCodes, after.trackingCodes) { it.isEmpty() },
            tags = follow(child.tags, before.tags, after.tags) { it.isEmpty() },
            taxType = follow(child.taxType, before.taxType, after.taxType) { it.isBlank() },
            taxRate = follow(child.taxRate, before.taxRate, after.taxRate) { it == null },
        )
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
            // `makeChild` copies the parent's Layers and tags, never its custom fields.
            trackingCodes = parent.trackingCodes,
            tags = parent.tags,
            carried = CarriedFields(),
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
        val line = CodedLine(id = newId(), carried = CarriedFields())
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

    /** The web's `OTHER_TAX_OPTION` value — a rate typed in, not a Production Setup type. */
    const val OTHER_TAX = "other"
    private const val AUTO_FILL_PASSES = 2
    private const val DEFAULT_DESCRIPTION = "Invoice"
    private const val PERCENT = 100.0
    private const val CENTS = 100.0
    private const val PENNY = 0.01
    private const val HALF = 0.5
}

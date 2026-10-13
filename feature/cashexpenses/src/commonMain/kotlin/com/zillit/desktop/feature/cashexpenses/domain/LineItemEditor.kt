package com.zillit.desktop.feature.cashexpenses.domain

import kotlin.math.round

/**
 * A coding line as the editor holds it.
 *
 * ## Net here, gross on the wire
 *
 * The editor works in **net** — quantity × unit price, the way a coder thinks
 * about it — while the cash service stores `total` as **gross** and
 * `unit_price` as net. Converting at the boundary rather than in the UI is
 * what stops a rate change silently rewriting a total, and it is why
 * [toWire] re-derives gross from net and tax rather than passing through
 * whatever the row arrived with.
 */
data class EditorLine(
    val id: String,
    val description: String = "",
    val quantity: Double = 1.0,
    /** Net price per unit. */
    val unitPrice: Double = 0.0,
    /** Cost code. */
    val account: String = "",
    val taxType: String = "",
    /**
     * Whole percent — 20 for 20%, never 0.2.
     *
     * The wire has been written at both scales by two different surfaces; the
     * reader normalises on the way in (see [LineItemEditor.fromWire]) and this
     * side is canonical, so a legitimate 0.5% is not mistaken for a decimal.
     */
    val taxRatePercent: Double = 0.0,
    /** The line this one was split off, or null for a top-level line. */
    val splitParentId: String? = null,
    /** Engine-owned deduction rows are shown but never sent back. */
    val autoDeduction: Boolean = false,
    /** What the line carries that the editor does not edit, kept for the save. */
    val extras: LineExtras = LineExtras(),
) {
    val net: Double get() = round2(quantity * unitPrice)

    val tax: Double get() = round2(net * (taxRatePercent / WHOLE_PERCENT))

    val gross: Double get() = round2(net + tax)

    val isSplitChild: Boolean get() = splitParentId != null
}

/** The web's `OTHER_TAX_OPTION`: a rate typed in rather than a Production Setup tax type. */
const val OTHER_TAX = "other"

/**
 * What the line's Tax select shows: its tax type when the production has it,
 * [OTHER_TAX] for a type or rate it does not (display only — the line keeps
 * what it holds until somebody picks), blank for no tax.
 */
fun EditorLine.taxChoice(types: List<CashTaxType>): String = when {
    types.any { it.identifier == taxType } -> taxType
    taxType.isNotBlank() || taxRatePercent > 0 -> OTHER_TAX
    else -> ""
}

/** The line after [picked] is chosen in its Tax select: the type brings its rate with it. */
fun EditorLine.withTaxChoice(picked: String, types: List<CashTaxType>): EditorLine {
    val type = types.firstOrNull { it.identifier == picked }
    return when {
        picked.isBlank() -> copy(taxType = "", taxRatePercent = 0.0)
        type != null -> copy(taxType = type.identifier, taxRatePercent = type.rate ?: 0.0)
        else -> copy(taxType = OTHER_TAX)
    }
}

/**
 * A line's keys the editor shows nothing for — the tax flag, coding layers,
 * tags, rental dates, order — carried from the wire to the save untouched.
 *
 * The server deletes and reinserts every line on save, so a key the save
 * leaves out is a key the line loses: a tax line stops being one, a coded
 * line drops its layers.
 */
data class LineExtras(
    val isTax: Boolean = false,
    val taxAmount: Double? = null,
    val trackingCodes: kotlinx.serialization.json.JsonElement? = null,
    val tags: kotlinx.serialization.json.JsonElement? = null,
    val rentalStart: String? = null,
    val rentalEnd: String? = null,
    val sortOrder: Int? = null,
    val expenditureType: String? = null,
) {
    companion object {
        fun of(line: ClaimLineItem) = LineExtras(
            isTax = line.isTax,
            taxAmount = line.taxAmount,
            trackingCodes = line.trackingCodes,
            tags = line.tags,
            rentalStart = line.rentalStart,
            rentalEnd = line.rentalEnd,
            sortOrder = line.sortOrder,
            expenditureType = line.expenditureType,
        )
    }
}

/**
 * Reading and writing the cash service's `line_items`.
 *
 * Every rule here exists because the wire and the editor disagree about
 * something, and each disagreement has cost someone a wrong total:
 *
 *  - **gross vs net** — see [EditorLine];
 *  - **tax rate scale** — historically written as both `0.20` and `20`;
 *  - **split linkage** — the server deletes and reinserts the whole set on
 *    save, so a child's parent reference must be a real id that survives the
 *    round trip, not a client-generated one;
 *  - **engine-owned rows** — the processing-rules engine maintains its own
 *    deduction lines and re-derives them on every save, so sending them back
 *    duplicates them.
 */
object LineItemEditor {

    /**
     * Wire rows → editor lines.
     *
     * A child whose parent is not in the set is **promoted to a parent**
     * rather than dropped. The server regenerates ids on reinsert, so a stale
     * reference is expected rather than exceptional — and a child excluded
     * from the parents-only total is money that silently vanishes from the
     * screen.
     */
    fun fromWire(lines: List<ClaimLineItem>): List<EditorLine> {
        val mapped = lines.map { line ->
            val quantity = line.quantity.takeIf { it > 0 } ?: 1.0
            val percent = normaliseTaxRate(line.taxRate)
            // The stored gross is authoritative; a stale unit price is not.
            val gross = line.total
            val net = if (percent > 0) gross / (1 + percent / WHOLE_PERCENT) else gross
            EditorLine(
                id = line.id ?: "",
                description = line.description,
                quantity = quantity,
                unitPrice = round2(net / quantity),
                account = line.account.orEmpty(),
                taxType = line.taxType.orEmpty(),
                taxRatePercent = percent,
                splitParentId = line.splitParentId,
                autoDeduction = line.autoDeduction,
                extras = LineExtras.of(line),
            )
        }
        val known = mapped.mapTo(mutableSetOf()) { it.id }
        return mapped.map { line ->
            if (line.splitParentId != null && line.splitParentId !in known) {
                line.copy(splitParentId = null)
            } else {
                line
            }
        }
    }

    /**
     * Editor lines → wire rows, ready to save.
     *
     * Engine-owned deduction rows are dropped: the server re-derives exactly
     * one per matching rule on the next evaluation, and persisting the client's
     * copy is what made the same deduction appear twice, then three times.
     *
     * Client-generated ids are replaced with real ones from [newId], and every
     * child's parent reference is remapped through the same table — otherwise
     * a split saved once comes back as two unrelated lines.
     */
    fun toWire(lines: List<EditorLine>, newId: () -> String): List<ClaimLineItem> {
        val persisted = lines.filterNot { it.autoDeduction }
        val idMap = persisted.associate { line ->
            line.id to if (isRealId(line.id)) line.id else newId()
        }
        return persisted.map { line ->
            ClaimLineItem(
                id = idMap[line.id],
                account = line.account.takeIf { it.isNotBlank() },
                description = line.description,
                total = line.gross,
                taxRate = line.taxRatePercent.takeIf { it > 0 },
                autoDeduction = false,
                quantity = line.quantity,
                unitPrice = line.unitPrice,
                taxType = line.taxType.takeIf { it.isNotBlank() },
                // A parent that is not being saved (an engine row someone tried
                // to split off) leaves the child at the top level rather than
                // pointing at nothing.
                splitParentId = line.splitParentId?.let { idMap[it] },
                isTax = line.extras.isTax,
                taxAmount = line.extras.taxAmount,
                trackingCodes = line.extras.trackingCodes,
                tags = line.extras.tags,
                rentalStart = line.extras.rentalStart,
                rentalEnd = line.extras.rentalEnd,
                sortOrder = line.extras.sortOrder,
                expenditureType = line.extras.expenditureType,
            )
        }
    }

    /**
     * Splits [parentId] into [ways] equal children.
     *
     * The remainder lands on the **first** child rather than being spread or
     * dropped, so the children always sum to the parent exactly. Splitting
     * £100 three ways gives 33.34 / 33.33 / 33.33, not three lots of 33.33 and
     * a missing penny.
     */
    fun split(lines: List<EditorLine>, parentId: String, ways: Int, newId: () -> String): List<EditorLine> {
        if (ways < 2) return lines
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines
        if (parent.autoDeduction) return lines

        val share = round2(parent.net / ways)
        val remainder = round2(parent.net - share * ways)
        val children = (0 until ways).map { index ->
            parent.copy(
                id = newId(),
                quantity = 1.0,
                unitPrice = if (index == 0) round2(share + remainder) else share,
                splitParentId = parent.id,
                description = "${parent.description} (${index + 1}/$ways)".trim(),
                // A child is its own line: the parent's place in the order and
                // its tax figure belong to the parent.
                extras = parent.extras.copy(isTax = false, taxAmount = null, sortOrder = null),
            )
        }
        // The parent stays in the list as the thing being split — the totals
        // below count children in its place, which is what makes the split
        // reversible by deleting them.
        val at = lines.indexOfFirst { it.id == parentId }
        return lines.take(at + 1) + children + lines.drop(at + 1)
    }

    /** Removes a line and re-parents anything split off it. */
    fun remove(lines: List<EditorLine>, id: String): List<EditorLine> =
        lines.filterNot { it.id == id }
            .map { if (it.splitParentId == id) it.copy(splitParentId = null) else it }

    /**
     * What the coding comes to, counting each amount once.
     *
     * A split parent is **replaced** by its children rather than added to
     * them — counting both is how a coded batch ends up at twice its receipt
     * total.
     *
     * The reclaimable-tax line **stands in for** the recoverable tax already
     * inside the lines rather than adding to it: with [recoverable] tax types
     * known and a tax line present, the lines' reclaimable tax is taken off
     * and the line's own amount put on, as the web's `claimTaxAdj` does. A
     * derived line therefore changes nothing; a typed one moves the total by
     * the difference. With no recoverable types the line simply adds.
     */
    fun total(lines: List<EditorLine>, recoverable: Set<String> = emptySet()): Double {
        val splitParents = lines.mapNotNullTo(mutableSetOf()) { it.splitParentId }
        val gross = lines.filterNot { it.id in splitParents }.sumOf { it.gross }
        val standIn = if (lines.any { it.extras.isTax }) reclaimable(lines, recoverable) else 0.0
        return round2(gross - standIn)
    }

    /**
     * The tax inside the parent lines whose tax type is recoverable — what the
     * reclaimable-tax line posts when nobody has typed over it
     * (`computeReclaimableTax`). Split children and the tax line itself are
     * skipped: a child's tax is its parent's.
     */
    fun reclaimable(lines: List<EditorLine>, recoverable: Set<String>): Double {
        if (recoverable.isEmpty()) return 0.0
        return round2(
            lines.filter {
                !it.isSplitChild && !it.extras.isTax && !it.autoDeduction && it.taxType in recoverable
            }.sumOf { it.tax },
        )
    }

    /**
     * [lines] with the reclaimable-tax line brought in line with the lines'
     * recoverable tax, unless it has been [overridden] by a typed amount.
     *
     * The line is created when there is tax to reclaim, follows it while it
     * changes (keeping the nominal, layers and tags it was given), and is
     * dropped when there is none left and no nominal to keep.
     */
    fun withDerivedTax(
        lines: List<EditorLine>,
        recoverable: Set<String>,
        overridden: Boolean,
        newId: () -> String,
    ): List<EditorLine> {
        if (overridden || recoverable.isEmpty()) return lines
        val derived = reclaimable(lines, recoverable)
        val existing = lines.firstOrNull { it.extras.isTax }
        return when {
            existing == null && derived > 0 ->
                lines + EditorLine(id = newId(), unitPrice = derived, extras = LineExtras(isTax = true))
            existing == null -> lines
            derived <= 0 && existing.account.isBlank() -> lines.filterNot { it.extras.isTax }
            else -> lines.map { if (it.extras.isTax) it.copy(unitPrice = derived, quantity = 1.0) else it }
        }
    }

    /**
     * Whether the coding matches the receipt it belongs to.
     *
     * Compared to the penny: coding that does not reach the receipt total is
     * the single most common reason a batch bounces back from audit, and
     * catching it in the editor saves a round trip through two queues.
     */
    fun balances(lines: List<EditorLine>, receiptGross: Double, recoverable: Set<String> = emptySet()): Boolean =
        kotlin.math.abs(total(lines, recoverable) - receiptGross) < PENNY

    /**
     * Reads a wire tax rate at either scale.
     *
     * Below 1 is treated as a legacy decimal and scaled up. Sub-1% rates do
     * not occur in this data — only the two surfaces that wrote the field ever
     * set it — so the heuristic is safe here and deliberately *not* applied on
     * the way out.
     */
    fun normaliseTaxRate(value: Double?): Double {
        val rate = value ?: return 0.0
        if (rate <= 0) return 0.0
        return if (rate < 1) round2(rate * WHOLE_PERCENT) else rate
    }

    /** Whether [id] is a server id rather than one this client invented. */
    private fun isRealId(id: String): Boolean = UUID.matches(id)

    private val UUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private const val PENNY = 0.005
}

/** 100, as in "per cent" — the scale every tax rate in this module is on. */
internal const val WHOLE_PERCENT = 100.0

internal fun round2(value: Double): Double =
    if (value.isNaN() || value.isInfinite()) 0.0 else round(value * HUNDREDTHS) / HUNDREDTHS

private const val HUNDREDTHS = 100.0

// The processing grid's cell edits that reach more than one row — a parent's
// expenditure type and tax follow its split children, as the web's
// `updateLine` does (POEntry.jsx:894-915).
package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.feature.purchaseorder.domain.OTHER_TAX_TYPE
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoTaxType
import com.zillit.desktop.feature.purchaseorder.domain.RENTAL_EXPENDITURE_TYPE

/**
 * The line with a typed quantity or unit price, its amount re-struck to the
 * penny — the web's `u.amount = +(qty * rate).toFixed(2)` (`POEntry.jsx:881`).
 * Left unrounded, `3 × 33.333` saved as `99.999` and the coded lines stopped
 * agreeing with a total the server holds to two places.
 */
internal fun PoLine.repriced(quantity: Double = this.quantity, unitPrice: Double = this.unitPrice): PoLine =
    copy(
        quantity = quantity,
        unitPrice = unitPrice,
        amount = Money.scaledHalfAway(quantity * unitPrice, 2) / PENNIES,
    )

private const val PENNIES = 100.0

/**
 * Sets the line's Exp. Type; its split children follow, and a type other than
 * Rent drops the rental window (`POLineItemsTable`'s select). A child's own
 * type is locked, so an index on one is a no-op.
 */
internal fun List<PoLine>.withExpenditureType(index: Int, type: String?): List<PoLine> {
    val line = getOrNull(index)?.takeUnless { it.isSplitChild } ?: return this
    val next = type?.takeIf { it.isNotBlank() }
    val key = line.id ?: "line-$index"
    return mapIndexed { at, row ->
        when {
            at == index -> row.copy(expenditureType = next).let {
                if (next == RENTAL_EXPENDITURE_TYPE) it else it.copy(rentalStart = null, rentalEnd = null)
            }

            row.splitParentId == key -> row.copy(expenditureType = next)
            else -> row
        }
    }
}

/**
 * Sets the line's rental window. Dates are ISO days; a blank one clears.
 * Only the parent's — a period child carries the window it was cut to.
 */
internal fun List<PoLine>.withRental(index: Int, start: String?, end: String?): List<PoLine> {
    if (getOrNull(index)?.isSplitChild != false) return this
    return mapIndexed { at, row ->
        if (at != index) {
            row
        } else {
            row.copy(rentalStart = start?.takeIf { it.isNotBlank() }, rentalEnd = end?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * Picks the line's tax type; its children inherit both type and rate.
 *
 * [preset] is the Production Setup row picked, null for "none" and for
 * [OTHER_TAX_TYPE]. A preset brings its own rate. "Other" keeps a rate only
 * when the line was already on Other: carrying the previous type's rate across
 * pre-filled a custom box with 5% the moment Custom was chosen (the web's
 * ZL-20656).
 */
internal fun List<PoLine>.withTaxType(index: Int, id: String?, preset: PoTaxType?): List<PoLine> {
    val line = getOrNull(index)?.takeUnless { it.isSplitChild } ?: return this
    val rate = when {
        preset != null -> preset.rate
        id == OTHER_TAX_TYPE && line.taxType == OTHER_TAX_TYPE -> line.vatRate
        else -> null
    }
    val type = id?.takeIf { it.isNotBlank() }
    return withTaxFollowingChildren(index) { it.copy(taxType = type, vatRate = rate) }
}

/** Types a custom percent on an "Other" line; the children quote the same rate. */
internal fun List<PoLine>.withTaxRate(index: Int, rate: Double?): List<PoLine> {
    if (getOrNull(index)?.isSplitChild != false) return this
    return withTaxFollowingChildren(index) { it.copy(vatRate = rate) }
}

private fun List<PoLine>.withTaxFollowingChildren(index: Int, change: (PoLine) -> PoLine): List<PoLine> {
    val key = this[index].id ?: "line-$index"
    return mapIndexed { at, row -> if (at == index || row.splitParentId == key) change(row) else row }
}

/**
 * A tax type the production no longer lists collapses to "Other", keeping its
 * stored rate — the web's `resolveLineTaxType`, so the picker shows Other over
 * an editable rate instead of a blank dropdown over a locked one. Only call
 * with the list of types actually read: an unread list would collapse every
 * named type.
 */
internal fun List<PoLine>.collapseUnknownTaxTypes(known: Set<String>): List<PoLine> = map { line ->
    val type = line.taxType
    if (type.isNullOrBlank() || type == OTHER_TAX_TYPE || type in known) line else line.copy(taxType = OTHER_TAX_TYPE)
}

/** A typed percent clamped into 0–100, null when blank or not a number — the web's `clampTaxRateInput`. */
internal fun clampTaxRate(text: String): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_TAX_RATE)

private const val MAX_TAX_RATE = 100.0

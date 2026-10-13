package com.zillit.desktop.feature.talentdeals.domain

import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers.num
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers.r2

/** One expense as the form holds it. */
data class ExpenseDraft(
    val id: String,
    val label: String = "",
    val amount: String = "",
    val withhold: Boolean = true,
    val grossUp: Boolean = false,
)

/** How the tax is worked out, reduced to one of three cases. */
sealed interface WithholdingBasis {
    /** Nothing to apply. */
    data object None : WithholdingBasis

    /** A percentage, line by line. */
    data class Rate(val rate: Double) : WithholdingBasis

    /** One figure, on the fee alone. */
    data class Flat(val amount: Double) : WithholdingBasis
}

/** The fee or one expense at what is paid, what it is taxed on and what the tax comes to. */
data class WithholdingRow(
    val id: String,
    val label: String,
    val net: Double,
    val gross: Double,
    val tax: Double,
    val grossedUp: Boolean,
    val withheld: Boolean,
)

data class WithholdingSummary(
    val basis: WithholdingBasis,
    val rows: List<WithholdingRow>,
    val expenses: Double,
    val grossTotal: Double,
    val tax: Double,
    /** What the production spends. */
    val cost: Double,
    /** What reaches the performer. */
    val net: Double,
)

/**
 * Foreign tax withholding, following HMRC's guidance for payments to foreign
 * performers (gov.uk/guidance/pay-tax-on-payments-to-foreign-performers).
 *
 * Two things matter, and both are easy to get wrong:
 *
 *  - GROSSING UP. Anything the production provides IN KIND — a flight, a
 *    hotel, a per diem — reaches the performer whole, so it is a payment made
 *    NET of tax. The tax is worked out on the grossed-up value and the
 *    production carries it on top. £100 of flights at 20% is taxed on £125.
 *  - AUTHORISATION. A rate agreed with the tax authority ahead of payment
 *    REPLACES the contract rate, on the fee and on every expense.
 *
 * A FLAT withholding is a single figure on the fee. With no rate behind it
 * there is nothing to apply line by line and nothing to gross an in-kind
 * expense up against, which is why gross-up closes on that basis.
 */
object TalentWithholding {

    /** UK basic rate — the default where nothing has been authorised. */
    const val DEFAULT_RATE = 20

    /** Offered in the expense picker; anything else is typed in. */
    val EXPENSE_TYPES: List<String> = listOf("Per diem", "Accommodation", "Travel", "Assistant", "Additional expenses")

    private const val PERCENT = 100.0

    /**
     * A figure nobody has filled in yet is no basis at all — that is what keeps
     * the breakdown from showing a confident £0.00 tax on a half-typed form.
     */
    fun basis(form: TalentForm): WithholdingBasis = basis(
        withholding = form.withholding,
        authorised = form.withholdingAuthorised,
        authorisedRate = form.authorisedRate,
        type = form.withholdingType,
        value = form.withholdingValue,
    )

    fun basis(
        withholding: Boolean,
        authorised: Boolean,
        authorisedRate: String,
        type: WithholdingType,
        value: String,
    ): WithholdingBasis {
        if (!withholding) return WithholdingBasis.None
        if (authorised) {
            val rate = num(authorisedRate)
            return if (rate > 0) WithholdingBasis.Rate(rate) else WithholdingBasis.None
        }
        val figure = num(value)
        if (figure <= 0) return WithholdingBasis.None
        return if (type == WithholdingType.Percent) WithholdingBasis.Rate(figure) else WithholdingBasis.Flat(figure)
    }

    /** A payment made net grosses up so the tax falls on the whole amount. */
    fun grossUpAmount(net: Double, rate: Double): Double =
        if (rate <= 0 || rate >= PERCENT) net else net / (1 - rate / PERCENT)

    private fun expenseRow(expense: ExpenseDraft, basis: WithholdingBasis, defaultLabel: String): WithholdingRow {
        val net = r2(num(expense.amount))
        val plain = WithholdingRow(
            id = expense.id,
            label = expense.label.ifBlank { defaultLabel },
            net = net,
            gross = net,
            tax = 0.0,
            grossedUp = false,
            withheld = expense.withhold,
        )
        if (!expense.withhold || basis !is WithholdingBasis.Rate) return plain

        // Provided in kind: the performer has it in full, so the tax sits on the
        // grossed-up value and the production carries it.
        val gross = if (expense.grossUp) r2(grossUpAmount(net, basis.rate)) else net
        return plain.copy(
            gross = gross,
            grossedUp = expense.grossUp,
            tax = r2(if (expense.grossUp) gross - net else gross * basis.rate / PERCENT),
        )
    }

    /**
     * The fee and every expense, each at what is paid, what it is taxed on and
     * what the tax comes to — plus the three totals anyone asks for: what the
     * production spends, what the tax authority receives, and what reaches the
     * performer.
     */
    fun summary(
        feeInput: String?,
        expenses: List<ExpenseDraft>,
        basis: WithholdingBasis,
        feeLabel: String = "Fee",
        expenseLabel: String = "Expense",
    ): WithholdingSummary {
        val fee = r2(num(feeInput))
        val feeRow = WithholdingRow(
            id = "fee",
            label = feeLabel,
            net = fee,
            gross = fee,
            // A flat withholding is the whole tax, and it comes out of the fee.
            // Capped at the fee: a figure larger than what is being paid is a
            // typo, and letting it through would report a negative net.
            tax = when (basis) {
                is WithholdingBasis.Rate -> r2(fee * basis.rate / PERCENT)
                is WithholdingBasis.Flat -> minOf(basis.amount, fee)
                WithholdingBasis.None -> 0.0
            },
            grossedUp = false,
            withheld = basis !is WithholdingBasis.None,
        )
        val rows = listOf(feeRow) + expenses.map { expenseRow(it, basis, expenseLabel) }

        val expenseTotal = r2(rows.drop(1).sumOf { it.net })
        val tax = r2(rows.sumOf { it.tax })

        // Lines the contract leaves outside the withholding are still paid, but
        // they are not part of what the tax is worked out on.
        val grossTotal = if (basis is WithholdingBasis.Rate) r2(rows.filter { it.withheld }.sumOf { it.gross }) else 0.0

        // Grossed-up lines are paid in full ON TOP of the tax; everything else
        // has its tax withheld from what the performer would otherwise receive.
        val grossedUpNet = r2(rows.filter { it.grossedUp }.sumOf { it.net })
        val straight = r2(rows.filter { !it.grossedUp }.sumOf { it.net })
        val straightTax = r2(rows.filter { !it.grossedUp }.sumOf { it.tax })

        return WithholdingSummary(
            basis = basis,
            rows = rows,
            expenses = expenseTotal,
            grossTotal = grossTotal,
            tax = tax,
            cost = r2(straight + grossedUpNet + r2(tax - straightTax)),
            net = r2(straight - straightTax + grossedUpNet),
        )
    }
}

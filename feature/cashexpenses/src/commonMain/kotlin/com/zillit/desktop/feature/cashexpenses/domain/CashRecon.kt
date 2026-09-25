package com.zillit.desktop.feature.cashexpenses.domain

/** One row of the cash count: a note or coin value and how many are in the safe. */
data class Denomination(
    val id: String,
    /** `note` or `coin`. */
    val type: String,
    /** As the web stores it — `"50"`, `"0.20"` — so a round trip changes nothing. */
    val value: String,
    val count: String = "",
) {
    val total: Double get() = (value.toDoubleOrNull() ?: 0.0) * (count.trim().toIntOrNull() ?: 0)

    val isNote: Boolean get() = type == NOTE

    companion object {
        const val NOTE = "note"
        const val COIN = "coin"
    }
}

/** A reconciling item: cash paid out, received, or a timing difference. */
data class ReconItem(
    val description: String = "",
    val reference: String = "",
    /** `out`, `in` or `timing`. */
    val type: String = OUT,
    val amount: String = "",
) {
    /** What it does to the book: out and timing reduce it, in adds to it. */
    val adjustment: Double
        get() {
            val value = amount.trim().toDoubleOrNull() ?: 0.0
            return when (type) {
                IN -> value
                OUT, TIMING -> -value
                else -> 0.0
            }
        }

    companion object {
        const val OUT = "out"
        const val IN = "in"
        const val TIMING = "timing"
        val TYPES = listOf(OUT, IN, TIMING)
    }
}

/**
 * One reconciliation open for editing — the web's Cash Recon edit view
 * (`PCCashReconPage.jsx`).
 *
 * Count the safe by denomination, list the reconciling items, and the variance
 * is what is left: physical total minus the book adjusted by the items. The
 * book comes from the server (`compute-book`) for the opening balance and the
 * month; until it answers, the opening balance stands in, as on the web.
 */
data class ReconDraft(
    val id: String?,
    val status: String = DRAFT,
    val currency: String?,
    val openingBalance: String,
    val year: Int,
    val month: Int,
    val denominations: List<Denomination>,
    val items: List<ReconItem> = emptyList(),
    val notes: String = "",
    /** The server's book balance for the opening balance and month, once it answers. */
    val computedBook: Double? = null,
    /** The book balance is being asked for — the web's "Computing book balance…". */
    val computingBook: Boolean = false,
    /** The period as the server last sent it — its stored figures and audit trail. */
    val saved: Reconciliation? = null,
) {
    val opening: Double get() = openingBalance.trim().toDoubleOrNull() ?: 0.0

    val bookBalance: Double get() = computedBook ?: opening

    val physicalTotal: Double get() = round2(denominations.sumOf { it.total })

    val adjustment: Double get() = round2(items.sumOf { it.adjustment })

    val adjustedBook: Double get() = round2(bookBalance + adjustment)

    val variance: Double get() = round2(physicalTotal - adjustedBook)

    val isSignedOff: Boolean get() = status == SIGNED_OFF

    companion object {
        const val DRAFT = "DRAFT"
        const val UNDER_REVIEW = "UNDER_REVIEW"
        const val SIGNED_OFF = "SIGNED_OFF"

        private val NOTES = listOf("50", "20", "10", "5")
        private val COINS = listOf("2", "1", "0.50", "0.20", "0.10", "0.05", "0.02", "0.01")

        /** The blank count a new reconciliation starts from: every note, then every coin. */
        fun seedDenominations(stamp: Long): List<Denomination> =
            NOTES.map { Denomination("n_${it}_$stamp", Denomination.NOTE, it) } +
                COINS.map { Denomination("c_${it}_$stamp", Denomination.COIN, it) }

        /** A saved reconciliation, reopened. */
        fun of(recon: Reconciliation, fallbackMonth: Pair<Int, Int>): ReconDraft {
            val (year, month) = CashDates.monthOf(recon.periodStart) ?: fallbackMonth
            return ReconDraft(
                id = recon.id,
                status = recon.status.ifBlank { DRAFT },
                currency = recon.currency,
                openingBalance = recon.openingBalance.takeIf { it != 0.0 }?.let(::plain).orEmpty(),
                year = year,
                month = month,
                denominations = recon.denominations,
                items = recon.reconcilingItems,
                notes = recon.note.orEmpty(),
                saved = recon,
            )
        }

        private fun plain(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}

package com.zillit.desktop.feature.cardexpenses.domain

/**
 * The engine's score thresholds, tested on the raw fraction the way the web does
 * (`ReceiptInboxPage.jsx:362,436,463`). Never on the printed percentage: 0.846
 * prints 85 yet is not a duplicate, and 0.795 prints 80 yet is not green.
 */
object ScoreBands {
    private const val STRONG = 0.8
    private const val FAIR = 0.5
    private const val CERTAIN = 0.85

    /** Green. */
    fun isStrong(fraction: Double): Boolean = fraction >= STRONG

    /** Amber. */
    fun isFair(fraction: Double): Boolean = fraction >= FAIR

    /** "Duplicate" rather than "Possible Duplicate"; "Personal" rather than "May be Personal". */
    fun isCertain(fraction: Double): Boolean = fraction >= CERTAIN
}

/** The score to threshold: the wire fraction, else the whole percentage scaled back (a row built by hand). */
val CardReceipt.matchRaw: Double? get() = matchFraction ?: matchScore?.let { it / PERCENT }
val CardReceipt.duplicateRaw: Double? get() = duplicateFraction ?: duplicateScore?.let { it / PERCENT }
val CardReceipt.personalRaw: Double? get() = personalFraction ?: personalScore?.let { it / PERCENT }

private const val PERCENT = 100.0

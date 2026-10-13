package com.zillit.desktop.feature.cardexpenses.domain

/**
 * Which currency code a Card Expenses amount prints in (`lib/resolveCurrency.js`).
 *
 * The record's own `currency`, then its `transaction_currency`, then the
 * caller's fallback (the card the record names by `card_id`, or the active
 * card), then the production's default — and `GBP` last, so a symbol always
 * prints. Upper-cased, as the formatter does. Never blank: a bare `12,480.55` on a screen about money is a figure
 * nobody can act on.
 */
object CurrencyCascade {
    /** What the web's formatter assumes before anything has resolved. */
    const val LEGACY_DEFAULT = "GBP"

    fun resolve(
        recordCurrency: String?,
        transactionCurrency: String? = null,
        fallback: String? = null,
        defaultCode: String? = null,
    ): String = listOf(recordCurrency, transactionCurrency, fallback, defaultCode)
        .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        ?.uppercase()
        ?: LEGACY_DEFAULT
}

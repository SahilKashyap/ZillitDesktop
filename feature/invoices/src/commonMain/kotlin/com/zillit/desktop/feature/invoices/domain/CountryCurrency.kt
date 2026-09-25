package com.zillit.desktop.feature.invoices.domain

/** One row of the core `preset/currencies` catalogue — `useCurrencies()`. */
data class CatalogueCurrency(
    val code: String,
    val name: String = "",
    val symbol: String = "",
    /** The country the currency belongs to, as the company rows name countries. */
    val country: String = "",
)

/**
 * A company's country to the currency it trades in — the web's
 * `data/country-currency.js`, which the invoice forms use so that picking a
 * company fills the currency (`applyCompany`).
 *
 * The base map comes from the catalogue rows that name a single country; the
 * currencies shared by many countries, and the countries that use another's
 * currency, are fixed on top. Anything else is null — the caller leaves the
 * currency alone rather than guess.
 */
object CountryCurrency {

    private val EUROZONE = listOf(
        "Austria", "Belgium", "Croatia", "Cyprus", "Estonia", "Finland", "France", "Germany", "Greece",
        "Ireland", "Italy", "Latvia", "Lithuania", "Luxembourg", "Malta", "Netherlands", "Portugal",
        "Slovakia", "Slovenia", "Spain",
    )
    private val XOF = listOf(
        "Benin", "Burkina Faso", "Cote D'Ivoire", "Guinea-Bissau", "Mali", "Niger", "Senegal", "Togo",
    )
    private val XAF = listOf(
        "Cameroon", "Central African Republic", "Chad", "Republic of the Congo", "Equatorial Guinea", "Gabon",
    )
    private val XCD = listOf(
        "Anguilla", "Antigua and Barbuda", "Dominica", "Grenada", "Montserrat", "Saint Kitts and Nevis",
        "Saint Lucia", "Saint Vincent and the Grenadines",
    )
    private val XPF = listOf("French Polynesia", "New Caledonia")
    private val USD_DE_FACTO = listOf(
        "Ecuador", "El Salvador", "Marshall Islands", "Micronesia, Federated States of Micronesia", "Palau",
        "Timor-Leste", "Panama",
    )

    /** `COUNTRY_CURRENCY_OVERRIDES` — these win over the catalogue. */
    val OVERRIDES: Map<String, String> = buildMap {
        EUROZONE.forEach { put(it, "EUR") }
        XOF.forEach { put(it, "XOF") }
        XAF.forEach { put(it, "XAF") }
        XCD.forEach { put(it, "XCD") }
        XPF.forEach { put(it, "XPF") }
        USD_DE_FACTO.forEach { put(it, "USD") }
        put("Liechtenstein", "CHF")
        put("Hong Kong SAR China", "HKD")
    }

    private val MULTI_COUNTRY = Regex("[,()&]")
    private val DECENTRALIZED = Regex("Decentralized", RegexOption.IGNORE_CASE)

    /** `buildCountryCurrencyMap`. */
    fun map(catalogue: List<CatalogueCurrency>): Map<String, String> {
        val base = catalogue
            .filter { it.country.isNotBlank() && it.code.isNotBlank() }
            .filterNot { MULTI_COUNTRY.containsMatchIn(it.country) || DECENTRALIZED.containsMatchIn(it.country) }
            .associate { it.country to it.code }
        return base + OVERRIDES
    }

    /** `getCurrencyForCountry` — the country's currency, or null when there is no confident answer. */
    fun forCountry(country: String?, catalogue: List<CatalogueCurrency>): String? {
        if (country.isNullOrBlank()) return null
        return map(catalogue)[country]
    }
}

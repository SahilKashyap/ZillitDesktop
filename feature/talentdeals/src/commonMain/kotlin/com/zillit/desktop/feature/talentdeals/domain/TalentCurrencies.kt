package com.zillit.desktop.feature.talentdeals.domain

/** One currency the picker can show. [isProject] marks the production's own selection. */
data class TalentCurrency(
    val code: String,
    val name: String = "",
    val symbol: String = "",
    val country: String = "",
    val isProject: Boolean = false,
) {
    /** The `CODE symbol — Country` label the hub's selectors share. */
    val label: String
        get() {
            val head = if (symbol.isNotBlank()) "$code $symbol" else code
            return if (country.isNotBlank()) "$head — $country" else head
        }
}

/**
 * The currency data a contract's form needs: the production's selected
 * currencies (Production Setup → Project Currencies) and its default, and the
 * whole catalogue.
 */
data class TalentCurrencies(
    val selected: List<String> = emptyList(),
    val defaultCode: String? = null,
    val catalogue: List<TalentCurrency> = emptyList(),
) {
    /** A currency by code; a code the catalogue does not know stands for itself. */
    fun get(code: String): TalentCurrency =
        catalogue.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: TalentCurrency(code = code, symbol = code)

    /**
     * The picker's rows: the project's selected currencies tagged and listed
     * first, then every other currency in the catalogue — the Add Bank Account
     * picker's list. Tagging reads the ACTUAL selection: with nothing selected
     * yet the list is the whole catalogue, and none of it is a "Project"
     * currency. [ensure] (a record's already-saved currency) is always
     * selectable, even when it is not one of the project's.
     */
    fun options(ensure: String = ""): List<TalentCurrency> {
        val selectedUpper = selected.map { it.uppercase() }.toSet()
        val baseCodes = when {
            selected.isEmpty() -> catalogue.map { it.code }
            defaultCode != null && defaultCode !in selected -> listOf(defaultCode) + selected
            else -> selected
        }
        val base = baseCodes.toMutableList()
        val wanted = ensure.trim().uppercase()
        if (wanted.isNotEmpty() && base.none { it.uppercase() == wanted }) base.add(0, wanted)

        val project = base.map { get(it) }
        val seen = project.mapTo(HashSet()) { it.code.uppercase() }
        val others = catalogue.filter { it.code.isNotBlank() && it.code.uppercase() !in seen }
        return (project + others).map { it.copy(isProject = it.code.uppercase() in selectedUpper) }
    }
}

/**
 * Country → currency, for a territory the deal-memo wizard has no default for —
 * the web's `getCurrencyForCountry`. Most rows self-derive from the catalogue;
 * the currencies that span several countries, and the countries that use a
 * foreign currency de facto, are explicit. Null when there is no confident
 * answer, and the caller leaves the currency alone rather than guess.
 */
object TalentCountryCurrency {

    private val EUROZONE = listOf(
        "Austria", "Belgium", "Croatia", "Cyprus", "Estonia", "Finland", "France", "Germany", "Greece", "Ireland",
        "Italy", "Latvia", "Lithuania", "Luxembourg", "Malta", "Netherlands", "Portugal", "Slovakia", "Slovenia",
        "Spain",
    )
    private val XOF = listOf("Benin", "Burkina Faso", "Cote D'Ivoire", "Guinea-Bissau", "Mali", "Niger", "Senegal",
        "Togo")
    private val XAF = listOf(
        "Cameroon", "Central African Republic", "Chad", "Republic of the Congo", "Equatorial Guinea", "Gabon",
    )
    private val XCD = listOf(
        "Anguilla", "Antigua and Barbuda", "Dominica", "Grenada", "Montserrat", "Saint Kitts and Nevis",
        "Saint Lucia", "Saint Vincent and the Grenadines",
    )
    private val XPF = listOf("French Polynesia", "New Caledonia")
    private val USD = listOf(
        "Ecuador", "El Salvador", "Marshall Islands", "Micronesia, Federated States of Micronesia", "Palau",
        "Timor-Leste", "Panama",
    )

    private val OVERRIDES: Map<String, String> = buildMap {
        EUROZONE.forEach { put(it, "EUR") }
        XOF.forEach { put(it, "XOF") }
        XAF.forEach { put(it, "XAF") }
        XCD.forEach { put(it, "XCD") }
        XPF.forEach { put(it, "XPF") }
        USD.forEach { put(it, "USD") }
        put("Liechtenstein", "CHF")
        put("Hong Kong SAR China", "HKD")
    }

    private val QUALIFIER = Regex("[,()&]")

    fun lookup(catalogue: List<TalentCurrency>): (String) -> String? {
        val derived = HashMap<String, String>()
        catalogue
            .filter { it.country.isNotBlank() && it.code.isNotBlank() }
            .filterNot { QUALIFIER.containsMatchIn(it.country) || it.country.contains("Decentralized",
                ignoreCase = true) }
            .forEach { derived[it.country] = it.code }
        return { name -> OVERRIDES[name] ?: derived[name] }
    }
}

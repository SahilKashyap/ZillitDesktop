package com.zillit.desktop.feature.talentdeals.domain

/**
 * The roles a contract is for, the guilds each always sees in the union
 * picker, and the territory list — the web's `talentContract.js` and
 * `useTalentTerritories`.
 *
 * Roles, tranche stages and expense types are DATA, not interface text: they
 * are stored on the contract as written, in English, exactly as the web
 * stores them, so they are never run through the language catalogue.
 */
object TalentRoles {

    /** Roles offered in the picker; anything else is typed in. */
    val ROLES: List<String> = listOf("Director", "Cast", "Producer")

    /**
     * The guild each of the three roles ALWAYS sees in the union picker,
     * whatever territory is picked and whatever the lookup returns: a director
     * gets DGA, cast get SAG-AFTRA, a producer gets PGA. The lookup's rows are
     * merged on top; a matching identifier from the lookup wins, so a
     * server-side rename of the display name shows through — except that the
     * baseline's name stays, since it is the one carrying the abbreviation.
     *
     * `pga` is not in the backend's union data at all — producers are the
     * employer side and mostly not unionised — so its identifier is set here;
     * `union_identifier` is a free slug on the model.
     */
    private val BASE_UNIONS: Map<String, List<TalentUnion>> = mapOf(
        "director" to listOf(TalentUnion("dga", "dga", "DGA - Directors Guild of America")),
        "cast" to listOf(TalentUnion("sag-aftra", "sag-aftra", "SAG - Screen Actors Guild - AFTRA")),
        "producer" to listOf(TalentUnion("pga", "pga", "PGA - Producers Guild of America")),
    )

    /** Display names for the identifiers a form may hold, when the lookup knows nothing of them. */
    val UNION_LABELS: Map<String, String> = mapOf("dga" to "DGA", "sag" to "SAG", "equity" to "Equity")

    private val CREW_TYPES = setOf("cast", "director", "producer")

    /** The always-present guilds for a role — empty for a typed role. */
    fun baseUnionsFor(role: String?): List<TalentUnion> = BASE_UNIONS[role.orEmpty().trim().lowercase()].orEmpty()

    /** The display name for a baseline identifier, whatever the role — or null. */
    fun baseUnionName(identifier: String?): String? =
        BASE_UNIONS.values.flatten().firstOrNull { it.identifier == identifier }?.name

    /**
     * Baseline first, then the lookup's rows, deduplicated by identifier — so
     * the list never loses DGA / SAG-AFTRA / PGA for their roles, and never
     * shows one twice. On a clash the lookup's row is kept (its `union_id` is
     * the real one) but the BASELINE's display name stays.
     */
    fun mergeUnions(base: List<TalentUnion>, looked: List<TalentUnion>): List<TalentUnion> {
        val byId = LinkedHashMap<String, TalentUnion>()
        base.forEach { byId[it.identifier] = it }
        looked.forEach { union ->
            if (union.identifier.isBlank()) return@forEach
            val prior = byId[union.identifier]
            byId[union.identifier] = if (prior != null) union.copy(name = prior.name) else union
        }
        return byId.values.toList()
    }

    /**
     * The lookup segment for a role — the three crew types the server knows,
     * else `all`. An unknown crew type is a 400, not an empty list.
     */
    fun crewTypeFor(role: String?): String = role.orEmpty().trim().lowercase().takeIf { it in CREW_TYPES } ?: "all"

    /**
     * Exhibit G is a SAG-AFTRA cast document. A director on a DGA agreement
     * has no Exhibit G, and neither does a producer — so the panel is offered
     * on exactly one combination. `union` is a free slug, so "sag" and
     * "sag-aftra" both count.
     */
    fun exhibitGApplies(role: String?, union: String?): Boolean =
        role == "Cast" && union.orEmpty().contains("sag", ignoreCase = true)

    /**
     * Whether the union field is mandatory: only when there is something to
     * pick from — nothing can be required from an empty picker.
     */
    fun unionRequired(options: List<TalentUnion>): Boolean = options.isNotEmpty()

    /**
     * The options for the union field. The stored identifier on Edit may be in
     * neither list any more, so it is kept selectable rather than blanking the
     * trigger.
     */
    fun unionOptions(role: String?, looked: List<TalentUnion>, selected: String): List<TalentUnion> {
        val merged = mergeUnions(baseUnionsFor(role), looked)
        if (selected.isBlank() || merged.any { it.identifier == selected }) return merged
        return merged + TalentUnion(selected, selected, selected)
    }
}

/** One territory the picker offers; [id] is the stored `territory_code`. */
data class TalentTerritory(val id: String, val iso: String, val label: String, val currency: String)

/** A country as the ISD list names it, for the territories after the wizard's 41. */
data class TalentCountry(val code: String, val name: String)

/**
 * The territory list: the 41 deal-memo wizard territories first, in the order
 * Step 1 offers them with their default currency, then the rest of the world
 * from the Add Company country list. A talent engaged somewhere the wizard has
 * no agreement for can still be placed.
 */
object TalentTerritories {

    private val WIZARD: List<Triple<String, String, String>> = listOf(
        Triple("uk", "United Kingdom", "GBP"), Triple("ie", "Ireland", "EUR"),
        Triple("us", "United States", "USD"), Triple("ca", "Canada", "CAD"),
        Triple("at", "Austria", "EUR"), Triple("pt", "Portugal", "EUR"), Triple("gr", "Greece", "EUR"),
        Triple("de", "Germany", "EUR"), Triple("fr", "France", "EUR"), Triple("es", "Spain", "EUR"),
        Triple("it", "Italy", "EUR"), Triple("be", "Belgium", "EUR"), Triple("nl", "Netherlands", "EUR"),
        Triple("au", "Australia", "AUD"), Triple("nz", "New Zealand", "NZD"), Triple("jp", "Japan", "JPY"),
        Triple("in", "India", "INR"), Triple("sg", "Singapore", "SGD"), Triple("kr", "South Korea", "KRW"),
        Triple("ae", "UAE / Dubai", "AED"), Triple("za", "South Africa", "ZAR"), Triple("ma", "Morocco", "MAD"),
        Triple("mx", "Mexico", "MXN"), Triple("br", "Brazil", "BRL"), Triple("co", "Colombia", "COP"),
        Triple("ar", "Argentina", "ARS"),
        Triple("dk", "Denmark", "DKK"), Triple("fi", "Finland", "EUR"), Triple("no", "Norway", "NOK"),
        Triple("se", "Sweden", "SEK"),
        Triple("cz", "Czech Republic", "CZK"), Triple("hu", "Hungary", "HUF"), Triple("pl", "Poland", "PLN"),
        Triple("ro", "Romania", "RON"),
        Triple("bg", "Bulgaria", "BGN"), Triple("hr", "Croatia", "EUR"), Triple("rs", "Serbia", "RSD"),
        Triple("mt", "Malta", "EUR"), Triple("lu", "Luxembourg", "EUR"), Triple("is", "Iceland", "ISK"),
        Triple("il", "Israel", "ILS"),
    )

    /** The wizard's territories — `uk` is its own slug for the country whose ISO code is `GB`. */
    val wizard: List<TalentTerritory> = WIZARD.map { (id, label, currency) ->
        TalentTerritory(id = id, iso = if (id == "uk") "GB" else id.uppercase(), label = label, currency = currency)
    }

    /**
     * The wizard's list, then every ISD country it does not already have, by
     * name. [currencyOf] resolves a country name to its currency code (the
     * hub's country → currency lookup); blank when it has no confident answer.
     */
    fun build(countries: List<TalentCountry>, currencyOf: (String) -> String?): List<TalentTerritory> {
        val seen = wizard.mapTo(HashSet()) { it.iso }
        val rest = countries
            .filter { it.name.isNotBlank() && it.code.isNotBlank() && it.code.uppercase() !in seen }
            .map { country ->
                TalentTerritory(
                    id = country.code.lowercase(),
                    iso = country.code.uppercase(),
                    label = country.name,
                    currency = currencyOf(country.name).orEmpty(),
                )
            }
            .sortedBy { it.label }
        return wizard + rest
    }
}

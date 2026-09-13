package com.zillit.desktop.feature.dealmemo.domain.rates

/** One shooting territory — `uk`, `us` — as the union catalogue slugs it. */
data class Territory(val id: String, val label: String) {
    /** The id as the pages print it: `UK`. */
    val code: String get() = id.uppercase()
}

/** A group of territories — the sidebar's headings and the welcome page's region cards. */
data class Region(val id: String, val label: String, val territories: List<Territory>)

/**
 * The web's bundled region → territory taxonomy (`accountHub/data/regions.js`).
 *
 * The same tree is served by `GET /branches/regions`; the web reads its own
 * copy and so does this, in the web's declaration order — the sidebar and the
 * region grid are both drawn in it.
 */
object TerritoryCatalogue {

    val regions: List<Region> = listOf(
        region("europe-uk", "Europe & UK", "uk" to "United Kingdom", "ie" to "Ireland"),
        region("north-america", "North America", "us" to "United States", "ca" to "Canada"),
        region(
            "europe", "Europe",
            "at" to "Austria", "pt" to "Portugal", "gr" to "Greece", "de" to "Germany", "fr" to "France",
            "es" to "Spain", "it" to "Italy", "be" to "Belgium", "nl" to "Netherlands",
        ),
        region(
            "asia-pacific", "Asia-Pacific",
            "au" to "Australia", "nz" to "New Zealand", "jp" to "Japan", "in" to "India",
            "sg" to "Singapore", "kr" to "South Korea",
        ),
        region(
            "middle-east-africa",
            "Middle East & Africa",
            "ae" to "UAE / Dubai",
            "za" to "South Africa",
            "ma" to "Morocco",
        ),
        region(
            "latin-america",
            "Latin America",
            "mx" to "Mexico",
            "br" to "Brazil",
            "co" to "Colombia",
            "ar" to "Argentina",
        ),
        region(
            "europe-nordic",
            "Europe — Nordic",
            "dk" to "Denmark",
            "fi" to "Finland",
            "no" to "Norway",
            "se" to "Sweden",
        ),
        region(
            "europe-cee",
            "Europe — CEE",
            "cz" to "Czech Republic",
            "hu" to "Hungary",
            "pl" to "Poland",
            "ro" to "Romania",
        ),
        region("eastern-europe", "Eastern Europe", "bg" to "Bulgaria", "hr" to "Croatia", "rs" to "Serbia"),
        region("southern-europe", "Southern Europe", "mt" to "Malta"),
        region("western-europe", "Western Europe", "lu" to "Luxembourg"),
        region("scandinavia", "Scandinavia", "is" to "Iceland"),
        region("middle-east", "Middle East", "il" to "Israel"),
    )

    /** The markets the welcome page pins, in its order (`DMConfigPage.jsx` `pinnedIds`). */
    val pinned: List<String> = listOf("uk", "us", "ca", "de", "fr", "au", "ie", "nz")

    val territories: List<Territory> = regions.flatMap { it.territories }

    /** 41 — the welcome copy quotes the whole catalogue, filtered or not. */
    val total: Int get() = territories.size

    private val byId: Map<String, Territory> = territories.associateBy { it.id }
    private val regionById: Map<String, Region> = regions.associateBy { it.id }
    private val regionOfTerritory: Map<String, Region> =
        regions.flatMap { region -> region.territories.map { it.id to region } }.toMap()

    fun territory(id: String?): Territory? = id?.lowercase()?.let(byId::get)

    /** `TERRITORY_LABELS[id]`, or null for a slug the catalogue does not know. */
    fun label(id: String?): String? = territory(id)?.label

    /** `REGION_LABELS[TERRITORY_TO_REGION[id]]`. */
    fun regionLabelOf(territoryId: String?): String? = territoryId?.lowercase()?.let(regionOfTerritory::get)?.label

    /** `REGION_LABELS[regionId] ?? regionId` — a branch names its region by id. */
    fun regionLabel(regionId: String?): String? =
        regionId?.takeIf { it.isNotBlank() }?.let { regionById[it]?.label ?: it }

    /**
     * The contract currency a territory defaults to, when neither the rate row
     * nor its branch names one (`TERRITORY_DEFAULT_CURRENCY`).
     */
    fun defaultCurrency(territoryId: String?): String? = territoryId?.lowercase()?.let(DEFAULT_CURRENCY::get)

    /**
     * The tree with only the territories that have a branch.
     *
     * **Fails open**: with no coverage known — still loading, the call failed,
     * or the server answered `[]` — every territory is listed, as the web does
     * rather than showing an empty sidebar.
     */
    fun coveredTree(covered: Set<String>): List<Region> {
        if (covered.isEmpty()) return regions
        return regions.mapNotNull { region ->
            region.territories.filter { it.id in covered }
                .takeIf { it.isNotEmpty() }
                ?.let { region.copy(territories = it) }
        }
    }

    /**
     * The sidebar's list for [query].
     *
     * The emptiness check trims but the match does not — the web's own quirk,
     * kept: a trailing space defeats a match. A region whose name matches keeps
     * all of its (covered) territories.
     */
    fun sidebarTree(covered: Set<String>, query: String): List<Region> {
        val tree = coveredTree(covered)
        if (query.isBlank()) return tree
        val q = query.lowercase()
        return tree.mapNotNull { region ->
            if (region.label.lowercase().contains(q) || region.id.lowercase().contains(q)) return@mapNotNull region
            region.territories
                .filter { it.label.lowercase().contains(q) || it.id.lowercase().contains(q) }
                .takeIf { it.isNotEmpty() }
                ?.let { region.copy(territories = it) }
        }
    }

    private fun region(id: String, label: String, vararg territories: Pair<String, String>) =
        Region(id, label, territories.map { (slug, name) -> Territory(slug, name) })

    private val DEFAULT_CURRENCY: Map<String, String> = buildMap {
        put("uk", "GBP")
        listOf("ie", "at", "pt", "gr", "de", "fr", "es", "it", "be", "nl", "fi", "hr", "mt", "lu")
            .forEach { put(it, "EUR") }
        putAll(
            mapOf(
                "us" to "USD", "ca" to "CAD", "au" to "AUD", "nz" to "NZD", "jp" to "JPY", "in" to "INR",
                "sg" to "SGD", "kr" to "KRW", "ae" to "AED", "za" to "ZAR", "ma" to "MAD", "mx" to "MXN",
                "br" to "BRL", "co" to "COP", "ar" to "ARS", "dk" to "DKK", "no" to "NOK", "se" to "SEK",
                "cz" to "CZK", "hu" to "HUF", "pl" to "PLN", "ro" to "RON", "bg" to "BGN", "rs" to "RSD",
                "is" to "ISK", "il" to "ILS",
            ),
        )
    }
}

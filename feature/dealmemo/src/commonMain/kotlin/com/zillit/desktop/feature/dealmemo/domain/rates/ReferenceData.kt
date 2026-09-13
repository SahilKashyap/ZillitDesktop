package com.zillit.desktop.feature.dealmemo.domain.rates

import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A union in the global catalogue — keyed by its `_identifier` slug, never an `id`. */
data class UnionSummary(
    val identifier: String,
    val name: String,
    val shortLabel: String? = null,
    val source: String? = null,
)

/**
 * A branch of a union — the level every rate card and agreement hangs off.
 * A "flat" union has one branch that shares the union's own identifier.
 */
data class Branch(
    val identifier: String,
    val name: String,
    val unionIdentifier: String,
    val shortLabel: String? = null,
    val territory: String? = null,
    val region: String? = null,
    val source: String? = null,
    val currency: String? = null,
)

/** The list projection of an agreement. */
data class AgreementSummary(
    val identifier: String,
    val name: String,
    val territory: String? = null,
    val currency: String? = null,
    val shortLabel: String? = null,
    val unionIdentifier: String? = null,
)

/** A territory's employment status — the labour-law defaults a deal's fringes key off. */
data class EmpStatus(
    val id: String,
    val label: String,
    val badge: String? = null,
    /** `blue | orange | red | gold | green` — the badge colour. */
    val alertClass: String? = null,
    val hpShown: Boolean = false,
    val sub: String? = null,
)

/** `GET /agreements` answers both at once: `{union: [...agreements], emp_statuses}`. */
data class AgreementListing(
    val agreements: List<AgreementSummary> = emptyList(),
    val empStatuses: List<EmpStatus> = emptyList(),
)

/** One published designation rate row (`/designation-rates`). The web never reads its id. */
data class DesignationRate(
    val designationIdentifier: String?,
    val departmentIdentifier: String? = null,
    val branchIdentifier: String? = null,
    val unionIdentifier: String? = null,
    val agreementIdentifier: String? = null,
    /** A translation key, `feature_label`. */
    val productionType: String? = null,
    val minBudget: Double? = null,
    val maxBudget: Double? = null,
    val minExp: Double? = null,
    val maxExp: Double? = null,
    val hourly: List<RateTierEntry> = emptyList(),
    val daily: List<RateTierEntry> = emptyList(),
    val weekly: List<RateTierEntry> = emptyList(),
    val flatRate: List<RateTierEntry> = emptyList(),
    val currency: String? = null,
    val notes: String? = null,
)

/** One role's rows on the branch rate card. */
data class RateDesignationGroup(
    val identifier: String,
    /** The catalogue's translation key; null for a role the catalogue does not know. */
    val nameKey: String?,
    val matched: Boolean,
    val rates: List<DesignationRate>,
) {
    /** The role cell spans every rate row plus each row's note line. */
    val rowSpan: Int get() = rates.sumOf { if (it.notes.isNullOrEmpty()) 1 else 2 }
}

/** One department band of the rate card. */
data class RateDepartmentSection(
    val identifier: String,
    val nameKey: String,
    val designations: List<RateDesignationGroup>,
) {
    val rateCount: Int get() = designations.sumOf { it.rates.size }
    val isOther: Boolean get() = identifier == OTHER_IDENTIFIER

    companion object {
        const val OTHER_IDENTIFIER = "__other__"
        const val OTHER_NAME_KEY = "other_label"
    }
}

/** A territory's unions with their branches, as the territory page groups them. */
data class UnionSection(val union: UnionSummary, val branches: List<Branch>) {
    /** One branch that is the union itself — no branch count is shown. */
    val isFlat: Boolean get() = branches.size == 1 && branches.single().identifier == union.identifier
}

/**
 * The page's grouping rules (`DMConfigPage.jsx`), kept out of the composables
 * so they can be pinned by tests.
 */
object GlobalRatesRules {

    private val byName: Comparator<String> = compareBy<String> { it.lowercase() }.thenBy { it }

    /**
     * The territory page's sections: every branch under its union (an unknown
     * union becomes a section titled with its raw identifier), unions without a
     * branch dropped, sorted by name; a query matching a union keeps all of its
     * branches, otherwise branches are filtered one by one.
     */
    fun unionSections(unions: List<UnionSummary>, branches: List<Branch>, query: String): List<UnionSection> {
        val grouped = LinkedHashMap<String, Pair<UnionSummary, MutableList<Branch>>>()
        unions.forEach { grouped[it.identifier] = it to mutableListOf() }
        branches.forEach { branch ->
            val entry = grouped.getOrPut(branch.unionIdentifier) {
                UnionSummary(branch.unionIdentifier, branch.unionIdentifier) to mutableListOf()
            }
            entry.second += branch
        }
        val q = query.trim().lowercase()
        fun matches(vararg fields: String?) = fields.any { it.orEmpty().lowercase().contains(q) }
        return grouped.values
            .map { (union, rows) -> UnionSection(union, rows.toList()) }
            .filter { it.branches.isNotEmpty() }
            .let { sections ->
                if (q.isEmpty()) {
                    sections
                } else {
                    sections.map { section ->
                        val union = section.union
                        if (matches(union.name, union.shortLabel, union.identifier)) {
                            section
                        } else {
                            section.copy(
                                branches = section.branches.filter { matches(it.name, it.shortLabel, it.identifier) },
                            )
                        }
                    }.filter { it.branches.isNotEmpty() }
                }
            }
            .sortedWith(compareBy(byName) { it.union.name })
    }

    /** The agreements list filter: name, identifier, short label or union, trimmed. */
    fun filterAgreements(agreements: List<AgreementSummary>, query: String): List<AgreementSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return agreements
        return agreements.filter { a ->
            listOf(a.name, a.identifier, a.shortLabel, a.unionIdentifier).any { it.orEmpty().lowercase().contains(q) }
        }
    }

    /**
     * The branch rate card, grouped department → designation in catalogue
     * order, with unknown roles gathered under a final "Other".
     *
     * Rows without a designation are dropped. A row whose department does not
     * list its designation vanishes without being orphaned — the web's own
     * behaviour, kept so the two clients show the same card.
     */
    fun rateSections(rates: List<DesignationRate>, catalogue: DepartmentCatalogue): List<RateDepartmentSection> {
        val byDepartment = LinkedHashMap<String, LinkedHashMap<String, MutableList<DesignationRate>>>()
        val orphans = mutableListOf<DesignationRate>()
        rates.forEach { row ->
            val designation = row.designationIdentifier?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (!catalogue.hasDesignation(designation)) {
                orphans += row
                return@forEach
            }
            val department = row.departmentIdentifier ?: catalogue.departmentOf(designation)?.identifier
            if (department == null) {
                orphans += row
                return@forEach
            }
            byDepartment.getOrPut(department) { LinkedHashMap() }.getOrPut(designation) { mutableListOf() } += row
        }

        val sections = catalogue.departments.mapNotNull { department ->
            val rows = byDepartment[department.identifier] ?: return@mapNotNull null
            department.designations
                .filter { it.identifier in rows }
                .map { designation ->
                    val rates = sortRows(rows.getValue(designation.identifier))
                    RateDesignationGroup(designation.identifier, designation.nameKey, matched = true, rates = rates)
                }
                .takeIf { it.isNotEmpty() }
                ?.let { RateDepartmentSection(department.identifier, department.nameKey, it) }
        }

        if (orphans.isEmpty()) return sections
        val orphanGroups = LinkedHashMap<String, MutableList<DesignationRate>>()
        orphans.forEach { orphanGroups.getOrPut(it.designationIdentifier.orEmpty()) { mutableListOf() } += it }
        val other = RateDepartmentSection(
            identifier = RateDepartmentSection.OTHER_IDENTIFIER,
            nameKey = RateDepartmentSection.OTHER_NAME_KEY,
            designations = orphanGroups.map { (id, rows) ->
                RateDesignationGroup(id, null, matched = false, rates = sortRows(rows))
            },
        )
        return sections + other
    }

    /** An unknown role's label: the identifier without `designation_` or a trailing `_other`. */
    fun orphanLabelKey(identifier: String): String = identifier.removePrefix("designation_").removeSuffix("_other")

    /** `sortRateRows`: production type, then lowest budget, then least experience — absent values first. */
    fun sortRows(rows: List<DesignationRate>): List<DesignationRate> = rows.sortedWith(
        compareBy<DesignationRate, String>(byName) { it.productionType.orEmpty() }
            .thenBy(nullsFirst()) { it.minBudget }
            .thenBy(nullsFirst()) { it.minExp },
    )

    /** `source` with its protocol and trailing slash removed, as the link text reads. */
    fun sourceText(url: String): String = url.replace(Regex("^https?://"), "").removeSuffix("/")

    /** Rate-card currency: the row's own, the branch's, then the territory's default. */
    fun fallbackCurrency(branch: Branch): String? =
        branch.currency ?: TerritoryCatalogue.defaultCurrency(branch.territory)

    /** A 1 / N plural the way the page writes it: `1 branch`, `2 branches`. */
    fun count(n: Int, one: String, many: String = "${one}s"): String = "$n ${if (n == 1) one else many}"
}

/**
 * A collective agreement as authored (`GET /agreements/:identifier`), with the
 * readers its detail page needs. Kept as the document rather than a model so
 * every rule block renders whatever shape its union published.
 */
data class AgreementDocument(val json: JsonObject) {

    val identifier: String? get() = text("_identifier")

    /** `label ?? name`, the heading. */
    val title: String get() = text("name") ?: text("label") ?: identifier.orEmpty()

    val source: String? get() = text("source")
    val territory: String? get() = text("territory")
    val currency: String? get() = text("currency")

    val parties: List<String>
        get() = (json["parties"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty) }

    fun block(key: String): JsonObject? = json[key] as? JsonObject

    /** `{note, rows[]}` rule blocks — hidden when they carry neither. */
    fun ruleBlock(key: String): RuleBlock? {
        val block = block(key) ?: return null
        val rows = (block["rows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val note = (block["note"] as? JsonPrimitive)?.takeIf { Js.truthy(it) }?.let { Js.text(it) }
        return if (rows.isEmpty() && note == null) null else RuleBlock(note, rows)
    }

    val empStatuses: List<EmpStatus>
        get() = (json["emp_statuses"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::empStatusOf) }

    private fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { Js.truthy(it) }?.let {
        Js.text(it)
    }
}

/** An agreement's rule block: an optional note above its rows. */
data class RuleBlock(val note: String?, val rows: List<JsonObject>)

/** Reads an employment status the way both the agreement doc and the listing carry it. */
fun empStatusOf(json: JsonObject): EmpStatus? {
    fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { Js.truthy(it) }?.let { Js.text(it) }
    val id = text("id") ?: return null
    return EmpStatus(
        id = id,
        label = text("label") ?: id,
        badge = text("badge"),
        alertClass = text("alert_cls"),
        hpShown = Js.truthy(json["hp_show"]),
        sub = text("sub"),
    )
}

/** A JSON value as text when present — the readers' common denominator. */
fun JsonElement?.textOrNull(): String? = this?.takeUnless(Js::isNullish)?.let { Js.text(it) }

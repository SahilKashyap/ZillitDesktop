package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One pay line of the nominal table: the key its code is stored under, and what it is called. */
data class NominalRow(
    val key: String,
    val element: String,
    val kind: NominalKind,
    val refId: String? = null,
    val category: String? = null,
)

enum class NominalKind { Core, Overtime, Premium, Turnaround, Fringe, ExtraFee, Penalty, Rental, Allowance }

/** An allowance or rental row as the nominal table edits it: its id, whether it is on, its code. */
data class EntitlementCode(val id: String, val name: String, val on: Boolean, val nominal: String)

/**
 * The nominal codes being edited — the wizard form's `nominalOverrides`, and
 * the codes carried on its allowance and rental rows.
 *
 * A key's presence matters: `basic_labour` and `holiday_pay` are only sent
 * when they exist, and they exist only when a code was stored or typed.
 */
data class NominalForm(
    val overrides: Map<String, String> = emptyMap(),
    val allowances: List<EntitlementCode> = emptyList(),
    val rentals: List<EntitlementCode> = emptyList(),
) {
    /** `nominalValueFor`. */
    fun valueFor(row: NominalRow): String = when (row.kind) {
        NominalKind.Allowance -> allowances.firstOrNull { it.id == row.refId }?.nominal.orEmpty()
        NominalKind.Rental -> rentals.firstOrNull { it.id == row.refId }?.nominal.orEmpty()
        else -> overrides[row.key].orEmpty()
    }

    fun withValue(row: NominalRow, code: String): NominalForm = when (row.kind) {
        NominalKind.Allowance ->
            copy(allowances = allowances.map { if (it.id == row.refId) it.copy(nominal = code) else it })
        NominalKind.Rental -> copy(rentals = rentals.map { if (it.id == row.refId) it.copy(nominal = code) else it })
        else -> copy(overrides = overrides + (row.key to code))
    }

    /**
     * `nominalCodeSignature`: sorted non-blank codes, then allowance and rental
     * codes — so typing a code and clearing it again is not a change.
     */
    val signature: String
        get() {
            val codes = overrides.filterValues { it.isNotBlank() }.toSortedMap().map { (k, v) -> "$k=${v.trim()}" }
            fun entitlements(list: List<EntitlementCode>, prefix: String) =
                list.filter { it.nominal.isNotBlank() }.map { "$prefix:${it.id}=${it.nominal.trim()}" }.sorted()
            return (codes + entitlements(allowances, "allow") + entitlements(rentals, "rent")).joinToString("|")
        }
}

/**
 * Nominal coding on a saved deal (`utils/nominalCoding.js`,
 * `utils/dealEditActions.js`): which lines need a code, which are still
 * missing one, and the PATCH that re-codes them.
 */
object NominalCoding {

    private val RULE_SURFACES = listOf(
        Triple("ot", "overtimes", NominalKind.Overtime),
        Triple("prem", "premiums", NominalKind.Premium),
        Triple("turn", "turnarounds", NominalKind.Turnaround),
        Triple("fringe", "fringes", NominalKind.Fringe),
        Triple("extra", "extra_fees", NominalKind.ExtraFee),
        Triple("penalty", "penalties", NominalKind.Penalty),
    )

    private val CATEGORY = mapOf(
        NominalKind.Overtime to "Overtime",
        NominalKind.Premium to "Premium",
        NominalKind.Turnaround to "Turnaround",
        NominalKind.Fringe to "Fringe",
        NominalKind.ExtraFee to "Extra Fee",
        NominalKind.Penalty to "Penalty",
    )

    private val HOLIDAY_PAY_LABEL = Regex("holiday\\s*pay", RegexOption.IGNORE_CASE)

    /** `nominalCodingApplies`: not a draft, not rejected, not a deal winding down. */
    fun applies(deal: DealDoc): Boolean =
        deal.rawStatus != DealStatus.Draft.wire && deal.rawStatus != DealStatus.Rejected.wire && !deal.isDeactivating

    /** `ruleRowId`: `row_id ?? source.id ?? ""`. */
    fun ruleRowId(row: JsonObject): String {
        val id = row["row_id"]?.takeUnless { it is JsonNull }
            ?: DocRead.obj(row, "source")?.get("id")?.takeUnless { it is JsonNull }
        return id?.let(Js::text).orEmpty()
    }

    /** `ruleRowKeys`: an id's first occurrence is the id, later ones `id#1`, `id#2`… */
    fun occurrenceKeys(ids: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return ids.map { id ->
            val count = seen[id] ?: 0
            seen[id] = count + 1
            if (count == 0) id else "$id#$count"
        }
    }

    /** `fromDealMemoPayload`'s nominal half: stored codes under the keys the table reads. */
    fun hydrate(deal: DealDoc): NominalForm {
        val overrides = linkedMapOf<String, String>()
        DocRead.text(deal.rates, "nominal_code")?.let { overrides["basic_labour"] = it }
        DocRead.text(DocRead.obj(deal.json, "holiday_pay"), "nominal_code")?.let { overrides["holiday_pay"] = it }
        RULE_SURFACES.forEach { (prefix, surface, _) ->
            val rows = DocRead.objects(deal.json[surface])
            val keys = occurrenceKeys(rows.map(::ruleRowId))
            rows.forEachIndexed { index, row ->
                val code = DocRead.text(row, "nominal_code") ?: return@forEachIndexed
                overrides["$prefix:${keys[index]}"] = code
                if (keys[index] == ruleRowId(row)) {
                    DocRead.text(row, "row_id")?.let { overrides["$prefix:$it"] = code }
                    DocRead.text(DocRead.obj(row, "source"), "id")?.let { overrides["$prefix:$it"] = code }
                }
            }
        }
        return NominalForm(overrides, entitlements(deal, "allowances"), entitlements(deal, "rentals"))
    }

    private fun entitlements(deal: DealDoc, surface: String): List<EntitlementCode> =
        DocRead.objects(deal.json[surface]).map { row ->
            // `enable !== false`: only a JSON false switches a row off.
            val enable = row["enable"] as? JsonPrimitive
            EntitlementCode(
                id = DocRead.text(row, "id") ?: DocRead.text(row, "_id").orEmpty(),
                name = DocRead.text(row, "name").orEmpty(),
                on = !(enable != null && !enable.isString && enable.booleanOrNull == false),
                nominal = DocRead.text(row, "nominal_code").orEmpty(),
            )
        }

    /**
     * `buildNominalRowsFromDeal`: Basic Labour, each rule line from the deal's
     * own arrays (id-less rows skipped but still counted, the holiday-pay
     * fringe never listed), enabled rentals, enabled allowances — de-duplicated.
     */
    fun rowsFromDeal(deal: DealDoc, form: NominalForm): List<NominalRow> {
        val list = mutableListOf(NominalRow("basic_labour", "Basic Labour", NominalKind.Core))
        RULE_SURFACES.forEach { (prefix, surface, kind) ->
            var rows = DocRead.objects(deal.json[surface])
            if (kind == NominalKind.Fringe) {
                rows = rows.filterNot { isHolidayPaySource(DocRead.obj(it, "source") ?: it) }
            }
            val keys = occurrenceKeys(rows.map(::ruleRowId))
            rows.forEachIndexed { index, row ->
                val id = ruleRowId(row).ifEmpty { return@forEachIndexed }
                val source = DocRead.obj(row, "source")
                val element =
                    listOf("label", "name", "raw_label").firstNotNullOfOrNull { DocRead.text(source, it) } ?: id
                list += NominalRow("$prefix:${keys[index]}", element, kind, id, CATEGORY[kind])
            }
        }
        form.rentals.filter { it.on }.forEach {
            list += NominalRow("rent:${it.id}", it.name.ifEmpty { it.id }, NominalKind.Rental, it.id, "Rental")
        }
        form.allowances.filter { it.on }.forEach {
            list += NominalRow("allow:${it.id}", it.name.ifEmpty { it.id }, NominalKind.Allowance, it.id, "Allowance")
        }
        return list.distinctBy { it.key }
    }

    /** The lines still without a code — whitespace is no code. */
    fun missingRows(deal: DealDoc, form: NominalForm): List<NominalRow> =
        rowsFromDeal(deal, form).filter { form.valueFor(it).isBlank() }

    /** The nominal table's `isHolidayPayRow`, on a rule's source: its id, or a label that says holiday pay. */
    private fun isHolidayPaySource(source: JsonObject): Boolean {
        val id = DocRead.text(source, "id")
        val label = (DocRead.text(source, "label") ?: DocRead.text(source, "name")).orEmpty()
        return id == "holiday_pay" || id == "hp" || HOLIDAY_PAY_LABEL.containsMatchIn(label)
    }

    /**
     * `buildNominalCodesPayload`: rates and holiday pay only when their key
     * exists; every rule surface the deal has, whole and in order; allowances
     * and rentals by id. Codes are trimmed and never wrapped here.
     */
    fun payload(form: NominalForm, deal: DealDoc): JsonObject = buildJsonObject {
        form.overrides["basic_labour"]?.let { put("rates", buildJsonObject { put("nominal_code", it.trim()) }) }
        form.overrides["holiday_pay"]?.let { put("holiday_pay", buildJsonObject { put("nominal_code", it.trim()) }) }
        RULE_SURFACES.forEach { (prefix, surface, _) ->
            val rows = DocRead.objects(deal.json[surface])
            if (rows.isEmpty()) return@forEach
            val keys = occurrenceKeys(rows.map(::ruleRowId))
            put(
                surface,
                buildJsonArray {
                    rows.forEachIndexed { index, row ->
                        val code =
                            form.overrides["$prefix:${keys[index]}"] ?: DocRead.text(row, "nominal_code").orEmpty()
                        add(
                            buildJsonObject {
                                put("row_id", ruleRowId(row))
                                put("nominal_code", code.trim())
                            },
                        )
                    }
                },
            )
        }
        listOf("allowances" to form.allowances, "rentals" to form.rentals).forEach { (surface, edits) ->
            val rows = DocRead.objects(deal.json[surface])
            if (rows.isEmpty()) return@forEach
            put(
                surface,
                buildJsonArray {
                    rows.forEach { row ->
                        val id = DocRead.text(row, "id") ?: DocRead.text(row, "_id").orEmpty()
                        val code =
                            edits.firstOrNull { it.id == id }?.nominal ?: DocRead.text(row, "nominal_code").orEmpty()
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("nominal_code", code.trim())
                            },
                        )
                    }
                },
            )
        }
    }
}

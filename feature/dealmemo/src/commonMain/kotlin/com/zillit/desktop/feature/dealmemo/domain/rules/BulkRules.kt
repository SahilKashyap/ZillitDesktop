package com.zillit.desktop.feature.dealmemo.domain.rules

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

/**
 * One row of the full-page rules grid (`BulkRulesEditor.jsx`): every field a
 * column, the rule it was seeded from kept whole in [source], and a [uid] that
 * is the row's identity — rule ids repeat across an agreement's rows.
 */
data class BulkRuleRow(
    val uid: String,
    val id: String,
    val template: RuleTemplate?,
    val label: String = "",
    val rateType: String = "multiplier",
    val amount: String = "",
    val basis: String = "hour",
    val form: TriggerForm = TriggerForm(),
    val nominal: String = "",
    val increment: String = "",
    val cap: String = "",
    val bdrMin: String = "",
    val bdrMax: String = "",
    val note: String = "",
    val dayType: String = "",
    val isEnhancement: Boolean = false,
    /** The model the row was seeded from — its unshown keys survive a save. */
    val source: JsonObject? = null,
    /** The list it came from: turnarounds stay turnarounds. */
    val origin: RuleList? = null,
) {
    /** `bulkRowReady`: a type, a name, a finite amount. */
    val ready: Boolean get() =
        template != null && label.isNotBlank() && amount.isNotEmpty() && amount.toDoubleOrNull()?.isFinite() == true

    /** Untouched since it was added — never ready, so it can only block Save. */
    val pristine: Boolean get() = template == null && label.isBlank() && amount.isEmpty()

    /** The storage list this row saves into. */
    val list: RuleList get() = origin ?: template?.list ?: RuleList.Overtimes

    /** Picking a type sets its rate type, base and trigger defaults. */
    fun withTemplate(next: RuleTemplate?): BulkRuleRow = if (next == null) {
        copy(template = null)
    } else {
        copy(template = next, rateType = next.rateType, basis = next.basis, form = next.defaultForm)
    }

    /** A name preset configures the whole rule from its type's defaults. */
    fun withPreset(preset: NamePreset): BulkRuleRow {
        val base = template ?: return copy(label = preset.label)
        return copy(
            label = preset.label,
            rateType = preset.rateType ?: base.rateType,
            amount = Js.number(preset.amount ?: base.rateAmount),
            basis = base.basis,
            form = mergeForm(base.defaultForm, preset.form),
        )
    }

    private fun mergeForm(defaults: TriggerForm, preset: TriggerForm?): TriggerForm = preset?.let {
        TriggerForm(
            hours = it.hours.ifEmpty { defaults.hours },
            time = it.time.ifEmpty { defaults.time },
            dayKinds = it.dayKinds.ifEmpty { defaults.dayKinds },
        )
    } ?: defaults
}

/** The rule model lists the grid reads and writes: `{overtimes, premiums, turnarounds, penalties}`. */
data class RuleLists(val lists: Map<RuleList, List<JsonObject>>)

/** The grid's conversions, and the deal's side of them (`utils/dealEditActions.js:195-262`). */
object BulkRules {

    private val SEED_ORDER = listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Turnarounds, RuleList.Penalties)
    private val DEAL_EDITABLE = listOf(RuleList.Overtimes, RuleList.Premiums, RuleList.Penalties)
    private val LEGACY_RATE_KEYS = setOf("multiplier", "flat", "percentage", "amount")

    fun blank(uid: String): BulkRuleRow = BulkRuleRow(uid = uid, id = uid, template = null)

    /** `listsToRows`: every list in order, one row per model. */
    fun rows(lists: RuleLists, newUid: () -> String): List<BulkRuleRow> = SEED_ORDER.flatMap { list ->
        lists.lists[list].orEmpty().map { model -> row(model, list, newUid) }
    }

    /** `bulkRowFromModel`. */
    fun row(model: JsonObject, list: RuleList?, newUid: () -> String): BulkRuleRow {
        val template = RuleTemplate.match(model["triggers"]) ?: RuleTemplate.classify(model, list)
        val (rateType, rateAmount) = normalizeRate(model)
        val first = ((model["triggers"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: JsonObject(emptyMap())
        val increment = first["increment"]?.takeUnless { it is JsonNull } ?: model["increments"]?.takeUnless {
            it is JsonNull
        }
        val capped = DocRead.text(model, "cap_type") == "capped" && DocRead.present(model, "cap_amount")
        return BulkRuleRow(
            uid = newUid(),
            id = DocRead.text(model, "id") ?: newUid(),
            template = template,
            label = DocRead.text(model, "label").orEmpty(),
            rateType = rateType,
            amount = rateAmount?.let(Js::number).orEmpty(),
            basis = DocRead.text(model, "basis") ?: "hour",
            form = template.fromTrigger(first),
            nominal = DocRead.text(model, "nominal_code").orEmpty(),
            increment = increment?.let(Js::text).orEmpty(),
            cap = if (capped) model["cap_amount"]?.let(Js::text).orEmpty() else "",
            bdrMin = first["bdr_min"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty(),
            bdrMax = first["bdr_max"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty(),
            note = DocRead.text(model, "note").orEmpty(),
            dayType = DocRead.text(model, "day_type").orEmpty(),
            isEnhancement = (model["is_enhancement"] as? JsonPrimitive)?.booleanOrNull == true,
            source = model,
            origin = list,
        )
    }

    /**
     * `normalizeRuleRate`: the canonical pair, else the legacy spellings — a
     * numeric string counts, a boolean does not.
     */
    @Suppress("CyclomaticComplexMethod")
    fun normalizeRate(model: JsonObject): Pair<String, Double?> {
        fun number(key: String): Double? {
            val value = model[key] as? JsonPrimitive ?: return null
            if (value is JsonNull || (!value.isString && value.booleanOrNull != null)) return null
            if (value.isString && value.content.isEmpty()) return null
            return (value.doubleOrNull ?: value.content.trim().toDoubleOrNull())?.takeIf { it.isFinite() }
        }
        val type = DocRead.text(model, "rate_type")
            ?: "multiplier".takeIf { number("multiplier") != null }
            ?: "flat".takeIf { number("flat") != null }
            ?: "percentage".takeIf { number("percentage") != null }
            ?: "multiplier"
        val amount = number("rate_amount") ?: number("amount") ?: number("multiplier") ?: number("flat")
            ?: number("percentage")
        return type to amount
    }

    /**
     * `modelFromBulkRow`: the seeded model with the grid's fields laid over it —
     * one canonical trigger, the cap made explicit, codes and notes set or
     * deleted — so a cleared value cannot come back from the source.
     */
    fun model(row: BulkRuleRow): Pair<JsonObject, RuleList> {
        val template = row.template ?: RuleTemplate.Ot
        val trigger = template.toTrigger(row.form).toMutableMap()
        row.bdrMin.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { trigger["bdr_min"] = number(it) }
        row.bdrMax.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { trigger["bdr_max"] = number(it) }
        row.increment.toDoubleOrNull()?.let { floor(it) }?.takeIf { it.isFinite() && it > 1 }
            ?.let { trigger["increment"] = JsonPrimitive(it.toLong()) }
        val cap = row.cap.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val model = (row.source ?: JsonObject(emptyMap())).filterKeys { it !in LEGACY_RATE_KEYS }.toMutableMap()
        model["id"] = JsonPrimitive(row.id)
        model["label"] = JsonPrimitive(row.label.trim())
        model["rate_type"] = JsonPrimitive(row.rateType)
        model["rate_amount"] = number(row.amount.toDoubleOrNull() ?: Double.NaN)
        model["basis"] = JsonPrimitive(row.basis)
        model["triggers"] = buildJsonArray { add(JsonObject(trigger)) }
        model["cap_type"] = JsonPrimitive(if (cap != null) "capped" else "uncapped")
        model["cap_amount"] = cap?.let(::number) ?: JsonNull
        setOrDelete(model, "nominal_code", row.nominal.trim())
        setOrDelete(model, "note", row.note.trim())
        model["is_enhancement"] = JsonPrimitive(row.isEnhancement)
        setOrDelete(model, "day_type", row.dayType)
        return JsonObject(model) to row.list
    }

    /** `rowsToLists`: back into the storage lists — turnarounds only when it has rows. */
    fun lists(rows: List<BulkRuleRow>): RuleLists {
        val grouped = rows.map(::model).groupBy({ it.second }, { it.first })
        val lists = linkedMapOf(
            RuleList.Overtimes to grouped[RuleList.Overtimes].orEmpty(),
            RuleList.Premiums to grouped[RuleList.Premiums].orEmpty(),
            RuleList.Penalties to grouped[RuleList.Penalties].orEmpty(),
        )
        grouped[RuleList.Turnarounds]?.takeIf { it.isNotEmpty() }?.let { lists[RuleList.Turnarounds] = it }
        return RuleLists(lists)
    }

    /** Add-only import: the source's rows whose id is not already on screen. */
    fun importable(source: List<BulkRuleRow>, onScreen: List<BulkRuleRow>): List<BulkRuleRow> {
        val have = onScreen.map { it.id }.toSet()
        return source.filter { it.id !in have }
    }

    /**
     * `dealRulesToEditorData`: each deal row's `source`, with the row's own id
     * and nominal code folded in. Turnarounds are never seeded.
     */
    fun fromDeal(deal: DealDoc): RuleLists = RuleLists(
        DEAL_EDITABLE.associateWith { list ->
            DocRead.objects(deal.json[list.wire]).map { row ->
                val source = DocRead.obj(row, "source") ?: JsonObject(emptyMap())
                val id = source["id"]?.takeUnless { it is JsonNull } ?: row["row_id"]?.takeUnless { it is JsonNull }
                JsonObject(
                    source + mapOf(
                        "id" to (id ?: JsonPrimitive("")),
                        "nominal_code" to (row["nominal_code"]?.takeUnless { it is JsonNull } ?: JsonPrimitive("")),
                    ),
                )
            }
        },
    )

    /**
     * `editorDataToDealRules`: each committed model merged over the deal row it
     * came from (matched by id), so the engine fields the grid never shows
     * survive; new rows pass through, deleted rows are gone, turnarounds are
     * never sent. An empty result means there was nothing to amend.
     */
    fun toDeal(lists: RuleLists, deal: DealDoc): JsonObject = buildJsonObject {
        DEAL_EDITABLE.forEach { list ->
            val models = lists.lists[list] ?: return@forEach
            val originals = DocRead.objects(deal.json[list.wire]).associateBy { row ->
                val source = DocRead.obj(row, "source")
                (source?.get("id")?.takeUnless { it is JsonNull } ?: row["row_id"]?.takeUnless { it is JsonNull })
                    ?.let(Js::text).orEmpty()
            }
            put(list.wire, buildJsonArray { models.forEach { add(mergeOver(it, originals)) } })
        }
    }

    private fun mergeOver(model: JsonObject, originals: Map<String, JsonObject>): JsonObject {
        val code = model["nominal_code"]
        val source = JsonObject(model - "nominal_code")
        val sourceId = source["id"]?.takeUnless { it is JsonNull }
        val original = originals[sourceId?.let(Js::text).orEmpty()]
        val originalSource = DocRead.obj(original, "source") ?: JsonObject(emptyMap())
        val row = (original ?: JsonObject(emptyMap())).toMutableMap()
        row["row_id"] = original?.get("row_id")?.takeUnless { it is JsonNull } ?: sourceId ?: JsonPrimitive("")
        row["source"] = JsonObject(originalSource + source)
        row["nominal_code"] = code?.takeUnless { it is JsonNull } ?: original?.get("nominal_code")?.takeUnless {
            it is JsonNull
        }
            ?: JsonPrimitive("")
        row["pay_frequency"] = original?.get("pay_frequency")?.takeUnless { it is JsonNull } ?: JsonPrimitive("")
        return JsonObject(row)
    }

    private fun setOrDelete(model: MutableMap<String, JsonElement>, key: String, value: String) {
        if (value.isNotEmpty()) model[key] = JsonPrimitive(value) else model.remove(key)
    }

    /** A whole number stays an integer on the wire, as JavaScript writes it. */
    private fun number(value: Double): JsonElement = when {
        !value.isFinite() -> JsonNull
        value == floor(value) && abs(value) < MAX_SAFE -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    private const val MAX_SAFE = 9_007_199_254_740_991.0
}

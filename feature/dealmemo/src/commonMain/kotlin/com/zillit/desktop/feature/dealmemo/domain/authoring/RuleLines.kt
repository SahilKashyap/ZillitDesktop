package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Holiday pay on the projected fringes: whether it is a fringe at all, and inclusive or exclusive. */
data class HolidayPayLine(val isFringe: Boolean, val treatment: String?)

/** Every rule line a deal will carry, as raw source rows in persisted order. */
data class RuleLineSet(
    val isFlatFee: Boolean,
    val customOn: Boolean,
    val overtimes: List<JsonObject>,
    val premiums: List<JsonObject>,
    val turnarounds: List<JsonObject>,
    val fringes: List<JsonObject>,
    val extraFees: List<JsonObject>,
    val penalties: List<JsonObject>,
    val hp: HolidayPayLine,
)

/**
 * The one answer to "which rule lines exist on this deal?" (`ruleLineProjection.js`),
 * shared by the payload and the Nominal Coding table so neither can list a
 * line the other won't carry.
 */
object RuleLines {

    private val PENALTY_ID = Regex("penalty|broken[_\\s-]turnaround|rest[_\\s-]day|curtail")
    private val PENALTY_LABEL = Regex("penalty|broken turnaround|rest day|curtail")
    private val HOLIDAY_PAY = Regex("holiday\\s*pay", RegexOption.IGNORE_CASE)
    private val ROW_FIELDS = listOf("label", "basis", "cap_type", "cap_amount", "min", "max", "nominal_code", "note")

    /** Agreements publish penalties inside the overtime block; the deal splits them out. */
    fun isPenaltyRow(row: JsonObject?): Boolean {
        val id = text(row?.get("id")).lowercase()
        val label = (nonNull(row?.get("label")) ?: nonNull(row?.get("raw_label")))?.let(Js::text).orEmpty().lowercase()
        return PENALTY_ID.containsMatchIn(id) || PENALTY_LABEL.containsMatchIn(label)
    }

    fun isHolidayPayRow(row: JsonObject?): Boolean {
        val id = (row?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val label = (nonNull(row?.get("label")) ?: nonNull(row?.get("name")))?.let(Js::text).orEmpty()
        return id == "holiday_pay" || id == "hp" || HOLIDAY_PAY.containsMatchIn(label)
    }

    fun isFlatFeeDealType(dealType: String?): Boolean =
        dealType == "picture" || dealType == "buyout" || dealType == "buy-out"

    /** The "Customize pay rules" toggle; null (a fresh edit-load) reads ON when any authoring was reconstructed. */
    fun customOn(form: DealForm): Boolean {
        val flag = form["rulesCustomized"]
        return if (flag == null || flag is JsonNull) {
            (form.obj("ruleOverrides")?.isNotEmpty() == true) ||
                (form.obj("ruleRowEdits")?.isNotEmpty() == true) ||
                form.list("ruleCustomRows").isNotEmpty()
        } else {
            Js.truthy(flag)
        }
    }

    /** A persisted row's id: `row_id ?? source.id ?? ""`. */
    fun rowId(row: JsonObject?): String =
        nonNull(row?.get("row_id"))?.let(Js::text)
            ?: nonNull((row?.get("source") as? JsonObject)?.get("id"))?.let(Js::text)
            ?: ""

    /** `ruleRowKeys`: the first row with an id keeps the bare id, repeats count `id#1`, `id#2` — per id. */
    fun rowKeys(rows: List<JsonObject?>): List<String> {
        val seen = HashMap<String, Int>()
        return rows.map { row ->
            val id = nonNull(row?.get("id"))?.let(Js::text).orEmpty()
            val n = seen[id] ?: 0
            seen[id] = n + 1
            if (n == 0) id else "$id#$n"
        }
    }

    /** `normalizeRuleRate`: canonical pair first, then `amount`, then the legacy spellings. */
    @Suppress("CyclomaticComplexMethod")
    fun normalizeRate(row: JsonObject?): Pair<String, Double?> {
        @Suppress("ReturnCount") // Each unusable shape returns early, as the web's does.
        fun num(value: JsonElement?): Double? {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            if (primitive.isString && primitive.content.isEmpty()) return null
            if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return null
            return Js.toNumber(primitive)
        }
        val rateType = nonNull(row?.get("rate_type"))?.let(Js::text)?.takeIf { it.isNotEmpty() }
            ?: "multiplier".takeIf { num(row?.get("multiplier")) != null }
            ?: "flat".takeIf { num(row?.get("flat")) != null }
            ?: "percentage".takeIf { num(row?.get("percentage")) != null }
            ?: "multiplier"
        val amount = num(row?.get("rate_amount")) ?: num(row?.get("amount")) ?: num(row?.get("multiplier"))
            ?: num(row?.get("flat")) ?: num(row?.get("percentage"))
        return rateType to amount
    }

    /** Has an authored row moved away from its base? Triggers by JSON, the rate by meaning. */
    fun rowDiffers(edited: JsonObject?, base: JsonObject?): Boolean {
        if (edited == null || base == null) return edited != null
        if ((edited["triggers"] ?: JsonNull).toString() != (base["triggers"] ?: JsonNull).toString()) return true
        val (typeA, amountA) = normalizeRate(edited)
        val (typeB, amountB) = normalizeRate(base)
        if (typeA != typeB || !sameNumber(amountA, amountB)) return true
        return ROW_FIELDS.any { field -> fieldDiffers(edited[field], base[field]) }
    }

    private fun sameNumber(a: Double?, b: Double?): Boolean {
        // `Number(null)` is 0 and NaN never equals itself.
        val x = a ?: 0.0
        val y = b ?: 0.0
        return x == y
    }

    private fun fieldDiffers(a: JsonElement?, b: JsonElement?): Boolean {
        val aNull = a == null || a is JsonNull
        val bNull = b == null || b is JsonNull
        if (aNull && bNull) return false
        if (isNumber(a) || isNumber(b)) {
            // `Number(a) !== Number(b)`: undefined is NaN, so a missing side always differs.
            val x = Js.toNumber(a)
            val y = Js.toNumber(b)
            return x == null || y == null || x != y
        }
        return (if (aNull) "" else Js.text(a)) != (if (bNull) "" else Js.text(b))
    }

    private fun isNumber(value: JsonElement?): Boolean =
        value is JsonPrimitive &&value !is JsonNull && !value.isString && value.content != "true" &&
            value.content != "false"

    /** `applyRowAuthoring`: an edit replaces its row wholesale; a removal drops it. */
    fun applyAuthoring(rows: List<JsonObject>, edits: JsonObject?, removals: Set<String>?): List<JsonObject> {
        if (edits == null && removals == null) return rows
        val keys = rowKeys(rows)
        return rows.mapIndexedNotNull { index, row ->
            val key = keys[index]
            when {
                removals?.contains(key) == true -> null
                else -> (edits?.get(key) as? JsonObject)?.takeIf { Js.truthy(it) } ?: row
            }
        }
    }

    /** The authoring a form carries, or none while the customiser is off. */
    fun authoringOf(form: DealForm): Pair<JsonObject?, Set<String>?> {
        if (!customOn(form)) return null to null
        val removals = form.list("ruleRowRemovals").map(Js::text)
        return form.obj("ruleRowEdits") to removals.takeIf { it.isNotEmpty() }?.toSet()
    }

    /** `projectRuleLines(form, selectedUnion, nonUnionPaybreakdown)`. */
    @Suppress("CyclomaticComplexMethod")
    fun project(form: DealForm, selectedUnion: JsonObject?, nonUnionPaybreakdown: JsonObject?): RuleLineSet {
        val flat = isFlatFeeDealType(form.text("dealType").takeIf { form["dealType"] != null })
        val custom = customOn(form)
        val customRows = if (custom) form.objects("ruleCustomRows") else emptyList()
        fun customFor(group: String) = customRows
            .filter { Js.text(it["group"]) == group }
            .mapNotNull { (it["row"] as? JsonObject)?.takeIf { row -> Js.truthy(row["id"]) } }

        val nuActive = isNonUnionId(form.text("union")) && nonUnionPaybreakdown != null
        fun nu(key: String) = objects(nonUnionPaybreakdown?.get(key))
        fun union(block: String) = objects((selectedUnion?.get(block) as? JsonObject)?.get("rows"))

        val allOt = when {
            flat -> emptyList()
            nuActive -> nu("overtimes")
            else -> union("overtimes")
        }
        val overtimes = (if (nuActive) allOt else allOt.filterNot(::isPenaltyRow)) +
            (if (flat) emptyList() else customFor("Overtime"))
        val premiums = when {
            flat -> emptyList()
            nuActive -> nu("premiums")
            else -> union("premiums")
        } + (if (flat) emptyList() else customFor("Premium"))
        val turnarounds = (if (flat || nuActive) emptyList() else union("turnaround")) +
            (if (flat) emptyList() else customFor("Turnaround"))
        val penalties = when {
            flat -> emptyList()
            nuActive -> nu("penalties")
            else -> allOt.filter(::isPenaltyRow)
        } + (if (flat) emptyList() else customFor("Penalty"))
        val extraFees = if (flat) emptyList() else union("allowances").filter { Js.text(it["id"]) != "production_fee" }

        val fringeKey = ((selectedUnion?.get("fringe_map") as? JsonObject)?.get(form.text("employmentStatus")))
            ?.takeUnless { it is JsonNull }?.let(Js::text) ?: "default"
        val fringes = selectedUnion?.get("fringes") as? JsonObject
        val pkg = (fringes?.get(fringeKey) as? JsonObject) ?: (fringes?.get("default") as? JsonObject)
        val fringesRaw = objects(pkg?.get("items"))
        val hpIsFringe = fringesRaw.any(::isHolidayPayRow)
        val treatment = if (hpIsFringe) (if (form.text("hpMode") == "excl") "excl" else "incl") else null
        val keptFringes = if (treatment == "incl") fringesRaw.filterNot(::isHolidayPayRow) else fringesRaw

        val edits = if (custom) form.obj("ruleRowEdits") else null
        val removals = if (custom) {
            form.list("ruleRowRemovals").map(Js::text).takeIf { it.isNotEmpty() }?.toSet()
        } else {
            null
        }
        return RuleLineSet(
            isFlatFee = flat,
            customOn = custom,
            overtimes = applyAuthoring(overtimes, edits, removals),
            premiums = applyAuthoring(premiums, edits, removals),
            turnarounds = applyAuthoring(turnarounds, edits, removals),
            fringes = keptFringes,
            extraFees = extraFees,
            penalties = applyAuthoring(penalties, edits, removals),
            hp = HolidayPayLine(hpIsFringe, treatment),
        )
    }

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private fun nonNull(value: JsonElement?): JsonElement? = value?.takeUnless { it is JsonNull }

    private fun text(value: JsonElement?): String = nonNull(value)?.let(Js::text).orEmpty()
}

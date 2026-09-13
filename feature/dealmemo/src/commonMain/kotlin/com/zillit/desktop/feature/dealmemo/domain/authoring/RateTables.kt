package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementFormat
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs
import kotlin.math.ceil

/** A reference table's block: its note and the rows the deal carries. */
data class RuleSection(val note: String?, val rows: List<JsonObject>)

/** The agreement's rule blocks as this deal authors them, and the penalty rows shown beside overtime. */
data class RuleSections(
    val overtime: RuleSection?,
    val premiums: RuleSection?,
    val turnaround: RuleSection?,
    val penaltyRows: List<JsonObject>,
)

/** One line of the Overtimes table. */
data class OvertimeLine(
    val band: String,
    val sub: String?,
    val multiplier: String,
    val min: String,
    val max: String,
    val enhancement: Boolean,
)

/** One line of the fringe table. */
data class FringeLine(val cost: String, val percent: String, val flat: String, val basis: String, val type: String)

data class FringeTotals(val percentCost: Double, val flatCost: Double, val total: Double)

/** The fringe package's holiday-pay line, as the Rates step and its validator find it. */
data class HolidayPayItem(val item: JsonObject, val percentText: String, val rate: Double) {
    /** The red tag beside the toggle: the line's label, else its name, else its percentage. */
    val tag: String
        get() = (item["label"]?.takeUnless(Js::isNullish) ?: item["name"]?.takeUnless(Js::isNullish))?.let(Js::text)
            ?: "$percentText%"
}

/** The DGA production fee a US DGA role publishes. */
data class DgaFee(val prodFee: JsonElement, val note: String?, val coaBasis: List<JsonObject>, val shootDays: Int) {
    /** `calcPPWeeks`: whole weeks of principal photography. */
    val ppWeeks: Int get() = ceil(shootDays / DAYS_PER_WEEK).toInt()

    private companion object {
        const val DAYS_PER_WEEK = 5.0
    }
}

/**
 * What the Rates step shows beside the rate inputs (`Step5Rates.jsx`): the
 * fringe package and its totals, holiday pay, the agreement's rule tables,
 * the constraint chips and the DGA production fee — each figure in the
 * step's own words, which are not always the memo's.
 */
@Suppress("TooManyFunctions") // One table per card the rates editor draws.
object RateTables {

    private val HOLIDAY_PAY = Regex("holiday\\s*pay", RegexOption.IGNORE_CASE)
    private val CONGES_PAYES = Regex("congés\\s*payés|icp", RegexOption.IGNORE_CASE)
    private val PENALTY_ID = Regex("penalty|broken[_\\s-]turnaround|rest[_\\s-]day")
    private val PENALTY_LABEL = Regex("penalty|broken turnaround|rest day")
    private val FLAT_EXCLUDED_ID = Regex("penalty|broken[_\\s-]turnaround|rest[_\\s-]day|meal_allowance")
    private val FLAT_EXCLUDED_LABEL = Regex("penalty|broken turnaround|rest day|meal allowance")

    /** The fringe table's basis names — its own map, capitalised unlike the memo's. */
    private val FRINGE_BASIS = mapOf(
        "weekly_gross" to "Weekly Gross",
        "qualifying_earnings" to "Qualifying Earnings",
        "base_weekly" to "Base Weekly",
        "ordinary_time" to "Ordinary Time",
        "scale_wages" to "Scale Wages",
        "gross_invoice" to "Gross Invoice",
        "gross_fees" to "Gross Fees",
        "excess_threshold" to "Excess of Threshold",
        "futa_wage_base" to "FUTA Wage Base",
    )

    private val BASIS_DISPLAY = mapOf(
        "30_minutes" to "30 min", "hour" to "hour", "day" to "day", "days" to "days", "night" to "night",
        "event" to "event", "mile" to "mile", "meal" to "meal", "call" to "call", "penalty" to "penalty",
        "additional" to "additional", "actuals" to "Actuals",
    )

    /** `getFringePackage`: the package the employment status maps to, else the default one. */
    fun fringePackage(agreement: JsonObject?, employmentStatus: String): JsonObject? {
        agreement ?: return null
        val key = (agreement["fringe_map"] as? JsonObject)
            ?.get(employmentStatus)
            ?.takeUnless(Js::isNullish)
            ?.let(Js::text) ?: "default"
        val fringes = agreement["fringes"] as? JsonObject
        return fringes?.get(key) as? JsonObject ?: fringes?.get("default") as? JsonObject
    }

    fun items(pkg: JsonObject?): List<JsonObject> = objects(pkg?.get("items"))

    /** The holiday-pay line: flagged exclusive-only, named so, or France's congés payés / ICP. */
    fun holidayPay(pkg: JsonObject?): HolidayPayItem? {
        val item = items(pkg).firstOrNull { item ->
            val name = nameOf(item)
            isStrictTrue(item["hp_excl_only"]) || isText(item["id"], "holiday_pay") ||
                HOLIDAY_PAY.containsMatchIn(name) || CONGES_PAYES.containsMatchIn(name)
        } ?: return null
        val raw = present(item["rate_amount"]) ?: present(item["percentage"])
        return HolidayPayItem(item, raw?.let(Js::text) ?: "0", (raw?.let(Js::toNumber) ?: 0.0) / PERCENT)
    }

    /** The fringe table: a holiday-pay line only while HP is paid on top. */
    @Suppress("CyclomaticComplexMethod")
    fun fringeLines(pkg: JsonObject?, hpMode: String): List<FringeLine> = items(pkg)
        .filter { item ->
            val hpLine = isStrictTrue(item["hp_excl_only"]) || isText(item["id"], "holiday_pay") ||
                HOLIDAY_PAY.containsMatchIn(nameOf(item))
            !hpLine || hpMode == "excl"
        }
        .map { item ->
            val amount = present(item["rate_amount"]) ?: present(item["percentage"]) ?: present(item["flat"])
            val type = present(item["rate_type"])?.let(Js::text)
                ?: if (present(item["flat"]) != null) "flat" else "percentage"
            val statutory = present(item["statutory"])
            FringeLine(
                cost = (present(item["label"]) ?: present(item["name"]))?.let(Js::text) ?: RateFormat.DASH,
                percent = if (type == "percentage" && amount != null) "${Js.text(amount)}%" else RateFormat.DASH,
                flat = if (type == "flat" && amount != null) {
                    "£${AgreementFormat.groupAmount(amount)}"
                } else {
                    RateFormat.DASH
                },
                basis = present(item["basis"])?.let(Js::text)?.let { FRINGE_BASIS[it] ?: it } ?: RateFormat.DASH,
                type = when {
                    statutory != null -> if (Js.truthy(statutory)) "Statutory" else "Contractual"
                    else -> present(item["type"])?.let(Js::text) ?: RateFormat.DASH
                },
            )
        }

    /**
     * `calcFringeTotal`: percentages of the weekly rate plus flat amounts, an exclusive-only line only when exclusive.
     */
    fun fringeTotals(weeklyRate: Double, items: List<JsonObject>, hpMode: String): FringeTotals {
        var percentCost = 0.0
        var flatCost = 0.0
        items.forEach { item ->
            val exclusiveOnly = present(item["hp_excl_only"]) ?: present(item["hpExclOnly"])
            if (Js.truthy(exclusiveOnly) && hpMode != "excl") return@forEach
            val type = present(item["rate_type"])?.let(Js::text)
                ?: if (present(item["flat"]) != null) "flat" else "percentage"
            val amount = listOf("rate_amount", "flat", "percentage", "rate")
                .firstNotNullOfOrNull { present(item[it]) }
                ?.let(Js::toNumber)
            if (amount == null || amount <= 0) return@forEach
            when (type) {
                "flat" -> flatCost += amount
                "percentage" -> percentCost += weeklyRate * amount / PERCENT
            }
        }
        return FringeTotals(round2(percentCost), round2(flatCost), round2(percentCost + flatCost))
    }

    /** `fmtComp`: `OT rate`, `10% of gross`, `£25/event`, `×1.5`, `+0.5`, `Actuals`, a basis, or a dash. */
    @Suppress("CyclomaticComplexMethod")
    fun compensation(row: JsonObject, sym: String): String {
        if (Js.truthy(row["use_ot_rate"])) return "OT rate"
        val rawType = present(row["rate_type"])
        val type = when {
            rawType != null && isText(rawType, "fixed") -> "flat"
            rawType != null -> Js.text(rawType)
            present(row["multiplier"]) != null -> "multiplier"
            present(row["flat"]) != null -> "flat"
            present(row["percentage"]) != null -> "percentage"
            else -> null
        }
        val amount = present(row["rate_amount"]) ?: present(row["multiplier"]) ?: present(row["flat"])
            ?: present(row["percentage"])
        val basis = row["basis"]?.takeIf(Js::truthy)?.let(Js::text)?.let { BASIS_DISPLAY[it] ?: it.replace('_', ' ') }
        return when {
            type == "percentage" && amount != null -> "${Js.text(amount)}% of ${basis ?: "gross"}"
            type == "flat" && amount != null -> "$sym${AgreementFormat.groupAmount(amount)}/${basis ?: "event"}"
            type == "multiplier" && amount != null ->
                "${if (Js.truthy(row["is_enhancement"])) "+" else "×"}${Js.text(amount)}"
            rawType != null && isText(rawType, "actuals") -> "Actuals"
            basis != null -> basis
            else -> RateFormat.DASH
        }
    }

    /**
     * `resolveRuleSources` with the deal's authoring laid over each block: the
     * project pay breakdown for a non-union deal, the agreement's own blocks
     * otherwise. A union's penalties stay inside its overtime block.
     */
    fun ruleSections(form: DealForm, agreement: JsonObject?, paybreakdown: JsonObject): RuleSections {
        val nonUnion = isNonUnionId(form.text("union"))
        val (edits, removals) = RuleLines.authoringOf(form)
        val customRows = if (RuleLines.customOn(form)) form.objects("ruleCustomRows") else emptyList()
        fun authored(rows: List<JsonObject>, group: String): List<JsonObject> =
            RuleLines.applyAuthoring(rows, edits, removals) +
                customRows.filter { isText(it["group"], group) && Js.truthy(it["row"]) }.mapNotNull {
                    it["row"] as? JsonObject
                }
        fun section(base: JsonObject?, group: String): RuleSection? {
            val rows = authored(objects(base?.get("rows")), group)
            if (base == null && rows.isEmpty()) return null
            return RuleSection(base?.get("note")?.takeIf(Js::truthy)?.let(Js::text), rows)
        }
        fun rowsOnly(key: String) = JsonObject(mapOf("rows" to (paybreakdown[key] ?: JsonArray(emptyList()))))
        return RuleSections(
            overtime = section(
                if (nonUnion) rowsOnly("overtimes") else agreement?.get("overtimes") as? JsonObject,
                "Overtime",
            ),
            premiums = section(
                if (nonUnion) rowsOnly("premiums") else agreement?.get("premiums") as? JsonObject,
                "Premium",
            ),
            turnaround = section(if (nonUnion) null else agreement?.get("turnaround") as? JsonObject, "Turnaround"),
            penaltyRows = authored(if (nonUnion) objects(paybreakdown["penalties"]) else emptyList(), "Penalty"),
        )
    }

    /** The Overtimes table: the overtime block's rows, then the penalty rows. */
    fun overtimeLines(sections: RuleSections, sym: String): List<OvertimeLine> =
        (sections.overtime?.rows.orEmpty() + sections.penaltyRows).map { row ->
            val amount = present(row["rate_amount"]) ?: present(row["multiplier"])
            val type = present(row["rate_type"])?.let(Js::text)
                ?: if (present(row["multiplier"]) != null) "multiplier" else null
            OvertimeLine(
                band = present(row["label"])?.let(Js::text).orEmpty(),
                sub = present(row["note"])?.let(Js::text),
                multiplier = when {
                    Js.truthy(row["use_ot_rate"]) -> "OT rate"
                    type == "multiplier" && amount != null -> "×${Js.text(amount)}"
                    else -> RateFormat.DASH
                },
                min = present(row["min"])?.let { "$sym${AgreementFormat.groupAmount(it)}/hr" } ?: RateFormat.DASH,
                max = present(row["max"])?.let { "$sym${AgreementFormat.groupAmount(it)}/hr" } ?: RateFormat.DASH,
                enhancement = Js.truthy(row["is_enhancement"]),
            )
        }

    /**
     * The Agreement Constraints chips: the hourly rate from the day rate, the
     * first published OT floor and cap, and the OT multipliers and flat
     * rates — penalties, turnarounds and rest days left out.
     */
    fun constraintChips(
        dayRate: Double,
        daily: MergedTier?,
        overtimeRows: List<JsonObject>,
        sym: String,
    ): List<Pair<String, String?>> {
        val divisor = daily?.hours
        val divisorValue = divisor?.let(Js::toNumber) ?: DEFAULT_HOURS
        val capped = overtimeRows.filter { present(it["min"]) != null || present(it["max"]) != null }
        val floor = capped.firstNotNullOfOrNull { present(it["min"]) }
        val cap = capped.firstNotNullOfOrNull { present(it["max"]) }
        val multipliers = distinctSorted(
            overtimeRows.filter { isOtRow(it, "multiplier", PENALTY_ID, PENALTY_LABEL) }
                .mapNotNull { present(it["rate_amount"]) ?: present(it["multiplier"]) },
        )
        val flats = distinctSorted(
            overtimeRows.filter { isOtRow(it, "flat", FLAT_EXCLUDED_ID, FLAT_EXCLUDED_LABEL) }
                .mapNotNull { present(it["rate_amount"]) },
        )
        return buildList {
            val hourly = RateFormat.groupAmount(dayRate / divisorValue)
            add("Hourly Rate: $sym$hourly/hr" to "(day rate ÷ ${divisor?.let(Js::text) ?: "10"})")
            floor?.let { add("OT Min: $sym${AgreementFormat.groupAmount(it)}/hr" to null) }
            cap?.let { add("OT Max: $sym${AgreementFormat.groupAmount(it)}/hr" to null) }
            when (multipliers.size) {
                0 -> Unit
                1 -> add("OT Rate: ×${Js.text(multipliers.first())}T all OT" to null)
                else -> add("OT Rate: ${multipliers.joinToString(" / ") { "×${Js.text(it)}T" }}" to null)
            }
            when (flats.size) {
                0 -> Unit
                1 -> add("OT Rate: $sym${AgreementFormat.groupAmount(flats.first())}/hr" to null)
                else -> {
                    val range = "$sym${AgreementFormat.groupAmount(flats.first())}–" +
                        "$sym${AgreementFormat.groupAmount(flats.last())}"
                    add("OT Rate: $range/hr" to null)
                }
            }
        }
    }

    /** The agreement's own allowances — travel, idle days, ICP — without the DGA production fee. */
    fun agreementAllowances(agreement: JsonObject?): RuleSection? {
        val block = agreement?.get("allowances") as? JsonObject
        val rows = objects(block?.get("rows")).filterNot { isText(it["id"], "production_fee") }
        if (rows.isEmpty()) return null
        return RuleSection(block?.get("note")?.takeIf(Js::truthy)?.let(Js::text), rows)
    }

    /** `agreementLabel(union, short_label ?? label ?? union ?? "Agreement")`. */
    fun agreementName(union: String, agreement: JsonObject?): String = when {
        isNonUnionId(union) -> "Non-Union"
        else -> (present(agreement?.get("short_label")) ?: present(agreement?.get("label")))?.let(Js::text) ?: union
    }

    /** The role's name for the scale line: the custom title, else the designation's label. */
    fun designationName(form: DealForm, catalogue: DepartmentCatalogue): String? {
        val job = form.text("jobTitle")
        if (job.isEmpty()) return null
        if (job == DealForm.CUSTOM_JOB_TITLE) return form.text("customJobTitle").ifEmpty { null }
        catalogue.departments.forEach { department ->
            department.designations.firstOrNull { it.id == job || it.identifier == job }?.let {
                return DealLabels.formatLabel(it.nameKey)
            }
        }
        return if (job.startsWith("designation_")) CrewRoles.designationLabel(job, catalogue) else job
    }

    /** The production fee card's facts — US DGA, a flagged agreement, a role with a published fee. */
    @Suppress("ReturnCount") // Each missing input ends the lookup, as the web's does.
    fun dgaFee(form: DealForm, agreement: JsonObject?): DgaFee? {
        if (!isDga(form)) return null
        val info = agreement?.get("dga") as? JsonObject
        val entry = scaleEntry(form, agreement) ?: return null
        val fee = present(entry["prod_fee"]) ?: return null
        val flagged =
            (info?.get("production_fee") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
        if (!flagged) return null
        return DgaFee(
            prodFee = fee,
            note = entry["note"]?.takeIf(Js::truthy)?.let(Js::text),
            coaBasis = objects(info?.get("coa_basis")),
            shootDays = BuilderSeeds.inclusiveDays(form.text("schedShootStart"), form.text("schedShootEnd")),
        )
    }

    /** The weekly fee the step fills in for a US DGA role with a published fee. */
    fun dgaSeedFee(form: DealForm, agreement: JsonObject?): String? {
        if (!isDga(form)) return null
        return scaleEntry(form, agreement)?.get("prod_fee")?.takeIf(Js::truthy)?.let(Js::text)
    }

    /** `calcDGACOA`. */
    fun coaAmount(basis: String, weeklyRate: Double, negotiated: Double): Double = when (basis) {
        "1week", "100pct" -> round2(weeklyRate)
        "2.5days" -> round2(weeklyRate / DGA_WEEK_DAYS * COA_DAYS)
        "50pct" -> round2(weeklyRate * HALF)
        "negotiated" -> round2(negotiated)
        else -> 0.0
    }

    /** `n.toLocaleString(locale, {minimumFractionDigits: 2})`: grouped, two to three places. */
    fun localeAmount(value: Double): String {
        if (!value.isFinite()) return Js.number(value)
        val fixed = Js.toFixed(abs(value), LOCALE_MAX_DIGITS)
        val whole = fixed.substringBefore('.')
        val fraction = fixed.substringAfter('.', "").trimEnd('0').padEnd(2, '0')
        val sign = if (value < 0 && fixed.any { it in '1'..'9' }) "-" else ""
        return "$sign${whole.reversed().chunked(THOUSANDS).joinToString(",").reversed()}.$fraction"
    }

    /** `parseFloat(n.toFixed(2))`. */
    fun round2(value: Double): Double = if (value.isFinite()) Js.toFixed(value, 2).toDouble() else value

    private fun isDga(form: DealForm) = form.text("union") == "dga" && form.text("territory") == "us"

    private fun scaleEntry(form: DealForm, agreement: JsonObject?): JsonObject? {
        val designation = form.text("designation").ifEmpty { return null }
        return (agreement?.get("scale") as? JsonObject)?.get(designation) as? JsonObject
    }

    private fun isOtRow(row: JsonObject, rateType: String, idPattern: Regex, labelPattern: Regex): Boolean {
        if (Js.truthy(row["use_ot_rate"])) return false
        if (!isText(row["rate_type"], rateType)) return false
        val id = present(row["id"])?.let(Js::text).orEmpty().lowercase()
        val label = present(row["label"])?.let(Js::text).orEmpty().lowercase()
        return !idPattern.containsMatchIn(id) && !labelPattern.containsMatchIn(label)
    }

    /** `Array.from(new Set(values)).sort((a, b) => a - b)`. */
    private fun distinctSorted(values: List<JsonElement>): List<JsonElement> =
        values.distinct().sortedBy { Js.toNumber(it) ?: Double.NaN }

    private fun nameOf(item: JsonObject): String =
        (present(item["label"]) ?: present(item["name"]))?.let(Js::text).orEmpty()

    private fun present(value: JsonElement?): JsonElement? = value?.takeUnless { it is JsonNull }

    private fun isStrictTrue(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

    private fun isText(value: JsonElement?, text: String): Boolean =
        (value as? JsonPrimitive)?.let { it.isString && it.content == text } == true

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private const val PERCENT = 100.0
    private const val DEFAULT_HOURS = 10.0
    private const val DGA_WEEK_DAYS = 5.0
    private const val COA_DAYS = 2.5
    private const val HALF = 0.5
    private const val LOCALE_MAX_DIGITS = 3
    private const val THOUSANDS = 3
}

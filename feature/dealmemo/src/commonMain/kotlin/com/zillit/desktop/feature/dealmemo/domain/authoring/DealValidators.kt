package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One section's failure: the popup copy and the field list the section's banner names. */
data class SectionFailure(val title: String, val message: String, val fields: List<String>)

/** An enabled allowance or rental row that can't save as it stands. */
data class EntitlementIssue(val kind: String, val id: String, val name: String, val missing: List<String>)

/** A line of the Nominal Coding table, keyed as the payload keys it. */
data class NominalLine(
    val key: String,
    val element: String,
    val kind: String,
    val refId: String?,
    val category: String?,
)

/**
 * The builder's section gates (`utils/stepValidators.js`, `entitlementValidation.js`,
 * `nominalCoding.js`, and the page-local rules in `runStepValidator`), as pure
 * functions over the form so Issue can walk every section at once.
 */
object DealValidators {

    const val TERRITORY = 1
    const val CREW = 2
    const val PERSONAL = 3
    const val EMPLOYMENT = 4
    const val DEAL = 5
    const val RATES = 6
    const val ALLOWANCES = 7
    const val NOMINAL = 9

    private val HP_LABEL = Regex("holiday\\s*pay", RegexOption.IGNORE_CASE)
    private val HP_FRENCH = Regex("congés\\s*payés|icp", RegexOption.IGNORE_CASE)
    private val LEGACY_BASES = setOf("hour", "night", "event")

    private fun badEmail(label: String, value: String) =
        "$label — enter a valid email address".takeUnless { CrewFormRules.validEmail(value) }

    private fun badPhone(label: String, value: String) =
        "$label — enter a valid phone number (5–15 digits)".takeUnless { CrewFormRules.validPhone(value) }

    /** `validateStep1`: entity, territory (union deals), agreement, and a band when the agreement publishes them. */
    fun territory(form: DealForm, union: JsonObject?): SectionFailure? {
        val bands = ((union?.get("pact") as? JsonObject)?.get("bands") as? JsonArray).orEmpty()
        val fields = buildList {
            if (form.text("productionEntity").isEmpty()) add("Production Entity")
            if (!isNonUnionId(form.text("union")) && form.text("territory").isEmpty()) add("Territory")
            if (form.text("union").isEmpty()) add("Agreement")
            if (bands.isNotEmpty() && form.text("pactBand").isEmpty()) add("Budget Band")
        }
        return fields.takeIf { it.isNotEmpty() }?.let {
            SectionFailure(
                "Territory & Agreement required",
                "Pick a territory and the matching agreement before continuing — these drive the working rules and " +
                    "rate cards used by every later step.",
                it,
            )
        }
    }

    /** `validateStep2`. */
    fun crew(form: DealForm): SectionFailure? {
        val fields = buildList {
            if (form.flag("isExternal")) {
                if (form.text("crewName").trim().isEmpty()) add("Crew Name")
            } else if (form.text("userId").isEmpty()) {
                add("Crew Member")
            }
            if (form.text("department").isEmpty()) add("Department")
            if (form.text("designation").isEmpty()) add("Designation")
            if (form.text("crewType").isEmpty()) add("Crew Type")
            if (form.text("unit").isEmpty()) add("Unit")
        }
        return fields.takeIf { it.isNotEmpty() }?.let {
            SectionFailure(
                "Crew details required",
                "Fill the crew member's role and department — these drive the rate-card lookup and which fringe " +
                    "package applies.",
                it,
            )
        }
    }

    /** `validateEmployeeStatus`. */
    fun employment(form: DealForm): SectionFailure? {
        val fields = buildList {
            if (form.text("employmentStatus").isEmpty()) add("Employment Status")
            badEmail("Loan Out Company email", form.text("loanOutEmail"))?.let(::add)
            badPhone("Loan Out Company phone", form.text("loanOutPhoneNumber"))?.let(::add)
        }
        return fields.takeIf { it.isNotEmpty() }?.let {
            SectionFailure(
                "Employee status required",
                "Select the crew member's employment status — it determines which fringe package applies in Rates & " +
                    "Compensation.",
                it,
            )
        }
    }

    /** `validatePersonal`. */
    fun personal(form: DealForm): SectionFailure? {
        val fields = buildList {
            if (form.text("fullLegalName").trim().isEmpty()) add("Full Legal Name")
            if (form.flag("isExternal") && form.text("email").isEmpty()) add("Email")
            listOfNotNull(
                badEmail("Email", form.text("email")),
                badEmail("Emergency contact email", form.text("emergencyEmail")),
                badEmail("Agency email", form.text("representativeEmail")),
                badPhone("Mobile", form.text("mobile")),
                badPhone("Emergency contact number", form.text("emergencyContactNumber")),
                badPhone("Agency phone", form.text("representativePhone")),
            ).forEach(::add)
        }
        return fields.takeIf { it.isNotEmpty() }?.let {
            SectionFailure(
                "Crew contact required",
                "External crew need a contact email so they can receive their deal and complete their own details.",
                it,
            )
        }
    }

    /** `validateStep3`, plus the page's phase-schedule completeness. */
    fun deal(form: DealForm): SectionFailure? {
        val fields = buildList {
            if (form.text("dealType").isEmpty()) add("Deal Type")
            if (form.text("dealStart").isEmpty()) add("Start Date")
            if (form.text("dealEnd").isEmpty()) add("Estimated End Date")
            if (form.flag("schedOn")) {
                var anyFilled = false
                listOf("Prep", "Shoot", "Wrap").forEach { name ->
                    val start = form.text("sched${name}Start")
                    val end = form.text("sched${name}End")
                    if (start.isNotEmpty() || end.isNotEmpty()) anyFilled = true
                    if (start.isNotEmpty() && end.isEmpty()) add("$name End Date")
                    if (start.isEmpty() && end.isNotEmpty()) add("$name Start Date")
                }
                if (!anyFilled) add("Phase Schedule dates (or turn the schedule off)")
            }
        }
        return fields.takeIf { it.isNotEmpty() }?.let {
            SectionFailure(
                "Deal terms required",
                "The deal type and date range anchor the engagement. Picture / buy-out deals collapse the rate " +
                    "fields in the next step, so the choice has to land here first.",
                it,
            )
        }
    }

    /** The selected employment status's fringe package publishes holiday pay. */
    fun agreementHasHolidayPay(form: DealForm, union: JsonObject?): Boolean {
        val map = union?.get("fringe_map") as? JsonObject
        val key = map?.get(form.text("employmentStatus"))?.takeUnless { it is JsonNull }?.let(Js::text) ?: "default"
        val fringes = union?.get("fringes") as? JsonObject
        val pkg = (fringes?.get(key) as? JsonObject) ?: (fringes?.get("default") as? JsonObject)
        return ((pkg?.get("items") as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }.any { item ->
            val label = (item["label"]?.takeUnless { it is JsonNull } ?: item["name"])
                ?.takeUnless { it is JsonNull }
                ?.let(Js::text)
                .orEmpty()
            (item["hp_excl_only"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true ||
                Js.text(item["id"] ?: JsonNull) == "holiday_pay" ||
                HP_LABEL.containsMatchIn(label) || HP_FRENCH.containsMatchIn(label)
        }
    }

    /** `validateStep4`, then the page's zero-rate rule: a 0 rate is never an agreed rate. */
    @Suppress("CyclomaticComplexMethod")
    fun rates(form: DealForm, union: JsonObject?): SectionFailure? {
        val type = form.text("dealType")
        val picture = type == "picture"
        val box = type == "boxrental"
        val buyout = type == "buyout" || type == "buy-out"
        val hpRequired = agreementHasHolidayPay(form, union) && !picture && !buyout
        val mode = form.text("buyoutRateMode").ifEmpty { "weekly" }
        fun blank(key: String) = form.text(key).trim().isEmpty()
        fun zero(key: String) = !blank(key) && (Js.parseFloat(form[key]) ?: 0.0) == 0.0
        val fields = mutableListOf<String>()
        if (form.text("currency").isEmpty()) fields += "Contract Currency"
        if (form.text("paymentCurrency").isEmpty()) fields += "Payment Currency"
        val customNoticeMissing =
            form.text("noticeType") == "custom" && (Js.toNumber(form["noticeCustomValue"]) ?: 0.0) <= 0.0
        if (!picture && !box && customNoticeMissing) fields += "Custom Notice Period"
        when {
            picture -> if (blank("pictureFee")) fields += "Picture Fee"
            buyout -> {
                if (mode != "daily" && blank("buyoutRate")) fields += "Buy-Out Rate (weekly)"
                if (mode != "weekly" && blank("buyoutDailyRate")) fields += "Daily Rate"
            }
            else -> {
                if (blank("dayRate")) fields += "Day Rate"
                if (blank("weeklyRate")) fields += "Weekly Rate"
            }
        }
        if (hpRequired && form.text("hpMode").isEmpty()) fields += "Holiday Pay (HP) Treatment"
        val unnamedDay = form.list("customDays").any { day ->
            ((day as? JsonObject)?.get("name")?.takeUnless { it is JsonNull }?.let(Js::text) ?: "").trim().isEmpty()
        }
        if (unnamedDay) fields += "Custom day name"
        fun zeroBlocks(key: String, label: String) {
            if (zero(key) && label !in fields) fields += label
        }
        when {
            picture -> zeroBlocks("pictureFee", "Picture Fee")
            buyout -> {
                if (mode != "daily") zeroBlocks("buyoutRate", "Buy-Out Rate (weekly)")
                if (mode != "weekly") zeroBlocks("buyoutDailyRate", "Daily Rate")
            }
            else -> {
                zeroBlocks("dayRate", "Day Rate")
                zeroBlocks("weeklyRate", "Weekly Rate")
            }
        }
        if (fields.isEmpty()) return null
        return SectionFailure(
            "Rates & compensation required",
            if (hpRequired && "Holiday Pay (HP) Treatment" in fields) {
                "The selected agreement publishes a holiday-pay fringe — pick whether HP is included in the day rate " +
                    "or paid on top before continuing."
            } else {
                "Fill the contract / payment currency and the agreed rate before continuing."
            },
            fields,
        )
    }

    /** `getMissingEntitlementFields(row, kind)`. */
    @Suppress("CyclomaticComplexMethod")
    fun entitlementMissing(row: JsonObject, kind: String): List<String> {
        fun blank(key: String) = row[key]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty().trim().isEmpty()
        val missing = mutableListOf<String>()
        if (isCustomRow(row) && blank("name")) missing += "name"
        listOf("rate", "basis", "applies_to").forEach { if (blank(it)) missing += it }
        if ("basis" !in missing && Js.text(row["basis"] ?: JsonNull).trim().lowercase() in LEGACY_BASES) {
            missing += "basis"
        }
        if (
            "rate" !in missing &&
            PayloadParts.entitlementRate(row["rate"]?.takeUnless { it is JsonNull }?.let(Js::text)) == null
        ) {
            missing += "rate"
        }
        if (kind == "rental" && Js.text(row["cap_type"] ?: JsonNull) == "capped" && blank("cap_amount")) {
            missing += "cap_amount"
        }
        return missing
    }

    fun isCustomRow(row: JsonObject?): Boolean = (row?.get("id") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.startsWith("custom-") == true

    /** `validateEntitlements`: every enabled row with something missing. */
    fun entitlementIssues(form: DealForm): List<EntitlementIssue> =
        listOf("allowance" to "allowances", "rental" to "rentals").flatMap { (kind, key) ->
            form.objects(key).filter { Js.truthy(it["on"]) }.mapNotNull { row ->
                val missing = entitlementMissing(row, kind)
                if (missing.isEmpty()) return@mapNotNull null
                val id = row["id"]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()
                val name = row["name"]?.takeIf(Js::truthy)?.let(Js::text) ?: id.ifEmpty { "(unnamed)" }
                EntitlementIssue(kind, id, name, missing)
            }
        }

    /** `summariseEntitlementIssues`. */
    fun entitlementSummary(issues: List<EntitlementIssue>): String {
        if (issues.isEmpty()) return ""
        val names = issues.map { it.name }
        val more = if (names.size > 2) " and ${names.size - 2} more" else ""
        return "Allowances & Rentals: ${names.take(2).joinToString(", ")}$more — fill rate, pay frequency, " +
            "applies-to (and cap amount when capped) on every enabled row, or toggle the row off."
    }

    fun allowances(form: DealForm): SectionFailure? {
        val issues = entitlementIssues(form)
        if (issues.isEmpty()) return null
        return SectionFailure(
            "Allowances & Rentals incomplete",
            entitlementSummary(issues),
            issues.map { "${it.name} — missing ${it.missing.joinToString(", ")}" },
        )
    }

    /** `buildNominalRows`: every codeable line the saved deal will carry, holiday pay never among them. */
    fun nominalLines(form: DealForm, union: JsonObject?, nonUnionPaybreakdown: JsonObject?): List<NominalLine> {
        val lines = RuleLines.project(form, union, nonUnionPaybreakdown)
        val out = mutableListOf(NominalLine("basic_labour", "Basic Labour", "core", null, null))
        fun push(rows: List<JsonObject>, prefix: String, kind: String, category: String) {
            val keys = RuleLines.rowKeys(rows)
            rows.forEachIndexed { index, row ->
                val id = row["id"]?.takeUnless { it is JsonNull }?.let(Js::text)
                val element = row["label"]?.takeIf(Js::truthy)?.let(Js::text) ?: id.orEmpty()
                out += NominalLine("$prefix:${keys[index]}", element, kind, id, category)
            }
        }
        push(lines.overtimes, "ot", "ot", "Overtime")
        push(lines.premiums, "prem", "premium", "Premium")
        push(lines.turnarounds, "turn", "turnaround", "Turnaround")
        push(lines.fringes.filterNot(RuleLines::isHolidayPayRow), "fringe", "fringe", "Fringe")
        push(lines.extraFees, "extra", "extra_fee", "Extra Fee")
        push(lines.penalties, "penalty", "penalty", "Penalty")
        listOf("rentals" to ("rent" to "Rental"), "allowances" to ("allow" to "Allowance")).forEach { (key, spec) ->
            form.objects(key).filter { Js.truthy(it["on"]) }.forEach { row ->
                val id = row["id"]?.takeUnless { it is JsonNull }?.let(Js::text)
                val element = row["name"]?.takeIf(Js::truthy)?.let(Js::text) ?: id.orEmpty()
                out += NominalLine(
                    "${spec.first}:$id",
                    element,
                    if (key == "rentals") "rental" else "allowance",
                    id,
                    spec.second,
                )
            }
        }
        return out.distinctBy { it.key }
    }

    /** `nominalValueFor`. */
    fun nominalValue(form: DealForm, line: NominalLine): String = when (line.kind) {
        "allowance", "rental" -> form.objects(if (line.kind == "allowance") "allowances" else "rentals")
            .firstOrNull { it["id"]?.let(Js::text) == line.refId }
                ?.get("nominal")
                ?.takeUnless { it is JsonNull }
                ?.let(Js::text)
                .orEmpty()
        else -> form.obj("nominalOverrides")?.get(line.key)?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()
    }

    fun missingNominals(form: DealForm, union: JsonObject?, nonUnionPaybreakdown: JsonObject?): List<NominalLine> =
        nominalLines(form, union, nonUnionPaybreakdown).filter { nominalValue(form, it).trim().isEmpty() }

    /** The page's section validator by id; null means the section passes. */
    fun section(id: Int, form: DealForm, union: JsonObject?): SectionFailure? = when (id) {
        TERRITORY -> territory(form, union)
        CREW -> crew(form)
        PERSONAL -> personal(form)
        EMPLOYMENT -> employment(form)
        DEAL -> deal(form)
        RATES -> rates(form, union)
        ALLOWANCES -> allowances(form)
        else -> null
    }
}

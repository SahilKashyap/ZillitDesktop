package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId
import com.zillit.desktop.feature.dealmemo.domain.preview.BankWire
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A crew member's master department and designation ids, for the deal's top-level scoping keys. */
data class PayloadUser(val departmentId: String? = null, val designationId: String? = null)

/** What `toDealMemoPayload` reads besides the form. */
data class PayloadContext(
    /** The full agreement document, or the synthetic non-union one. */
    val selectedUnion: JsonObject? = null,
    /** The first resolved rate-card row for the role. */
    val resolvedRate: JsonObject? = null,
    val departments: DepartmentCatalogue = DepartmentCatalogue(),
    val usersById: Map<String, PayloadUser> = emptyMap(),
    val payrollDefaults: JsonObject? = null,
    val projectDayTypes: List<JsonObject> = emptyList(),
    val nonUnionPaybreakdown: JsonObject? = null,
    /** The chart of accounts' codes; null or empty means codes are never wrapped. */
    val coaCodes: Set<String>? = null,
)

/**
 * `toDealMemoPayload(form, ctx)`: the flat builder form as the deal-memo
 * schema groups it. Only this object decides the wire shape; the save paths
 * add `_id`, `status` and `notify` around it.
 */
@Suppress("TooManyFunctions") // One function per payload section, in the web's order.
object DealPayload {

    private val NON_UNION_DAY_TYPES = listOf(
        dayType("SWD", 600, 60, "Standard Working Day"),
        dayType("CWD", 540, 0, "Continuous Working Day"),
        dayType("SCWD", 570, 30, "Semi-Continuous Working Day"),
    )

    private val ONBOARD = listOf(
        Triple("starter", "P45 or Starter Checklist", true),
        Triple("rtw-doc", "Right to Work documentation", true),
        Triple("bank", "Bank account details (BACS)", true),
        Triple("sds", "IR35 Status Determination Statement", true),
        Triple("nda", "NDA signed and returned", false),
        Triple("hs", "H&S induction acknowledged", false),
        Triple("covid", "COVID-19 policy acknowledged", false),
        Triple("privacy", "Privacy notice acknowledged", false),
    )

    private val RULE_OVERRIDE_FIELDS = listOf("rate_amount", "multiplier", "min", "max", "cap_amount")
    private const val PASSPORT_MAX = 2

    @Suppress("LongMethod")
    fun build(form: DealForm, ctx: PayloadContext): JsonObject {
        val union = ctx.selectedUnion
        val lines = RuleLines.project(form, union, ctx.nonUnionPaybreakdown)
        val crewUser = form.text("userId").takeIf { it.isNotEmpty() }?.let(ctx.usersById::get)
        return buildJsonObject {
            put("user_id", if (form.flag("isExternal")) null else form.text("userId").ifEmpty { null })
            put("is_external", form.flag("isExternal"))
            put("company_id", form.text("productionEntity").ifEmpty { null })
            put("department_id", crewUser?.departmentId?.ifEmpty { null })
            put("designation_id", crewUser?.designationId?.ifEmpty { null })
            put("deal_reference", JsonNull)
            put("status", "draft")
            put("territory_union", territoryUnion(form, ctx))
            put("crew_details", crewDetails(form, ctx))
            put("deal", deal(form, lines.isFlatFee))
            put("credit_conditions", conditions(form))
            put("rates", rates(form, ctx, lines.isFlatFee))
            put("holiday_pay", holidayPay(form, ctx, lines))
            put("rules_customized", lines.customOn)
            put("overtimes", decorate(form, ctx, lines, lines.overtimes, "ot"))
            put("premiums", decorate(form, ctx, lines, lines.premiums, "prem"))
            put("turnarounds", decorate(form, ctx, lines, lines.turnarounds, "turn"))
            put("fringes", decorate(form, ctx, lines, lines.fringes, "fringe"))
            put("extra_fees", decorate(form, ctx, lines, lines.extraFees, "extra"))
            put("day_types", dayTypes(form, ctx))
            put("custom_days", customDays(form))
            put(
                "allowances",
                JsonArray(form.objects("allowances").filter { Js.truthy(it["on"]) }.map { entitlement(it, form, ctx) }),
            )
            put(
                "rentals",
                JsonArray(form.objects("rentals").filter { Js.truthy(it["on"]) }.map { entitlement(it, form, ctx) }),
            )
            put("penalties", decorate(form, ctx, lines, lines.penalties, "penalty"))
            put("nominal_coding", nominalCoding(form, ctx))
            put(
                "compliance_onboarding",
                buildJsonObject {
                    put("checks", JsonArray(emptyList()))
                    put(
                        "onboard",
                        buildJsonArray {
                            ONBOARD.forEach { (id, label, required) ->
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("label", label)
                                        put("required", required)
                                        put("done", Js.truthy(form.obj("onboardChecked")?.get(id)))
                                    },
                                )
                            }
                        },
                    )
                },
            )
            put("additional_documents", documents(form))
            put("long_form_contract", form["longFormContract"]?.takeIf(Js::truthy) ?: JsonNull)
            put("payroll", payroll(form, ctx))
            put("bank", BankWire.payload(form.obj("bank")))
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun territoryUnion(form: DealForm, ctx: PayloadContext): JsonObject {
        val union = ctx.selectedUnion
        val pact = union?.get("pact") as? JsonObject
        val special = form.flag("pactSpecialDept") && objects(pact?.get("special_depts")).any { entry ->
            val designation = entry["designation_identifier"]
            Js.text(entry["department_identifier"] ?: JsonNull) == form.text("department") &&
                (!Js.truthy(designation) || Js.text(designation) == form.text("designation"))
        }
        val extra = pact?.get("extra_contracted_hours") as? JsonObject
        return buildJsonObject {
            put("prod_entity", form.text("productionEntity"))
            put("prod_type", form.text("productionType"))
            put("territory_code", form.text("territory"))
            put("agreement_identifier", form.text("union"))
            put("union_identifier", union?.get("union_identifier")?.takeIf(Js::truthy) ?: JsonPrimitive(""))
            put(
                "branch_identifier",
                ctx.resolvedRate?.get("branch_identifier")?.takeUnless { it is JsonNull }
                    ?: union?.get("union_identifier")?.takeUnless { it is JsonNull }
                    ?: JsonPrimitive(""),
            )
            put("band", form.text("pactBand"))
            put("special_dept", special)
            put(
                "special_dept_extra_hours",
                buildJsonObject {
                    if (special && extra != null) {
                        put("hrs", extra["hrs"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(0))
                        put("multiplier", extra["multiplier"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(1.0))
                        put("basis", extra["basis"]?.takeUnless { it is JsonNull } ?: JsonNull)
                        put("note", extra["note"]?.takeUnless { it is JsonNull } ?: JsonNull)
                    } else {
                        put("hrs", 0)
                        put("multiplier", 1.0)
                        put("basis", JsonNull)
                        put("note", JsonNull)
                    }
                },
            )
            put("territory", JsonNull)
            put("union", union?.get("_id")?.takeUnless { it is JsonNull } ?: JsonNull)
            put("union_band", form.text("pactBand"))
        }
    }

    @Suppress("LongMethod")
    private fun crewDetails(form: DealForm, ctx: PayloadContext): JsonObject = buildJsonObject {
        put("crew_name", form.text("crewName").ifEmpty { form.text("fullLegalName") })
        put("full_legal_name", form.text("fullLegalName").ifEmpty { null })
        put("preferred_name", form.text("preferredName").ifEmpty { null })
        put("screen_credit_designation", form.text("screenCreditDesignation").ifEmpty { null })
        put("passport_attachment", JsonArray(passports(form["passportAttachment"]).take(PASSPORT_MAX)))
        put("department_id", departmentId(form.text("department"), ctx.departments))
        put("designation_id", designationId(form.text("designation"), ctx.departments))
        put("department_identifier", form.text("department").ifEmpty { null })
        put("designation_identifier", form.text("designation").ifEmpty { null })
        put(
            "custom_designation",
            if (form.text("jobTitle") == DealForm.CUSTOM_JOB_TITLE) {
                form.text("customJobTitle").ifEmpty { null }
            } else {
                null
            },
        )
        put(
            "reports_to",
            when (form.text("reportsToType")) {
                "Other" -> form.text("reportsTo")
                "N/A" -> "N/A"
                else -> "HOD"
            },
        )
        put("call_sheet_tier", form.text("callSheetTier").ifEmpty { "Crew" })
        put("crew_type", form.text("crewType").ifEmpty { null })
        put("experience_yrs", JsonNull)
        put("emp_status", form.text("employmentStatus"))
        put(
            "loan_out_company",
            buildJsonObject {
                put("name", form.text("loanOutCompanyName"))
                put("address", PayloadParts.normalizeAddress(form["loanOutCompanyAddress"]))
                put("country_code", form.text("loanOutCountryCode"))
                put("phone_number", form.text("loanOutPhoneNumber"))
                put("email", form.text("loanOutEmail"))
            },
        )
        put("agency_id", form.text("agencyId").ifEmpty { null })
        put("agency_name", form.text("agencyName").ifEmpty { null })
        put("dob", PayloadParts.toEpoch(form.text("dob")))
        put("insurance_no", form.text("niNumber").ifEmpty { null })
        put("tax_code", form.text("taxCode").ifEmpty { null })
        put("right_to_work", form.text("rightToWork").ifEmpty { null })
        put("home_address", PayloadParts.normalizeAddress(form["homeAddress"]))
        put("uk", ukToWire(form.obj("uk")))
        put("emergency_contact", form.text("emergencyContact").ifEmpty { null })
        put("emergency_contact_name", form.text("emergencyContactName").ifEmpty { null })
        put("emergency_contact_number", form.text("emergencyContactNumber").ifEmpty { null })
        put(
            "emergency_details",
            contact(
                form.text("emergencyContactName"),
                form.text("emergencyCountryCode"),
                form.text("emergencyContactNumber"),
                form.text("emergencyEmail"),
                form["emergencyAddress"],
            ),
        )
        put(
            "representative_details",
            contact(
                form.text("representativeName"),
                form.text("representativeCountryCode"),
                form.text("representativePhone"),
                form.text("representativeEmail"),
                form["representativeAddress"],
            ),
        )
        put("email", form.text("email").ifEmpty { null })
        put("mobile", form.text("mobile").ifEmpty { null })
        put("gender", form.text("gender").ifEmpty { null })
        put("unit", form.text("unit").ifEmpty { null })
        put("mandatory_fields", JsonArray(form.list("crewMandatoryFields")))
    }

    private fun contact(
        name: String,
        code: String,
        phone: String,
        email: String,
        address: JsonElement?,
    ) = buildJsonObject {
        put("name", name)
        put("country_code", code)
        put("phone_number", phone)
        put("email", email)
        put("address", PayloadParts.normalizeAddress(address))
    }

    /** `ukToWire`: blanks to null, the figures through `num`, the pension wish never null. */
    fun ukToWire(block: JsonObject?): JsonObject = buildJsonObject {
        fun orNull(key: String) = block?.get(key)?.takeIf(Js::truthy) ?: JsonNull
        put("starter_statement", orNull("starter_statement"))
        put("p45_previous_pay", PayloadParts.number(PayloadParts.num(block?.get("p45_previous_pay"))))
        put("p45_previous_tax", PayloadParts.number(PayloadParts.num(block?.get("p45_previous_tax"))))
        put("p45_leaving_date", block?.get("p45_leaving_date")?.takeUnless { it is JsonNull } ?: JsonNull)
        put("p45_previous_paye_ref", orNull("p45_previous_paye_ref"))
        put("student_loan_plan", orNull("student_loan_plan"))
        put("pg_loan", orNull("pg_loan"))
        put("ni_category", orNull("ni_category"))
        put("pension_status", block?.get("pension_status")?.takeIf(Js::truthy) ?: JsonPrimitive("opt_in"))
    }

    private fun deal(form: DealForm, flat: Boolean): JsonObject = buildJsonObject {
        put("type", form.text("dealType").ifEmpty { "weekly" })
        put("start_date", PayloadParts.toEpoch(form.text("dealStart")))
        put("end_date", PayloadParts.toEpoch(form.text("dealEnd")))
        put("deal_completion_due", PayloadParts.toEpoch(form.text("completionDue")))
        put("deal_memo_date", PayloadParts.toEpoch(form.text("dealMemoDate")))
        put("billing_basis", form.text("billingBasis").ifEmpty { "week" })
        listOf("prep" to "Prep", "shoot" to "Shoot", "wrap" to "Wrap").forEach { (key, phase) ->
            put(
                key,
                buildJsonObject {
                    put("start_date", if (flat) null else PayloadParts.toEpoch(form.text("sched${phase}Start")))
                    put("end_date", if (flat) null else PayloadParts.toEpoch(form.text("sched${phase}End")))
                },
            )
        }
        put("notice_period", PayloadParts.noticePeriodToken(form))
        put("notice_reminder", PayloadParts.noticeReminderToken(form))
        put("additional_notes", form.text("additionalNotes").trim())
    }

    private fun conditions(form: DealForm): JsonObject = buildJsonObject {
        put("work_location", form.text("workLocationType"))
        put("travel_zone", form.text("travelZone").ifEmpty { "30mile" })
        put("distant_loc_applied", form.flag("distantLocation"))
        put(
            "custom_conditions",
            JsonArray(
                form.list("customConditions")
                    .map { if (it is JsonNull) "" else Js.text(it).trim() }
                    .filter { it.isNotEmpty() }
                    .map(::JsonPrimitive),
            ),
        )
    }

    private fun rates(form: DealForm, ctx: PayloadContext, flat: Boolean): JsonObject {
        val brd = ctx.selectedUnion?.get("basic_rate_details") as? JsonObject
        val effHourly = mergeTier(
            pickTierEntry(ctx.resolvedRate?.get("hourly"), tierHours(brd, "hourly")),
            brd?.get("hourly") as? JsonObject,
        )
        val effDaily = mergeTier(
            pickTierEntry(ctx.resolvedRate?.get("daily"), tierHours(brd, "daily")),
            brd?.get("daily") as? JsonObject,
        )
        val effWeekly = mergeTier(
            pickTierEntry(ctx.resolvedRate?.get("weekly"), tierHours(brd, "weekly")),
            brd?.get("weekly") as? JsonObject,
        )
        val dayRate = PayloadParts.num(form.text("dayRate"))
        val dailyHours = effDaily?.second
        val basicHours = PayloadParts.num(form.text("basicWorkingHoursPerDay")) ?: 0.0
        val weeklyRate = PayloadParts.num(form.text("weeklyRate")) ?: 0.0
        val zero = JsonPrimitive(0)
        return buildJsonObject {
            put("contract_currency", form.text("currency").ifEmpty { "GBP" })
            put("pay_currency", form.text("paymentCurrency").ifEmpty { form.text("currency") }.ifEmpty { "GBP" })
            put(
                "daily",
                buildJsonObject {
                    put("rate", if (flat) zero else PayloadParts.number(dayRate ?: 0.0))
                    put("hrs", if (flat) zero else (dailyHours ?: PayloadParts.number(basicHours)))
                },
            )
            put(
                "weekly",
                buildJsonObject {
                    put("rate", if (flat) zero else PayloadParts.number(weeklyRate))
                    put("hrs", if (flat) zero else (effWeekly?.second ?: zero))
                },
            )
            put("hr_rate", if (flat) zero else hourlyRate(effHourly, dailyHours, dayRate))
            put(
                "phase_rates",
                buildJsonObject {
                    put("on", form.flag("phaseRatesOn"))
                    put("prep_rate", PayloadParts.number(PayloadParts.num(form.text("prepRate"))))
                    put("shoot_rate", PayloadParts.number(PayloadParts.num(form.text("shootRate"))))
                    put("wrap_rate", PayloadParts.number(PayloadParts.num(form.text("wrapRate"))))
                },
            )
            put("picture_fee", PayloadParts.number(PayloadParts.num(form.text("pictureFee"))))
            put("buyout_rate", PayloadParts.number(PayloadParts.num(form.text("buyoutRate"))))
            put("buyout_covers", form["buyoutCovers"]?.takeIf(Js::truthy) ?: JsonNull)
            put("buyout_daily_rate", PayloadParts.number(PayloadParts.num(form.text("buyoutDailyRate"))))
            put("dga_production_fee", JsonNull)
            put("travel_day_full", if (form["travelDayFull"] == null) true else form.flag("travelDayFull"))
            put("rest_day_double", form.flag("restDayDouble"))
            put("nominal_code", PayloadParts.wrapNominal(overrideText(form, "basic_labour"), ctx.coaCodes))
        }
    }

    /** `effHourly.base_rate` as a number, else the day rate over the daily hours to 2dp, else 0. */
    private fun hourlyRate(
        effHourly: Pair<JsonElement?, JsonElement?>?,
        dailyHours: JsonElement?,
        dayRate: Double?,
    ): JsonElement {
        val base = effHourly?.first
        if (base != null && base !is JsonNull) return PayloadParts.number(Js.toNumber(base))
        val hours = Js.toNumber(dailyHours)?.takeIf { Js.truthy(dailyHours) }
        return if (hours != null && dayRate != null && dayRate != 0.0) {
            PayloadParts.number(Js.toFixed(dayRate / hours, 2).toDouble())
        } else {
            JsonPrimitive(0)
        }
    }

    private fun tierHours(brd: JsonObject?, tier: String): JsonElement? =
        (brd?.get(tier) as? JsonObject)?.get("work_hrs")

    /** `pickRcEntry`: the variant whose hours match the agreement, else the one with no day type, else the first. */
    private fun pickTierEntry(tier: JsonElement?, agreementHours: JsonElement?): JsonObject? {
        if (tier !is JsonArray) return tier as? JsonObject
        if (tier.isEmpty()) return null
        val entries = tier.map { it as? JsonObject }
        if (agreementHours != null && agreementHours !is JsonNull) {
            val want = Js.toNumber(agreementHours)
            entries.firstOrNull { e -> want != null && Js.toNumber(e?.get("work_hrs")) == want }?.let { return it }
        }
        return entries.firstOrNull { e -> e != null && (e["day_type"] == null || e["day_type"] is JsonNull) }
            ?: entries.firstOrNull()
    }

    /** `mergeTier`: base rate and hours field by field, the rate card over the agreement. */
    private fun mergeTier(card: JsonObject?, agreement: JsonObject?): Pair<JsonElement?, JsonElement?>? {
        if (card == null && agreement == null) return null
        fun pick(key: String) = card?.get(key)?.takeUnless { it is JsonNull } ?: agreement?.get(key)?.takeUnless {
            it is JsonNull
        }
        return pick("base_rate") to pick("work_hrs")
    }

    private fun holidayPay(form: DealForm, ctx: PayloadContext, lines: RuleLineSet): JsonObject {
        val agreementHp = ctx.selectedUnion?.get("holiday_pay") as? JsonObject
        return buildJsonObject {
            put(
                "type",
                if (lines.hp.isFringe) {
                    agreementHp?.get("rate_type")?.takeIf(Js::truthy) ?: JsonPrimitive("percentage")
                } else {
                    JsonNull
                },
            )
            val amount = (agreementHp?.get("rate_amount") as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }
            put("amount", amount?.takeIf { lines.hp.isFringe } ?: JsonNull)
            put("treatment", lines.hp.treatment)
            put("nominal_code", PayloadParts.wrapNominal(overrideText(form, "holiday_pay"), ctx.coaCodes))
        }
    }

    private fun overrideText(form: DealForm, key: String): String =
        form.obj("nominalOverrides")?.get(key)?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty().trim()

    /** `_decorate`: each projected row as `{row_id, source, nominal_code, pay_frequency}`. */
    private fun decorate(
        form: DealForm,
        ctx: PayloadContext,
        lines: RuleLineSet,
        rows: List<JsonObject>,
        prefix: String,
    ): JsonArray {
        val nominals = form.obj("nominalOverrides")
        val overrides = if (lines.customOn) form.obj("ruleOverrides") else null
        val keys = RuleLines.rowKeys(rows)
        return JsonArray(
            rows.mapIndexed { index, row ->
                val code =
                    nominals?.get("$prefix:${keys[index]}")?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()
                val wire = if (prefix == "penalty") code else PayloadParts.wrapNominal(code, ctx.coaCodes)
                val source = applyOverride(row, overrides)
                buildJsonObject {
                    put(
                        "row_id",
                        source["id"]?.takeUnless { it is JsonNull } ?: source["label"]?.takeUnless { it is JsonNull }
                            ?: JsonNull,
                    )
                    put("source", source)
                    put("nominal_code", wire)
                    put("pay_frequency", "")
                }
            },
        )
    }

    private fun applyOverride(row: JsonObject, overrides: JsonObject?): JsonObject {
        val idKey = row["id"]?.takeUnless { it is JsonNull }?.let(Js::text) ?: return row
        val override = overrides?.get(idKey) as? JsonObject ?: return row
        val patch = RULE_OVERRIDE_FIELDS.mapNotNull { key ->
            val value = override[key] ?: return@mapNotNull null
            if (Js.isNullishOrEmpty(value)) return@mapNotNull null
            key to PayloadParts.number(Js.toNumber(value))
        }
        return if (patch.isEmpty()) row else JsonObject(row + patch)
    }

    private fun dayTypes(form: DealForm, ctx: PayloadContext): JsonArray {
        val source = if (isNonUnionId(form.text("union"))) {
            mergeNonUnionDayTypes(ctx.projectDayTypes)
        } else {
            objects(ctx.selectedUnion?.get("day_types"))
        }
        return JsonArray(
            source.map { d ->
                buildJsonObject {
                    putPresent("day_type", d["day_type"])
                    put("work_min", PayloadParts.number(Js.toNumber(d["work_min"])))
                    put(
                        "meal_break_min",
                        d["meal_break_min"]?.takeUnless { it is JsonNull }?.let { PayloadParts.number(Js.toNumber(it)) }
                            ?: JsonNull,
                    )
                    put("label", d["label"]?.takeUnless { it is JsonNull } ?: JsonNull)
                    put("note", d["note"]?.takeUnless { it is JsonNull } ?: JsonNull)
                }
            },
        )
    }

    /** The three defaults, each replaced by a project row with the same code, then the project's own. */
    fun mergeNonUnionDayTypes(project: List<JsonObject>): List<JsonObject> {
        val coded = project.filter { Js.truthy(it["day_type"]) }
        val byCode = coded.associateBy { Js.text(it["day_type"]) }
        val defaults = NON_UNION_DAY_TYPES.map { Js.text(it["day_type"]) }.toSet()
        return NON_UNION_DAY_TYPES.map { byCode[Js.text(it["day_type"])] ?: it } +
            coded.filterNot { Js.text(it["day_type"]) in defaults }
    }

    val defaultDayTypes: List<JsonObject> get() = NON_UNION_DAY_TYPES

    private fun customDays(form: DealForm): JsonArray = JsonArray(
        form.objects("customDays")
            .filter { it["name"]?.let { name -> name !is JsonNull && Js.text(name).trim().isNotEmpty() } == true }
            .map { day ->
                buildJsonObject {
                    put("name", Js.text(day["name"]).trim())
                    put("start_date", PayloadParts.toEpoch(textOf(day["start_date"])))
                    put("end_date", PayloadParts.toEpoch(textOf(day["end_date"])))
                }
            },
    )

    private fun entitlement(row: JsonObject, form: DealForm, ctx: PayloadContext): JsonObject {
        val raw = textOf(row["rate"])
        val type = PayloadParts.entitlementRateType(raw)
        val numeric = PayloadParts.entitlementRate(raw)
        val capType = row["cap_type"]?.takeUnless { it is JsonNull }
        val capAmount = if (capType?.let(Js::text) == "capped") {
            PayloadParts.num(textOf(row["cap_amount"]).filter { it.isDigit() || it == '.' })
        } else {
            null
        }
        return buildJsonObject {
            putPresent("id", row["id"])
            putPresent("name", row["name"])
            put("rate_type", type)
            put("amount", if (type == "pct") JsonNull else PayloadParts.number(numeric))
            put("rate_pct", if (type == "pct") PayloadParts.number(numeric) else JsonNull)
            put("currency", form.text("currency").ifEmpty { "GBP" })
            put("basis", row["basis"]?.takeUnless { it is JsonNull } ?: JsonNull)
            put("enable", Js.truthy(row["on"]))
            put("required", false)
            put(
                "nominal_code",
                PayloadParts.wrapNominal(row["nominal"]?.takeIf(Js::truthy)?.let(Js::text), ctx.coaCodes),
            )
            put(
                "pay_frequency",
                PayloadParts.payFrequencyOf(row["basis"]?.takeUnless { it is JsonNull }?.let(Js::text)),
            )
            put("applies_to", row["applies_to"]?.takeIf(Js::truthy) ?: JsonNull)
            put("cap_type", capType ?: JsonNull)
            put("cap_amount", PayloadParts.number(capAmount))
        }
    }

    private fun nominalCoding(form: DealForm, ctx: PayloadContext): JsonObject = buildJsonObject {
        put("primary_code", form.text("primarySetCode"))
        put("budget_line", form.text("budgetLine"))
        put("department_id", departmentId(form.text("department"), ctx.departments))
        put("hetv_class", form.text("hetvClass"))
        put("uk_spend", form.text("ukSpend").ifEmpty { "Yes" })
        put(
            "tax_credit_rate",
            PayloadParts.number(PayloadParts.num(form.text("taxCreditRate").filter { it.isDigit() || it == '.' })),
        )
        put("tax_credits", (form["taxCredits"] as? JsonArray) ?: JsonArray(emptyList()))
    }

    private fun documents(form: DealForm): JsonObject = buildJsonObject {
        put(
            "docs",
            JsonArray(
                form.objects("documents").filter { Js.truthy(it["attachment"]) }.map { doc ->
                    val att = doc["attachment"] as? JsonObject ?: JsonObject(emptyMap())
                    fun attText(key: String) = att[key]?.takeIf(Js::truthy)?.let(Js::text).orEmpty()
                    buildJsonObject {
                        putPresent("_id", doc["id"])
                        put("source", doc["source"]?.takeIf(Js::truthy) ?: JsonPrimitive("custom"))
                        put("system", Js.text(doc["source"] ?: JsonNull) == "ps")
                        put("ps_agreement_id", doc["ps_agreement_id"]?.takeIf(Js::truthy) ?: JsonNull)
                        put(
                            "title",
                            doc["title"]?.takeIf(Js::truthy)?.let(Js::text) ?: attText("title").ifEmpty {
                                attText("name")
                            },
                        )
                        put("description", doc["description"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty())
                        put(
                            "sign_required",
                            !(doc["signRequired"].let { it is JsonPrimitive && !it.isString && it.content == "false" }),
                        )
                        put("name", attText("name"))
                        put("media", attText("media"))
                        put("bucket", attText("bucket"))
                        put("region", attText("region"))
                        put("content_type", attText("content_type"))
                        put("content_subtype", attText("content_subtype"))
                        put("file_size", att["file_size"]?.takeIf(Js::truthy) ?: JsonNull)
                        put(
                            "caption",
                            attText("caption").ifEmpty {
                                doc["description"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty()
                            },
                        )
                        put("document", att)
                    }
                },
            ),
        )
        put(
            "doc_settings",
            buildJsonObject {
                put("u_sign", form.flag("docuSign"))
                put("crew_counter_sig", form.flag("crewCounterSig"))
                put("senior_sign_off", form.flag("seniorSignOff"))
                put("copy_prod_office", form.flag("copyProdOffice"))
            },
        )
    }

    private fun payroll(form: DealForm, ctx: PayloadContext): JsonObject = buildJsonObject {
        put("bureau", form.text("bureau"))
        put("first_pay_period", PayloadParts.toEpoch(form.text("firstPayPeriod")))
        put("pay_frequency", PayloadParts.payFrequencyOf(form.text("payFrequency")).ifEmpty { "weekly" })
        val defaults = ctx.payrollDefaults
        put("auto_sync", defaults?.get("auto_sync")?.takeUnless { it is JsonNull }?.let(Js::truthy) ?: true)
        put("notify_payroll", defaults?.get("notify_payroll")?.takeUnless { it is JsonNull }?.let(Js::truthy) ?: true)
        put("include_pdf", defaults?.get("include_pdf")?.takeUnless { it is JsonNull }?.let(Js::truthy) ?: false)
    }

    /** `asPassportList`: a list without blanks, a legacy single object as one, nothing as none. */
    fun passports(value: JsonElement?): List<JsonElement> = when (value) {
        is JsonArray -> value.filter(Js::truthy)
        null, JsonNull -> emptyList()
        else -> if (Js.truthy(value)) listOf(value) else emptyList()
    }

    fun departmentId(identifier: String, catalogue: DepartmentCatalogue): String? {
        if (identifier.isEmpty()) return null
        return catalogue.departments.firstOrNull { it.identifier == identifier }?.id?.takeIf { it.isNotEmpty() }
    }

    fun designationId(identifier: String, catalogue: DepartmentCatalogue): String? {
        if (identifier.isEmpty()) return null
        catalogue.departments.forEach { department ->
            department.designations.firstOrNull { it.identifier == identifier }?.id?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
        }
        return null
    }

    private fun dayType(code: String, work: Int, meal: Int, label: String) = buildJsonObject {
        put("day_type", code)
        put("work_min", work)
        put("meal_break_min", meal)
        put("label", label)
        put("note", JsonNull)
    }

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private fun textOf(value: JsonElement?): String = value?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()

    /** `{key: value}` where JSON.stringify would drop an undefined value. */
    private fun JsonObjectBuilder.putPresent(key: String, value: JsonElement?) {
        if (value != null) put(key, value)
    }
}

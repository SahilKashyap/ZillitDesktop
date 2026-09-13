package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * `fromDealMemoPayload(deal, {departments})`: a saved deal (or setup payload)
 * back into the builder's form keys — only the keys it maps, for the caller to
 * lay over [DealForm.INITIAL].
 */
@Suppress("TooManyFunctions") // One reader per payload section, in the web's order.
object DealPayloadRead {

    private val RULE_OVERRIDE_FIELDS = listOf("rate_amount", "multiplier", "min", "max", "cap_amount")
    private val TURNAROUND = Regex("turnaround", RegexOption.IGNORE_CASE)
    private val MEAL = Regex("meal", RegexOption.IGNORE_CASE)
    private val REST_DAY = Regex("rest[\\s_-]?day", RegexOption.IGNORE_CASE)
    private const val DOC_ID_CHARS = 8

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One key per line, in the web's order, for diffing.
    fun read(
        deal: JsonObject,
        departments: DepartmentCatalogue,
        random: Random = Random.Default,
    ): Map<String, JsonElement> {
        val tu = obj(deal, "territory_union")
        val cd = obj(deal, "crew_details")
        val ed = obj(cd, "emergency_details")
        val rd = obj(cd, "representative_details")
        val lo = obj(cd, "loan_out_company")
        val dl = obj(deal, "deal")
        val rt = obj(deal, "rates")
        val cc = obj(deal, "credit_conditions")
        val nc = obj(deal, "nominal_coding")
        val co = obj(deal, "compliance_onboarding")
        val ad = obj(deal, "additional_documents")
        val pay = obj(deal, "payroll")
        val phases = listOf("prep", "shoot", "wrap").map { obj(dl, it) }
        val hasPhase = phases.any { phase -> Js.truthy(phase?.get("start_date")) || Js.truthy(phase?.get("end_date")) }
        val designation = cd?.get("designation_identifier")?.takeIf(Js::truthy)?.let(Js::text)
            ?: designationIdentifier(cd?.get("designation_id"), departments).orEmpty()
        val reportsTo = cd?.get("reports_to")?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()

        return buildMap {
            put("userId", orBlank(deal["user_id"]))
            put("isExternal", JsonPrimitive(external(deal, cd)))
            put(
                "territory",
                str(tu?.get("territory_code")?.takeIf(Js::truthy)?.let(Js::text) ?: refId(tu?.get("territory"))),
            )
            put(
                "union",
                str(tu?.get("agreement_identifier")?.takeIf(Js::truthy)?.let(Js::text) ?: refId(tu?.get("union"))),
            )
            put("productionType", nullish(tu?.get("prod_type")) ?: JsonPrimitive("scripted-tv"))
            put("productionEntity", nullish(tu?.get("prod_entity")) ?: nullish(deal["company_id"]) ?: JsonPrimitive(""))
            put("pactBand", truthyOr(tu?.get("band"), tu?.get("union_band")))
            put("pactSpecialDept", JsonPrimitive(Js.truthy(tu?.get("special_dept"))))
            put("bank", PayloadParts.bankFromWire(deal["bank"]))
            put("bankAccId", nullish(deal["bank_acc_id"]) ?: JsonNull)
            put(
                "department",
                str(
                    cd?.get("department_identifier")?.takeIf(Js::truthy)?.let(Js::text)
                        ?: departmentIdentifier(cd?.get("department_id"), departments).orEmpty(),
                ),
            )
            put("designation", str(designation))
            put(
                "jobTitle",
                str(if (Js.truthy(cd?.get("custom_designation"))) DealForm.CUSTOM_JOB_TITLE else designation),
            )
            put("customJobTitle", orBlank(cd?.get("custom_designation")))
            put("reportsTo", str(reportsTo))
            put(
                "reportsToType",
                str(
                    when {
                        reportsTo == "N/A" -> "N/A"
                        reportsTo == "HOD" || reportsTo.isEmpty() -> "HOD"
                        else -> "Other"
                    },
                ),
            )
            put("callSheetTier", nullish(cd?.get("call_sheet_tier")) ?: JsonPrimitive("HOD"))
            put("crewType", orBlank(cd?.get("crew_type")))
            put(
                "fullLegalName",
                nullish(cd?.get("full_legal_name")) ?: nullish(cd?.get("crew_name")) ?: JsonPrimitive(""),
            )
            put("crewName", orBlank(cd?.get("crew_name")))
            put("preferredName", orBlank(cd?.get("preferred_name")))
            put("screenCreditDesignation", orBlank(cd?.get("screen_credit_designation")))
            put("passportAttachment", nullish(cd?.get("passport_attachment")) ?: JsonNull)
            put("dob", str(PayloadParts.fromEpoch(cd?.get("dob"))))
            put("niNumber", orBlank(cd?.get("insurance_no")))
            put("uk", ukFromWire(cd?.get("uk") as? JsonObject))
            put("taxCode", orBlank(cd?.get("tax_code")))
            put("rightToWork", orBlank(cd?.get("right_to_work")))
            put("homeAddress", PayloadParts.normalizeAddress(cd?.get("home_address")))
            put("emergencyContact", orBlank(cd?.get("emergency_contact")))
            put("emergencyContactName", truthyOr(cd?.get("emergency_contact_name"), ed?.get("name")))
            put("emergencyContactNumber", truthyOr(cd?.get("emergency_contact_number"), ed?.get("phone_number")))
            put("emergencyCountryCode", orBlank(ed?.get("country_code")))
            put("emergencyEmail", orBlank(ed?.get("email")))
            put("emergencyAddress", PayloadParts.normalizeAddress(ed?.get("address")))
            put("representativeName", orBlank(rd?.get("name")))
            put("representativeCountryCode", orBlank(rd?.get("country_code")))
            put("representativePhone", orBlank(rd?.get("phone_number")))
            put("representativeEmail", orBlank(rd?.get("email")))
            put("representativeAddress", PayloadParts.normalizeAddress(rd?.get("address")))
            put("email", orBlank(cd?.get("email")))
            put("mobile", orBlank(cd?.get("mobile")))
            put("gender", orBlank(cd?.get("gender")))
            put("unit", orBlank(cd?.get("unit")))
            put("crewMandatoryFields", (cd?.get("mandatory_fields") as? JsonArray) ?: JsonArray(emptyList()))
            put("employmentStatus", orBlank(cd?.get("emp_status")))
            put("loanOutCompanyName", orBlank(lo?.get("name")))
            put("loanOutCountryCode", orBlank(lo?.get("country_code")))
            put("loanOutPhoneNumber", orBlank(lo?.get("phone_number")))
            put("loanOutEmail", orBlank(lo?.get("email")))
            put("loanOutCompanyAddress", PayloadParts.normalizeAddress(lo?.get("address")))
            put("agencyId", orBlank(cd?.get("agency_id")))
            put("agencyName", orBlank(cd?.get("agency_name")))
            put(
                "onboardChecked",
                buildJsonObject {
                    objects(co?.get("onboard")).filter { Js.truthy(it["done"]) }.forEach {
                        put(Js.text(it["id"] ?: JsonNull), true)
                    }
                },
            )
            put("dealType", orBlank(dl?.get("type")))
            put("longFormContract", nullish(deal["long_form_contract"]) ?: JsonNull)
            put("dealStart", str(PayloadParts.fromEpoch(dl?.get("start_date"))))
            put("dealEnd", str(PayloadParts.fromEpoch(dl?.get("end_date"))))
            put("completionDue", str(PayloadParts.fromEpoch(dl?.get("deal_completion_due"))))
            put("dealMemoDate", str(PayloadParts.fromEpoch(dl?.get("deal_memo_date"))))
            put("billingBasis", nullish(dl?.get("billing_basis")) ?: JsonPrimitive("week"))
            putAll(
                PayloadParts.noticeFromStored(dl?.get("notice_period")?.takeUnless { it is JsonNull }?.let(Js::text)),
            )
            putAll(
                PayloadParts.reminderFromStored(
                    dl?.get("notice_reminder")?.takeUnless { it is JsonNull }?.let(Js::text),
                ),
            )
            put("additionalNotes", orBlank(dl?.get("additional_notes")))
            put("schedOn", JsonPrimitive(hasPhase))
            put(
                "workLocationType",
                nullish(cc?.get("work_location")) ?: nullish(dl?.get("work_location")) ?: JsonPrimitive("studio"),
            )
            put(
                "travelZone",
                nullish(cc?.get("travel_zone")) ?: nullish(dl?.get("travel_zone")) ?: JsonPrimitive("30mile"),
            )
            put(
                "distantLocation",
                JsonPrimitive(Js.truthy(nullish(cc?.get("distant_loc_applied")) ?: dl?.get("distant_loc_applied"))),
            )
            put(
                "customConditions",
                (cc?.get("custom_conditions") as? JsonArray) ?: (dl?.get("custom_conditions") as? JsonArray)
                    ?: JsonArray(emptyList()),
            )
            listOf("Prep" to phases[0], "Shoot" to phases[1], "Wrap" to phases[2]).forEach { (name, phase) ->
                put("sched${name}Start", str(PayloadParts.fromEpoch(phase?.get("start_date"))))
                put("sched${name}End", str(PayloadParts.fromEpoch(phase?.get("end_date"))))
            }
            put(
                "customDays",
                JsonArray(
                    ((deal["custom_days"] as? JsonArray).orEmpty()).map { row ->
                        val day = row as? JsonObject
                        buildJsonObject {
                            put("name", nullish(day?.get("name")) ?: JsonPrimitive(""))
                            put("start_date", PayloadParts.fromEpoch(day?.get("start_date")))
                            put("end_date", PayloadParts.fromEpoch(day?.get("end_date")))
                        }
                    },
                ),
            )
            putAll(rates(deal, rt))
            put("allowances", JsonArray(objects(deal["allowances"]).map(::entitlement)))
            put("rentals", JsonArray(objects(deal["rentals"]).map(::entitlement)))
            val penalties = objects(deal["penalties"])
            put("penaltyTurnaround", JsonPrimitive(penalties.any { TURNAROUND.containsMatchIn(penaltyKey(it)) }))
            put("penaltyMeal", JsonPrimitive(penalties.any { MEAL.containsMatchIn(penaltyKey(it)) }))
            put("penaltyRestDay", JsonPrimitive(penalties.any { REST_DAY.containsMatchIn(penaltyKey(it)) }))
            put("primarySetCode", nullish(nc?.get("primary_code")) ?: JsonPrimitive("1400"))
            put("budgetLine", orBlank(nc?.get("budget_line")))
            put("nominalOverrides", nominalOverrides(deal))
            put("rulesCustomized", JsonNull)
            put("ruleOverrides", ruleOverrides(deal))
            put("ruleCustomRows", ruleCustomRows(deal))
            put("ruleRowEdits", ruleRowEdits(deal))
            put("ruleRowRemovals", JsonArray(emptyList()))
            put("hetvClass", orBlank(nc?.get("hetv_class")))
            put("ukSpend", nullish(nc?.get("uk_spend")) ?: JsonPrimitive("Yes"))
            put(
                "taxCreditRate",
                str(nullish(nc?.get("tax_credit_rate"))?.let { "${Js.text(it)}%" } ?: "25%"),
            )
            put("taxCredits", (nc?.get("tax_credits") as? JsonArray) ?: JsonArray(emptyList()))
            put("documents", JsonArray(objects(ad?.get("docs")).mapNotNull { document(it, random) }))
            val settings = obj(ad, "doc_settings")
            put("docuSign", JsonPrimitive(Js.truthy(settings?.get("u_sign"))))
            put("crewCounterSig", JsonPrimitive(Js.truthy(settings?.get("crew_counter_sig"))))
            put("seniorSignOff", JsonPrimitive(Js.truthy(settings?.get("senior_sign_off"))))
            put("copyProdOffice", JsonPrimitive(Js.truthy(settings?.get("copy_prod_office"))))
            put("bureau", orBlank(pay?.get("bureau")))
            put("firstPayPeriod", str(PayloadParts.fromEpoch(pay?.get("first_pay_period"))))
            put("payFrequency", nullish(pay?.get("pay_frequency")) ?: JsonPrimitive("weekly"))
        }
    }

    private fun rates(deal: JsonObject, rt: JsonObject?): Map<String, JsonElement> {
        fun rate(value: JsonElement?) = str(nullish(value)?.let(Js::text).orEmpty())
        val daily = obj(rt, "daily")
        val weekly = obj(rt, "weekly")
        val phase = obj(rt, "phase_rates")
        val treatment = nullish(obj(deal, "holiday_pay")?.get("treatment")) ?: rt?.get("hp_treatment")
        return mapOf(
            "currency" to (nullish(rt?.get("contract_currency")) ?: JsonPrimitive("GBP")),
            "paymentCurrency" to (nullish(rt?.get("pay_currency")) ?: JsonPrimitive("GBP")),
            "dayRate" to rate(daily?.get("rate")),
            "weeklyRate" to rate(weekly?.get("rate")),
            "hpMode" to str(if (treatment?.takeUnless { it is JsonNull }?.let(Js::text) == "excl") "excl" else "incl"),
            "phaseRatesOn" to JsonPrimitive(Js.truthy(phase?.get("on"))),
            "prepRate" to rate(phase?.get("prep_rate")),
            "shootRate" to rate(phase?.get("shoot_rate")),
            "wrapRate" to rate(phase?.get("wrap_rate")),
            "pictureFee" to rate(rt?.get("picture_fee")),
            "buyoutRate" to rate(rt?.get("buyout_rate")),
            "buyoutCovers" to (nullish(rt?.get("buyout_covers")) ?: JsonPrimitive("")),
            "buyoutDailyRate" to rate(rt?.get("buyout_daily_rate")),
            "buyoutRateMode" to str(buyoutMode(rt?.get("buyout_rate"), rt?.get("buyout_daily_rate"))),
            "basicWorkingHoursPerDay" to rate(daily?.get("hrs")),
            "travelDayFull" to JsonPrimitive(
                !(rt?.get("travel_day_full").let { it is JsonPrimitive && !it.isString && it.content == "false" }),
            ),
            "restDayDouble" to JsonPrimitive(Js.truthy(rt?.get("rest_day_double"))),
        )
    }

    /** `buyoutModeFromRates`: both, daily, or weekly — the mode a new buy-out starts in. */
    fun buyoutMode(weekly: JsonElement?, daily: JsonElement?): String {
        fun has(value: JsonElement?) = value != null && value !is JsonNull && Js.text(value).trim().isNotEmpty()
        return when {
            has(weekly) && has(daily) -> "both"
            has(daily) -> "daily"
            else -> "weekly"
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun entitlement(row: JsonObject): JsonObject = buildJsonObject {
        val rate = when (Js.text(row["rate_type"] ?: JsonNull)) {
            "pct" -> "${orEmptyText(row["rate_pct"])}%"
            "per_mile" -> "${orEmptyText(row["amount"])}p/mile"
            "per_km" -> "${orEmptyText(row["amount"])}p/km"
            else -> nullish(row["amount"])?.let(Js::text).orEmpty()
        }
        put("id", nullish(row["id"]) ?: nullish(row["_id"]) ?: JsonPrimitive(""))
        put("name", nullish(row["name"]) ?: JsonPrimitive(""))
        put("on", !(row["enable"].let { it is JsonPrimitive && !it.isString && it.content == "false" }))
        put("rate", rate)
        put("basis", nullish(row["basis"]) ?: JsonPrimitive(""))
        put("applies_to", nullish(row["applies_to"]) ?: JsonPrimitive(""))
        put("cap_type", nullish(row["cap_type"]) ?: JsonPrimitive("uncapped"))
        put("cap_amount", nullish(row["cap_amount"])?.let(Js::text).orEmpty())
        put("nominal", nullish(row["nominal_code"]) ?: JsonPrimitive(""))
    }

    @Suppress("CyclomaticComplexMethod")
    private fun document(doc: JsonObject, random: Random): JsonObject? {
        val nested = (doc["document"]?.takeIf(Js::truthy) as? JsonObject)
            ?: (doc["attachment"]?.takeIf(Js::truthy) as? JsonObject)
        fun pick(key: String): String =
            doc[key]?.takeIf(Js::truthy)?.let(Js::text) ?: nested?.get(key)?.takeIf(Js::truthy)?.let(Js::text).orEmpty()
        val media = pick("media")
        val bucket = pick("bucket")
        val region = pick("region")
        if (media.isEmpty() || bucket.isEmpty() || region.isEmpty()) return null
        val attachment = buildJsonObject {
            put("name", pick("name"))
            put("media", media)
            put("bucket", bucket)
            put("region", region)
            put("content_type", pick("content_type"))
            put("content_subtype", pick("content_subtype"))
            put(
                "file_size",
                doc["file_size"]?.takeIf(Js::truthy) ?: nested?.get("file_size")?.takeIf(Js::truthy) ?: JsonNull,
            )
            put("caption", pick("caption"))
            put("title", pick("title"))
        }
        val rowId = doc["_id"]?.takeIf(Js::truthy)?.let(Js::text) ?: doc["id"]?.takeIf(Js::truthy)?.let(Js::text)
            ?: "doc-${randomBase36(random)}"
        val system = Js.text(doc["source"] ?: JsonNull) == "ps" ||
            (doc["system"].let { it is JsonPrimitive && !it.isString && it.content == "true" }) ||
            Js.truthy(doc["ps_agreement_id"])
        return buildJsonObject {
            put("id", rowId)
            put(
                "source",
                if (system) JsonPrimitive("ps") else doc["source"]?.takeIf(Js::truthy) ?: JsonPrimitive("custom"),
            )
            put(
                "ps_agreement_id",
                doc["ps_agreement_id"]?.takeIf(Js::truthy) ?: if (system) JsonPrimitive(rowId) else JsonNull,
            )
            put(
                "title",
                doc["title"]?.takeIf(Js::truthy)?.let(Js::text)
                    ?: attachment["title"]?.takeIf(Js::truthy)?.let(Js::text)
                    ?: Js.text(attachment["name"] ?: JsonNull),
            )
            put("description", doc["description"]?.takeIf(Js::truthy)?.let(Js::text).orEmpty())
            put(
                "signRequired",
                !(doc["sign_required"].let { it is JsonPrimitive && !it.isString && it.content == "false" }),
            )
            put("attachment", attachment)
        }
    }

    private fun randomBase36(random: Random): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..DOC_ID_CHARS).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** `ukFromWire`: the empty block under what was stored; the pension wish never blank. */
    fun ukFromWire(block: JsonObject?): JsonObject {
        val merged = DealForm.EMPTY_UK + block.orEmpty()
        val pension = block?.get("pension_status")?.takeIf(Js::truthy) ?: JsonPrimitive("opt_in")
        return JsonObject(merged + ("pension_status" to pension))
    }

    private fun nominalOverrides(deal: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        obj(deal, "rates")?.get("nominal_code")?.takeIf(Js::truthy)?.let { out["basic_labour"] = it }
        obj(deal, "holiday_pay")?.get("nominal_code")?.takeIf(Js::truthy)?.let { out["holiday_pay"] = it }
        listOf(
            "overtimes" to "ot", "premiums" to "prem", "turnarounds" to "turn",
            "fringes" to "fringe", "extra_fees" to "extra", "penalties" to "penalty",
        ).forEach { (list, prefix) -> out += nominalEntries(objects(deal[list]), prefix) }
        return JsonObject(out)
    }

    /** `ruleNominalEntries`: occurrence keys, plus the bare `row_id` / `source.id` spellings for a first occurrence. */
    fun nominalEntries(rows: List<JsonObject>, prefix: String): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val keys = RuleLines.rowKeys(rows.map { JsonObject(mapOf("id" to JsonPrimitive(RuleLines.rowId(it)))) })
        rows.forEachIndexed { index, row ->
            val code = row["nominal_code"]?.takeIf(Js::truthy) ?: return@forEachIndexed
            out["$prefix:${keys[index]}"] = code
            if (keys[index] == RuleLines.rowId(row)) {
                listOf(row["row_id"], (row["source"] as? JsonObject)?.get("id")).forEach { id ->
                    if (Js.truthy(id)) out["$prefix:${Js.text(id)}"] = code
                }
            }
        }
        return out
    }

    private val RULE_LISTS = listOf("overtimes", "premiums", "turnarounds", "penalties")

    private fun ruleOverrides(deal: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        RULE_LISTS.forEach { list ->
            objects(deal[list]).forEach { row ->
                val source = row["source"] as? JsonObject ?: return@forEach
                val id = idOf(row) ?: return@forEach
                if (id.startsWith("custom-")) return@forEach
                val fields = RULE_OVERRIDE_FIELDS.mapNotNull { key ->
                    source[key]?.takeUnless { it is JsonNull }?.let { key to it }
                }
                if (fields.isNotEmpty()) out[id] = JsonObject(fields.toMap())
            }
        }
        return JsonObject(out)
    }

    private fun ruleRowEdits(deal: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        RULE_LISTS.forEach { list ->
            val rows = objects(deal[list])
            val keys = RuleLines.rowKeys(
                rows.map { row -> JsonObject(listOfNotNull(idOf(row)?.let { "id" to JsonPrimitive(it) }).toMap()) },
            )
            rows.forEachIndexed { index, row ->
                val source = row["source"] as? JsonObject ?: return@forEachIndexed
                val id = idOf(row) ?: return@forEachIndexed
                if (id.startsWith("custom-")) return@forEachIndexed
                out[keys[index]] = source
            }
        }
        return JsonObject(out)
    }

    private fun ruleCustomRows(deal: JsonObject): JsonArray = JsonArray(
        listOf(
            "overtimes" to "Overtime",
            "premiums" to "Premium",
            "turnarounds" to "Turnaround",
            "penalties" to "Penalty",
        )
            .flatMap { (list, group) ->
                objects(deal[list]).mapNotNull { row ->
                    val source = row["source"] as? JsonObject ?: return@mapNotNull null
                    val id = idOf(row) ?: return@mapNotNull null
                    if (!id.startsWith("custom-")) return@mapNotNull null
                    buildJsonObject {
                        put("group", group)
                        put("row", source)
                    }
                }
            },
    )

    /** `src?.id ?? r?.row_id`, when truthy. */
    private fun idOf(row: JsonObject): String? {
        val source = row["source"] as? JsonObject
        val id = source?.get("id")?.takeUnless { it is JsonNull } ?: row["row_id"]?.takeUnless { it is JsonNull }
        return id?.takeIf(Js::truthy)?.let(Js::text)
    }

    private fun penaltyKey(row: JsonObject): String {
        val source = row["source"] as? JsonObject
        return (nullish(source?.get("id")) ?: nullish(source?.get("label")) ?: nullish(row["row_id"]))
            ?.let(Js::text)
            .orEmpty()
    }

    private fun external(deal: JsonObject, cd: JsonObject?): Boolean {
        val flag = nullish(cd?.get("is_external")) ?: nullish(deal["is_external"])
        return if (flag != null) Js.truthy(flag) else !Js.truthy(deal["user_id"])
    }

    private fun refId(ref: JsonElement?): String = when {
        ref == null || !Js.truthy(ref) -> ""
        ref is JsonPrimitive -> if (ref.isString) ref.content else Js.text(ref)
        ref is JsonObject ->
            (nullish(ref["_identifier"]) ?: nullish(ref["identifier"]) ?: nullish(ref["id"]))?.let(Js::text)
            ?: nullish(ref["_id"])?.let(Js::text).orEmpty()
        else -> ""
    }

    private fun departmentIdentifier(id: JsonElement?, catalogue: DepartmentCatalogue): String? {
        if (!Js.truthy(id)) return null
        val key = Js.text(id)
        return catalogue.departments.firstOrNull { it.id == key }?.identifier
    }

    private fun designationIdentifier(id: JsonElement?, catalogue: DepartmentCatalogue): String? {
        if (!Js.truthy(id)) return null
        val key = Js.text(id)
        catalogue.departments.forEach { department ->
            department.designations.firstOrNull { it.id == key }?.identifier?.let { return it }
        }
        return null
    }

    private fun obj(json: JsonObject?, key: String): JsonObject? = json?.get(key) as? JsonObject

    private fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private fun nullish(value: JsonElement?): JsonElement? = value?.takeUnless { it is JsonNull }

    /** `x ?? ''`. */
    private fun orBlank(value: JsonElement?): JsonElement = nullish(value) ?: JsonPrimitive("")

    /** `a || b || ''`. */
    private fun truthyOr(first: JsonElement?, second: JsonElement?): JsonElement =
        first?.takeIf(Js::truthy) ?: second?.takeIf(Js::truthy) ?: JsonPrimitive("")

    private fun orEmptyText(value: JsonElement?): String = nullish(value)?.let(Js::text).orEmpty()

    private fun str(value: String) = JsonPrimitive(value)
}

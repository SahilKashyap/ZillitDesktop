package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

/**
 * The deal-memo create/update wire, in the exact shape the web's payload
 * builder emits (`accountHub/components/deal-memo/utils/toDealMemoPayload.js`).
 *
 * The backend spreads the request body straight into its Mongo document, so a
 * body in any other shape "saves" a document every read path ignores — the
 * first desktop create did exactly that (flat `weekly_rate` keys, success
 * answer, empty listings). Every key emitted here exists in
 * `toDealMemoPayload`'s output; fields the desktop form does not collect carry
 * the web's untouched-wizard defaults (`DMCreatePage.jsx` `INIT_FORM`,
 * lines 140-390).
 */

private const val CLIENT_ID_BYTES = 12
private const val HEX_RADIX = 16
private const val HEX_CHARS_PER_BYTE = 2
private const val SPECIAL_DEPT_MULTIPLIER = 1.0

/** `nominal_coding.tax_credit_rate` for an untouched wizard — `INIT_FORM.taxCreditRate` "25%". */
private const val UNTOUCHED_TAX_CREDIT_RATE = 25

/**
 * A client-minted deal id, sent as `_id` on the CREATE so a retried create
 * collides on Mongo's unique index instead of writing a second deal.
 *
 * MUST be ObjectId-castable: 24 hex chars, exactly as the web mints it from a
 * v4 uuid (`toDealMemoPayload.js:19` `newClientDealId`) — a 36-char uuid dies
 * in the ObjectId cast as a 422 on every save. Twelve random bytes keep the
 * web's 96 bits of randomness.
 */
internal fun newClientDealId(): String = Random.nextBytes(CLIENT_ID_BYTES)
    .joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(HEX_CHARS_PER_BYTE, '0') }

/**
 * The POST `/deals` body: the shared payload plus the client-minted `_id`
 * (`DMCreatePage.jsx:1462`) and `status: "draft"` (`toDealMemoPayload.js:740`,
 * re-asserted at `DMCreatePage.jsx:1514`). `notify` rides the query string,
 * never the body (`deal-memo.js:129-137`).
 */
internal fun NewDeal.createBody(id: String): JsonObject = JsonObject(
    mapOf("_id" to JsonPrimitive(id)) + updateBody() + mapOf("status" to JsonPrimitive("draft")),
)

/**
 * The PATCH `/deals/:id` body: the same full snapshot the web autosave sends
 * (`useDealAutosave.js:23-27` — every payload is a full snapshot, not a
 * delta), minus `status` (deleted before every PATCH so a save cannot clobber
 * an `awaiting_approval` deal back to draft — `DMCreatePage.jsx:1503-1506`)
 * and minus `_id` (stamped on CREATE payloads only — `DMCreatePage.jsx:1462`).
 */
internal fun NewDeal.updateBody(): JsonObject = buildJsonObject {
    // Top level — toDealMemoPayload.js:723-740. department_id/designation_id
    // are the crew member's Zillit-master ids read off the user directory
    // record; the desktop has no directory in hand, which is the web's own
    // null case (`crewUser?.department_id || null`).
    put("user_id", JsonPrimitive(userId))
    put("is_external", JsonPrimitive(false))
    put("company_id", JsonNull)
    put("department_id", JsonNull)
    put("designation_id", JsonNull)
    put("deal_reference", JsonNull)
    put("territory_union", territoryUnion())
    put("crew_details", crewDetails())
    put("deal", dealTerms())
    put("credit_conditions", creditConditions())
    put("rates", ratesBody())
    put("holiday_pay", holidayPay())
    // Agreement-snapshot tables — empty until an agreement doc is loaded,
    // exactly as the web sends with no `selectedUnion` (toDealMemoPayload.js:986-991).
    put("rules_customized", JsonPrimitive(false))
    put("overtimes", EMPTY_ROWS)
    put("premiums", EMPTY_ROWS)
    put("turnarounds", EMPTY_ROWS)
    put("fringes", EMPTY_ROWS)
    put("extra_fees", EMPTY_ROWS)
    put("day_types", EMPTY_ROWS)
    put("custom_days", EMPTY_ROWS)
    put("allowances", entitlementRows(rates.vehicleAllowance, "vehicle_allowance", "Vehicle Allowance", "non_shoot"))
    put("rentals", entitlementRows(rates.boxRental, "box_rental", "Box Rental", "full_production"))
    put("penalties", EMPTY_ROWS)
    put("nominal_coding", nominalCoding())
    put("compliance_onboarding", complianceOnboarding())
    put("additional_documents", additionalDocuments())
    put("long_form_contract", JsonNull)
    put("payroll", payrollBody())
    put("bank", bankBody())
}

private val EMPTY_ROWS = JsonArray(emptyList())

/**
 * Step 1 — territory / agreement / union (`toDealMemoPayload.js:749-780`).
 *
 * The desktop's union picker supplies the top-level union identifier and the
 * agreement picker the agreement identifier; `branch_identifier` falls back to
 * the union identifier exactly as the web does without a resolved rate row
 * (`toDealMemoPayload.js:763-765`). Everything else is the untouched-step
 * default: empty strings, `special_dept` false, and the legacy ObjectId refs
 * null (`selectedUnion?._id ?? null` with nothing loaded).
 */
private fun NewDeal.territoryUnion(): JsonObject = buildJsonObject {
    put("prod_entity", JsonPrimitive(""))
    put("prod_type", JsonPrimitive(""))
    put("territory_code", JsonPrimitive(""))
    put("agreement_identifier", JsonPrimitive(agreementId.orEmpty()))
    put("union_identifier", JsonPrimitive(unionId.orEmpty()))
    put("branch_identifier", JsonPrimitive(unionId.orEmpty()))
    put("band", JsonPrimitive(""))
    put("special_dept", JsonPrimitive(false))
    // Empty mechanics when special_dept is false — toDealMemoPayload.js:332-339.
    put(
        "special_dept_extra_hours",
        buildJsonObject {
            put("hrs", JsonPrimitive(0))
            put("multiplier", JsonPrimitive(SPECIAL_DEPT_MULTIPLIER))
            put("basis", JsonNull)
            put("note", JsonNull)
        },
    )
    put("territory", JsonNull)
    put("union", JsonNull)
    put("union_band", JsonPrimitive(""))
}

/**
 * Step 2 — crew details (`toDealMemoPayload.js:783-869`). The desktop knows
 * the name, the role and the department; the rest is the web's untouched-step
 * shape: `reports_to` "HOD" (`toDealMemoPayload.js:808-813` default branch),
 * `call_sheet_tier` "HOD" (`INIT_FORM.callSheetTier`), `experience_yrs`
 * always null (`toDealMemoPayload.js:820-824`), and blank structured contacts.
 */
@Suppress("LongMethod") // One wire object, one line per key — splitting hides the shape.
private fun NewDeal.crewDetails(): JsonObject = buildJsonObject {
    put("crew_name", JsonPrimitive(crewName))
    put("full_legal_name", JsonNull)
    put("preferred_name", JsonNull)
    put("screen_credit_designation", JsonNull)
    put("passport_attachment", JsonNull)
    // The Zillit-master _ids resolve through the departments master the web
    // loads (`toDealMemoPayload.js:43-55`); the identifier columns are the
    // authoritative ones and round-trip on their own (js:796-800).
    put("department_id", JsonNull)
    put("designation_id", JsonNull)
    put("department_identifier", JsonPrimitive(departmentId))
    put("designation_identifier", JsonPrimitive(designation))
    put("custom_designation", JsonNull)
    put("reports_to", JsonPrimitive("HOD"))
    put("call_sheet_tier", JsonPrimitive("HOD"))
    put("crew_type", JsonNull)
    put("experience_yrs", JsonNull)
    put("emp_status", JsonPrimitive(""))
    put("agency_id", JsonNull)
    put("agency_name", JsonNull)
    put("dob", JsonNull)
    put("insurance_no", JsonNull)
    put("tax_code", JsonNull)
    put("right_to_work", JsonNull)
    put("home_address", JsonNull)
    put("emergency_contact", JsonNull)
    put("emergency_contact_name", JsonNull)
    put("emergency_contact_number", JsonNull)
    put("emergency_details", blankContact())
    put("representative_details", blankContact())
    put("email", JsonNull)
    put("mobile", JsonNull)
    put("gender", JsonNull)
    put("unit", JsonNull)
    put("mandatory_fields", EMPTY_ROWS)
}

/** Structured contact, blank — `toDealMemoPayload.js:847-860`. */
private fun blankContact(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(""))
    put("country_code", JsonPrimitive(""))
    put("phone_number", JsonPrimitive(""))
    put("email", JsonPrimitive(""))
    put("address", JsonPrimitive(""))
}

/**
 * Step 4 — deal structure (`toDealMemoPayload.js:878-904`). Dates are epoch
 * millis or null (`toEpoch`, js:350-354); the desktop's notes land on
 * `additional_notes` (js:903); `type` defaults to "weekly" and
 * `billing_basis` to "week" exactly as an untouched wizard sends.
 */
private fun NewDeal.dealTerms(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("weekly"))
    put("start_date", JsonPrimitive(startDate))
    put("end_date", JsonPrimitive(endDate))
    put("deal_completion_due", JsonNull)
    put("deal_memo_date", JsonNull)
    put("billing_basis", JsonPrimitive("week"))
    put("prep", emptyPhase())
    put("shoot", emptyPhase())
    put("wrap", emptyPhase())
    put("notice_period", JsonPrimitive(""))
    put("notice_reminder", JsonPrimitive(""))
    put("additional_notes", JsonPrimitive(notes?.trim().orEmpty()))
}

/** A phase range nobody scheduled — `toDealMemoPayload.js:891-896` null branch. */
private fun emptyPhase(): JsonObject = buildJsonObject {
    put("start_date", JsonNull)
    put("end_date", JsonNull)
}

/** Step 6 — conditions, untouched (`toDealMemoPayload.js:909-917`; `INIT_FORM` studio/30mile). */
private fun creditConditions(): JsonObject = buildJsonObject {
    put("work_location", JsonPrimitive("studio"))
    put("travel_zone", JsonPrimitive("30mile"))
    put("distant_loc_applied", JsonPrimitive(false))
    put("custom_conditions", EMPTY_ROWS)
}

/**
 * Step 5 — rates (`toDealMemoPayload.js:930-968`). The desktop's weekly /
 * daily / hourly rates and standard hours land where every reader looks:
 * `weekly.rate` (`DMDealPreviewPage.jsx:1522`), `daily.rate`
 * (`DMDealsPage.jsx:493`), `hr_rate` (`DMDealPreviewPage.jsx:2954`) and
 * `daily.hrs` (`fromDealMemoPayload` js:1386). The nominal code is the
 * basic-labour line's ledger code (`toDealMemoPayload.js:963-967`).
 */
private fun NewDeal.ratesBody(): JsonObject = buildJsonObject {
    val contractCurrency = currency?.takeIf { it.isNotBlank() } ?: "GBP"
    put("contract_currency", JsonPrimitive(contractCurrency))
    put("pay_currency", JsonPrimitive(contractCurrency))
    put(
        "daily",
        buildJsonObject {
            put("rate", JsonPrimitive(rates.dailyRate))
            put("hrs", JsonPrimitive(rates.standardHours))
        },
    )
    put(
        "weekly",
        buildJsonObject {
            put("rate", JsonPrimitive(rates.weeklyRate))
            put("hrs", JsonPrimitive(0))
        },
    )
    put("hr_rate", JsonPrimitive(rates.hourlyRate))
    put(
        "phase_rates",
        buildJsonObject {
            put("on", JsonPrimitive(false))
            put("prep_rate", JsonNull)
            put("shoot_rate", JsonNull)
            put("wrap_rate", JsonNull)
        },
    )
    put("picture_fee", JsonNull)
    put("buyout_rate", JsonNull)
    put("buyout_covers", JsonNull)
    put("buyout_daily_rate", JsonNull)
    put("dga_production_fee", JsonNull)
    put("travel_day_full", JsonPrimitive(true))
    put("rest_day_double", JsonPrimitive(false))
    put("nominal_code", JsonPrimitive(nominalCode?.trim().orEmpty()))
}

/** Holiday pay with no fringe package loaded — all null (`toDealMemoPayload.js:600-617, 975-980`). */
private fun holidayPay(): JsonObject = buildJsonObject {
    put("type", JsonNull)
    put("amount", JsonNull)
    put("treatment", JsonNull)
    put("nominal_code", JsonPrimitive(""))
}

/**
 * One deal-authored entitlement row, in the web's `mapEntitlement` shape
 * (`toDealMemoPayload.js:390-421`): flat amount, basis "week", cadence
 * "weekly" (`BASIS_TO_FREQ`, js:361-373), `applies_to` per surface —
 * "full_production" for rentals, "non_shoot" for allowances (js:417).
 * Empty when the form left the amount at zero, matching the web's
 * enabled-rows-only filter (js:1037-1038).
 */
private fun NewDeal.entitlementRows(
    amount: Double,
    id: String,
    name: String,
    appliesTo: String,
): JsonArray = buildJsonArray {
    if (amount <= 0) return@buildJsonArray
    add(
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("rate_type", JsonPrimitive("flat"))
            put("amount", JsonPrimitive(amount))
            put("rate_pct", JsonNull)
            put("currency", JsonPrimitive(currency?.takeIf { it.isNotBlank() } ?: "GBP"))
            put("basis", JsonPrimitive("week"))
            put("enable", JsonPrimitive(true))
            put("required", JsonPrimitive(false))
            put("nominal_code", JsonPrimitive(""))
            put("pay_frequency", JsonPrimitive("weekly"))
            put("applies_to", JsonPrimitive(appliesTo))
            put("cap_type", JsonNull)
            put("cap_amount", JsonNull)
        },
    )
}

/** Step 7 metadata, untouched (`toDealMemoPayload.js:1047-1058`; `INIT_FORM` 1400/G/Yes/25%). */
private fun nominalCoding(): JsonObject = buildJsonObject {
    put("primary_code", JsonPrimitive("1400"))
    put("budget_line", JsonPrimitive("Below-the-Line Labour"))
    put("department_id", JsonNull)
    put("hetv_class", JsonPrimitive("G"))
    put("uk_spend", JsonPrimitive("Yes"))
    put("tax_credit_rate", JsonPrimitive(UNTOUCHED_TAX_CREDIT_RATE))
    put("tax_credits", EMPTY_ROWS)
}

/** The static onboarding checklist, nothing done (`toDealMemoPayload.js:629-643, 1061-1064`). */
private fun complianceOnboarding(): JsonObject = buildJsonObject {
    put("checks", EMPTY_ROWS)
    put(
        "onboard",
        buildJsonArray {
            add(onboardRow("starter", "P45 or Starter Checklist", required = true))
            add(onboardRow("rtw-doc", "Right to Work documentation", required = true))
            add(onboardRow("bank", "Bank account details (BACS)", required = true))
            add(onboardRow("sds", "IR35 Status Determination Statement", required = true))
            add(onboardRow("nda", "NDA signed and returned", required = false))
            add(onboardRow("hs", "H&S induction acknowledged", required = false))
            add(onboardRow("covid", "COVID-19 policy acknowledged", required = false))
            add(onboardRow("privacy", "Privacy notice acknowledged", required = false))
        },
    )
}

private fun onboardRow(id: String, label: String, required: Boolean): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("label", JsonPrimitive(label))
    put("required", JsonPrimitive(required))
    put("done", JsonPrimitive(false))
}

/** No documents, default signing toggles (`toDealMemoPayload.js:1067-1075`; `INIT_FORM` docuSign…). */
private fun additionalDocuments(): JsonObject = buildJsonObject {
    put("docs", EMPTY_ROWS)
    put(
        "doc_settings",
        buildJsonObject {
            put("u_sign", JsonPrimitive(true))
            put("crew_counter_sig", JsonPrimitive(true))
            put("senior_sign_off", JsonPrimitive(false))
            put("copy_prod_office", JsonPrimitive(true))
        },
    )
}

/**
 * Step 9 — payroll defaults (`toDealMemoPayload.js:1083-1093`). The three
 * sync toggles carry the server-seed fallbacks the web sends without a
 * project override; `first_pay_period` is null rather than `INIT_FORM`'s
 * hard-coded sample date — an unset date is the web's own empty-input value.
 */
private fun payrollBody(): JsonObject = buildJsonObject {
    put("bureau", JsonPrimitive(""))
    put("first_pay_period", JsonNull)
    put("pay_frequency", JsonPrimitive("weekly"))
    put("auto_sync", JsonPrimitive(true))
    put("notify_payroll", JsonPrimitive(true))
    put("include_pdf", JsonPrimitive(false))
}

/** The complete blank bank object — never `bank.id`/`bank_acc_id` (`toDealMemoPayload.js:165-195`). */
private fun bankBody(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(""))
    put("account_holder_name", JsonPrimitive(""))
    put("account_number", JsonPrimitive(""))
    put("sort_code", JsonPrimitive(""))
    put("iban_number", JsonPrimitive(""))
    put("swift_code", JsonPrimitive(""))
    put("nominal_code", JsonPrimitive(""))
    put("currency", JsonNull)
    put("additional_details", EMPTY_ROWS)
}

// -- read-side label fallback ------------------------------------------------

private val UPPERCASE_WORDS = setOf("sfx", "vfx", "epk", "dit", "covid", "hod", "pa")
private val KEEP_AS_IS = setOf("1st", "2nd", "3rd")

/**
 * The web's identifier→display fallback (`accountHub/data/utils.js:27-60,88`):
 * strip the `department_`/`designation_` prefix and any `_label` suffix,
 * underscores to spaces, title-case with the same acronym exceptions. Used
 * when a row carries only the rate-card identifier and no master to resolve
 * it against — which is the desktop's standing situation.
 */
internal fun humanisedIdentifier(raw: String?, prefix: String): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed
        .removePrefix(prefix)
        .removeSuffix("_label")
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            when {
                word in KEEP_AS_IS -> word
                word in UPPERCASE_WORDS -> word.uppercase()
                else -> word.replaceFirstChar { it.uppercase() }
            }
        }
        .takeIf { it.isNotEmpty() }
}

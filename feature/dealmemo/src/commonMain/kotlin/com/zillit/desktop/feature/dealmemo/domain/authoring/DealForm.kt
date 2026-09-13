package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The deal builder's form (`DMTemplateBuilderPage.jsx` `INIT_FORM`): one flat
 * object with the web's own camelCase keys, kept as JSON so the payload
 * mapper, the hydration and every step read and write it exactly as the web
 * does — a key nobody here models still round-trips.
 */
data class DealForm(val json: JsonObject) {

    operator fun get(key: String): JsonElement? = json[key]

    /** A present string, as typed; a number reads as its text; anything else is empty. */
    fun text(key: String): String = when (val value = json[key]) {
        is JsonPrimitive -> if (value is JsonNull) "" else value.content
        else -> ""
    }

    /** JavaScript truthiness of the key. */
    fun flag(key: String): Boolean = Js.truthy(json[key])

    fun obj(key: String): JsonObject? = json[key] as? JsonObject

    fun list(key: String): List<JsonElement> = (json[key] as? JsonArray).orEmpty()

    fun objects(key: String): List<JsonObject> = list(key).mapNotNull { it as? JsonObject }

    fun with(key: String, value: JsonElement): DealForm = DealForm(JsonObject(json + (key to value)))

    fun with(key: String, value: String): DealForm = with(key, JsonPrimitive(value))

    fun with(key: String, value: Boolean): DealForm = with(key, JsonPrimitive(value))

    fun with(patch: Map<String, JsonElement>): DealForm = DealForm(JsonObject(json + patch))

    companion object {
        const val CUSTOM_JOB_TITLE = "__custom__"

        /** Every key the builder starts with, at its web default. */
        val INITIAL: DealForm by lazy { initial() }

        @Suppress("LongMethod")
        private fun initial(): DealForm = DealForm(
            buildJsonObject {
                put("userId", "")
                put("isExternal", false)
                put("territory", "")
                put("union", "")
                put("productionType", "scripted-tv")
                put("productionEntity", "")
                put("schedOn", false)
                listOf(
                    "schedPrepStart",
                    "schedPrepEnd",
                    "schedShootStart",
                    "schedShootEnd",
                    "schedWrapStart",
                    "schedWrapEnd",
                ).forEach { put(it, "") }
                put("customDays", JsonArray(emptyList()))
                put("pactBand", "")
                put("pactSpecialDept", false)
                listOf("department", "designation", "jobTitle", "customJobTitle").forEach { put(it, "") }
                put("reportsToType", "HOD")
                put("reportsTo", "")
                put("callSheetTier", "HOD")
                put("crewType", "")
                listOf("fullLegalName", "crewName", "preferredName", "screenCreditDesignation").forEach { put(it, "") }
                put("passportAttachment", JsonNull)
                listOf("dob", "niNumber", "taxCode", "rightToWork").forEach { put(it, "") }
                put("homeAddress", EMPTY_ADDRESS)
                listOf(
                    "emergencyContact",
                    "emergencyContactName",
                    "emergencyContactNumber",
                    "emergencyCountryCode",
                    "emergencyEmail",
                )
                    .forEach { put(it, "") }
                put("emergencyAddress", EMPTY_ADDRESS)
                listOf("representativeName", "representativeCountryCode", "representativeEmail", "representativePhone")
                    .forEach { put(it, "") }
                put("representativeAddress", EMPTY_ADDRESS)
                listOf("loanOutCompanyName", "loanOutCountryCode", "loanOutPhoneNumber", "loanOutEmail").forEach {
                    put(it, "")
                }
                put("loanOutCompanyAddress", EMPTY_ADDRESS)
                put("uk", EMPTY_UK)
                listOf("email", "mobile", "gender", "unit").forEach { put(it, "") }
                // Seeded with the bank fields Send for Approval blocks on; either/or IBAN is the gate's call.
                put(
                    "crewMandatoryFields",
                    buildJsonArray {
                        add(JsonPrimitive("bankAccountHolderName"))
                        add(JsonPrimitive("bankName"))
                        add(JsonPrimitive("bankAccountNumber"))
                    },
                )
                listOf(
                    "employmentStatus",
                    "agencyId",
                    "agencyName",
                    "dealType",
                    "dealStart",
                    "dealEnd",
                    "completionDue",
                    "dealMemoDate",
                )
                    .forEach { put(it, "") }
                put("billingBasis", "week")
                put("noticeType", "none")
                put("noticeCustomValue", "")
                put("noticeCustomUnit", "week")
                put("noticeReminderValue", "")
                put("noticeReminderUnit", "day")
                put("customConditions", JsonArray(emptyList()))
                put("additionalNotes", "")
                put("longFormContract", JsonNull)
                put("travelDayFull", true)
                put("restDayDouble", false)
                put("currency", "GBP")
                listOf("dayRate", "weeklyRate", "rateAutoKey", "rateApiDay", "rateApiWeekly", "hpMode").forEach {
                    put(it, "")
                }
                put("phaseRatesOn", false)
                listOf("prepRate", "shootRate", "wrapRate", "pictureFee", "buyoutRate", "buyoutDailyRate").forEach {
                    put(it, "")
                }
                put("buyoutRateMode", "weekly")
                put("basicWorkingHoursPerDay", "")
                put("allowances", JsonArray(emptyList()))
                put("rentals", JsonArray(emptyList()))
                put("premiums", DEFAULT_PREMIUMS)
                put("workLocationType", "studio")
                put("travelZone", "30mile")
                put("distantLocation", false)
                put("paymentCurrency", "GBP")
                put("bank", EMPTY_BANK)
                put("bankAccId", JsonNull)
                put("dgaWeeklyFee", "")
                put("dgaCOABasis", "none")
                put("dgaNegotiatedCOA", "")
                put("penaltyTurnaround", true)
                put("penaltyMeal", true)
                put("penaltyRestDay", true)
                put("primarySetCode", "1400")
                put("dept", "Lighting / Electrical")
                put("budgetLine", "Below-the-Line Labour")
                put("nominalOverrides", JsonObject(emptyMap()))
                put("rulesCustomized", false)
                put("ruleOverrides", JsonObject(emptyMap()))
                put("ruleCustomRows", JsonArray(emptyList()))
                put("ruleRowEdits", JsonObject(emptyMap()))
                put("ruleRowRemovals", JsonArray(emptyList()))
                put("hetvClass", "G")
                put("ukSpend", "Yes")
                put("taxCreditRate", "25%")
                put("onboardChecked", JsonObject(emptyMap()))
                put("documents", JsonArray(emptyList()))
                put("docuSign", true)
                put("crewCounterSig", true)
                put("seniorSignOff", false)
                put("copyProdOffice", true)
                put("bureau", "")
                put("firstPayPeriod", "2026-04-20")
                put("payFrequency", "weekly")
            },
        )

        val EMPTY_ADDRESS: JsonObject = buildJsonObject {
            listOf("line1", "line2", "city", "state", "postal_code", "country").forEach { put(it, JsonNull) }
        }

        /** `emptyUk()`: every HMRC key null except the pension wish. */
        val EMPTY_UK: JsonObject = buildJsonObject {
            listOf(
                "starter_statement", "p45_previous_pay", "p45_previous_tax", "p45_leaving_date",
                "p45_previous_paye_ref", "student_loan_plan", "pg_loan", "ni_category",
            ).forEach { put(it, JsonNull) }
            put("pension_status", "opt_in")
        }

        val EMPTY_BANK: JsonObject = buildJsonObject {
            listOf(
                "name",
                "account_holder_name",
                "account_number",
                "sort_code",
                "iban_number",
                "swift_code",
                "nominal_code",
            )
                .forEach { put(it, "") }
            put("currency", JsonNull)
            put("additional_details", JsonArray(emptyList()))
        }

        private fun premium(id: String, name: String, rate: String, basis: String, freq: String, nominal: String) =
            buildJsonObject {
                put("id", id)
                put("name", name)
                put("on", true)
                put("rate", rate)
                put("basis", basis)
                put("freq", freq)
                put("nominal", nominal)
                put("mandatory", false)
            }

        private val DEFAULT_PREMIUMS = JsonArray(
            listOf(
                premium("nightshoot", "Night Shoot Premium", "10%", "night", "shoot", "4435"),
                premium("hazardous", "Wet / Hazardous Work Premium", "25%", "day", "daily", "4436"),
                premium("sixthday", "6th Day Supplement", "×1.5", "day", "daily", "4437"),
                premium("locationperdiem", "Distant Location Per Diem", "£35.00", "day", "daily", "4438"),
            ),
        )
    }
}

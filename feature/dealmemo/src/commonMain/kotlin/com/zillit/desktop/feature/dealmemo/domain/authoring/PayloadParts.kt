package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The small conversions `toDealMemoPayload.js` leans on: dates as the web's
 * `Date.parse` / `toISOString` read them, notice tokens, entitlement rates,
 * nominal wrapping and the six-key address.
 */
object PayloadParts {

    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val DURATION = Regex("^(\\d+)_(day|week|month|hour)$")
    private val LEGACY_COMPACT = Regex("^(\\d+)(day|week|month)s?$")
    private val LEGACY_FREE_TEXT = Regex("^(\\d+)\\s*(day|week|month)s?\\b", RegexOption.IGNORE_CASE)
    private val PENCE = Regex("(\\d+(?:\\.\\d+)?)\\s*p\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_SIGN = Regex("[£$€]")
    private val PER_MILE = Regex("p/mile|/mile", RegexOption.IGNORE_CASE)
    private val PER_KM = Regex("p/km|/km", RegexOption.IGNORE_CASE)
    private const val HEX = "0123456789abcdef"
    private const val CLIENT_ID_LENGTH = 24

    val ADDRESS_KEYS = listOf("line1", "line2", "city", "state", "postal_code", "country")

    /** `newClientDealId()`: 24 lower-case hex characters, ObjectId-castable. */
    fun newClientId(random: Random = Random.Default): String = buildString {
        repeat(CLIENT_ID_LENGTH) { append(HEX[random.nextInt(HEX.length)]) }
    }

    /** `num(v)`: `parseFloat`, finite, else null — so 0 stays 0. */
    fun num(value: JsonElement?): Double? = Js.parseFloat(value)

    fun num(text: String): Double? = Js.parseFloat(text)

    /** A JS number as JSON: whole values print bare. */
    fun number(value: Double?): JsonElement = when {
        value == null -> JsonNull
        value % 1.0 == 0.0 && abs(value) < WHOLE_LIMIT -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    private const val WHOLE_LIMIT = 1e15

    /** `Date.parse("YYYY-MM-DD")` — UTC midnight — or an ISO timestamp; anything else null. */
    fun toEpoch(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (ISO_DATE.matches(raw)) {
            return runCatching { LocalDate.parse(raw).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
        }
        return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
    }

    /** `fromEpoch(ms)`: the UTC calendar day as `YYYY-MM-DD`; a falsy or unreadable value is `""`. */
    @Suppress("ReturnCount") // One return per date shape the wire carries.
    fun fromEpoch(value: JsonElement?): String {
        if (!Js.truthy(value)) return ""
        val primitive = value as? JsonPrimitive ?: return ""
        val millis = if (primitive.isString) {
            toEpoch(primitive.content) ?: return ""
        } else {
            primitive.doubleOrNull?.toLong() ?: return ""
        }
        return runCatching { Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString() }
            .getOrDefault("")
    }

    /** `parseDuration`: the wire token, then the legacy compact and free-text spellings. */
    fun parseDuration(value: String?): Pair<String, String>? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = DURATION.find(raw) ?: LEGACY_COMPACT.find(raw) ?: LEGACY_FREE_TEXT.find(raw) ?: return null
        return match.groupValues[1] to match.groupValues[2].lowercase()
    }

    /** `noticePeriodToken`. */
    fun noticePeriodToken(form: DealForm): String = when (form.text("noticeType")) {
        "none" -> ""
        "custom" -> {
            val n = Js.toNumber(form["noticeCustomValue"]) ?: 0.0
            if (n > 0) "${Js.number(n)}_${form.text("noticeCustomUnit").ifEmpty { "week" }}" else ""
        }
        "1week" -> "1_week"
        else -> "2_week"
    }

    /** `noticeReminderToken`: nothing without a notice period (ZL-21066). */
    fun noticeReminderToken(form: DealForm): String {
        if (noticePeriodToken(form).isEmpty()) return ""
        val n = Js.toNumber(form["noticeReminderValue"]) ?: 0.0
        return if (n > 0) "${Js.number(n)}_${form.text("noticeReminderUnit").ifEmpty { "day" }}" else ""
    }

    /** `noticeFromStored`. */
    fun noticeFromStored(value: String?): Map<String, JsonElement> {
        val raw = value?.trim().orEmpty()
        fun notice(type: String, customValue: String = "", unit: String = "week") = mapOf(
            "noticeType" to JsonPrimitive(type),
            "noticeCustomValue" to JsonPrimitive(customValue),
            "noticeCustomUnit" to JsonPrimitive(unit),
        )
        return when {
            raw.isEmpty() -> notice("none")
            raw == "1_week" || raw == "1week" -> notice("1week")
            raw == "2_week" || raw == "2week" -> notice("2week")
            else -> parseDuration(raw)?.takeIf { it.second != "hour" }?.let { notice("custom", it.first, it.second) }
                ?: notice("custom")
        }
    }

    /** `reminderFromStored`. */
    fun reminderFromStored(value: String?): Map<String, JsonElement> {
        val parsed = parseDuration(value)?.takeIf { it.second == "hour" || it.second == "day" }
        return mapOf(
            "noticeReminderValue" to JsonPrimitive(parsed?.first.orEmpty()),
            "noticeReminderUnit" to JsonPrimitive(parsed?.second ?: "day"),
        )
    }

    /** `parseEntitlementRate`: a bare pence figure is major units ÷ 100, else the digits. */
    fun entitlementRate(rate: String?): Double? {
        val s = rate.orEmpty()
        if (s.isBlank()) return null
        val pence = PENCE.find(s)
        if (pence != null && !CURRENCY_SIGN.containsMatchIn(s)) {
            return pence.groupValues[1].toDoubleOrNull()?.div(PENCE_PER_POUND)
        }
        return Js.parseFloat(s.filter { it.isDigit() || it == '.' })
    }

    private const val PENCE_PER_POUND = 100.0

    /** `inferRateType` from the displayed rate string. */
    fun entitlementRateType(rate: String?): String {
        val s = rate.orEmpty()
        return when {
            '%' in s -> "pct"
            PER_MILE.containsMatchIn(s) -> "per_mile"
            PER_KM.containsMatchIn(s) -> "per_km"
            else -> "flat"
        }
    }

    /** `BASIS_TO_FREQ`. */
    fun payFrequencyOf(basis: String?): String = when (basis.orEmpty().lowercase()) {
        "day", "mile", "hour" -> "daily"
        "week", "3in5" -> "weekly"
        else -> ""
    }

    /** `wrapNominal`: a code the chart doesn't know is sent as `[[code]]` so the server creates it. */
    fun wrapNominal(code: String?, chart: Set<String>?): String {
        val c = code.orEmpty().trim()
        if (c.isEmpty() || chart.isNullOrEmpty()) return c
        if (c in chart || chart.any { it.equals(c, ignoreCase = true) }) return c
        return "[[$c]]"
    }

    /** `normalizeAddress`: always the six keys; a legacy string is line 1; blanks are null. */
    fun normalizeAddress(value: JsonElement?): JsonObject = buildJsonObject {
        when (value) {
            is JsonPrimitive -> if (value is JsonNull) {
                ADDRESS_KEYS.forEach { put(it, JsonNull) }
            } else {
                put("line1", value.content.trim().ifEmpty { null })
                ADDRESS_KEYS.drop(1).forEach { put(it, JsonNull) }
            }
            is JsonObject -> ADDRESS_KEYS.forEach { key ->
                val raw = value[key]
                put(key, if (raw == null || raw is JsonNull || Js.text(raw).trim().isEmpty()) JsonNull else raw)
            }
            else -> ADDRESS_KEYS.forEach { put(it, JsonNull) }
        }
    }

    /** `fromBankPayload`: the flat form bank, blank rows kept for editing. */
    fun bankFromWire(bank: JsonElement?): JsonObject {
        val src = bank as? JsonObject ?: JsonObject(emptyMap())
        fun text(key: String) = src[key]?.takeUnless { it is JsonNull }?.let(Js::text).orEmpty()
        val currency = src["currency"] as? JsonObject
        return buildJsonObject {
            listOf(
                "name",
                "account_holder_name",
                "account_number",
                "sort_code",
                "iban_number",
                "swift_code",
                "nominal_code",
            )
                .forEach { put(it, text(it)) }
            val code = currency?.get("code")?.takeIf(Js::truthy)
            if (code != null) {
                put(
                    "currency",
                    buildJsonObject {
                        put("code", code)
                        put("name", currency["name"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(""))
                        put("symbol", currency["symbol"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(""))
                    },
                )
            } else {
                put("currency", JsonNull)
            }
            put(
                "additional_details",
                JsonArray(
                    (src["additional_details"] as? JsonArray).orEmpty().map { row ->
                        val detail = row as? JsonObject
                        buildJsonObject {
                            put("field", detail?.get("field")?.takeUnless { it is JsonNull } ?: JsonPrimitive(""))
                            put("value", detail?.get("value")?.takeUnless { it is JsonNull } ?: JsonPrimitive(""))
                            put("field_type", detail?.get("field_type")?.takeIf(Js::truthy) ?: JsonPrimitive("text"))
                        }
                    },
                ),
            )
        }
    }

    /** `isBankEmpty`: no scalar, no currency code, no detail with a title or value. */
    fun isBankEmpty(bank: JsonObject?): Boolean {
        if (bank == null) return true
        val scalars = listOf(
            "name",
            "account_holder_name",
            "account_number",
            "sort_code",
            "iban_number",
            "swift_code",
            "nominal_code",
        )
        val anyScalar = scalars.any { key -> bank[key]?.let { it !is JsonNull && Js.text(it).isNotEmpty() } == true }
        val anyCurrency = Js.truthy((bank["currency"] as? JsonObject)?.get("code"))
        val anyDetail = (bank["additional_details"] as? JsonArray).orEmpty().any { row ->
            val d = row as? JsonObject
            listOf("field", "value").any { k -> d?.get(k)?.let { it !is JsonNull && Js.text(it).isNotEmpty() } == true }
        }
        return !anyScalar && !anyCurrency && !anyDetail
    }
}

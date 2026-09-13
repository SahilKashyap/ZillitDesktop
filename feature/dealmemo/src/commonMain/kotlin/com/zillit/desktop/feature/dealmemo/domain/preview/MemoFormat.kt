package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * The deal memo card's wording (`DMDealPreviewPage.jsx:154-260`,
 * `utils/rateFormat.js`, `utils/entitlements.js`) — every label map and
 * formatter the memo, the start form and the PDF context share.
 */
object MemoFormat {

    const val DASH = DealCrewLabels.DASH

    val DEAL_LABELS = mapOf(
        "weekly" to "Weekly Rolling",
        "fixed" to "Fixed Term",
        "dayplayer" to "Day Player",
        "daily" to "Day Player",
        "buyout" to "Buy-Out",
        "buy-out" to "Buy-Out",
        "picture" to "Picture Deal",
        "boxrental" to "Box Rental Only",
    )

    val NOTICE_LABELS = mapOf(
        "statutory" to "Statutory minimum",
        "1week" to "1 week",
        "2week" to "2 weeks",
        "4week" to "4 weeks",
        "1month" to "1 month",
        "production" to "Duration of production",
        "negotiated" to "Negotiated",
        "none" to "N/A (fixed term)",
        "custom" to "Custom",
    )

    val BILLING_BASIS_LABELS = mapOf(
        "week" to "Per week",
        "day" to "Per day",
        "flat" to "Flat deal",
        "episode" to "Per episode",
        "production" to "Per production",
    )

    val TRAVEL_ZONE_LABELS = mapOf(
        "30mile" to "30-mile radius (PACT/BECTU 8.3a)",
        "m25" to "Within the M25 (PACT/BECTU 8.3b)",
    )

    val COA_BASIS_LABELS = mapOf(
        "none" to "None",
        "1week" to "1 full week",
        "2.5days" to "2.5 days (½ × weekly)",
        "50pct" to "50% of weekly",
        "100pct" to "100% of weekly",
        "negotiated" to "Negotiated",
    )

    val PAY_FREQ_LABEL = mapOf(
        "weekly" to "Per Week",
        "daily" to "Per Day",
        "shoot" to "Per Shoot Day",
        "non_shoot" to "Per Non-Shoot Day",
        "all" to "All Days",
    )

    private val PREVIEW_BASIS_LABELS = mapOf(
        "30_minutes" to "30 min",
        "hour" to "hour",
        "day" to "day",
        "days" to "days",
        "night" to "night",
        "event" to "event",
        "mile" to "mile",
        "meal" to "meal",
        "call" to "call",
        "penalty" to "penalty",
        "additional" to "additional",
        "actuals" to "Actuals",
    )

    private val ENTITLEMENT_BASIS = mapOf(
        "day" to "Daily",
        "week" to "5 Days Week",
        "3in5" to "3 in 5",
        "mile" to "Per Mile",
        "hour" to "Per Hour (retired — re-select)",
        "night" to "Per Night (retired — re-select)",
        "event" to "Per Event (retired — re-select)",
    )

    private val LEGACY_DURATIONS = mapOf(
        "statutory" to "Statutory minimum",
        "production" to "Duration of production",
        "negotiated" to "Negotiated",
        "none" to "N/A (fixed term)",
    )

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DURATION = Regex("^(\\d+)_(day|week|month|hour)$")
    private val LEGACY_COMPACT = Regex("^(\\d+)(day|week|month)s?$")
    private val LEGACY_FREE_TEXT = Regex("^(\\d+)\\s*(day|week|month)s?\\b", RegexOption.IGNORE_CASE)

    /** `fmtMoney`: the dash for nothing, else the symbol and en-GB grouping at two places. */
    fun money(value: JsonElement?, symbol: String): String {
        if (value == null || value is JsonNull) return DASH
        if (value is JsonPrimitive && value.isString && value.content.isEmpty()) return DASH
        val number = Js.toNumber(value) ?: return DASH
        return "$symbol${RateFormat.groupAmount(number)}"
    }

    fun money(value: Double?, symbol: String): String = value?.let { "$symbol${RateFormat.groupAmount(it)}" } ?: DASH

    /** `fmtEpochDate`: `05 Oct 1989` in the reader's zone. */
    fun date(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (millis == null) return DASH
        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${pad(local.day)} ${MONTHS[local.month.ordinal]} ${local.year}"
    }

    /** `fmtEpoch`: en-GB medium date and short time, `5 Oct 2026, 14:03`, in the reader's zone. */
    fun dateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (millis == null) return DASH
        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${local.day} ${MONTHS[local.month.ordinal]} ${local.year}, ${pad(local.hour)}:${pad(local.minute)}"
    }

    /** `fmtDateRange`: `05 Oct 2026 → 20 Oct 2026`, or the dash when neither end is set. */
    fun dateRange(range: JsonObject?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (range == null) return DASH
        val start = DocRead.epoch(range, "start_date")
        val end = DocRead.epoch(range, "end_date")
        if (start == null && end == null) return DASH
        return "${date(start, zone)} → ${date(end, zone)}"
    }

    /** `formatDurationToken`: `3 Weeks`, `1 Week`, `24 Hours`; a legacy label; else the raw text. */
    fun durationToken(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val match = DURATION.find(raw) ?: LEGACY_COMPACT.find(raw) ?: LEGACY_FREE_TEXT.find(raw)
        if (match != null) {
            val count = match.groupValues[1].toLong()
            val unit = match.groupValues[2].lowercase().replaceFirstChar { it.uppercase() }
            return "$count $unit${if (count == 1L) "" else "s"}"
        }
        return LEGACY_DURATIONS[raw] ?: raw
    }

    /** `formatSortCode`: digits as `XX-XX-XX`, partial while short; more than six digits verbatim. */
    fun sortCode(value: String?): String {
        val text = value.orEmpty()
        val digits = text.filter { it.isDigit() }
        return if (digits.length > SORT_CODE_DIGITS) text else digits.chunked(2).joinToString("-")
    }

    /** `entitlementBasisLabel`: the picker's own wording, retired bases flagged, else underscores to spaces. */
    fun entitlementBasis(basis: String?): String {
        val value = basis?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return ""
        return ENTITLEMENT_BASIS[value] ?: value.replace('_', ' ')
    }

    private fun previewBasis(basis: String?): String? =
        basis?.takeIf { it.isNotEmpty() }?.let { PREVIEW_BASIS_LABELS[it] ?: it.replace('_', ' ') }

    /**
     * `fmtUnionComp`: a rule row's rate — `×1.5`, `+0.5`, `10% of gross`,
     * `£25/event`, `Actuals`, the bare basis, or the dash. Reads the row's
     * `source` snapshot, current and legacy shapes both.
     */
    @Suppress("CyclomaticComplexMethod")
    fun unionComp(row: JsonObject, symbol: String): String {
        val source = DocRead.obj(row, "source")
        if (Js.truthy(source?.get("use_ot_rate"))) return "OT rate"
        val rawType = DocRead.text(source, "rate_type")
        val type = when {
            rawType == "fixed" -> "flat"
            source?.get("rate_type").let { it != null && it !is JsonNull } -> rawType
            present(source, "multiplier") -> "multiplier"
            present(source, "flat") -> "flat"
            present(source, "percentage") -> "percentage"
            else -> null
        }
        val amount = listOf("rate_amount", "multiplier", "flat", "percentage")
            .firstNotNullOfOrNull { key -> source?.get(key)?.takeUnless { it is JsonNull } }
        val basis = previewBasis(DocRead.text(source, "basis"))
        return when {
            type == "multiplier" && amount != null ->
                "${if (Js.truthy(source?.get("is_enhancement"))) "+" else "×"}${Js.text(amount)}"
            type == "percentage" && amount != null -> "${Js.text(amount)}% of ${basis ?: "gross"}"
            type == "flat" && amount != null ->
                "$symbol${Js.toNumber(amount)?.let(RateFormat::groupAmountAuto) ?: Js.text(amount)}/${basis ?: "event"}"
            rawType == "actuals" -> "Actuals"
            basis != null -> basis
            else -> DASH
        }
    }

    /** `PAY_FREQ_LABEL[x] ?? x ?? "—"`. */
    fun payFrequency(row: JsonObject): String {
        val raw = row["pay_frequency"]?.takeUnless { it is JsonNull } ?: return DASH
        val text = (raw as? JsonPrimitive)?.content ?: return Js.text(raw)
        return PAY_FREQ_LABEL[text] ?: text
    }

    /** `a ?? b ?? "—"` over text keys — a present empty string still wins, as JavaScript's `??` does. */
    fun coalesce(json: JsonObject?, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> json?.get(key)?.takeUnless { it is JsonNull }?.let(Js::text) } ?: DASH

    private fun present(json: JsonObject?, key: String): Boolean = DocRead.present(json, key)

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private const val SORT_CODE_DIGITS = 6
}

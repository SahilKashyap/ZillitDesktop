package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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

    val DEAL_LABELS: Map<String, String>
        get() = mapOf(
            "weekly" to str(S.desktop_dm_deal_type_weekly_rolling),
            "fixed" to str(S.desktop_dm_deal_type_fixed_term),
            "dayplayer" to str(S.desktop_dm_deal_type_day_player),
            "daily" to str(S.desktop_dm_deal_type_day_player),
            "buyout" to str(S.desktop_dm_deal_type_buy_out),
            "buy-out" to str(S.desktop_dm_deal_type_buy_out),
            "picture" to str(S.desktop_dm_deal_type_picture_deal),
            "boxrental" to str(S.desktop_dm_deal_type_box_rental_only),
        )

    val NOTICE_LABELS: Map<String, String>
        get() = mapOf(
            "statutory" to str(S.desktop_dm_notice_statutory_minimum),
            "1week" to str(S.desktop_dm_notice_1_week),
            "2week" to str(S.desktop_dm_notice_2_weeks),
            "4week" to str(S.desktop_dm_notice_4_weeks),
            "1month" to str(S.desktop_dm_notice_1_month),
            "production" to str(S.desktop_dm_notice_duration_of_production),
            "negotiated" to str(S.desktop_negotiated),
            "none" to str(S.desktop_dm_notice_na_fixed_term),
            "custom" to str(S.custom),
        )

    val BILLING_BASIS_LABELS: Map<String, String>
        get() = mapOf(
            "week" to str(S.desktop_dm_billing_per_week),
            "day" to str(S.desktop_dm_billing_per_day),
            "flat" to str(S.desktop_dm_billing_flat_deal),
            "episode" to str(S.desktop_dm_billing_per_episode),
            "production" to str(S.desktop_dm_billing_per_production),
        )

    val TRAVEL_ZONE_LABELS: Map<String, String>
        get() = mapOf(
            "30mile" to str(S.desktop_dm_travel_zone_30mile),
            "m25" to str(S.desktop_dm_travel_zone_m25),
        )

    val COA_BASIS_LABELS: Map<String, String>
        get() = mapOf(
            "none" to str(S.none),
            "1week" to str(S.desktop_dm_coa_1_full_week),
            "2.5days" to str(S.desktop_dm_coa_2_5_days),
            "50pct" to str(S.desktop_dm_coa_50pct),
            "100pct" to str(S.desktop_dm_coa_100pct),
            "negotiated" to str(S.desktop_negotiated),
        )

    val PAY_FREQ_LABEL: Map<String, String>
        get() = mapOf(
            "weekly" to str(S.desktop_dm_freq_per_week),
            "daily" to str(S.desktop_dm_freq_per_day),
            "shoot" to str(S.desktop_dm_freq_per_shoot_day),
            "non_shoot" to str(S.desktop_dm_freq_per_non_shoot_day),
            "all" to str(S.desktop_dm_all_days),
        )

    private val PREVIEW_BASIS_LABELS: Map<String, String>
        get() = mapOf(
            "30_minutes" to str(S.desktop_unit_30_min),
            "hour" to str(S.desktop_unit_hour),
            "day" to str(S.day_label),
            "days" to str(S.dm_ds_days_label),
            "night" to str(S.desktop_unit_night),
            "event" to str(S.desktop_unit_event),
            "mile" to str(S.desktop_unit_mile),
            "meal" to str(S.desktop_unit_meal),
            "call" to str(S.desktop_unit_call),
            "penalty" to str(S.desktop_unit_penalty),
            "additional" to str(S.desktop_unit_additional),
            "actuals" to str(S.desktop_actuals),
        )

    private val ENTITLEMENT_BASIS: Map<String, String>
        get() = mapOf(
            "day" to str(S.daily),
            "week" to str(S.desktop_5_days_week),
            "3in5" to str(S.desktop_3_in_5),
            "mile" to str(S.desktop_per_mile),
            "hour" to str(S.desktop_hub_per_hour_retired_re_select_dashes),
            "night" to str(S.desktop_hub_per_night_retired_re_select_dashes),
            "event" to str(S.desktop_hub_per_event_retired_re_select_dashes),
        )

    private val LEGACY_DURATIONS: Map<String, String>
        get() = mapOf(
            "statutory" to str(S.desktop_dm_notice_statutory_minimum),
            "production" to str(S.desktop_dm_notice_duration_of_production),
            "negotiated" to str(S.desktop_negotiated),
            "none" to str(S.desktop_dm_notice_na_fixed_term),
        )

    private val MONTHS: List<String>
        get() = listOf(
            str(S.desktop_month_short_jan), str(S.desktop_month_short_feb), str(S.desktop_month_short_mar),
            str(S.desktop_month_short_apr), str(S.desktop_month_short_may), str(S.desktop_month_short_jun),
            str(S.desktop_month_short_jul), str(S.desktop_month_short_aug), str(S.desktop_month_short_sep),
            str(S.desktop_month_short_oct), str(S.desktop_month_short_nov), str(S.desktop_month_short_dec),
        )
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
            val one = count == 1L
            val key = when (match.groupValues[2].lowercase()) {
                "day" -> if (one) S.desktop_dm_dur_day else S.desktop_dm_dur_days
                "week" -> if (one) S.desktop_dm_dur_week else S.desktop_dm_dur_weeks
                "month" -> if (one) S.desktop_dm_dur_month else S.desktop_dm_dur_months
                else -> if (one) S.desktop_dm_dur_hour else S.desktop_dm_dur_hours
            }
            return str(key, count)
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
        if (Js.truthy(source?.get("use_ot_rate"))) return str(S.desktop_dm_ot_rate)
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
            type == "percentage" && amount != null ->
                str(S.desktop_dm_percent_of, Js.text(amount), basis ?: str(S.desktop_unit_gross))
            type == "flat" && amount != null ->
                "$symbol${Js.toNumber(amount)?.let(RateFormat::groupAmountAuto) ?: Js.text(amount)}/" +
                    (basis ?: str(S.desktop_unit_event))
            rawType == "actuals" -> str(S.desktop_actuals)
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

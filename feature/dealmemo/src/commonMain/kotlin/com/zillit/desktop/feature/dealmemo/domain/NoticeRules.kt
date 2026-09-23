package com.zillit.desktop.feature.dealmemo.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/** The three groups of the Notices table, in display order. */
enum class NoticeGroupKind(private val titleKey: String) {
    Deactivated(S.dm_notices_group_deactivated),
    WithNotice(S.dm_notices_group_with_notice),
    WithoutNotice(S.dm_notices_group_without_notice),
    ;

    val title: String get() = str(titleKey)
}

data class NoticeGroup(val kind: NoticeGroupKind, val deals: List<DealDoc>) {
    val unsent: List<DealDoc> get() = deals.filterNot { it.noticeSent }
}

/**
 * The Notices page's rules (`DMNoticesPage.jsx`): which deals are listed, how
 * they group and sort, and how a notice letter is filled.
 */
object NoticeRules {

    /** Account standings that mean the crew member is off the show (`dealStatus.js:208-215`). */
    private val OFFBOARDED = setOf("removed", "left", "deactivated", "deactive", "inactive", "deleted")

    /** The web's fallback letter, used until the production's own template loads. */
    const val DEFAULT_TEMPLATE: String = "Dear {{crew_name}},\n\n" +
        "This letter serves as formal notice that your engagement will end on {{contract_end_date}}, " +
        "in line with your {{notice_period}} notice period.\n\n" +
        "Your last pay day will be {{last_pay_day}}.\n\n" +
        "Thank you for your contribution to the production."

    private val LEGACY_LABELS: Map<String, String>
        get() = mapOf(
            "statutory" to str(S.desktop_dm_notice_statutory_minimum),
            "1week" to str(S.desktop_dm_notice_1_week),
            "2week" to str(S.desktop_dm_notice_2_weeks),
            "4week" to str(S.desktop_dm_notice_4_weeks),
            "1month" to str(S.desktop_dm_notice_1_month),
            "production" to str(S.desktop_dm_notice_duration_of_production),
            "negotiated" to str(S.desktop_negotiated),
            "none" to str(S.desktop_dm_notice_na_fixed_term),
        )

    private val LEGACY_RANK = mapOf(
        "production" to 9999,
        "1month" to 30,
        "4week" to 28,
        "2week" to 14,
        "1week" to 7,
        "statutory" to 0,
    )

    private val DURATION = Regex("^(\\d+)_(day|week|month|hour)$")
    private val LEGACY_COMPACT = Regex("^(\\d+)(day|week|month)s?$")
    private val LEGACY_FREE_TEXT = Regex("^(\\d+)\\s*(day|week|month)s?\\b", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("\\{\\{(\\w+)\\}\\}")

    /**
     * Active and deactivated deals matching the search, grouped: offboarded
     * crew first, then those with a notice period (longest first), then the
     * rest — each by soonest contract end, undated last.
     */
    fun groups(rows: List<DealDoc>, query: String, labels: DealCrewLabels): List<NoticeGroup> {
        val q = query.trim().lowercase()
        val relevant = rows.filter {
            (it.rawStatus == DealStatus.Active.wire || it.rawStatus == DealStatus.Deactivated.wire) &&
                (q.isEmpty() || DealListRules.matchesSearch(it, q, labels))
        }
        val byEnd = compareBy<DealDoc, Long?>(nullsLast()) { it.endDate }
        val (offboarded, staying) = relevant.partition { isOffboarded(it, labels) }
        val (withNotice, without) = staying.partition { !it.noticePeriod.isNullOrEmpty() }
        return listOf(
            NoticeGroup(NoticeGroupKind.Deactivated, offboarded.sortedWith(byEnd)),
            NoticeGroup(
                NoticeGroupKind.WithNotice,
                withNotice.sortedWith(compareByDescending<DealDoc> { rank(it.noticePeriod) }.then(byEnd)),
            ),
            NoticeGroup(NoticeGroupKind.WithoutNotice, without.sortedWith(byEnd)),
        ).filter { it.deals.isNotEmpty() }
    }

    fun isOffboarded(deal: DealDoc, labels: DealCrewLabels): Boolean =
        labels.person(deal.userId)?.status?.lowercase() in OFFBOARDED || deal.rawStatus == DealStatus.Deactivated.wire

    /**
     * The Deactivate button: not already deactivated, no last pay date or day
     * stamped, and not deactivated from this screen this session — the list
     * rows never carry the stamp, so without that last check the button comes
     * straight back.
     */
    fun canDeactivate(deal: DealDoc, deactivatedHere: Set<String>): Boolean =
        deal.rawStatus != DealStatus.Deactivated.wire && deal.lastPayDate == null && deal.lastPayDay == null &&
            deal.id !in deactivatedHere

    /** A notice period in words: `2 Weeks`, `Statutory minimum`, or a dash for none. */
    fun noticeLabel(value: String?): String {
        if (value.isNullOrEmpty()) return DealCrewLabels.DASH
        LEGACY_LABELS[value]?.let { return it }
        val (count, unit) = parseDuration(value) ?: return DealLabels.formatLabel(value)
        val one = count == 1L
        val key = when (unit) {
            "day" -> if (one) S.desktop_dm_dur_day else S.desktop_dm_dur_days
            "week" -> if (one) S.desktop_dm_dur_week else S.desktop_dm_dur_weeks
            "month" -> if (one) S.desktop_dm_dur_month else S.desktop_dm_dur_months
            else -> if (one) S.desktop_dm_dur_hour else S.desktop_dm_dur_hours
        }
        return str(key, count)
    }

    /** Longest first: duration of production, then the period in days; unknown just above statutory. */
    fun rank(value: String?): Int {
        if (value == null) return -1
        LEGACY_RANK[value]?.let { return it }
        val (count, unit) = parseDuration(value) ?: return 1
        return (count * DAYS.getValue(unit)).toInt()
    }

    /** `<n>_<unit>`, the legacy `4week`, or free text that starts with a duration. */
    fun parseDuration(value: String?): Pair<Long, String>? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = DURATION.find(raw) ?: LEGACY_COMPACT.find(raw) ?: LEGACY_FREE_TEXT.find(raw) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[2].lowercase()
    }

    /** `{{token}}`s filled; an empty or unknown value leaves the token in the letter. */
    fun fill(template: String, values: Map<String, String?>): String =
        TOKEN.replace(template) { match -> values[match.groupValues[1]]?.takeIf { it.isNotEmpty() } ?: match.value }

    /** The values one deal's letter is filled with. */
    fun values(deal: DealDoc, lastPayDay: Long?): Map<String, String?> = mapOf(
        "crew_name" to deal.crewName.orEmpty(),
        "contract_end_date" to DealDates.longUtc(deal.endDate),
        "last_pay_day" to DealDates.longUtc(lastPayDay),
        "notice_period" to noticeLabel(deal.noticePeriod),
    )

    /** A notice's last pay day: noon UTC of the picked day, so the letter shows that day everywhere. */
    fun noonUtc(isoDate: String): Long? = runCatching {
        LocalDateTime(LocalDate.parse(isoDate), LocalTime(NOON, 0)).toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
    }.getOrNull()

    /** A deactivation's last pay date: `Date.parse("YYYY-MM-DD")`, UTC midnight. */
    fun midnightUtc(isoDate: String): Long? = runCatching {
        LocalDate.parse(isoDate).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }.getOrNull()

    private val DAYS = mapOf("hour" to 0L, "day" to 1L, "week" to 7L, "month" to 30L)
    private const val NOON = 12
}

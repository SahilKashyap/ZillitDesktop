package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay

/** The dashboard's sections. */
enum class AdDestination(val slug: String, private val labelKey: String) {
    Today("today", S.today),
    Register("register", S.desktop_ad_artiste_register),
    Days("days", S.desktop_ad_shoot_days),
    ;

    val label: String get() = str(labelKey)

    /** Which socket refresh kind this page answers to. */
    val refresh: AdRefresh
        get() = when (this) {
            Today -> AdRefresh.Today
            Register -> AdRefresh.Register
            Days -> AdRefresh.Days
        }

    /**
     * The units the notification service files this page's rows under — the
     * web's `AD_UNIT_BY_NAV` (`ad-dashboard-badge-helpers.js:33`), folded onto
     * the desktop's three pages: today's list and the add-extras flow on
     * Today, the register and its queries on Register, the schedule on Days.
     *
     * The AD reports go on Days: they are each a shoot day's report, the
     * desktop has no Reports page, and the phones clear them from a nav row
     * of their own (`ADHomeScreen.swift:87-94`) — without a home here they
     * counted on the tile with nothing that could clear them.
     */
    val badgeKeys: List<String>
        get() = when (this) {
            Today -> listOf(AdBadges.TODAY_LIST, AdBadges.ADD_EXTRAS)
            Register -> listOf(AdBadges.REGISTER, AdBadges.QUERY)
            Days -> listOf(AdBadges.SHOOT_SCHEDULE, AdBadges.REPORTS)
        }

    /**
     * The units this page reads whole when it is shown — every one of its
     * [badgeKeys] but the register, which is read per artiste
     * (`ADRegisterScreen.swift:224-229`, web `AD_ITEM_UNITS`): opening the
     * tab used to clear every artiste's dot at once. The queries are read
     * per query on the phones (`ADQueriesScreen.swift:121-126`), but the
     * desktop has no query list to read one from, so they stay whole here.
     */
    val pageReads: List<String>
        get() = badgeKeys - AdBadges.REGISTER
}

/**
 * The AD dashboard's notification units under `ad_dashboard_label` (iOS
 * `ADBadgeViewModel.Unit`, `ADBadgeViewModel.swift:37-47`).
 */
object AdBadges {
    const val REGISTER = "artiste_register_label"
    const val QUERY = "artiste_query_label"
    const val REPORTS = "ad_reports_label"
    const val TODAY_LIST = "today_list_label"
    const val ADD_EXTRAS = "add_extras_label"
    const val SHOOT_SCHEDULE = "shoot_schedule_label"

    /**
     * Notification-only units — no tab, no chip, on any client (web
     * `ad-dashboard-badge-helpers.js:20-21`) — that still count on the tile.
     * Neither the web nor iOS ever reads them, so they would sit on the tile
     * for good; here the dashboard being shown reads them.
     */
    val NOTICE_ONLY: List<String> = listOf("rates_config_label")
}

/** Adding artistes from the register to the day. */
data class AddToDayState(
    val search: String = "",
    val chosen: Set<String> = emptySet(),
    val callTime: String = "",
    val saving: Boolean = false,
) {
    val ready: Boolean get() = chosen.isNotEmpty()
}

/** Blocking an artiste, with the reason recorded against them. */
data class BlockState(
    val artiste: Artiste,
    val reason: String = "",
    val saving: Boolean = false,
)

data class AdUiState(
    val viewer: AdViewer = AdViewer(),
    val destination: AdDestination = AdDestination.Today,
    /** Unread notifications per unit — the tabs' red chips. */
    val unread: Map<String, Int> = emptyMap(),
    /** Unread per unit, then per record (`level_3`) — the register's per-artiste dots. */
    val entityUnread: Map<String, Map<String, Int>> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** UTC midnight — every date on this service is, deliberately. */
    val shootDate: Long = 0L,
    val today: AdShootDay? = null,
    val dayList: List<SupportingArtistDay> = emptyList(),
    val artistes: List<Artiste> = emptyList(),
    val shootDays: List<AdShootDay> = emptyList(),
    val registerSearch: String = "",
    val categoryFilter: ArtisteCategory? = null,
    val statusFilter: ArtisteStatus? = null,
    val addToDay: AddToDayState? = null,
    val block: BlockState? = null,
    val confirmSubmit: Boolean = false,
) {
    /**
     * Whether anything may be changed right now.
     *
     * Two gates, and both must pass: the viewer's posting right, and the
     * day's own status — a submitted day is read-only for everyone.
     */
    val canEditDay: Boolean get() = viewer.canPost && dayIsOpen

    /**
     * Whether the day itself still accepts changes.
     *
     * Separate from [canEditDay] because the two halves are answered
     * differently: a submitted day is shut to everyone and its controls are
     * gone, while a missing posting right leaves them on screen and turns a
     * press into a request — see `AdViewModel.editEntry`.
     */
    val dayIsOpen: Boolean get() = today?.editable ?: false

    val register: List<Artiste>
        get() = artistes
            .filter { artiste ->
                val needle = registerSearch.trim()
                needle.isBlank() ||
                    artiste.name.contains(needle, ignoreCase = true) ||
                    artiste.refNumber.contains(needle, ignoreCase = true) ||
                    artiste.email.contains(needle, ignoreCase = true)
            }
            .filter { categoryFilter == null || it.category == categoryFilter }
            .filter { statusFilter == null || it.status == statusFilter }

    /** One artiste's unread updates (web `getAdItemUnread(register, id)`). */
    fun artisteUnread(id: String): Int = entityUnread[AdBadges.REGISTER]?.get(id) ?: 0

    /** Who is on today, present or not. */
    val onToday: Int get() = dayList.size

    val presentToday: Int get() = dayList.count { it.attendance == AttendanceStatus.Present }

    val unsignedToday: Int get() = dayList.count { !it.signed }

    /**
     * Artistes not already on the day — what the add dialog offers.
     *
     * A blocked artiste is never offered: blocking exists to keep them off
     * the call sheet, and the server refuses them anyway.
     */
    fun addable(search: String): List<Artiste> {
        val already = dayList.map { it.artisteId }.toSet()
        val needle = search.trim()
        return artistes
            .filterNot { it.id in already || it.blocked }
            .filter { needle.isBlank() || it.name.contains(needle, ignoreCase = true) }
    }
}

sealed interface AdEvent {
    data class Open(val destination: AdDestination) : AdEvent
    data object Refresh : AdEvent

    /**
     * Whether the dashboard's window is in front. The page on screen is read
     * as rows land only while it is: the view model outlives its window, and
     * reading behind a closed one cleared rows nobody saw.
     */
    data class WindowFocus(val focused: Boolean) : AdEvent

    /** An artiste's row looked at — its unread updates are read (`ADRegisterScreen.swift:224-229`). */
    data class SeeArtiste(val id: String) : AdEvent
    data object ClearNotice : AdEvent
    data object DismissError : AdEvent

    data class ChangeDay(val shootDate: Long) : AdEvent
    data class SetAttendance(val id: String, val status: AttendanceStatus) : AdEvent
    data class SetCallTime(val id: String, val time: String) : AdEvent
    data class SetWrapTime(val id: String, val time: String) : AdEvent
    data class RemoveFromDay(val id: String) : AdEvent
    data object AskSubmitDay : AdEvent
    data object ConfirmSubmitDay : AdEvent
    data object CancelSubmitDay : AdEvent

    data object OpenAddToDay : AdEvent
    data class AddSearch(val text: String) : AdEvent
    data class ToggleArtiste(val id: String) : AdEvent
    data class AddCallTime(val time: String) : AdEvent
    data object ConfirmAddToDay : AdEvent
    data object CancelAddToDay : AdEvent

    data class RegisterSearch(val text: String) : AdEvent
    data class FilterCategory(val category: ArtisteCategory?) : AdEvent
    data class FilterStatus(val status: ArtisteStatus?) : AdEvent
    data class Verify(val id: String) : AdEvent
    data class StartBlock(val artiste: Artiste) : AdEvent
    data class BlockReason(val text: String) : AdEvent
    data object ConfirmBlock : AdEvent
    data object CancelBlock : AdEvent
    data class Unblock(val id: String) : AdEvent
}

sealed interface AdEffect {
    data class Failed(val message: String) : AdEffect
}

package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay

/** The dashboard's sections. */
enum class AdDestination(val slug: String, val label: String) {
    Today("today", "Today"),
    Register("register", "Artiste register"),
    Days("days", "Shoot days"),
    ;

    /** Which socket refresh kind this page answers to. */
    val refresh: AdRefresh
        get() = when (this) {
            Today -> AdRefresh.Today
            Register -> AdRefresh.Register
            Days -> AdRefresh.Days
        }
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

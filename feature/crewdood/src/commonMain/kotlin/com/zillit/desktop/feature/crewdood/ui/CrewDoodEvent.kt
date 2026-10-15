package com.zillit.desktop.feature.crewdood.ui

import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar

/** Everything the Crew DOOD screen can ask for. */
sealed interface CrewDoodEvent {
    data object Reload : CrewDoodEvent
    data object Back : CrewDoodEvent
    data class Search(val query: String) : CrewDoodEvent
    data class SelectDepartment(val key: String) : CrewDoodEvent
    data class SetWeeks(val weeks: Boolean) : CrewDoodEvent
    data class SetBookedOnly(val bookedOnly: Boolean) : CrewDoodEvent
    data class ToggleDepartment(val key: String) : CrewDoodEvent
    data class JumpTo(val band: DoodBand) : CrewDoodEvent
    data object OpenCalendar : CrewDoodEvent
    data object CloseCalendar : CrewDoodEvent

    /** Save the editor's draft — `PUT`; the toast is the server's message. */
    data class SaveCalendar(val calendar: DoodCalendar) : CrewDoodEvent

    /** The deal-memo service's workbook. */
    data object Export : CrewDoodEvent
    data object DismissToast : CrewDoodEvent
}

/** One-shot things the screen does rather than renders. */
sealed interface CrewDoodEffect {
    /** The top bar's back arrow — wherever the tool was opened from. */
    data object Leave : CrewDoodEffect

    /** Scroll the grid to the first column of a band. */
    data class JumpTo(val band: DoodBand) : CrewDoodEffect

    /** The Weeks view starts from the left edge, as in the design. */
    data object ResetScroll : CrewDoodEffect
}

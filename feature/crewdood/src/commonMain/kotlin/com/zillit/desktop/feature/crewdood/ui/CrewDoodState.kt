package com.zillit.desktop.feature.crewdood.ui

import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrew
import com.zillit.desktop.feature.crewdood.domain.DoodModel

/** A department as the grid draws it: the rows that passed the filters, and the counts. */
data class DoodGroup(
    val key: String,
    val name: String,
    val crew: List<DoodCrew>,
    /** Booked / total over the department's **whole** crew, as the design counts it. */
    val booked: Int,
    val total: Int,
)

/** One department in the toolbar's picker. */
data class DoodDeptOption(val key: String, val name: String)

/** A toast: always the server's own words on a write, this app's on a read. */
data class DoodToast(val text: String, val success: Boolean)

/**
 * Everything the screen draws.
 *
 * [groups] and [counts] are computed once per filter change rather than per
 * recomposition: a 1,100-day chart with two hundred rows is 220,000 cells, and
 * re-deriving them on every frame is what makes a grid feel slow.
 */
data class CrewDoodUiState(
    val loading: Boolean = true,
    /** The read failed — the retry state. Only the crew list and deals can cause it. */
    val error: String? = null,
    val model: DoodModel = DoodModel(),
    val query: String = "",
    /** "" = every department. */
    val departmentKey: String = "",
    val weeks: Boolean = false,
    val bookedOnly: Boolean = false,
    val collapsed: Set<String> = emptySet(),
    val groups: List<DoodGroup> = emptyList(),
    /** Crew on each grid day, over the rows that passed the filters. */
    val counts: List<Int> = emptyList(),
    val departmentOptions: List<DoodDeptOption> = emptyList(),
    /** Which bands the chart actually has — a Jump to button for a band with none is dead. */
    val bands: Set<DoodBand> = emptySet(),
    /** True while any crew exists at all, filters aside — "No crew on this project yet." */
    val hasCrew: Boolean = false,
    val calendarOpen: Boolean = false,
    val saving: Boolean = false,
    val exporting: Boolean = false,
    val toast: DoodToast? = null,
) {
    /** The saved calendar the editor opens on. */
    val calendar: DoodCalendar get() = model.calendar

    val busy: Boolean get() = loading || error != null
}

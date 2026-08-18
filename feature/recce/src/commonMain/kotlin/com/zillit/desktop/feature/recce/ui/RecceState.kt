package com.zillit.desktop.feature.recce.ui

import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceDraft
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.ReccePerson
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.domain.Weather

/** The list's status filter — the web's segmented All / Published / Drafts. */
enum class RecceFilter(val label: String) { All("All"), Published("Published"), Drafts("Drafts") }

/** Which page the window shows; the web derives this from the URL. */
sealed interface ReccePage {
    data object Index : ReccePage
    data class Detail(val id: String) : ReccePage
    /** Null id creates. */
    data class Form(val id: String?) : ReccePage
}

/** One stop under edit — text fields, parsed on save. */
data class StopEditor(
    val time: String = "",
    val endTime: String = "",
    val kind: StopKind = StopKind.Continue,
    val place: String = "",
    val address: String = "",
    val w3w: String = "",
    val latText: String = "",
    val lngText: String = "",
    val contact: String = "",
    val description: String = "",
    val travel: String = "",
) {
    companion object {
        fun from(stop: RecceStop) = StopEditor(
            time = RecceClock.hm(stop.timeMs),
            endTime = RecceClock.hm(stop.endTimeMs),
            kind = stop.kind,
            place = stop.place,
            address = stop.address,
            w3w = stop.w3w,
            latText = stop.lat?.toString().orEmpty(),
            lngText = stop.long?.toString().orEmpty(),
            contact = stop.contact,
            description = stop.description,
            travel = stop.travel,
        )
    }

    fun toStop(dateYmd: String) = RecceStop(
        timeMs = RecceClock.clockMillis(dateYmd, time),
        endTimeMs = RecceClock.clockMillis(dateYmd, endTime),
        kind = kind,
        place = place,
        address = address,
        w3w = w3w.removePrefix("///"),
        lat = latText.trim().toDoubleOrNull(),
        long = lngText.trim().toDoubleOrNull(),
        description = description,
        contact = contact,
        travel = travel,
    )
}

data class PersonEditor(
    val userId: String? = null,
    val name: String = "",
    val role: String = "",
    val email: String = "",
    val contact: String = "",
    val note: String = "",
) {
    companion object {
        fun from(person: ReccePerson) = PersonEditor(
            userId = person.userId,
            name = person.name,
            role = person.role,
            email = person.email,
            contact = person.contact,
            note = person.note,
        )
    }

    fun toPerson() = ReccePerson(
        userId = userId,
        name = name,
        role = role,
        email = email,
        contact = contact,
        note = note,
    )
}

/**
 * The create/edit form. Field for field the web's `RecceForm`; the weather
 * line is split into its parts and rejoined on save, as the web does.
 */
data class RecceEditor(
    val id: String? = null,
    val uniqueId: String,
    val title: String = "",
    val unit: String = "",
    val dateYmd: String = "",
    val station: String = "",
    val weather: Weather = Weather(),
    val crewNote: String = "",
    val rdv: StopEditor = StopEditor(kind = StopKind.Rendezvous),
    val stops: List<StopEditor> = listOf(StopEditor(kind = StopKind.Rendezvous)),
    val personnel: List<PersonEditor> = listOf(PersonEditor()),
    val dirty: Boolean = false,
    val saving: RecceStatus? = null,
) {
    val isEditing: Boolean get() = id != null

    /** The web's publish validation: title, date, RDV time, RDV place. */
    fun publishProblems(): List<String> = buildList {
        if (title.isBlank()) add("Give the recce a title")
        if (RecceClock.dayMillis(dateYmd) == 0L) add("Pick the recce date")
        if (RecceClock.clockMillis(dateYmd, rdv.time) == 0L) add("Set the RDV time")
        if (rdv.place.isBlank()) add("Set the rendezvous point")
    }

    fun toDraft(status: RecceStatus, timezone: String) = RecceDraft(
        uniqueId = uniqueId,
        timezone = timezone,
        title = title,
        unit = unit,
        dateMs = RecceClock.dayMillis(dateYmd),
        station = station,
        weather = weather.compose(),
        crewNote = crewNote,
        rdv = rdv.toStop(dateYmd),
        itinerary = stops.map { it.toStop(dateYmd) },
        personnel = personnel.map { it.toPerson() },
        status = status,
    )

    companion object {
        /** Seeds a blank row where the record has none — the web does (ZL-19907). */
        fun from(recce: Recce) = RecceEditor(
            id = recce.id,
            uniqueId = recce.uniqueId,
            title = recce.title,
            unit = recce.unit,
            dateYmd = RecceClock.ymd(recce.dateMs),
            station = recce.station,
            weather = Weather.parse(recce.weather),
            crewNote = recce.crewNote,
            rdv = StopEditor.from(recce.rdv).copy(kind = StopKind.Rendezvous),
            stops = recce.itinerary.map { StopEditor.from(it) }
                .ifEmpty { listOf(StopEditor(kind = StopKind.Rendezvous)) },
            personnel = recce.personnel.map { PersonEditor.from(it) }.ifEmpty { listOf(PersonEditor()) },
        )
    }
}

data class RecceUiState(
    val viewer: RecceViewer = RecceViewer(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val recces: List<Recce> = emptyList(),
    val units: List<ProductionUnit> = emptyList(),
    val crew: List<RecceCrewMember> = emptyList(),
    val filter: RecceFilter = RecceFilter.All,
    val query: String = "",
    val unitFilter: String? = null,
    val page: ReccePage = ReccePage.Index,
    val selected: Recce? = null,
    val editor: RecceEditor? = null,
    /** The id awaiting a delete confirmation. */
    val confirmDelete: String? = null,
) {
    val visible: List<Recce>
        get() {
            val needle = query.trim().lowercase()
            return recces
                .filter {
                    when (filter) {
                        RecceFilter.All -> true
                        RecceFilter.Published -> it.status == RecceStatus.Published
                        RecceFilter.Drafts -> it.status == RecceStatus.Draft
                    }
                }
                .filter { unitFilter == null || it.unit == unitFilter }
                .filter { needle.isEmpty() || it.title.contains(needle, true) || it.rdv.place.contains(needle, true) }
        }

    val publishedCount: Int get() = recces.count { it.status == RecceStatus.Published }
    val draftCount: Int get() = recces.count { it.status == RecceStatus.Draft }

    /** The units seen in the list, for the filter — the web builds it from the rows. */
    val unitsInList: List<ProductionUnit>
        get() = recces.map { it.unit }.filter { it.isNotBlank() }.distinct().map { id ->
            units.firstOrNull { it.id == id } ?: ProductionUnit(id, id)
        }

    /** A unit id (or a legacy unit name) shown as its name. */
    fun unitName(unit: String): String =
        units.firstOrNull { it.id == unit }?.name ?: unit
}

sealed interface RecceEvent {
    data object Refresh : RecceEvent
    data class Filter(val filter: RecceFilter) : RecceEvent
    data class Search(val query: String) : RecceEvent
    data class FilterUnit(val unit: String?) : RecceEvent
    data class Open(val id: String) : RecceEvent
    data object Back : RecceEvent
    data object New : RecceEvent
    data class Edit(val id: String) : RecceEvent
    data class Delete(val id: String) : RecceEvent
    data object ConfirmDelete : RecceEvent
    data object CancelDelete : RecceEvent
    data object GeneratePdf : RecceEvent
    data class OpenUrl(val url: String) : RecceEvent

    data class EditorChanged(
        val title: String? = null,
        val unit: String? = null,
        val clearUnit: Boolean = false,
        val dateYmd: String? = null,
        val station: String? = null,
        val weather: Weather? = null,
        val crewNote: String? = null,
        val rdv: StopEditor? = null,
    ) : RecceEvent
    data object AddStop : RecceEvent
    data class StopChanged(val index: Int, val stop: StopEditor) : RecceEvent
    data class RemoveStop(val index: Int) : RecceEvent
    data class MoveStop(val index: Int, val delta: Int) : RecceEvent
    data object AddPerson : RecceEvent
    data class AddCrewMember(val userId: String) : RecceEvent
    data class PersonChanged(val index: Int, val person: PersonEditor) : RecceEvent
    data class RemovePerson(val index: Int) : RecceEvent
    data object SaveDraft : RecceEvent
    data object Publish : RecceEvent
    data object CancelEdit : RecceEvent
    data object DismissError : RecceEvent
}

sealed interface RecceEffect {
    data class Notice(val text: String) : RecceEffect
    data class OpenUrl(val url: String) : RecceEffect
}

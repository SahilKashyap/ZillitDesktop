package com.zillit.desktop.feature.recce.ui

import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceDraft
import com.zillit.desktop.feature.recce.domain.RecceQuery
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.ReccePerson
import com.zillit.desktop.feature.recce.domain.ReccePdfPage
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.StopKind
import com.zillit.desktop.feature.recce.domain.Weather
import com.zillit.desktop.feature.recce.domain.mapsLink

/** The list's status filter — the web's segmented All / Published / Drafts. */
enum class RecceFilter(val label: String, val status: RecceStatus?) {
    All("All", null),
    Published("Published", RecceStatus.Published),
    Drafts("Drafts", RecceStatus.Draft),
}

/** Which page the window shows; the web derives this from the URL. */
sealed interface ReccePage {
    data object Index : ReccePage
    data class Detail(val id: String) : ReccePage
    /** Null id creates. */
    data class Form(val id: String?) : ReccePage
}

/**
 * The tab badges — project-wide counts, not the loaded page. The web keeps
 * drafts as `all − published` because a recce's status is binary.
 */
data class RecceCounts(val all: Int = 0, val published: Int = 0, val draft: Int = 0) {
    fun of(filter: RecceFilter): Int = when (filter) {
        RecceFilter.All -> all
        RecceFilter.Published -> published
        RecceFilter.Drafts -> draft
    }

    fun with(filter: RecceFilter, count: Int): RecceCounts = when (filter) {
        RecceFilter.All -> copy(all = count, draft = (count - published).coerceAtLeast(0))
        RecceFilter.Published -> copy(published = count, draft = (all - count).coerceAtLeast(0))
        RecceFilter.Drafts -> copy(draft = count)
    }
}

/** The fields the web validates before a publish. */
enum class RecceField { Title, Date, RdvTime, RdvPlace }

/** One stop under edit — text fields, parsed on save. */
data class StopEditor(
    val time: String = "",
    val endTime: String = "",
    val kind: StopKind = StopKind.Continue,
    val place: String = "",
    val address: String = "",
    val w3w: String = "",
    val lat: Double? = null,
    val long: Double? = null,
    val contact: String = "",
    val description: String = "",
    val travel: String = "",
) {
    val pin: LatLng? get() = if (lat != null && long != null) LatLng(lat, long) else null

    /** The link the form shows and copies — always derived from the pin, never stored. */
    val mapsUrl: String get() = pin?.let { mapsLink(it) }.orEmpty()

    companion object {
        fun from(stop: RecceStop) = StopEditor(
            time = RecceClock.hm(stop.timeMs),
            endTime = RecceClock.hm(stop.endTimeMs),
            kind = stop.kind,
            place = stop.place,
            address = stop.address,
            w3w = stop.w3w,
            lat = stop.lat,
            long = stop.long,
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
        lat = lat,
        long = long,
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
    /** A seed row nobody touched — it gives way to the first crew pick. */
    val isBlankSeed: Boolean get() = userId == null && name.isBlank() && role.isBlank() && contact.isBlank()

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
    /** Set by a refused publish; cleared as each field is edited. */
    val errors: Map<RecceField, String> = emptyMap(),
) {
    val isEditing: Boolean get() = id != null

    /** The web's footer counter leaves lunch out, as the list does. */
    val stopCount: Int get() = stops.count { it.kind != StopKind.Lunch }

    /** The web's publish validation: title, date, RDV time, RDV place. */
    fun publishProblems(): Map<RecceField, String> = buildMap {
        if (title.isBlank()) put(RecceField.Title, "Give the recce a title")
        if (RecceClock.dayMillis(dateYmd) == 0L) put(RecceField.Date, "Pick the recce date")
        if (RecceClock.clockMillis(dateYmd, rdv.time) == 0L) put(RecceField.RdvTime, "Set the RDV time")
        if (rdv.place.isBlank()) put(RecceField.RdvPlace, "Set the rendezvous point")
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

/** The pending delete — the web's `{ ids, label }` confirm. */
data class DeleteTarget(val id: String, val label: String)

/** The generated report in the in-app viewer — the web's `DocumentViewer` over the new tab. */
data class ReccePdfViewer(
    val name: String,
    val bytes: ByteArray? = null,
    val pages: List<ReccePdfPage> = emptyList(),
    val loading: Boolean = true,
    val failed: String? = null,
    val printing: Boolean = false,
    val downloading: Boolean = false,
)

/** The route picture for the open recce, keyed by the itinerary it was drawn from. */
data class RouteMapState(
    val signature: String,
    val image: ByteArray? = null,
    val loading: Boolean = true,
    /** How many stops made it onto the picture; zero shows the web's prompt. */
    val plotted: Int = 0,
)

data class RecceUiState(
    val viewer: RecceViewer = RecceViewer(),
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    // -- list, one server page --
    val recces: List<Recce> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = RecceQuery.DEFAULT_PAGE_SIZE,
    val counts: RecceCounts = RecceCounts(),
    val filter: RecceFilter = RecceFilter.All,
    /** What the search box shows; the committed search is debounced behind it. */
    val query: String = "",
    val unitFilter: String? = null,
    val units: List<ProductionUnit> = emptyList(),
    val crew: List<RecceCrewMember> = emptyList(),
    // -- pages --
    val route: ReccePage = ReccePage.Index,
    val selected: Recce? = null,
    val editor: RecceEditor? = null,
    val leavePrompt: Boolean = false,
    val deleteTarget: DeleteTarget? = null,
    val deleting: Boolean = false,
    val pdf: ReccePdfViewer? = null,
    val routeMap: RouteMapState? = null,
    /** Form previews, one picture per placed pin; null marks a fetch that found nothing. */
    val previews: Map<LatLng, ByteArray?> = emptyMap(),
) {
    /**
     * Tab and search are applied server-side; the unit filter is the one
     * left on the client — the list endpoint has no unit param (the web
     * verified a nonsense `unit_id` still answers every row), so it can only
     * narrow the page in hand.
     */
    val visible: List<Recce>
        get() = if (unitFilter == null) recces else recces.filter { it.unit == unitFilter }

    /** The units seen on this page, for the filter — the web builds it from the rows. */
    val unitsInList: List<ProductionUnit>
        get() = recces.map { it.unit }.filter { it.isNotBlank() }.distinct().map { id ->
            units.firstOrNull { it.id == id } ?: ProductionUnit(id, id)
        }

    val pageCount: Int get() = if (total <= 0) 1 else (total + pageSize - 1) / pageSize

    /** "1–50 of 120" — the web's `showTotal`. */
    val rangeLabel: String
        get() {
            if (total == 0) return "0 of 0"
            val from = (page - 1) * pageSize + 1
            val to = minOf(page * pageSize, total)
            return "$from–$to of $total"
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
    data class GoToPage(val page: Int) : RecceEvent
    data class PageSize(val size: Int) : RecceEvent
    data class Open(val id: String) : RecceEvent
    data object Back : RecceEvent
    data object New : RecceEvent
    data class Edit(val id: String) : RecceEvent
    data class Delete(val id: String) : RecceEvent
    data object ConfirmDelete : RecceEvent
    data object CancelDelete : RecceEvent
    data object GeneratePdf : RecceEvent
    data object PrintPdf : RecceEvent
    data object DownloadPdf : RecceEvent
    data object ClosePdf : RecceEvent
    data class OpenUrl(val url: String) : RecceEvent
    data class NeedPreview(val pin: LatLng) : RecceEvent
    /** The window's theme, so the map pictures are drawn to match. */
    data class Theme(val dark: Boolean) : RecceEvent

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
    /** Back or Cancel on the form — asks first when there are unsaved edits. */
    data object RequestCancel : RecceEvent
    data object KeepEditing : RecceEvent
    data object DiscardChanges : RecceEvent
    data object DismissError : RecceEvent
}

sealed interface RecceEffect {
    data class Notice(val text: String, val success: Boolean = true) : RecceEffect
    data class OpenUrl(val url: String) : RecceEffect
    /** A refused publish — the web scrolls to the first failing field. */
    data object ScrollToTop : RecceEffect
}

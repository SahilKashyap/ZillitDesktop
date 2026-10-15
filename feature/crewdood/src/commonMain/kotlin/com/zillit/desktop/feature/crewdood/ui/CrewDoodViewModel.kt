package com.zillit.desktop.feature.crewdood.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.crewdood.domain.CrewDoodExport
import com.zillit.desktop.feature.crewdood.domain.CrewDoodRepository
import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrew
import com.zillit.desktop.feature.crewdood.domain.DoodDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodSources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The Crew DOOD's one view model: the four reads, the filters, the calendar
 * save and the export.
 *
 * The four reads go out together, as the web's `Promise.all` does. Only the
 * crew list and the deals can fail the screen — without shoot days there are
 * no bands, without the master no names for externals, and without a saved
 * calendar the model falls back to Saturday and Sunday off — so those three
 * are taken as whatever they answered.
 */
class CrewDoodViewModel(
    private val repository: CrewDoodRepository,
    private val export: CrewDoodExport = CrewDoodExport.None,
    /** Today, as a local day — the window the chart opens on when there is nothing to show. */
    private val today: () -> LocalDate = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
) : ZillitViewModel<CrewDoodUiState, CrewDoodEvent, CrewDoodEffect>(CrewDoodUiState()) {

    private var started = false
    private var sources = DoodSources()

    /** Called by the tool provider when the screen first appears. */
    fun start() {
        if (started) return
        started = true
        load()
        launch {
            // A deal created, changed, activated or deactivated anywhere on
            // the project moves bars here. The deals alone are refetched, in
            // the background; a failed refresh keeps what is on screen.
            repository.dealChanges.collectLatest { refreshDeals() }
        }
    }

    /** The production changed under the tool: everything here belongs to one. */
    fun onProjectChanged() {
        sources = DoodSources()
        setState { CrewDoodUiState() }
        load()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per event: a dispatcher, not a decision.
    override fun onEvent(event: CrewDoodEvent) {
        when (event) {
            CrewDoodEvent.Reload -> load()
            CrewDoodEvent.Back -> sendEffect(CrewDoodEffect.Leave)
            is CrewDoodEvent.Search -> setState { copy(query = event.query).refiltered() }
            is CrewDoodEvent.SelectDepartment -> setState { copy(departmentKey = event.key).refiltered() }
            is CrewDoodEvent.SetWeeks -> {
                if (event.weeks != currentState.weeks) {
                    setState { copy(weeks = event.weeks) }
                    sendEffect(CrewDoodEffect.ResetScroll)
                }
            }
            is CrewDoodEvent.SetBookedOnly -> setState { copy(bookedOnly = event.bookedOnly).refiltered() }
            is CrewDoodEvent.ToggleDepartment -> setState {
                copy(collapsed = if (event.key in collapsed) collapsed - event.key else collapsed + event.key)
            }
            is CrewDoodEvent.JumpTo -> sendEffect(CrewDoodEffect.JumpTo(event.band))
            CrewDoodEvent.OpenCalendar -> setState { copy(calendarOpen = true) }
            CrewDoodEvent.CloseCalendar -> if (!currentState.saving) setState { copy(calendarOpen = false) }
            is CrewDoodEvent.SaveCalendar -> saveCalendar(event.calendar)
            CrewDoodEvent.Export -> runExport()
            CrewDoodEvent.DismissToast -> setState { copy(toast = null) }
        }
    }

    // -- loading ------------------------------------------------------------

    private fun load() {
        setState { copy(loading = true, error = null) }
        launch {
            val roster = async { repository.roster() }
            val deals = async { repository.deals() }
            val shootDays = async { repository.shootDays() }
            val directory = async { repository.directory() }
            val calendar = async { repository.calendar() }
            awaitAll(roster, deals, shootDays, directory, calendar)
            val units = roster.await()
            val dealList = deals.await()
            // Either of the two required reads failing is the retry state. The
            // web shows its own copy here rather than the server's, because a
            // read can fail without the server saying anything at all.
            if (units is ZillitResult.Failure || dealList is ZillitResult.Failure) {
                setState { copy(loading = false, error = str(S.desktop_cdood_load_failed)) }
                return@launch
            }
            sources = DoodSources(
                units = (units as ZillitResult.Success).data,
                deals = (dealList as ZillitResult.Success).data,
                shootDays = shootDays.await().dataOrEmpty(),
                directory = directory.await().dataOrEmpty(),
                calendar = (calendar.await() as? ZillitResult.Success)?.data,
            )
            setState { copy(loading = false, error = null).rebuilt() }
        }
    }

    private fun refreshDeals() {
        launch {
            val outcome = repository.deals()
            if (outcome is ZillitResult.Success) {
                sources = sources.copy(deals = outcome.data)
                setState { rebuilt() }
            }
        }
    }

    private fun <T> ZillitResult<List<T>>.dataOrEmpty(): List<T> =
        (this as? ZillitResult.Success)?.data ?: emptyList()

    // -- writes -------------------------------------------------------------

    private fun saveCalendar(calendar: DoodCalendar) {
        if (currentState.saving) return
        setState { copy(saving = true) }
        launchResult(
            block = { repository.saveCalendar(calendar) },
            onSuccess = { saved ->
                // The grid redraws from what was stored, with no refetch.
                sources = sources.copy(calendar = saved.calendar)
                setState {
                    copy(
                        saving = false,
                        calendarOpen = false,
                        toast = saved.message.takeIf { it.isNotBlank() }?.let { DoodToast(it, success = true) },
                    ).rebuilt()
                }
            },
            onError = { error ->
                // Who may edit the calendar is the server's check; its refusal
                // is what the user is shown.
                setState { copy(saving = false, toast = DoodToast(error.localised(), success = false)) }
            },
        )
    }

    /**
     * The workbook. Success is the download itself — no toast — plus a note
     * when the service left deals off for missing dates (`X-Dood-Skipped`).
     */
    private fun runExport() {
        if (currentState.exporting) return
        setState { copy(exporting = true) }
        launchResult(
            block = { export.export() },
            onSuccess = { done ->
                val warning = when {
                    done.skipped == 1 -> str(S.desktop_cdood_export_skipped_one)
                    done.skipped > 1 -> str(S.desktop_cdood_export_skipped, done.skipped)
                    else -> null
                }
                setState {
                    copy(exporting = false, toast = warning?.let { DoodToast(it, success = false) })
                }
            },
            onError = { error ->
                setState { copy(exporting = false, toast = DoodToast(error.localised(), success = false)) }
            },
        )
    }

    // -- derivation ---------------------------------------------------------

    /** The model, then the filters — after a read, a calendar save or a deal refresh. */
    private fun CrewDoodUiState.rebuilt(): CrewDoodUiState {
        val model = DoodCalc.buildDood(
            sources = sources,
            today = today(),
            noDepartment = str(S.desktop_tasks_no_department),
            labelOf = { it.localised() },
        )
        val departments = model.departments.filter { it.crew.isNotEmpty() }
        return copy(
            model = model,
            hasCrew = departments.isNotEmpty(),
            departmentOptions = departments.map { DoodDeptOption(it.key, it.name) },
            bands = model.phases.mapNotNull { it?.band }.toSet(),
        ).refiltered()
    }

    /**
     * The rows that pass Find, the department picker and All / Booked, grouped
     * by department. The footer counts them all — a collapsed department still
     * counts, as in the design.
     */
    private fun CrewDoodUiState.refiltered(): CrewDoodUiState {
        val needle = query.trim().lowercase()
        val matching = mutableListOf<DoodCrew>()
        val groups = mutableListOf<DoodGroup>()
        model.departments.filter { it.crew.isNotEmpty() }.forEach { department ->
            if (departmentKey.isNotBlank() && department.key != departmentKey) return@forEach
            val crew = department.crew.filter { it.matches(needle, bookedOnly) }
            if (crew.isEmpty()) return@forEach
            matching += crew
            groups += department.toGroup(crew)
        }
        return copy(groups = groups, counts = DoodCalc.crewPerDay(matching, model.dates.size))
    }

    private fun DoodCrew.matches(needle: String, bookedOnly: Boolean): Boolean {
        val hit = needle.isEmpty() || "$role $name".lowercase().contains(needle)
        return hit && (!bookedOnly || days > 0)
    }

    private fun DoodDepartment.toGroup(crew: List<DoodCrew>) = DoodGroup(
        key = key,
        name = name,
        crew = crew,
        booked = bookedCount,
        total = this.crew.size,
    )
}

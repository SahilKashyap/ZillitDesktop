package com.zillit.desktop.feature.settings.preferences

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What the cards need to know about the open production. */
data class PreferencesContext(
    val projectId: String? = null,
    /** The web shows Default Home Unit, Notifications and the location schedule on team productions only. */
    val isTeamProject: Boolean = true,
)

data class AppPreferencesUiState(
    val projectId: String? = null,
    val isTeamProject: Boolean = true,
    val units: List<HomeUnitChoice> = emptyList(),
    val defaultUnitId: String? = null,
    val savingUnit: Boolean = false,
    /** The schedule as edited; written by Update Location, not as it changes. */
    val location: LocationSharing = LocationSharing(),
    val togglingLocation: Boolean = false,
    val savingSchedule: Boolean = false,
    val notifications: NotificationsDrawer = NotificationsDrawer(),
    val toast: PreferenceToast? = null,
) {
    /** Null when the stored unit is no longer one of the tabs — the web's placeholder then. */
    val defaultUnit: HomeUnitChoice? get() = units.firstOrNull { it.id == defaultUnitId }

    /** The schedule shows under the switch only while sharing is on, and only on a team production. */
    val showsSchedule: Boolean get() = location.enabled && isTeamProject
}

/** The Manage Notifications drawer (`NotificationOnOff.jsx`). */
data class NotificationsDrawer(
    val open: Boolean = false,
    val loading: Boolean = false,
    val sections: List<NotificationSection> = emptyList(),
    val query: String = "",
    /** Sections folded shut. Empty = every one open, as on the web. */
    val collapsed: Set<String> = emptySet(),
)

/** The web's `message.success` / `message.error` / `message.info` after each change. */
data class PreferenceToast(val text: String, val isError: Boolean = false)

sealed interface AppPreferencesEvent {
    /** The page came into view — re-read what the server holds. */
    data object Refresh : AppPreferencesEvent
    data class DefaultUnitPicked(val unitId: String) : AppPreferencesEvent

    data class LocationToggled(val on: Boolean) : AppPreferencesEvent
    data class ModeChanged(val mode: LocationMode) : AppPreferencesEvent
    data class EverydayStartChanged(val minute: Int) : AppPreferencesEvent
    data class EverydayEndChanged(val minute: Int) : AppPreferencesEvent
    data class DayToggled(val day: Int) : AppPreferencesEvent
    data class DayStartChanged(val day: Int, val minute: Int) : AppPreferencesEvent
    data class DayEndChanged(val day: Int, val minute: Int) : AppPreferencesEvent
    data object UpdateLocation : AppPreferencesEvent

    data object OpenNotifications : AppPreferencesEvent
    data object CloseNotifications : AppPreferencesEvent
    data class NotificationQuery(val query: String) : AppPreferencesEvent
    data class SectionFolded(val name: String) : AppPreferencesEvent
    data class SectionSwitched(val name: String, val on: Boolean) : AppPreferencesEvent
    data class ToolSwitched(val name: String, val on: Boolean) : AppPreferencesEvent
    data class ActivitySwitched(val tool: String, val activity: String, val on: Boolean) : AppPreferencesEvent

    data object ToastDismissed : AppPreferencesEvent
}

/**
 * The web's App Preferences cards, on the desktop's Settings page.
 *
 * Its own view model rather than more of [com.zillit.desktop.feature.settings.ui.SettingsViewModel]:
 * that one holds this computer's switches, and every value here is the
 * server's — read back from the profile and `device/notification`, written
 * with a call, and refused by the server sometimes.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One seam per source; one handler per control.
class AppPreferencesViewModel(
    /** Null before a production is open — there is nobody to ask. */
    private val repository: AppPreferencesRepository?,
    context: Flow<PreferencesContext> = flowOf(PreferencesContext()),
    /** The Home tabs this person can see — the web's viewable, enabled `home/unit/user` rows. */
    private val homeUnits: suspend () -> List<HomeUnitChoice> = { emptyList() },
    /**
     * Every `unit_name` in `project/tools`. The drawer lists a tool only when
     * it is among them, as the web filters on its posting-rights list; empty
     * while that list is loading, when nothing is filtered.
     */
    private val toolNames: () -> Set<String> = { emptySet() },
    /** Where this computer is, `lat to lng`, or null when it cannot be found. */
    private val locate: suspend () -> Pair<Double, Double>? = { null },
    /** After a profile write, so the rest of the app reads the new value (Home's landing tab). */
    private val onProfileChanged: suspend () -> Unit = {},
) : ZillitViewModel<AppPreferencesUiState, AppPreferencesEvent, Nothing>(AppPreferencesUiState()) {

    init {
        launch {
            context.collect { ctx ->
                val switched = ctx.projectId != currentState.projectId
                setState {
                    if (switched) {
                        AppPreferencesUiState(projectId = ctx.projectId, isTeamProject = ctx.isTeamProject)
                    } else {
                        copy(isTeamProject = ctx.isTeamProject)
                    }
                }
                if (switched && ctx.projectId != null) load()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive dispatch over the sealed set.
    override fun onEvent(event: AppPreferencesEvent) {
        when (event) {
            AppPreferencesEvent.Refresh -> if (currentState.projectId != null) load()
            is AppPreferencesEvent.DefaultUnitPicked -> pickUnit(event.unitId)

            is AppPreferencesEvent.LocationToggled -> toggleLocation(event.on)
            is AppPreferencesEvent.ModeChanged -> editLocation { copy(mode = event.mode) }
            is AppPreferencesEvent.EverydayStartChanged -> editEveryday(start = event.minute)
            is AppPreferencesEvent.EverydayEndChanged -> editEveryday(end = event.minute)
            is AppPreferencesEvent.DayToggled -> toggleDay(event.day)
            is AppPreferencesEvent.DayStartChanged -> editDay(event.day, start = event.minute)
            is AppPreferencesEvent.DayEndChanged -> editDay(event.day, end = event.minute)
            AppPreferencesEvent.UpdateLocation -> saveSchedule()

            AppPreferencesEvent.OpenNotifications -> openNotifications()
            // The web forgets the search, the folds and the list on close.
            AppPreferencesEvent.CloseNotifications -> setState { copy(notifications = NotificationsDrawer()) }
            is AppPreferencesEvent.NotificationQuery ->
                setState { copy(notifications = notifications.copy(query = event.query)) }
            is AppPreferencesEvent.SectionFolded -> setState {
                val folded = notifications.collapsed
                copy(
                    notifications = notifications.copy(
                        collapsed = if (event.name in folded) folded - event.name else folded + event.name,
                    ),
                )
            }
            is AppPreferencesEvent.SectionSwitched -> switchSection(event.name, event.on)
            is AppPreferencesEvent.ToolSwitched -> switchTool(event.name, event.on)
            is AppPreferencesEvent.ActivitySwitched -> switchActivity(event.tool, event.activity, event.on)

            AppPreferencesEvent.ToastDismissed -> setState { copy(toast = null) }
        }
    }

    private fun load() {
        val repository = repository ?: return
        launch {
            val units = homeUnits()
            setState { copy(units = units) }
        }
        launch {
            repository.profile().getOrNull()?.let { profile ->
                setState { copy(defaultUnitId = profile.defaultUnitId, location = profile.location) }
            }
        }
    }

    // -- Default Home Unit ---------------------------------------------------

    private fun pickUnit(unitId: String) {
        val repository = repository ?: return
        if (currentState.savingUnit) return
        setState { copy(savingUnit = true) }
        launch {
            when (val saved = repository.setDefaultUnit(unitId)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            defaultUnitId = unitId,
                            savingUnit = false,
                            toast = success(str(S.desktop_default_unit_updated)),
                        )
                    }
                    onProfileChanged()
                }
                is ZillitResult.Failure -> setState { copy(savingUnit = false, toast = failure(saved)) }
            }
        }
    }

    // -- Location ------------------------------------------------------------

    /**
     * The web's order: find this computer, send that position, then flip the
     * profile — and the switch moves only once all of it has answered.
     */
    private fun toggleLocation(on: Boolean) {
        val repository = repository ?: return
        if (currentState.togglingLocation) return
        setState { copy(togglingLocation = true) }
        launch {
            val result = if (on) {
                val point = locate()
                if (point == null) {
                    setState { copy(togglingLocation = false, toast = error(str(S.desktop_location_unavailable))) }
                    return@launch
                }
                when (val sent = repository.reportLocation(point.first, point.second)) {
                    is ZillitResult.Failure -> sent
                    is ZillitResult.Success -> repository.setLocationSharing(true)
                }
            } else {
                repository.setLocationSharing(false)
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            togglingLocation = false,
                            location = location.copy(enabled = on),
                            toast = result.data.toToast(),
                        )
                    }
                    onProfileChanged()
                }
                is ZillitResult.Failure -> setState { copy(togglingLocation = false, toast = failure(result)) }
            }
        }
    }

    private fun editLocation(change: LocationSharing.() -> LocationSharing) =
        setState { copy(location = location.change()) }

    /** A start after the end, or an end before the start, is refused on the spot, as the web's picker refuses it. */
    private fun editEveryday(start: Int? = null, end: Int? = null) {
        val window = currentState.location.everyday
        val next = window.copy(startMinute = start ?: window.startMinute, endMinute = end ?: window.endMinute)
        if (!next.isValid) {
            setState { copy(toast = error(orderMessage(start != null))) }
            return
        }
        editLocation { copy(everyday = next) }
    }

    private fun toggleDay(day: Int) = editLocation {
        // A newly ticked day opens on the whole day, as the web adds it.
        copy(days = if (day in days) days - day else days + (day to TimeWindow()))
    }

    private fun editDay(day: Int, start: Int? = null, end: Int? = null) {
        val window = currentState.location.days[day] ?: return
        val next = window.copy(startMinute = start ?: window.startMinute, endMinute = end ?: window.endMinute)
        if (!next.isValid) {
            setState { copy(toast = error(orderMessage(start != null))) }
            return
        }
        editLocation { copy(days = days + (day to next)) }
    }

    private fun saveSchedule() {
        val repository = repository ?: return
        val sharing = currentState.location
        if (currentState.savingSchedule || currentState.togglingLocation) return
        val problem = when {
            sharing.mode == LocationMode.Everyday && !sharing.everyday.isValid -> orderMessage(true)
            sharing.mode == LocationMode.SelectDays && sharing.days.isEmpty() -> str(S.p_select_at_least_one_day)
            sharing.mode == LocationMode.SelectDays ->
                sharing.days.entries.sortedBy { it.key }.firstOrNull { !it.value.isValid }
                    ?.let { (day, _) -> "${dayLabel(day)}: ${orderMessage(true)}" }
            else -> null
        }
        if (problem != null) {
            setState { copy(toast = error(problem)) }
            return
        }
        setState { copy(savingSchedule = true) }
        launch {
            when (val saved = repository.saveLocationSchedule(sharing)) {
                is ZillitResult.Success -> {
                    setState { copy(savingSchedule = false, toast = saved.data.toToast()) }
                    onProfileChanged()
                }
                is ZillitResult.Failure -> setState { copy(savingSchedule = false, toast = failure(saved)) }
            }
        }
    }

    // -- Notifications -------------------------------------------------------

    private fun openNotifications() {
        val repository = repository ?: return
        setState { copy(notifications = NotificationsDrawer(open = true, loading = true)) }
        launch {
            val result = repository.notificationSections()
            val allowed = toolNames()
            setState {
                if (!notifications.open) return@setState this
                when (result) {
                    is ZillitResult.Success -> copy(
                        notifications = notifications.copy(
                            loading = false,
                            sections = result.data.map { section -> section.onlyTools(allowed) },
                        ),
                    )
                    is ZillitResult.Failure -> copy(
                        notifications = notifications.copy(loading = false),
                        toast = failure(result),
                    )
                }
            }
        }
    }

    /**
     * Each switch moves at once and is put back if the server refuses —
     * the web leaves a refused switch where it was flipped, which then lies.
     */
    private fun switchSection(name: String, on: Boolean) = switch(
        apply = { sections -> sections.map { if (it.name == name) it.copy(enabled = on) else it } },
        call = { setNotification(name, on) },
    )

    private fun switchTool(name: String, on: Boolean) = switch(
        apply = { sections ->
            sections.map { section ->
                section.copy(tools = section.tools.map { if (it.name == name) it.copy(enabled = on) else it })
            }
        },
        call = { setNotification(name, on) },
    )

    private fun switchActivity(tool: String, activity: String, on: Boolean) = switch(
        apply = { sections ->
            sections.map { section ->
                section.copy(
                    tools = section.tools.map { row ->
                        if (row.name != tool) {
                            row
                        } else {
                            row.copy(
                                activities = row.activities.map {
                                    if (it.name == activity) it.copy(enabled = on) else it
                                },
                            )
                        }
                    },
                )
            }
        },
        call = { setActivity(tool, activity, on) },
    )

    private fun switch(
        apply: (List<NotificationSection>) -> List<NotificationSection>,
        call: suspend AppPreferencesRepository.() -> ZillitResult<String?>,
    ) {
        val repository = repository ?: return
        val before = currentState.notifications.sections
        setState { copy(notifications = notifications.copy(sections = apply(notifications.sections))) }
        launch {
            when (val saved = repository.call()) {
                is ZillitResult.Success -> setState { copy(toast = saved.data.toToast()) }
                is ZillitResult.Failure -> setState {
                    copy(notifications = notifications.copy(sections = before), toast = failure(saved))
                }
            }
        }
    }

    // -- Messages ------------------------------------------------------------

    private fun orderMessage(startMoved: Boolean): String =
        if (startMoved) str(S.desktop_start_after_end) else str(S.desktop_end_before_start)

    private fun success(text: String) = PreferenceToast(text)
    private fun error(text: String) = PreferenceToast(text, isError = true)
    private fun failure(result: ZillitResult.Failure) =
        error(result.error.localised())

    /** The server's own message key, read in the reader's language; nothing to say when it sent none. */
    private fun String?.toToast(): PreferenceToast? =
        this?.takeIf { it.isNotBlank() }?.let { success(it.localisedMessage()) }
}

/**
 * The tools this production has. An empty [allowed] is `project/tools` still
 * loading, not a production with no tools, so nothing is dropped then.
 */
private fun NotificationSection.onlyTools(allowed: Set<String>): NotificationSection =
    if (allowed.isEmpty()) this else copy(tools = tools.filter { it.name in allowed })

/** The web's `DAYS_OF_WEEK`, 0 = Sunday — the `day_of_week` the server stores. */
private val DAY_LABELS = listOf(S.sun, S.mon, S.tue, S.wed, S.thu, S.fri, S.sat)

internal val WEEK_DAYS: List<Int> = DAY_LABELS.indices.toList()

internal fun dayLabel(day: Int): String = str(DAY_LABELS[day.coerceIn(DAY_LABELS.indices)])

package com.zillit.desktop.feature.crewdood.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * What the model is built from — the four reads, in model terms.
 *
 * The repository turns wire into these, so [DoodCalc] has no JSON, no HTTP and
 * no clock in it and every rule in §2 of the handoff is unit-testable.
 */

/** A unit of the crew list. The DOOD has no units: they only carry department order. */
data class DoodCrewUnit(val departments: List<DoodCrewDepartment>)

/**
 * A department as the crew list lists it, in the admin's department priority.
 *
 * [aliases] is every value the department is known by — its id, its `_id`, its
 * identifier, its label key — so a deal can be matched to it whichever one it
 * carries. Which of those the crew list actually ships is still an open
 * question on the backend (handoff §5.6), hence all of them.
 */
data class DoodCrewDepartment(
    val name: String,
    val aliases: List<String>,
    val users: List<DoodCrewUser>,
)

/** A person on the crew list, in designation priority. */
data class DoodCrewUser(
    val userId: String,
    val name: String,
    val role: String,
    val picture: String? = null,
)

/**
 * A deal, as the DOOD reads one.
 *
 * Statuses are kept raw: only `active` and `deactivated` book anyone
 * ([DoodCalc.BOOKED_STATUSES]) and that filter is this client's, because the
 * endpoint takes no parameters (backend's ask).
 */
data class DoodDeal(
    val id: String,
    val status: String,
    val userId: String,
    val reference: String,
    val crewName: String,
    val customDesignation: String,
    /** Every value the deal names its designation by, in the web's precedence. */
    val designationRefs: List<String>,
    /** Every value the deal names its department by, in the web's precedence. */
    val departmentRefs: List<String>,
    /** `deal.start_date`, read as a UTC day. */
    val start: LocalDate?,
    /** `deal.end_date`, read as a UTC day; null is open-ended. */
    val end: LocalDate?,
    /** The last pay day, read as a UTC day — WF lands on this when it is earlier. */
    val lastPayDay: LocalDate?,
)

/** A department of the project's master list, with its designations. */
data class DoodMasterDepartment(
    val name: String,
    val aliases: List<String>,
    val designations: List<DoodMasterDesignation>,
)

data class DoodMasterDesignation(val name: String, val aliases: List<String>)

/** The four reads, as the view model holds them. */
data class DoodSources(
    val units: List<DoodCrewUnit> = emptyList(),
    val deals: List<DoodDeal> = emptyList(),
    val shootDays: List<LocalDate> = emptyList(),
    val directory: List<DoodMasterDepartment> = emptyList(),
    /** Null = nothing saved, or a read that failed: the model then uses [DoodCalendar.Default]. */
    val calendar: DoodCalendar? = null,
)

/**
 * Crew DOOD's own service seam.
 *
 * Four reads and one write, across three hosts: the crew list on `unitsapi`,
 * the deals and the project calendar on `dealmemoapi`, the shoot days on
 * `productionapi`, and the department master on the core host. Only
 * [roster] and [deals] are required — a failure in either is what shows the
 * retry state; the other three degrade to "no bands", "no master names" and
 * Saturday/Sunday off.
 */
interface CrewDoodRepository {

    /** `GET {units}crewlist/list` — the people and their order. */
    suspend fun roster(): ZillitResult<List<DoodCrewUnit>>

    /** `GET {dealMemo}deal-memo/deals` — every deal, any status, no parameters. */
    suspend fun deals(): ZillitResult<List<DoodDeal>>

    /** `GET {production}production/days` — the `production_dates` entry's days. */
    suspend fun shootDays(): ZillitResult<List<LocalDate>>

    /** `GET {core}departments?designations=true` — names for externals. */
    suspend fun directory(): ZillitResult<List<DoodMasterDepartment>>

    /**
     * `GET {dealMemo}deal-memo/crew-dood/calendar`.
     *
     * Null when the project has saved none, which the model draws as Saturday
     * and Sunday off.
     */
    suspend fun calendar(): ZillitResult<DoodCalendar?>

    /**
     * `PUT {dealMemo}deal-memo/crew-dood/calendar`.
     *
     * Answers what was saved, so the grid redraws without a second read, and
     * the server's own `message` for the toast.
     */
    suspend fun saveCalendar(calendar: DoodCalendar): ZillitResult<CalendarSaved>

    /**
     * A deal created, changed, activated or deactivated anywhere on the
     * project moves bars here — the app-wide `deal:*` events the web turns
     * into `ah:deal_memo:list`. Only the deals are refetched, in the
     * background, so a failed refresh keeps what is on screen.
     */
    val dealChanges: Flow<Unit>

    /**
     * A calendar saved by anyone on the project.
     *
     * The frame carries the calendar, so what arrives is applied in place with
     * no read — [CalendarPush.Saved], kept only when it is newer than the one
     * on screen ([DoodCalendar.isNewerThan]). A frame that does not look like
     * a calendar is [CalendarPush.Unknown]: read it instead of guessing.
     */
    val calendarPushes: Flow<CalendarPush>

    /**
     * Someone joined, left, was removed, was reordered or had their profile
     * changed: the crew list alone is stale. Already filtered to this project.
     */
    val crewChanges: Flow<Unit>
}

/** What a `crew_dood_calendar:updated` frame turned out to carry. */
sealed interface CalendarPush {
    /** The saved calendar itself, as the frame sent it. */
    data class Saved(val calendar: DoodCalendar) : CalendarPush

    /** A frame with no calendar in it — read the calendar rather than guess. */
    data object Unknown : CalendarPush
}

/** What a calendar save answers: the stored calendar, and the server's message. */
data class CalendarSaved(val calendar: DoodCalendar, val message: String)

/**
 * The DOOD workbook — `POST {dealMemo}deal-memo/deals/export/dood`, built by
 * the deal-memo service, landed by the host.
 *
 * Not the screen's own render: the service builds the file, and its rules
 * still differ from this screen until the backend aligns them (handoff §5.1).
 * The host is what knows about files, so this seam carries only the outcome:
 * how many deals the service left out ([CrewDoodExported.skipped], its
 * `X-Dood-Skipped` header), for the warning the toolbar shows.
 */
fun interface CrewDoodExport {
    suspend fun export(): ZillitResult<CrewDoodExported>

    companion object {
        /** A host with no file system — tests, and the render tests' screens. */
        val None: CrewDoodExport = CrewDoodExport { ZillitResult.Success(CrewDoodExported(skipped = 0)) }
    }
}

/** The outcome of one export: the file is already saved, this is only what to say about it. */
data class CrewDoodExported(val skipped: Int = 0)

package com.zillit.desktop.feature.crewdood.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.crewdood.domain.CalendarPush
import com.zillit.desktop.feature.crewdood.domain.CalendarSaved
import com.zillit.desktop.feature.crewdood.domain.CrewDoodRepository
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUnit
import com.zillit.desktop.feature.crewdood.domain.DoodDeal
import com.zillit.desktop.feature.crewdood.domain.DoodMasterDepartment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement

/**
 * Crew DOOD's reads, across the four hosts the web reads them from.
 *
 * | What | Host | Path |
 * |---|---|---|
 * | Crew list | `unitsapi` | `crewlist/list` |
 * | Deals, project calendar | `dealmemoapi` | `deal-memo/deals`, `deal-memo/crew-dood/calendar` |
 * | Shoot days | `productionapi` | `production/days` |
 * | Department master | core | `departments?designations=true` |
 *
 * Transcription notes:
 *
 *  - **`GET deals` takes no parameters** — the backend's ask; `active` and
 *    `deactivated` are filtered in [com.zillit.desktop.feature.crewdood.domain.DoodCalc].
 *    The service's own list query is capped at 50 rows, newest first, so a
 *    project with more deals than that loses its older crew until the
 *    endpoint returns them all (handoff §5.2).
 *  - **a 200 answering `status: 0` is a refusal** on these services, as
 *    everywhere else in this app, so each read checks the envelope;
 *  - **a calendar read that fails is not an error on this screen**: it
 *    resolves to `null`, which the model draws as Saturday and Sunday off.
 *    The route is live (verified 2026-10-15), so that fallback is no longer
 *    the common path.
 */
class CrewDoodRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** Null keeps the tool socket-less — tests, and hosts without a bus. */
    private val bus: SocketEventBus? = null,
    private val currentProjectId: () -> String? = { null },
    /** A label key's words; the catalogue's own resolver, so tests can be mute. */
    private val labelOf: (String) -> String = { it.localised() },
) : CrewDoodRepository {

    private val units = config.apiV2(ZillitService.Units)
    private val dealMemo = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"
    private val production = config.apiV2(ZillitService.PreAndProduction)
    private val core = config.apiV2(ZillitService.Core)

    override val dealChanges: Flow<Unit> = signals(DOOD_DEAL_EVENTS)

    override val calendarPushes: Flow<CalendarPush> =
        bus?.on(DOOD_CALENDAR_EVENT)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { message ->
                CrewDoodWire.calendarPush(message.payload)
                    ?.let { CalendarPush.Saved(it) }
                    ?: CalendarPush.Unknown
            }
            ?: emptyFlow()

    override val crewChanges: Flow<Unit> = signals(DOOD_CREW_EVENTS)

    /** Any of [events] that belongs to this project, as a bare trigger. */
    private fun signals(events: Set<SocketEventName>): Flow<Unit> =
        bus?.onAny(events)
            ?.filter { message -> message.payload.matchesProject(currentProjectId()) }
            ?.map { }
            ?: emptyFlow()

    override suspend fun roster(): ZillitResult<List<DoodCrewUnit>> =
        read("${units}crewlist/list").map { data -> CrewDoodWire.units(data, labelOf) }

    override suspend fun deals(): ZillitResult<List<DoodDeal>> =
        read("$dealMemo/deals").map { data -> CrewDoodWire.deals(data) }

    override suspend fun shootDays(): ZillitResult<List<LocalDate>> =
        read("${production}production/days").map { data -> CrewDoodWire.shootDays(data) }

    override suspend fun directory(): ZillitResult<List<DoodMasterDepartment>> =
        read("${core}departments", mapOf("designations" to "true"))
            .map { data -> CrewDoodWire.directory(data, labelOf) }

    override suspend fun calendar(): ZillitResult<DoodCalendar?> =
        read("$dealMemo/$CALENDAR_PATH_PREFIX").map { data -> CrewDoodWire.calendar(data) }

    override suspend fun saveCalendar(calendar: DoodCalendar): ZillitResult<CalendarSaved> {
        val body = CrewDoodWire.calendarBody(calendar)
        val outcome = apiClient.envelope(
            verb = HttpVerb.Put,
            url = "$dealMemo/$CALENDAR_PATH_PREFIX",
            module = RequestModule.ProjectUser,
            body = body,
        )
        return when (outcome) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success -> {
                val envelope = outcome.data
                if (envelope.status == REFUSED) {
                    ZillitResult.Failure(ZillitError.Validation(envelope.refusal()))
                } else {
                    // The grid redraws from what the server stored; a service
                    // that answers no body is taken at its word and the draft
                    // stands, which is what the web does.
                    ZillitResult.Success(
                        CalendarSaved(
                            calendar = CrewDoodWire.calendar(envelope.data) ?: calendar,
                            message = envelope.message?.localisedMessage().orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun read(
        url: String,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<JsonElement?> {
        val outcome = apiClient.envelope(
            verb = HttpVerb.Get,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = query,
        )
        return when (outcome) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success ->
                if (outcome.data.status == REFUSED) {
                    ZillitResult.Failure(ZillitError.Validation(outcome.data.refusal()))
                } else {
                    ZillitResult.Success(outcome.data.data)
                }
        }
    }

    /** The server's own words for a refusal — never this client's. */
    private fun ApiEnvelope.refusal(): String = message?.localisedMessage().orEmpty()

    private companion object {
        const val REFUSED = 0
        const val CALENDAR_PATH_PREFIX = "crew-dood/calendar"
    }
}

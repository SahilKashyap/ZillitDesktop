package com.zillit.desktop.feature.settings.preferences

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * App Preferences against the calls the web makes.
 *
 * | Card | Call |
 * |---|---|
 * | Default Home Unit | `PUT {core}user/profile {default_unit_id}` |
 * | Location On/Off | `PUT {core}device {location}`, then `PUT {core}user/profile {show_location}` |
 * | Update Location | `PUT {core}user/profile {location_tracking_mode, everyday_timing \| select_days}` |
 * | Manage Notifications | `GET`/`PUT {notification}device/notification` |
 *
 * The web reaches the profile through `profilesettingBase` and `projectBase`,
 * both the core host. Every write answers `{status, message}`, and a 200 with
 * `status: 0` is a refusal here, as the web's `status !== 1` check makes it.
 */
class AppPreferencesRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** Today, for the timestamps the schedule is sent as. */
    private val now: () -> Instant = { Clock.System.now() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : AppPreferencesRepository {

    private val core get() = config.apiV2(ZillitService.Core)
    private val notification get() = config.apiV2(ZillitService.Notification)

    override suspend fun profile(): ZillitResult<PreferencesProfile> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${core}user/profile",
            serializer = PreferencesProfileDto.serializer(),
            module = RequestModule.ProjectUser,
        ).map { it.toDomain(zone()) }

    override suspend fun setDefaultUnit(unitId: String): ZillitResult<String?> =
        putProfile(buildJsonObject { put("default_unit_id", unitId) })

    override suspend fun setLocationSharing(on: Boolean): ZillitResult<String?> =
        putProfile(buildJsonObject { put("show_location", on) })

    override suspend fun saveLocationSchedule(sharing: LocationSharing): ZillitResult<String?> {
        val tz = zone()
        val today = now().toLocalDateTime(tz).date
        fun stamp(minute: Int): Long =
            LocalDateTime(today.year, today.month, today.day, minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)
                .toInstant(tz)
                .toEpochMilliseconds()

        val body = buildJsonObject {
            put("location_tracking_mode", sharing.mode.wire)
            when (sharing.mode) {
                LocationMode.Everyday -> putJsonObject("everyday_timing") {
                    put("start_time", stamp(sharing.everyday.startMinute))
                    put("end_time", stamp(sharing.everyday.endMinute))
                }
                LocationMode.SelectDays -> putJsonArray("select_days") {
                    sharing.days.toSortedMap().forEach { (day, window) ->
                        addJsonObject {
                            put("day_of_week", day)
                            put("start_time", stamp(window.startMinute))
                            put("end_time", stamp(window.endMinute))
                        }
                    }
                }
            }
        }
        return putProfile(body)
    }

    override suspend fun reportLocation(lat: Double, lng: Double): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${core}device",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                putJsonObject("location") {
                    put("lat", lat)
                    put("long", lng)
                }
            },
        ).flatMap { it.accepted() }.map { }

    override suspend fun notificationSections(): ZillitResult<List<NotificationSection>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${notification}device/notification",
            serializer = ListSerializer(NotificationSectionDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun setNotification(name: String, enabled: Boolean): ZillitResult<String?> =
        putNotification(
            buildJsonObject {
                put("tool", name)
                put("enabled", enabled)
            },
        )

    override suspend fun setActivity(tool: String, activity: String, enabled: Boolean): ZillitResult<String?> =
        putNotification(
            buildJsonObject {
                put("tool", tool)
                putJsonArray("activities") {
                    addJsonObject {
                        put("activity", activity)
                        put("enabled", enabled)
                    }
                }
            },
        )

    private suspend fun putProfile(body: JsonObject): ZillitResult<String?> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${core}user/profile",
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { it.accepted() }

    private suspend fun putNotification(body: JsonObject): ZillitResult<String?> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "${notification}device/notification",
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { it.accepted() }

    private companion object {
        const val MINUTES_PER_HOUR = 60
        const val HTTP_OK = 200
    }

    /** The server's message on success; `status: 0` is a refusal carrying it. */
    private fun ApiEnvelope.accepted(): ZillitResult<String?> =
        if (status == 0) {
            ZillitResult.Failure(
                ZillitError.Http(
                    status = HTTP_OK,
                    serverMessage = message,
                    messageElements = messageElements.orEmpty(),
                ),
            )
        } else {
            ZillitResult.Success(message)
        }
}

/** The part of `GET user/profile` these cards read. */
@Serializable
internal data class PreferencesProfileDto(
    @SerialName("default_unit_id") val defaultUnitId: String? = null,
    @SerialName("show_location") val showLocation: Boolean? = null,
    @SerialName("location_tracking_mode") val mode: String? = null,
    @SerialName("everyday_timing") val everyday: TimingDto? = null,
    @SerialName("select_days") val days: List<DayTimingDto>? = null,
) {
    fun toDomain(zone: TimeZone): PreferencesProfile {
        val mode = LocationMode.of(mode)
        // The web's reading: Select Days only when the mode says so and there
        // are days to show; otherwise Every Day, with its stored window if any.
        val days = days.orEmpty()
            .mapNotNull { day -> day.day?.let { it to window(day.start, day.end, zone) } }
            .toMap()
        val selectDays = mode == LocationMode.SelectDays && days.isNotEmpty()
        return PreferencesProfile(
            defaultUnitId = defaultUnitId?.takeIf { it.isNotBlank() },
            location = LocationSharing(
                enabled = showLocation == true,
                mode = if (selectDays) LocationMode.SelectDays else LocationMode.Everyday,
                everyday = if (mode == LocationMode.Everyday && everyday != null) {
                    window(everyday.start, everyday.end, zone)
                } else {
                    TimeWindow()
                },
                days = if (selectDays) days else emptyMap(),
            ),
        )
    }
}

@Serializable
internal data class TimingDto(
    @SerialName("start_time") val start: Long? = null,
    @SerialName("end_time") val end: Long? = null,
)

@Serializable
internal data class DayTimingDto(
    @SerialName("day_of_week") val day: Int? = null,
    @SerialName("start_time") val start: Long? = null,
    @SerialName("end_time") val end: Long? = null,
)

/**
 * A stored window, as the time of day in [zone]; a missing end is the end of
 * the day, as the web reads it.
 *
 * `0`/`0` is the server's "never set", not midnight UTC: a profile nobody has
 * scheduled answers `{start_time: 0, end_time: 0}`, which read as stamps is
 * 05:30–05:30 in India. Android takes that pair as unset and shows the whole
 * day (`AppPreferencesPage.checkForStartTimeAndEndTime`); so does this.
 */
private fun window(start: Long?, end: Long?, zone: TimeZone): TimeWindow {
    if ((start ?: 0L) == 0L && (end ?: 0L) == 0L) return TimeWindow()
    return TimeWindow(
        startMinute = start?.let { minuteOfDay(it, zone) } ?: 0,
        endMinute = end?.let { minuteOfDay(it, zone) } ?: TimeWindow.LAST_MINUTE,
    )
}

private fun minuteOfDay(millis: Long, zone: TimeZone): Int =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let { it.hour * MINUTES_IN_HOUR + it.minute }

private const val MINUTES_IN_HOUR = 60

/** One row of `device/notification` — Android's `NotificationOnOffModel`. */
@Serializable
internal data class NotificationSectionDto(
    @SerialName("section_name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("data") val tools: List<NotificationToolDto>? = null,
) {
    fun toDomain(): NotificationSection? = name?.takeIf { it.isNotBlank() }?.let { key ->
        NotificationSection(
            name = key,
            enabled = enabled == true,
            tools = tools.orEmpty().mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class NotificationToolDto(
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("activities") val activities: List<NotificationActivityDto>? = null,
) {
    fun toDomain(): NotificationTool? = name?.takeIf { it.isNotBlank() }?.let { key ->
        NotificationTool(
            name = key,
            enabled = enabled == true,
            activities = activities.orEmpty().mapNotNull { activity ->
                activity.name?.takeIf { it.isNotBlank() }?.let { NotificationActivity(it, activity.enabled == true) }
            },
        )
    }
}

@Serializable
internal data class NotificationActivityDto(
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
)

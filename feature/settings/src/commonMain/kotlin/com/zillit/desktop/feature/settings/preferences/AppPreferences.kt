package com.zillit.desktop.feature.settings.preferences

import com.zillit.desktop.core.common.ZillitResult

/**
 * The web's App Preferences (`PermissionSetting.jsx`): which Home unit opens
 * first, which alerts arrive, and when this person's location is shared.
 *
 * Every one of them is the server's, not this computer's — they follow the
 * person to their phone and the web — which is why they sit apart from the
 * desktop's own switches further down the Settings page.
 */
interface AppPreferencesRepository {

    /** The profile fields these cards read back: `default_unit_id` and the location schedule. */
    suspend fun profile(): ZillitResult<PreferencesProfile>

    /** `PUT user/profile {default_unit_id}` — the web's `EditdefaultPermission`. */
    suspend fun setDefaultUnit(unitId: String): ZillitResult<String?>

    /** `PUT user/profile {show_location}`. */
    suspend fun setLocationSharing(on: Boolean): ZillitResult<String?>

    /** `PUT user/profile` with the mode and its windows, as the web's Update Location sends. */
    suspend fun saveLocationSchedule(sharing: LocationSharing): ZillitResult<String?>

    /**
     * `PUT device {location:{lat,long}}` — where this computer is now. The web
     * sends it before switching sharing on, so the first position the crew
     * sees is a real one rather than wherever the account was last seen.
     */
    suspend fun reportLocation(lat: Double, lng: Double): ZillitResult<Unit>

    /** `GET {notification}device/notification` — sections, their tools, and those tools' activities. */
    suspend fun notificationSections(): ZillitResult<List<NotificationSection>>

    /** `PUT {notification}device/notification {tool, enabled}` — a section or a tool. */
    suspend fun setNotification(name: String, enabled: Boolean): ZillitResult<String?>

    /**
     * `PUT … {tool, activities:[{activity, enabled}]}` — one activity under a
     * tool. The activity rides inside the array: a top-level `activity` mutes
     * the whole tool, and leaving `enabled` off the top keeps the tool as it is.
     */
    suspend fun setActivity(tool: String, activity: String, enabled: Boolean): ZillitResult<String?>
}

/** What the profile says about these cards. */
data class PreferencesProfile(
    val defaultUnitId: String? = null,
    val location: LocationSharing = LocationSharing(),
)

/** One Home unit the reader can land on. [name] is the server's label key. */
data class HomeUnitChoice(val id: String, val name: String)

/** `location_tracking_mode` — the web's `LOCATION_TRACKING_MODES`. */
enum class LocationMode(val wire: String) {
    Everyday("Everyday"),
    SelectDays("SelectDays"),
    ;

    companion object {
        fun of(wire: String?): LocationMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A stretch of a day, in minutes since local midnight.
 *
 * Minutes rather than the timestamps the wire carries: the web sends today's
 * date at the chosen time (`dayjs().valueOf()`) and reads back only the time
 * of day, so the date in the stamp is noise.
 */
data class TimeWindow(
    val startMinute: Int = 0,
    val endMinute: Int = LAST_MINUTE,
) {
    val isValid: Boolean get() = startMinute <= endMinute

    companion object {
        /** 11:59 PM — the web's `endOf('day')`, to the minute. */
        const val LAST_MINUTE = 23 * 60 + 59
    }
}

/**
 * When this person's position is shared with the production.
 *
 * [days] is keyed by the web's day numbers, 0 = Sunday … 6 = Saturday — the
 * `day_of_week` the server stores.
 */
data class LocationSharing(
    val enabled: Boolean = false,
    val mode: LocationMode = LocationMode.Everyday,
    val everyday: TimeWindow = TimeWindow(),
    val days: Map<Int, TimeWindow> = emptyMap(),
)

/** One activity under a tool — only Document Distribution has any today (ZL-19961). */
data class NotificationActivity(val name: String, val enabled: Boolean)

/** One tool's alerts. [name] is the label key the switch is saved under. */
data class NotificationTool(
    val name: String,
    val enabled: Boolean,
    val activities: List<NotificationActivity> = emptyList(),
)

/** A section of the Manage Notifications drawer — C&C, Email, Tools… */
data class NotificationSection(
    val name: String,
    val enabled: Boolean,
    val tools: List<NotificationTool> = emptyList(),
)

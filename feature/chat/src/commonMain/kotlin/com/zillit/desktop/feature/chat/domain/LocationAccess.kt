package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.database.UserLocation
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What pressing Location on a contact should do, decided before anything happens. */
sealed interface LocationAccess {
    /**
     * They share, they are inside their window: show this position.
     *
     * The coordinates rather than a link, because this client shows a place
     * on its own embedded map; [mapsUrl] is there for the hosts that have no
     * map and for the "Open in Maps" beside it.
     */
    data class Open(val lat: Double, val long: Double) : LocationAccess {
        val mapsUrl: String get() = "https://www.google.com/maps?q=$lat,$long"
    }

    /** They share, but not at this hour — the web refuses and says so. */
    data object OutsideWindow : LocationAccess

    /** Sharing is off, or nothing has been reported: offer to ask them to turn it on. */
    data object NotShared : LocationAccess
}

/**
 * Whether this person's position may be opened right now.
 *
 * Android's rule (`LocationTrackingViewExtensions.handleUserLocationTracking`),
 * which is the complete one — the web states the same gate in
 * `InfoSider.handleLocationRedirect` but omits the older `show_location_timing`
 * fallback and the "nothing scheduled means always" case.
 *
 * The one place this does NOT copy the web is the comparison itself. The web
 * tests `Date.now()` against the stored stamps whole, and those stamps carry
 * the date they were saved on (`dayjs().valueOf()`), so a schedule set
 * yesterday refuses every request made today. Android maps each stamp onto
 * today before comparing (`Extensions.getTimeCalendar`), which is plainly what
 * a daily window means and what this client's own Settings card already
 * assumes (`feature:settings`' `AppPreferencesRepositoryImpl.window`), so this
 * compares times of day.
 */
fun CrewContact.locationAccess(nowMillis: Long, zone: TimeZone): LocationAccess {
    val place = location?.takeIf { it.shared } ?: return LocationAccess.NotShared
    // 0/0 is the server's "never reported", not the Gulf of Guinea.
    val lat = place.latitude?.takeIf { it != 0.0 }
    val long = place.longitude?.takeIf { it != 0.0 }
    if (lat == null || long == null) return LocationAccess.NotShared
    return if (place.allowsViewingAt(nowMillis, zone)) {
        LocationAccess.Open(lat, long)
    } else {
        LocationAccess.OutsideWindow
    }
}

/** The web's `LOCATION_TRACKING_MODES.SELECT_DAYS`. */
private const val SELECT_DAYS = "SelectDays"

/**
 * Whether the schedule lets anyone look at [nowMillis].
 *
 * A blank mode with days listed is the select-day mode — older rows carry the
 * days without ever having been stamped with a mode, and Android reads them
 * that way rather than falling through to a window nobody set.
 */
private fun UserLocation.allowsViewingAt(nowMillis: Long, zone: TimeZone): Boolean {
    val byDay = mode.equals(SELECT_DAYS, ignoreCase = true) || (mode.isNullOrBlank() && days.isNotEmpty())
    if (byDay) {
        // Scheduled by day, with no days chosen: nothing restricts the hour.
        val today = days.firstOrNull { it.dayOfWeek == sundayFirstDay(nowMillis, zone) }
            ?: return days.isEmpty()
        return insideWindow(nowMillis, today.startMillis, today.endMillis, zone)
    }
    return insideWindow(nowMillis, everydayStart, everydayEnd, zone)
}

/**
 * `0`/`0` is the server's "never scheduled", so the whole day is open — the
 * same pair this client's Settings card reads as unset rather than as
 * midnight. One end alone is a half-written schedule and lets nobody in,
 * which is Android's reading.
 */
private fun insideWindow(nowMillis: Long, start: Long, end: Long, zone: TimeZone): Boolean {
    if (start == 0L && end == 0L) return true
    if (start == 0L || end == 0L) return false
    val now = millisOfDay(nowMillis, zone)
    return now > millisOfDay(start, zone) && now < millisOfDay(end, zone)
}

/** `day_of_week` as the server stores it: 0 = Sunday … 6 = Saturday. */
private fun sundayFirstDay(millis: Long, zone: TimeZone): Int =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date.dayOfWeek.isoDayNumber % DAYS_IN_WEEK

private fun millisOfDay(millis: Long, zone: TimeZone): Int =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
        ((it.hour * MINUTES_IN_HOUR + it.minute) * SECONDS_IN_MINUTE + it.second) * MILLIS_IN_SECOND +
            it.nanosecond / NANOS_IN_MILLI
    }

private const val DAYS_IN_WEEK = 7
private const val MINUTES_IN_HOUR = 60
private const val SECONDS_IN_MINUTE = 60
private const val MILLIS_IN_SECOND = 1000
private const val NANOS_IN_MILLI = 1_000_000

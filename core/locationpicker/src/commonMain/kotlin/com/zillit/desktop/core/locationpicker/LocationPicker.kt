package com.zillit.desktop.core.locationpicker

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A place the user picked: what it is called, where it is, and its coordinates.
 *
 * The union of the two shapes the web emits — box schedule's `PlacePicker`
 * hands back `{ formattedAddress, name, lat, lng }`
 * (boxScheduleV2/components/PlacePicker.jsx:107-115) and transportation's
 * `LocationPicker` hands back `{ address, lat, lng }`
 * (transportationHub/common/LocationPicker.jsx:101-103) — so one picker can
 * feed both kinds of call site.
 *
 * [name] is the Places result's own name when there was one, and otherwise the
 * first line of the address: a caller that shows a single line ("Aria Hotel")
 * gets something readable, and a caller that stores the whole address still
 * has it.
 *
 * Note for ride endpoints: they spell the longitude `long`, not `lng` — see
 * `toLongLat` (transportationHub/common/LocationPicker.jsx:209-212). That
 * rename belongs at the call site, not here.
 */
data class PickedLocation(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)

/**
 * The app-level service that opens the map picker.
 *
 * An interface rather than a composable because the map is a heavyweight,
 * platform-specific surface — on desktop it is embedded Chromium — and nothing
 * that merely wants a place should have to know that. Provided once at the app
 * root through [LocalLocationPicker]; a host that has no map (a test, a future
 * non-desktop target) simply provides nothing.
 */
interface LocationPicker {

    /** Opens the picker; returns null when the user cancels. */
    suspend fun pick(initial: PickedLocation? = null, title: String = str(S.desktop_pick_location)): PickedLocation?
}

/**
 * The picker in scope, or null where none is wired — the field then behaves as
 * plain text.
 *
 * `static` because the picker is installed once at the app root and never
 * changes: a dynamic local would add a read-tracking dependency to every field
 * that only ever answers the same instance.
 */
val LocalLocationPicker: ProvidableCompositionLocal<LocationPicker?> = staticCompositionLocalOf { null }

/**
 * The one line a picked place reads as: its name, then its address.
 *
 * Lives here rather than in each caller because four screens needed the
 * same sentence and wrote it four times; a place must read identically
 * whether it is a calendar location, a pickup address or a shared pin.
 */
fun PickedLocation.oneLine(): String = when {
    name.isBlank() -> address
    address.isBlank() -> name
    address.startsWith(name, ignoreCase = true) -> address
    else -> "$name, $address"
}

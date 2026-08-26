// Dialogs branch on the viewer's role and the trip's state; one composable per dialog.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.MapsLink
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.feature.transportation.domain.PermanentPassenger
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripPriority
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.ui.PermanentEditor
import com.zillit.desktop.feature.transportation.ui.TransportClock
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.ui.priorityTone
import com.zillit.desktop.feature.transportation.ui.statusTone

/** Every overlay the tool opens. */
@Composable
internal fun TransportDialogs(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    state.openTrip?.let { TripDialog(state, onEvent) }
    state.raise?.let { RaiseDialog(state, onEvent) }
    state.vehicleEditor?.let { VehicleDialog(state, onEvent) }
    state.assignDriverFor?.let { AssignDriverDialog(state, it, onEvent) }
    state.permanentEditor?.let { PermanentDialog(state, it, onEvent) }
    state.confirmDeleteVehicles?.let { ids ->
        ZillitDialogShell(
            title = "Delete vehicle",
            onDismiss = { onEvent(TransportEvent.CancelDeleteVehicles) },
            visible = true,
            actions = {
                ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelDeleteVehicles) },
                    variant = ButtonVariant.Tertiary)
                ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.ConfirmDeleteVehicles) },
                    variant = ButtonVariant.Danger, loading = state.busy)
            },
        ) {
            ZillitText(text = "Are you sure you want to delete ${ids.size} vehicle(s)?",
                style = ZillitTheme.typography.bodyMedium)
        }
    }
}

// Trip detail ----------------------------------------------------------------

@Composable
private fun TripDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val open = state.openTrip ?: return
    val trip = open.trip
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    val mine = trip.raisedBy == state.viewer.userId
    val driving = trip.driverId == state.viewer.userId
    ZillitDialogShell(
        title = "Pickup request",
        subtitle = "${trip.status.label} · ${trip.priority}",
        onDismiss = { onEvent(TransportEvent.CloseTrip) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Close", onClick = { onEvent(TransportEvent.CloseTrip) },
                variant = ButtonVariant.Tertiary)
            when {
                coordinator && trip.status == TripStatus.Pending -> {
                    ZillitButton(text = "Reject", onClick = { onEvent(TransportEvent.TripAct(TripAction.Reject)) },
                        variant = ButtonVariant.Danger, loading = open.busy)
                    ZillitButton(text = "Approve", onClick = { onEvent(TransportEvent.TripAct(TripAction.Approve)) },
                        loading = open.busy)
                }
                coordinator && trip.status == TripStatus.Assigned -> {
                    ZillitButton(
                        text = "Cancel trip",
                        onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) },
                        variant = ButtonVariant.Danger,
                        loading = open.busy,
                    )
                    ZillitButton(text = "Update", onClick = { onEvent(TransportEvent.TripAct(TripAction.Update)) },
                        loading = open.busy)
                }
                coordinator && trip.status == TripStatus.InProgress -> {
                    ZillitButton(text = "Update", onClick = { onEvent(TransportEvent.TripAct(TripAction.Update)) },
                        variant = ButtonVariant.Secondary, loading = open.busy)
                    ZillitButton(text = "Complete trip", onClick = { onEvent(TransportEvent.TripAct(TripAction.End)) },
                        loading = open.busy)
                }
                driving && trip.status == TripStatus.Assigned ->
                    ZillitButton(text = "Start trip", onClick = { onEvent(TransportEvent.TripAct(TripAction.Start)) },
                        loading = open.busy)
                driving && trip.status == TripStatus.InProgress ->
                    ZillitButton(text = "Complete trip", onClick = { onEvent(TransportEvent.TripAct(TripAction.End)) },
                        loading = open.busy)
                !coordinator && trip.status == TripStatus.Pending -> {
                    if (mine) {
                        ZillitButton(text = "Cancel trip",
                            onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) }, variant = ButtonVariant
                                .Danger, loading = open.busy)
                    }
                    ZillitButton(text = "Send reminder", onClick = { onEvent(TransportEvent.SendReminder) },
                        variant = ButtonVariant.Secondary, loading = state.busy)
                }
                !coordinator && trip.status == TripStatus.Assigned && mine ->
                    ZillitButton(text = "Cancel trip",
                        onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) }, variant = ButtonVariant
                            .Danger, loading = open.busy)
                else -> Unit
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitStatusPill(label = trip.status.label, tone = statusTone(trip.status))
                ZillitStatusPill(label = trip.priority.ifBlank { "—" }, tone = priorityTone(trip.priority))
                ZillitStatusPill(label = if (trip.mode == "self") "Self" else "For others", tone = StatusTone.Neutral)
            }
            ZillitText(text = "Raised by ${state.userName(trip.raisedBy)}", style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary)
            ZillitText(text = "PASSENGERS", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            trip.passengers.forEach { p ->
                Column(Modifier.padding(bottom = ZillitTheme.spacing.xs)) {
                    ZillitText(
                        text = p.name.ifBlank { state.userName(p.userId) } + " — " +
                            TransportClock.dateTime(p.pickupMs),
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                    )
                    ZillitText(
                        text = "From ${p.pickup.address.ifBlank { "—" }}  →  ${p.dropOff.address.ifBlank { "—" }}",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            if (trip.ccUsers.isNotEmpty()) {
                ZillitText(text = "CC: " + trip.ccUsers.joinToString { state.userName(it) },
                    style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
            val reassignable = trip.status != TripStatus.Completed && trip.status != TripStatus.Cancelled
            if (coordinator && reassignable) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = "Vehicle", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        VehiclePicker(state, open.vehicleId,
                            includeAll = trip.status != TripStatus.Pending) { onEvent(TransportEvent
                                .TripPick(vehicleId = it)) }
                    }
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = "Driver", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        DriverPicker(state, open.driverId,
                            forTrip = true) { onEvent(TransportEvent.TripPick(driverId = it)) }
                    }
                }
            } else {
                ZillitText(
                    text = "Driver ${state.userName(trip.driverId)} · ${state.vehicleLabel(trip.vehicleId)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (trip.startMs > 0 || trip.endMs > 0) {
                ZillitText(
                    text = "Started ${TransportClock.dateTime(trip.startMs)} · " +
                        "Ended ${TransportClock.dateTime(trip.endMs)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun VehiclePicker(state: TransportUiState, selected: String?, includeAll: Boolean, onPick: (String) -> Unit) {
    // Free vehicles for a new assignment; the current one stays pickable when editing.
    val options = state.vehicles.filter { includeAll || it.editable || it.id == selected }
    ZillitSelect(
        value = options.firstOrNull { it.id == selected },
        options = listOf<Vehicle?>(null) + options,
        onSelect = { it?.let { v -> onPick(v.id) } },
        label = { it?.let { v -> "${v.name} · ${v.number} (${v.seats})" } ?: "Pick a vehicle" },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DriverPicker(state: TransportUiState, selected: String?, forTrip: Boolean, onPick: (String) -> Unit) {
    // The web's raise/trip filter: not on a trip, not on a full-day allocation.
    val options = state.drivers.filter { !forTrip || it.userId == selected || (!it.isTripAssigned && !it.fullDayTrip) }
    ZillitSelect(
        value = options.firstOrNull { it.userId == selected },
        options = listOf<TransportUser?>(null) + options,
        onSelect = { it?.let { u -> onPick(u.userId) } },
        label = { it?.let { u -> "${u.fullName} — ${u.availability}" } ?: "Pick a driver" },
        modifier = Modifier.fillMaxWidth(),
    )
}

// Raise request --------------------------------------------------------------

@Composable
private fun RaiseDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.raise ?: return
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    ZillitDialogShell(
        title = "Raise pickup request",
        onDismiss = { onEvent(TransportEvent.CancelRaise) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelRaise) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Raise request", onClick = { onEvent(TransportEvent.SubmitRaise) },
                loading = editor.saving)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(FIELD)) {
                    ZillitText(text = "Priority", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    ZillitSelect(
                        value = editor.priority,
                        options = TripPriority.entries,
                        onSelect = { onEvent(TransportEvent.RaiseChanged(priority = it)) },
                        label = { it.wire },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitCheckbox(
                    checked = editor.selfAssign,
                    onCheckedChange = { onEvent(TransportEvent.RaiseChanged(selfAssign = it)) },
                    label = "I am travelling myself",
                )
            }
            ZillitText(text = "PASSENGERS", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            editor.passengers.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken,
                        ZillitTheme.shapes.medium).padding(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = "${p.name} — ${TransportClock.dateTime(p.pickupMs)}",
                            style = ZillitTheme.typography.bodyMedium)
                        ZillitText(
                            text = "From ${p.pickup.address.ifBlank { "—" }} → ${p.dropOff.address.ifBlank { "—" }}",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    ZillitButton(text = "Remove", onClick = { onEvent(TransportEvent.RemovePassenger(p.userId)) },
                        variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                }
            }
            val pe = editor.passenger
            val change = { updated: com.zillit.desktop.feature.transportation.ui
                .PassengerEditor -> onEvent(TransportEvent.RaiseChanged(passenger = updated)) }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = "Passenger", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    ZillitSelect(
                        value = state.passengerCandidates.firstOrNull { it.userId == pe.userId },
                        options = listOf<TransportUser?>(null) + state.passengerCandidates.filter { c -> editor
                            .passengers.none { it.userId == c.userId } },
                        onSelect = { change(pe.copy(userId = it?.userId.orEmpty())) },
                        label = { it?.fullName ?: "Pick someone" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(value = pe.dateYmd, onValueChange = { change(pe.copy(dateYmd = it)) }, label = "Date",
                    placeholder = "YYYY-MM-DD", modifier = Modifier.width(FIELD))
                ZillitTextField(value = pe.time, onValueChange = { change(pe.copy(time = it)) }, label = "Time",
                    placeholder = "HH:mm", modifier = Modifier.width(SMALL))
            }
            PlaceField(
                label = "Pickup address",
                address = pe.pickupAddress,
                lat = pe.pickupLat,
                lng = pe.pickupLng,
            ) { address, lat, lng ->
                change(pe.copy(pickupAddress = address, pickupLat = lat, pickupLng = lng))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom) {
                PlaceField(
                    label = "Drop-off address",
                    address = pe.dropAddress,
                    lat = pe.dropLat,
                    lng = pe.dropLng,
                    modifier = Modifier.weight(1f),
                ) { address, lat, lng ->
                    change(pe.copy(dropAddress = address, dropLat = lat, dropLng = lng))
                }
                ZillitButton(text = "Add passenger", onClick = { onEvent(TransportEvent.AddPassenger) },
                    variant = ButtonVariant.Secondary)
            }
            CcPicker(state, editor.ccUsers) { onEvent(TransportEvent.RaiseChanged(toggleCc = it)) }
            if (coordinator) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = "Vehicle", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        VehiclePicker(state, editor.vehicleId,
                            includeAll = false) { onEvent(TransportEvent.RaiseChanged(vehicleId = it)) }
                    }
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = "Driver", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                        DriverPicker(state, editor.driverId,
                            forTrip = true) { onEvent(TransportEvent.RaiseChanged(driverId = it)) }
                    }
                }
            }
        }
    }
}

/**
 * One end of a ride: its address, and the coordinates the server refuses to do
 * without — `POST request` answers 406 `trip_request_passengers_required` when
 * a passenger's pickup or drop-off carries `lat`/`long: null`, which
 * [com.zillit.desktop.feature.transportation.ui.TransportViewModel]'s
 * `passengerProblem` checks before the trip is ever sent.
 *
 * The web never has to ask: its field IS a map
 * (`transportationHub/common/LocationPicker.jsx`, whose `toLongLat` hands the
 * chosen place's coordinates to the payload). The map picker does the same here
 * — one pick fills the address and both numbers.
 *
 * Typing still works, and so do both fallbacks the desktop needed while it had
 * no map: a pasted Google Maps link fills the coordinates through
 * [MapsLink.parseLatLng], and the two small fields the trio used to show all
 * the time are still reachable. They open by themselves when no picker is
 * wired — offline, or a host without a maps key — so the only workflow that
 * ever existed here never becomes unreachable.
 */
@Composable
private fun PlaceField(
    label: String,
    address: String,
    lat: String,
    lng: String,
    modifier: Modifier = Modifier,
    onChange: (address: String, lat: String, lng: String) -> Unit,
) {
    var manual by remember { mutableStateOf(false) }
    val hasPicker = LocalLocationPicker.current != null
    val known = lat.isNotBlank() && lng.isNotBlank()
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitLocationField(
            text = address,
            onTextChange = { typed ->
                val at = MapsLink.parseLatLng(typed)
                if (at == null) onChange(typed, lat, lng)
                else onChange(typed, at.first.toString(), at.second.toString())
            },
            onPicked = { onChange(it.oneLine(), it.lat.toString(), it.lng.toString()) },
            label = label,
            // The coordinates read back as text once they are known, rather
            // than as two more inputs: they are a result here, not a question.
            helperText = if (known) "$lat, $lng" else "Pick on the map, or paste a Google Maps link",
            initial = pickedAt(address, lat, lng),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasPicker && !manual) {
            ZillitButton(
                text = "Enter coordinates",
                onClick = { manual = true },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        if (manual || !hasPicker) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = lat, onValueChange = { onChange(address, it, lng) }, label = "Lat",
                    modifier = Modifier.width(SMALL))
                ZillitTextField(value = lng, onValueChange = { onChange(address, lat, it) }, label = "Lng",
                    modifier = Modifier.width(SMALL))
                if (!hasPicker) {
                    ZillitText(text = "No map here — paste a Google Maps link or type the numbers",
                        style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

/** Where the map should open: where this place already is, when it has one. */
private fun pickedAt(address: String, lat: String, lng: String): PickedLocation? {
    val at = lat.trim().toDoubleOrNull() ?: return null
    val to = lng.trim().toDoubleOrNull() ?: return null
    return PickedLocation(name = "", address = address, lat = at, lng = to)
}

/** The one line a picked place reads as: its name, then its address. */

@Composable
private fun CcPicker(state: TransportUiState, chosen: List<String>, onToggle: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = "CC (optional)", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        if (chosen.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                chosen.forEach { id ->
                    ZillitStatusPill(label = state.userName(id), tone = StatusTone.Neutral,
                        modifier = Modifier.clickable { onToggle(id) })
                }
            }
        }
        ZillitSelect(
            value = null,
            options = listOf<TransportUser?>(null) + state.passengerCandidates.filter { it.userId !in chosen },
            onSelect = { it?.let { u -> onToggle(u.userId) } },
            label = { it?.fullName ?: "Add someone to CC…" },
            modifier = Modifier.width(DIALOG_HALF),
        )
    }
}

// Vehicle editor -------------------------------------------------------------

@Composable
private fun VehicleDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.vehicleEditor ?: return
    val colors = ZillitTheme.colors
    val change = { updated: com.zillit.desktop.feature.transportation.ui.VehicleEditor -> onEvent(TransportEvent
        .VehicleChanged(updated)) }
    ZillitDialogShell(
        title = if (editor.id == null) "Add vehicle" else "Edit vehicle",
        onDismiss = { onEvent(TransportEvent.CancelVehicle) },
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelVehicle) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Save", onClick = { onEvent(TransportEvent.SaveVehicle) }, loading = editor.saving)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.name, onValueChange = { change(editor.copy(name = it)) },
                    label = "Brand name", modifier = Modifier.weight(1f))
                ZillitTextField(value = editor.number,
                    onValueChange = { change(editor.copy(number = it
                        .uppercase())) }, label = "Vehicle number", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = "Type", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    ZillitSelect(
                        value = editor.type,
                        options = state.vehicleTypes.ifEmpty { listOf(editor.type) },
                        onSelect = { change(editor.copy(type = it)) },
                        label = { it.ifBlank { "Pick a type" } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(value = editor.seats,
                    onValueChange = { change(editor.copy(seats = it
                        .filter(Char::isDigit))) }, label = "Seats", modifier = Modifier.width(SMALL))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = editor.ownerName, onValueChange = { change(editor.copy(ownerName = it)) },
                    label = "Owner name", modifier = Modifier.weight(1f))
                ZillitTextField(value = editor.countryCode, onValueChange = { change(editor.copy(countryCode = it)) },
                    label = "Code", placeholder = "+44", modifier = Modifier.width(SMALL))
                ZillitTextField(value = editor.ownerContact,
                    onValueChange = { change(editor
                        .copy(ownerContact = it)) }, label = "Owner mobile", modifier = Modifier.width(FIELD))
            }
            ZillitTextField(value = editor.ownerAddress, onValueChange = { change(editor.copy(ownerAddress = it)) },
                label = "Owner address", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AssignDriverDialog(state: TransportUiState, vehicle: Vehicle, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    // The web's vehicle-list filter: drivers without a vehicle.
    val candidates = state.drivers.filter { it.vehicleId == null }
    ZillitDialogShell(
        title = "Assign a driver",
        subtitle = "${vehicle.name} · ${vehicle.number}",
        onDismiss = { onEvent(TransportEvent.CancelAssign) },
        visible = true,
        actions = { ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelAssign) },
            variant = ButtonVariant.Tertiary) },
    ) {
        if (candidates.isEmpty()) {
            ZillitText(text = "Every driver already has a vehicle.", style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            candidates.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, ZillitTheme.shapes.medium)
                        .clickable { onEvent(TransportEvent.PickDriver(user.userId)) }
                        .padding(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        ZillitText(text = user.fullName, style = ZillitTheme.typography.bodyMedium,
                            color = colors.textPrimary)
                        ZillitText(text = user.designation, style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted)
                    }
                    ZillitStatusPill(label = user.availability, tone = StatusTone.Neutral)
                }
            }
        }
    }
}

// Permanent editor -----------------------------------------------------------

@Composable
private fun PermanentDialog(state: TransportUiState, editor: PermanentEditor, onEvent: (TransportEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val change = { updated: PermanentEditor -> onEvent(TransportEvent.PermanentChanged(updated)) }
    ZillitDialogShell(
        title = if (editor.id == null) "New permanent allocation" else "Edit allocation",
        onDismiss = { onEvent(TransportEvent.CancelPermanent) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelPermanent) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Save as draft", onClick = { onEvent(TransportEvent.SavePermanent(asDraft = true)) },
                variant = ButtonVariant.Secondary, loading = editor.saving)
            ZillitButton(text = "Submit", onClick = { onEvent(TransportEvent.SavePermanent(asDraft = false)) },
                loading = editor.saving)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom) {
                ZillitTextField(value = editor.startYmd, onValueChange = { change(editor.copy(startYmd = it)) },
                    label = "Start date", placeholder = "YYYY-MM-DD", modifier = Modifier.width(FIELD))
                ZillitTextField(value = editor.endYmd, onValueChange = { change(editor.copy(endYmd = it)) },
                    label = "End date (optional)", placeholder = "YYYY-MM-DD", modifier = Modifier.width(FIELD))
                ZillitCheckbox(checked = editor.fullDay, onCheckedChange = { change(editor.copy(fullDay = it)) },
                    label = "Full day")
            }
            ZillitText(text = "PASSENGERS", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            if (editor.passengers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    editor.passengers.forEach { p ->
                        ZillitStatusPill(
                            label = p.name.ifBlank { state.userName(p.userId) },
                            tone = StatusTone.Neutral,
                            modifier = Modifier.clickable { change(editor.copy(passengers = editor.passengers
                                .filterNot { it.userId == p.userId })) },
                        )
                    }
                }
            }
            ZillitSelect(
                value = null,
                options = listOf<TransportUser?>(null) + state.passengerCandidates.filter { c -> editor.passengers
                    .none { it.userId == c.userId } },
                onSelect = { it?.let { u -> change(editor.copy(passengers = editor.passengers + PermanentPassenger(u
                    .userId, u.fullName))) } },
                label = { it?.fullName ?: "Add a passenger…" },
                modifier = Modifier.width(DIALOG_HALF),
            )
            CcPicker(state, editor.ccUsers) { id ->
                change(editor.copy(ccUsers = if (id in editor.ccUsers) editor.ccUsers - id else editor.ccUsers + id))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = "Vehicle", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    VehiclePicker(state, editor.vehicleId,
                        includeAll = editor.id != null) { change(editor.copy(vehicleId = it)) }
                }
                Column(Modifier.weight(1f)) {
                    ZillitText(text = "Driver", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    // The web's permanent filter: any accepted driver not on a full-day allocation.
                    val options = state.drivers.filter { it.userId == editor.driverId || !it.fullDayTrip }
                    ZillitSelect(
                        value = options.firstOrNull { it.userId == editor.driverId },
                        options = listOf<TransportUser?>(null) + options,
                        onSelect = { it?.let { u -> change(editor.copy(driverId = u.userId)) } },
                        label = { it?.let { u -> "${u.fullName} — ${u.availability}" } ?: "Pick a driver" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private val DIALOG_WIDE = 860.dp
private val DIALOG_HALF = 360.dp
private val FIELD = 180.dp
private val SMALL = 96.dp

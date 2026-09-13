// The pickup-request dialogs: raise, details, and the passenger form both share.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui.pages

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
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TripAction
import com.zillit.desktop.feature.transportation.domain.TripPassenger
import com.zillit.desktop.feature.transportation.domain.TripPriority
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.PassengerEditor
import com.zillit.desktop.feature.transportation.ui.PickPurpose
import com.zillit.desktop.feature.transportation.ui.TransportClock
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState

// Trip detail ----------------------------------------------------------------

@Composable
internal fun TripDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit, openLink: (String) -> Unit) {
    val open = state.openTrip ?: return
    val trip = open.trip
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    val mine = trip.raisedBy == state.viewer.userId
    val driving = trip.driverId == state.viewer.userId
    val editable = coordinator && trip.status.isOpen
    ZillitDialogShell(
        title = "Trip details",
        subtitle = "Raised by ${state.userName(trip.raisedBy)}",
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
                    ZillitButton(text = "Cancel trip", onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) },
                        variant = ButtonVariant.Danger, loading = open.busy)
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
                            onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) },
                            variant = ButtonVariant.Danger, loading = open.busy)
                    }
                    ZillitButton(text = "Send reminder", onClick = { onEvent(TransportEvent.SendReminder) },
                        loading = state.busy)
                }
                !coordinator && trip.status == TripStatus.Assigned && mine ->
                    ZillitButton(text = "Cancel trip", onClick = { onEvent(TransportEvent.TripAct(TripAction.Cancel)) },
                        variant = ButtonVariant.Danger, loading = open.busy)
                else -> Unit
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Block(title = "Request", action = {
                ZillitStatusPill(label = trip.status.label, tone = statusTone(trip.status))
            }) {
                DetailRow("Raised by", state.userName(trip.raisedBy))
                DetailRow("Priority", trip.priority.ifBlank { "—" })
                DetailRow("Pickup date", TransportClock.dateTime(trip.firstPickupMs))
                DetailRow("Passenger count", open.passengers.size.toString())
                if (trip.startMs > 0) DetailRow("Started", TransportClock.dateTime(trip.startMs))
                if (trip.endMs > 0) DetailRow("Ended", TransportClock.dateTime(trip.endMs))
                if (trip.status == TripStatus.InProgress && trip.driverId != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ZillitButton(text = "Track driver", onClick = { onEvent(TransportEvent.TrackTrip) },
                            variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Pin)
                    }
                }
            }
            if (editable) {
                SwitchRow("Self assign", "Add yourself as a passenger",
                    checked = open.passengers.any { it.userId == state.viewer.userId }) {
                    onEvent(TransportEvent.TripSelfAssign(it))
                }
            }
            Block(title = "Passengers", action = {
                if (editable) AddLink("Add passenger") { onEvent(TransportEvent.OpenPassenger(forOpenTrip = true)) }
            }) {
                if (open.passengers.isEmpty()) EmptyLine("Add at least one passenger")
                open.passengers.sortedBy { it.pickupMs }.forEach { p ->
                    PassengerRow(
                        state = state,
                        p = p,
                        openLink = openLink,
                        showDriverStatus = trip.status == TripStatus.InProgress,
                        onEdit = if (editable) {
                            { onEvent(TransportEvent.OpenPassenger(forOpenTrip = true, edit = p)) }
                        } else {
                            null
                        },
                        onRemove = if (editable) {
                            { onEvent(TransportEvent.TripRemovePassenger(p.userId)) }
                        } else {
                            null
                        },
                    )
                    ZillitDivider()
                }
            }
            if (open.passengers.isNotEmpty()) {
                Block(title = "CC users", action = {
                    if (editable) {
                        AddLink("Add CC user") { onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.TripCc)) }
                    }
                }) {
                    if (open.ccUsers.isEmpty()) EmptyLine("No CC users")
                    open.ccUsers.forEach { id ->
                        PersonRow(state, id, trailing = {
                            if (editable) RemoveButton({ onEvent(TransportEvent.TripRemoveCc(id)) })
                        })
                    }
                }
            }
            VehicleBlock(state, open.vehicleId, editable, AssignTarget.Trip, onEvent)
            DriverBlock(state, open.driverId, editable, AssignTarget.Trip, onEvent)
            if (open.confirm != null) {
                val cancelling = open.confirm == TripAction.Cancel
                ZillitNotice(
                    text = if (cancelling) "Cancel this trip? The driver and passengers will be told." else
                        "Reject this request? The person who raised it will be told.",
                    tone = StatusTone.Rejected,
                    action = {
                        ZillitButton(text = "No", onClick = { onEvent(TransportEvent.TripDismissConfirm) },
                            variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                        ZillitButton(text = "Yes", onClick = { onEvent(TransportEvent.TripAct(open.confirm)) },
                            variant = ButtonVariant.Danger, size = ButtonSize.Small, loading = open.busy)
                    },
                )
            }
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = colors.textMuted,
            modifier = Modifier.width(DETAIL_LABEL))
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
            if (subtitle != null) {
                ZillitText(text = subtitle, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        ZillitSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A passenger as the web's `PassengerItem`: name, from/to as map links, pickup time, driver status when running. */
@Composable
internal fun PassengerRow(
    state: TransportUiState,
    p: TripPassenger,
    openLink: (String) -> Unit,
    showDriverStatus: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val user = state.user(p.userId)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Face(user, p.name.ifBlank { state.userName(p.userId) }, size = FACE_SMALL)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = p.name.ifBlank { state.userName(p.userId) } + (user?.designationLabel?.takeIf { it.isNotBlank() }
                    ?.let { " · $it" } ?: ""),
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            PlaceLine("From", p.pickup.address, p.pickup.mapsUrl, openLink)
            PlaceLine("To", p.dropOff.address, p.dropOff.mapsUrl, openLink)
            ZillitText(text = "Pickup time: ${TransportClock.clockText(p.pickupMs).ifBlank { "—" }}",
                style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            if (showDriverStatus) {
                ZillitText(
                    text = "Driver status: ${p.driverStatus.ifBlank { "Not available" }.humanStatus()}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.accentText,
                )
            }
        }
        if (onEdit != null) ZillitIconButton(icon = ZillitIcons.Edit, contentDescription = "Edit passenger",
            onClick = onEdit, tint = colors.accent)
        if (onRemove != null) RemoveButton(onRemove, "Remove passenger")
    }
}

private fun String.humanStatus(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun PlaceLine(label: String, address: String, url: String?, openLink: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = "$label:", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        ZillitText(
            text = address.ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = if (url != null) colors.accentText else colors.textSecondary,
            modifier = if (url != null) Modifier.clickable { openLink(url) } else Modifier,
            maxLines = 1,
        )
    }
}

/** The web's "Vehicle details" block with its Assign/Update link. */
@Composable
internal fun VehicleBlock(
    state: TransportUiState,
    vehicleId: String?,
    editable: Boolean,
    target: AssignTarget,
    onEvent: (TransportEvent) -> Unit,
) {
    val vehicle = state.vehicle(vehicleId)
    Block(title = "Vehicle details", action = {
        if (editable) {
            AddLink(if (vehicle == null) "Assign vehicle" else "Update vehicle") {
                onEvent(TransportEvent.OpenVehiclePicker(target))
            }
        }
    }) {
        if (vehicle == null) EmptyLine("Vehicle not assigned yet") else VehicleRow(state, vehicle, forTrip = true)
    }
}

/** The web's "Driver details" block with its Assign/Update link. */
@Composable
internal fun DriverBlock(
    state: TransportUiState,
    driverId: String?,
    editable: Boolean,
    target: AssignTarget,
    onEvent: (TransportEvent) -> Unit,
) {
    val driver = state.user(driverId)
    Block(title = "Driver details", action = {
        if (editable) {
            AddLink(if (driver == null) "Assign driver" else "Update driver") {
                onEvent(TransportEvent.OpenDriverPicker(target))
            }
        }
    }) {
        if (driver == null) EmptyLine("Driver not assigned yet") else DriverRow(state, driver, onEvent, forTrip = true)
    }
}

// Raise request --------------------------------------------------------------

@Composable
internal fun RaiseDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit, openLink: (String) -> Unit) {
    val editor = state.raise ?: return
    val colors = ZillitTheme.colors
    val coordinator = state.viewer.isCoordinator
    val change = { updated: com.zillit.desktop.feature.transportation.ui.RaiseEditor ->
        onEvent(TransportEvent.RaiseChanged(updated))
    }
    ZillitDialogShell(
        title = "Raise pickup request",
        onDismiss = { onEvent(TransportEvent.CancelRaise) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelRaise) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = "Submit", onClick = { onEvent(TransportEvent.SubmitRaise) },
                loading = editor.saving, enabled = editor.passengers.isNotEmpty())
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitNotice(
                text = "Once raised, the request appears under Pickup requests, where it can be tracked and " +
                    "updated until the trip is complete.",
                tone = StatusTone.Pending,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Bottom) {
                ZillitDateField(value = editor.pickupYmd, onValueChange = { change(editor.copy(pickupYmd = it)) },
                    label = "Pickup date", modifier = Modifier.width(FIELD))
                Column(Modifier.width(FIELD)) {
                    ZillitText(text = "Priority", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                    ZillitSelect(
                        value = editor.priority,
                        options = TripPriority.entries,
                        onSelect = { change(editor.copy(priority = it)) },
                        label = { it.wire },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SwitchRow("Self assign", "Travel on this request yourself",
                checked = editor.passengers.any { it.userId == state.viewer.userId },
                enabled = editor.pickupYmd.isNotBlank()) { onEvent(TransportEvent.RaiseSelfAssign(it)) }
            Block(title = "Passengers", action = {
                AddLink("Add passenger") { onEvent(TransportEvent.OpenPassenger(forOpenTrip = false)) }
            }) {
                if (editor.passengers.isEmpty()) EmptyLine("Add at least one passenger")
                editor.passengers.forEach { p ->
                    PassengerRow(state, p, openLink,
                        onEdit = { onEvent(TransportEvent.OpenPassenger(forOpenTrip = false, edit = p)) },
                        onRemove = { onEvent(TransportEvent.RaiseRemovePassenger(p.userId)) })
                    ZillitDivider()
                }
            }
            Block(title = "CC users", action = {
                AddLink("Add CC user", enabled = editor.passengers.isNotEmpty()) {
                    onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.RaiseCc))
                }
            }) {
                if (editor.ccUsers.isEmpty()) EmptyLine("No CC users")
                editor.ccUsers.forEach { id ->
                    PersonRow(state, id, trailing = { RemoveButton({ onEvent(TransportEvent.RaiseRemoveCc(id)) }) })
                }
            }
            if (coordinator) {
                VehicleBlock(state, editor.vehicleId, editable = true, AssignTarget.Raise, onEvent)
                DriverBlock(state, editor.driverId, editable = true, AssignTarget.Raise, onEvent)
            }
        }
    }
}

// Add passenger --------------------------------------------------------------

/** The web's `AddPassengerModal`: who, where from, where to, and when. */
@Composable
internal fun PassengerDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val pe = state.passengerDialog ?: return
    val colors = ZillitTheme.colors
    val change = { updated: PassengerEditor -> onEvent(TransportEvent.PassengerChanged(updated)) }
    val taken = (if (pe.forOpenTrip) state.openTrip?.passengers else state.raise?.passengers).orEmpty()
        .map { it.userId }.toSet() - setOfNotNull(pe.editingUserId)
    val driverId = if (pe.forOpenTrip) state.openTrip?.driverId else state.raise?.driverId
    val candidates = state.passengerCandidates.filter { it.userId !in taken && it.userId != driverId }
    val me = state.me
    val fixed = pe.selfAssign || pe.isEdit
    val selectedUser = state.user(pe.userId)
    ZillitDialogShell(
        title = if (pe.isEdit) "Edit passenger" else "Add passenger",
        onDismiss = { onEvent(TransportEvent.CancelPassenger) },
        visible = true,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelPassenger) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = if (pe.isEdit) "Update" else "Add",
                onClick = { onEvent(TransportEvent.SubmitPassenger) })
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = "Passenger name", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            if (fixed) {
                ZillitTextField(
                    value = (selectedUser ?: me)?.let { "${it.fullName} (${it.designationLabel})" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ZillitSelect(
                    value = candidates.firstOrNull { it.userId == pe.userId },
                    options = listOf<TransportUser?>(null) + candidates,
                    onSelect = { change(pe.copy(userId = it?.userId.orEmpty())) },
                    label = { it?.let { u -> "${u.fullName} (${u.designationLabel})" } ?: "Pick a passenger" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PlaceField(label = "Pickup location", address = pe.pickupAddress, lat = pe.pickupLat,
                lng = pe.pickupLng) { address, lat, lng ->
                change(pe.copy(pickupAddress = address, pickupLat = lat, pickupLng = lng))
            }
            PlaceField(label = "Drop location", address = pe.dropAddress, lat = pe.dropLat,
                lng = pe.dropLng) { address, lat, lng ->
                change(pe.copy(dropAddress = address, dropLat = lat, dropLng = lng))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = pe.dateYmd, onValueChange = {}, readOnly = true, label = "Pickup date",
                    modifier = Modifier.width(FIELD))
                ZillitTextField(value = pe.time, onValueChange = { change(pe.copy(time = it)) }, label = "Pickup time",
                    placeholder = "HH:mm", modifier = Modifier.width(SMALL))
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
 * The web never has to ask: its field IS a map (`selectMap/map`), whose pick
 * hands the chosen place's coordinates to the payload. The map picker does
 * the same here — one pick fills the address and both numbers.
 *
 * Typing still works, and so do both fallbacks the desktop needed while it had
 * no map: a pasted Google Maps link fills the coordinates through
 * [MapsLink.parseLatLng], and the two small fields are still reachable. They
 * open by themselves when no picker is wired — offline, or a host without a
 * maps key — so the only workflow that ever existed here never becomes
 * unreachable.
 */
@Composable
internal fun PlaceField(
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
            ZillitButton(text = "Enter coordinates", onClick = { manual = true }, variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small)
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

private val DETAIL_LABEL = 140.dp

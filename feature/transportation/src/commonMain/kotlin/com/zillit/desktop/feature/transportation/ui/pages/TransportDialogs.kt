package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.transportation.ui.TransportConfirm
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState

/**
 * Every overlay the tool opens, in stacking order: a record, then the form
 * over it, then the picker over the form, then the question over everything.
 */
@Composable
internal fun TransportDialogs(
    state: TransportUiState,
    onEvent: (TransportEvent) -> Unit,
    openLink: (String) -> Unit = {},
) {
    state.openTrip?.let { TripDialog(state, onEvent, openLink) }
    state.raise?.let { RaiseDialog(state, onEvent, openLink) }
    state.vehicleDetails?.let { VehicleDetailsDialog(state, onEvent) }
    state.vehicleEditor?.let { VehicleDialog(state, onEvent) }
    state.permanentEditor?.let { PermanentDialog(state, onEvent) }
    state.driverDetails?.let { DriverDetailsDialog(state, onEvent) }
    state.tempDrivers?.let { TempDriversDialog(state, onEvent) }
    state.passengerDialog?.let { PassengerDialog(state, onEvent) }
    state.peoplePicker?.let { PeoplePickerDialog(state, onEvent) }
    state.vehiclePicker?.let { VehiclePickerDialog(state, it, onEvent) }
    state.driverPicker?.let { DriverPickerDialog(state, it, onEvent) }
    state.reminder?.let { ReminderDialog(state, onEvent) }
    state.confirm?.let { ConfirmOverlay(state, it, onEvent) }
}

@Composable
private fun ConfirmOverlay(state: TransportUiState, confirm: TransportConfirm, onEvent: (TransportEvent) -> Unit) {
    val cancel = { onEvent(TransportEvent.CancelConfirm) }
    val accept = { onEvent(TransportEvent.AcceptConfirm) }
    when (confirm) {
        is TransportConfirm.DeleteVehicles -> ConfirmDialog(
            title = "Confirm deletion",
            text = if (confirm.ids.size == 1) "Are you sure you want to delete this vehicle?"
            else "Are you sure you want to delete ${confirm.ids.size} vehicles?",
            confirmLabel = "Delete", onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.UnassignPermanent -> ConfirmDialog(
            title = "Unassign allocation",
            text = "End this permanent allocation? The driver and vehicle become available again.",
            confirmLabel = "Unassign", onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.DeletePermanent -> ConfirmDialog(
            title = "Delete draft",
            text = "Delete this draft allocation?",
            confirmLabel = "Delete", onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.ChangePrivateDriver -> ConfirmDialog(
            title = "Change driver",
            text = "This is the driver's own vehicle. Assigning another driver takes it out of their hands for this " +
                "trip. Continue?",
            confirmLabel = "Yes", onConfirm = accept, onCancel = cancel, danger = false,
        )
        is TransportConfirm.TempDriverVehicle -> ConfirmDialog(
            title = "Personal vehicle",
            text = "Does ${confirm.user.fullName} bring their own vehicle? You can add its details now.",
            confirmLabel = "Yes, add vehicle", cancelLabel = "No",
            onConfirm = accept, onCancel = { onEvent(TransportEvent.DeclineConfirm) }, onDismiss = cancel,
            danger = false, busy = state.busy,
        )
        is TransportConfirm.UnassignTempDriver -> ConfirmDialog(
            title = "Remove temporary driver",
            text = "${confirm.user.fullName} will no longer be offered as a driver, and their personal vehicle is " +
                "removed from the fleet.",
            confirmLabel = "Remove", onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
    }
}

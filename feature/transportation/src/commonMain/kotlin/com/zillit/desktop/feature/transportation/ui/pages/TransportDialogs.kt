package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
            title = str(S.desktop_email_confirm_deletion),
            text = if (confirm.ids.size == 1) str(S.desktop_transport_delete_vehicle_confirm)
            else str(S.desktop_transport_delete_vehicles_confirm, confirm.ids.size),
            confirmLabel = str(S.delete), onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.UnassignPermanent -> ConfirmDialog(
            title = str(S.desktop_transport_unassign_allocation),
            text = str(S.desktop_transport_unassign_allocation_confirm),
            confirmLabel = str(S.txt_unassign), onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.DeletePermanent -> ConfirmDialog(
            title = str(S.ah_delete_draft),
            text = str(S.desktop_transport_delete_draft_confirm),
            confirmLabel = str(S.delete), onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
        is TransportConfirm.ChangePrivateDriver -> ConfirmDialog(
            title = str(S.txt_change_driver),
            text = str(S.desktop_transport_change_driver_confirm),
            confirmLabel = str(S.yes), onConfirm = accept, onCancel = cancel, danger = false,
        )
        is TransportConfirm.TempDriverVehicle -> ConfirmDialog(
            title = str(S.desktop_transport_personal_vehicle),
            text = str(S.desktop_transport_personal_vehicle_question, confirm.user.fullName),
            confirmLabel = str(S.desktop_transport_yes_add_vehicle), cancelLabel = str(S.no),
            onConfirm = accept, onCancel = { onEvent(TransportEvent.DeclineConfirm) }, onDismiss = cancel,
            danger = false, busy = state.busy,
        )
        is TransportConfirm.UnassignTempDriver -> ConfirmDialog(
            title = str(S.desktop_transport_remove_temp_driver),
            text = str(S.desktop_transport_remove_temp_driver_confirm, confirm.user.fullName),
            confirmLabel = str(S.remove), onConfirm = accept, onCancel = cancel, busy = state.busy,
        )
    }
}

@file:Suppress("LongMethod", "TooManyFunctions") // One composable per dialog; a form is a long list of fields.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.ActiveCar
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.StopAction
import com.zillit.desktop.feature.transporthub.domain.Vehicle
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Every dialog the hub can have open.
 *
 * At most one editor is non-null at a time — the state holds them in separate
 * fields rather than one sealed "open dialog", because a half-filled form
 * must survive a list reload underneath it, and a single field would be
 * overwritten by whatever opened next.
 */
@Composable
internal fun HubDialogs(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    VehicleDialog(state, onEvent)
    DriverDialog(state, onEvent)
    DriverDetailsDialog(state, onEvent)
    RideDialog(state, onEvent)
    AllocationDialog(state, onEvent)
    SwapDialog(state, onEvent)
    StopDialog(state, onEvent)
    RidePassengerDialog(state, onEvent)
    ReasonDialog(state, onEvent)
    AssignDialog(state, onEvent)
    ActiveCarsDialog(state, onEvent)
    DraftPromptDialog(state, onEvent)
    ConfirmDialog(state, onEvent)
}

@Composable
private fun VehicleDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.vehicleEditor ?: return
    ZillitDialogShell(
        title = if (editor.isEdit) str(S.desktop_hub_edit_vehicle) else str(S.desktop_hub_new_vehicle),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseVehicleEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseVehicleEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveVehicle) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(name = it))) },
            label = str(S.name),
        )
        ZillitTextField(
            value = editor.licensePlate,
            onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(licensePlate = it))) },
            label = str(S.desktop_hub_plate),
        )
        ZillitSelect(
            value = editor.vehicleType,
            options = state.vehicleTypes.map { it.label },
            onSelect = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(vehicleType = it))) },
            label = { it.ifBlank { str(S.select) } },
            showInitials = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.capacity,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(capacity = it))) },
                label = str(S.hint_capacity),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.fuelType,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(fuelType = it))) },
                label = str(S.hint_fuel_type),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.make,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(make = it))) },
                label = str(S.av_make),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.model,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(model = it))) },
                label = str(S.av_model),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.year,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(year = it))) },
                label = str(S.av_year),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.color,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(color = it))) },
                label = str(S.av_color),
                modifier = Modifier.weight(1f),
            )
        }
        ZillitCheckbox(
            checked = editor.isPrivate,
            onCheckedChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(isPrivate = it))) },
            label = str(S.desktop_hub_production_owns_this),
        )
        // The owner's details are required together, and only for a hired
        // car: the server rejects a non-private vehicle without them.
        if (!editor.isPrivate) {
            ZillitTextField(
                value = editor.ownerName,
                onValueChange = { onEvent(HubEvent.VehicleEditorChanged(editor.copy(ownerName = it))) },
                label = str(S.desktop_transport_owner_name),
            )
            ZillitTextField(
                value = editor.ownerContactNumber,
                onValueChange = {
                    onEvent(HubEvent.VehicleEditorChanged(editor.copy(ownerContactNumber = it)))
                },
                label = str(S.desktop_hub_owner_contact),
            )
        }
        ZillitSectionLabel(text = str(S.desktop_hub_photos))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = str(
                    S.desktop_hub_photo_count,
                    editor.photos.size.toString(),
                    com.zillit.desktop.feature.transporthub.ui.VehicleEditor.MAX_PHOTOS.toString(),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_hub_add_photos),
                onClick = { onEvent(HubEvent.PickVehiclePhotos) },
                variant = ButtonVariant.Secondary,
            )
        }
        editor.photos.forEach { slot ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = slot.attachment.name.ifBlank { slot.attachment.media },
                    style = ZillitTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(HubEvent.RemoveVehiclePhoto(slot)) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }
    }
}

@Composable
private fun DriverDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.driverEditor ?: return
    // A project member already wired to a driver record is left out, so the
    // same person cannot be added twice.
    val taken = state.drivers.mapNotNull { it.userId }.toSet()
    val options = state.people.filterNot { it.userId in taken }
    ZillitDialogShell(
        title = str(S.driver_title_new),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseDriverEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseDriverEditor) },
                variant = ButtonVariant.Secondary,
            )
            // Saves now and files the record under the chase-list, phone and
            // licence blank. It is the only way to add a driver whose papers
            // have not arrived yet.
            ZillitButton(
                text = str(S.driver_ignore_and_create),
                onClick = { onEvent(HubEvent.SaveDriverIgnoringDetails) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveDriver) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitSelect(
            value = options.firstOrNull { it.userId == editor.userId } ?: ProjectPerson("", ""),
            options = options,
            onSelect = { onEvent(HubEvent.PickDriverUser(it.userId)) },
            label = { it.name.ifBlank { str(S.select) } },
            subtitle = { it.designation.takeIf { d -> d.isNotBlank() } },
        )
        ZillitTextField(
            value = editor.fullName,
            onValueChange = { onEvent(HubEvent.DriverEditorChanged(editor.copy(fullName = it))) },
            label = str(S.name),
        )
        ZillitTextField(
            value = editor.phone,
            onValueChange = { onEvent(HubEvent.DriverEditorChanged(editor.copy(phone = it))) },
            label = str(S.phone),
        )
        ZillitTextField(
            value = editor.licenseNumber,
            onValueChange = { onEvent(HubEvent.DriverEditorChanged(editor.copy(licenseNumber = it))) },
            label = str(S.desktop_hub_licence_number),
            helperText = str(S.desktop_hub_pending_details_hint),
        )
        ZillitCheckbox(
            checked = editor.isTempDriver,
            onCheckedChange = { onEvent(HubEvent.DriverEditorChanged(editor.copy(isTempDriver = it))) },
            label = str(S.desktop_hub_temp_driver),
            // Locked once a non-driver was picked: they can only be added
            // through the temporary path, so the box must stay ticked.
            enabled = !editor.tempDriverLocked,
        )
        if (editor.tempDriverLocked) {
            ZillitText(
                text = str(S.desktop_hub_temp_driver_locked),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        LicenceRow(
            frontName = editor.licenseFront?.attachment?.name,
            backName = editor.licenseBack?.attachment?.name,
            onPick = { isFront -> onEvent(HubEvent.PickLicencePhoto(isFront)) },
        )
    }
}

@Composable
private fun DriverDetailsDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.driverDetailsEditor ?: return
    ZillitDialogShell(
        title = str(S.drivers_review_complete),
        subtitle = editor.driverName.takeIf { it.isNotBlank() },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseDriverDetailsEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseDriverDetailsEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveDriverDetails) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitTextField(
            value = editor.phone,
            onValueChange = { onEvent(HubEvent.DriverDetailsEditorChanged(editor.copy(phone = it))) },
            label = str(S.phone),
        )
        ZillitTextField(
            value = editor.licenseNumber,
            onValueChange = {
                onEvent(HubEvent.DriverDetailsEditorChanged(editor.copy(licenseNumber = it)))
            },
            label = str(S.desktop_hub_licence_number),
        )
        LicenceRow(
            frontName = editor.licenseFront?.attachment?.name,
            backName = editor.licenseBack?.attachment?.name,
            onPick = { isFront -> onEvent(HubEvent.PickDetailsLicencePhoto(isFront)) },
        )
    }
}

@Composable
private fun LicenceRow(frontName: String?, backName: String?, onPick: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = frontName ?: str(S.desktop_hub_licence_front),
            onClick = { onPick(true) },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = backName ?: str(S.desktop_hub_licence_back),
            onClick = { onPick(false) },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RideDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.rideEditor ?: return
    ZillitDialogShell(
        title = if (editor.isEdit) str(S.desktop_hub_edit_ride) else str(S.desktop_hub_new_ride),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseRideEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseRideEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveRide) },
                loading = editor.saving,
            )
        },
    ) {
        LocationField(
            label = str(S.pickup),
            value = editor.pickup,
            onChange = { onEvent(HubEvent.RideEditorChanged(editor.copy(pickup = it))) },
        )
        LocationField(
            label = str(S.desktop_hub_dropoff),
            value = editor.dropoff,
            onChange = { onEvent(HubEvent.RideEditorChanged(editor.copy(dropoff = it))) },
        )
        ZillitSelect(
            value = editor.priority,
            options = RidePriority.entries,
            onSelect = { onEvent(HubEvent.RideEditorChanged(editor.copy(priority = it))) },
            label = { it.label() },
            showInitials = false,
        )
        PassengerPicker(
            people = state.people,
            chosen = editor.passengers.mapNotNull { it.userId },
            onToggle = { person ->
                val already = editor.passengers.any { it.userId == person.userId }
                val next = if (already) {
                    editor.passengers.filterNot { it.userId == person.userId }
                } else {
                    editor.passengers + com.zillit.desktop.feature.transporthub.domain.HubPassenger(
                        userId = person.userId,
                        name = person.name,
                    )
                }
                onEvent(HubEvent.RideEditorChanged(editor.copy(passengers = next)))
            },
        )
        ZillitSwitch(
            checked = editor.asSoonAsPossible,
            onCheckedChange = {
                onEvent(HubEvent.RideEditorChanged(editor.copy(asSoonAsPossible = it)))
            },
            label = str(S.desktop_hub_as_soon_as_possible),
        )
        ZillitTextField(
            value = editor.notes,
            onValueChange = { onEvent(HubEvent.RideEditorChanged(editor.copy(notes = it))) },
            label = str(S.notes),
            singleLine = false,
        )
    }
}

@Composable
private fun AllocationDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.allocationEditor ?: return
    ZillitDialogShell(
        title = if (editor.isEdit) {
            str(S.desktop_hub_edit_allocation)
        } else {
            str(S.desktop_hub_new_allocation)
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.RequestCloseAllocation) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.RequestCloseAllocation) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveAllocation) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitTextField(
            value = editor.title,
            onValueChange = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(title = it))) },
            label = str(S.title),
        )
        LocationField(
            label = str(S.pickup),
            value = editor.pickup,
            onChange = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(pickup = it))) },
        )
        LocationField(
            label = str(S.desktop_hub_dropoff),
            value = editor.dropoff,
            onChange = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(dropoff = it))) },
        )
        DriverPicker(
            drivers = state.availableDrivers.ifEmpty { state.drivers },
            chosenId = editor.driverId,
            onPick = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(driverId = it))) },
        )
        VehiclePicker(
            vehicles = state.availableVehicles.ifEmpty { state.vehicles },
            chosenId = editor.vehicleId,
            onPick = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(vehicleId = it))) },
        )
        DayPicker(
            chosen = editor.activeDays,
            onToggle = { day ->
                val next = if (day in editor.activeDays) {
                    editor.activeDays - day
                } else {
                    editor.activeDays + day
                }
                onEvent(
                    HubEvent.AllocationEditorChanged(
                        editor.copy(activeDays = HubDay.weekOrder.filter { it in next }),
                    ),
                )
            },
        )
        ZillitCheckbox(
            checked = editor.isFullDayTrip,
            onCheckedChange = {
                onEvent(HubEvent.AllocationEditorChanged(editor.copy(isFullDayTrip = it)))
            },
            label = str(S.desktop_hub_full_day_trip),
        )
        ZillitCheckbox(
            checked = editor.selfManage,
            onCheckedChange = {
                onEvent(HubEvent.AllocationEditorChanged(editor.copy(selfManage = it)))
            },
            label = str(S.desktop_hub_self_managed),
        )
        ZillitCheckbox(
            checked = editor.isExclusive,
            onCheckedChange = {
                onEvent(HubEvent.AllocationEditorChanged(editor.copy(isExclusive = it)))
            },
            label = str(S.desktop_hub_exclusive),
        )
        ZillitTextField(
            value = editor.notes,
            onValueChange = { onEvent(HubEvent.AllocationEditorChanged(editor.copy(notes = it))) },
            label = str(S.notes),
            singleLine = false,
        )
    }
}

@Composable
private fun SwapDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.swapEditor ?: return
    ZillitDialogShell(
        title = if (editor.isDriver) str(S.swap_driver) else str(S.desktop_hub_swap_vehicle),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseSwapEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseSwapEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SubmitSwap) },
                loading = editor.saving,
            )
        },
    ) {
        if (editor.isDriver) {
            DriverPicker(
                drivers = state.availableDrivers.ifEmpty { state.drivers },
                chosenId = editor.pickedId,
                onPick = { onEvent(HubEvent.SwapEditorChanged(editor.copy(pickedId = it))) },
            )
        } else {
            VehiclePicker(
                vehicles = state.availableVehicles.ifEmpty { state.vehicles },
                chosenId = editor.pickedId,
                onPick = { onEvent(HubEvent.SwapEditorChanged(editor.copy(pickedId = it))) },
            )
        }
        ZillitTextField(
            value = editor.reason,
            onValueChange = { onEvent(HubEvent.SwapEditorChanged(editor.copy(reason = it))) },
            label = str(S.reason),
        )
    }
}

@Composable
private fun StopDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.stopEditor ?: return
    ZillitDialogShell(
        title = str(S.recce_add_stop),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseStopEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseStopEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SubmitStop) },
                loading = editor.saving,
            )
        },
    ) {
        LocationField(
            label = str(S.address),
            value = editor.location,
            onChange = { onEvent(HubEvent.StopEditorChanged(editor.copy(location = it))) },
        )
        ZillitSelect(
            value = editor.action,
            options = StopAction.entries,
            onSelect = { onEvent(HubEvent.StopEditorChanged(editor.copy(action = it))) },
            label = { it.label() },
            showInitials = false,
        )
        ZillitTextField(
            value = editor.passengerName,
            onValueChange = { onEvent(HubEvent.StopEditorChanged(editor.copy(passengerName = it))) },
            label = str(S.desktop_hub_passenger_name),
        )
    }
}

@Composable
private fun RidePassengerDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.ridePassengerEditor ?: return
    ZillitDialogShell(
        title = str(S.desktop_hub_add_passenger),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseRidePassengerEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseRidePassengerEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SubmitRidePassenger) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitSelect(
            value = state.people.firstOrNull { it.userId == editor.userId } ?: ProjectPerson("", ""),
            options = state.people,
            onSelect = {
                onEvent(
                    HubEvent.RidePassengerEditorChanged(
                        editor.copy(userId = it.userId, name = it.name),
                    ),
                )
            },
            label = { it.name.ifBlank { str(S.txt_badge_guest) } },
        )
        ZillitTextField(
            value = editor.name,
            onValueChange = { onEvent(HubEvent.RidePassengerEditorChanged(editor.copy(name = it))) },
            label = str(S.name),
        )
        ZillitTextField(
            value = editor.contactNumber,
            onValueChange = {
                onEvent(HubEvent.RidePassengerEditorChanged(editor.copy(contactNumber = it)))
            },
            label = str(S.phone),
        )
        // Left blank, the passenger rides the request's own ends — so the row
        // the server stores is never left without a route.
        LocationField(
            label = str(S.pickup),
            value = editor.pickup,
            onChange = { onEvent(HubEvent.RidePassengerEditorChanged(editor.copy(pickup = it))) },
        )
        LocationField(
            label = str(S.desktop_hub_dropoff),
            value = editor.dropoff,
            onChange = { onEvent(HubEvent.RidePassengerEditorChanged(editor.copy(dropoff = it))) },
        )
    }
}

@Composable
private fun ReasonDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.reasonEditor ?: return
    ZillitDialogShell(
        title = if (editor.isCancel) {
            str(S.desktop_csync_cleaning_cancel_confirm)
        } else {
            str(S.desktop_decline_this_request)
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseReasonEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseReasonEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (editor.isCancel) str(S.confirm) else str(S.decline),
                onClick = { onEvent(HubEvent.SubmitReason) },
                variant = ButtonVariant.Danger,
                loading = editor.saving,
            )
        },
    ) {
        ZillitTextField(
            value = editor.reason,
            onValueChange = { onEvent(HubEvent.ReasonEditorChanged(editor.copy(reason = it))) },
            label = str(S.reason),
            singleLine = false,
        )
    }
}

@Composable
private fun AssignDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.assignEditor ?: return
    ZillitDialogShell(
        title = str(S.assign),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseAssignEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseAssignEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.assign),
                onClick = { onEvent(HubEvent.SubmitAssign) },
                loading = editor.saving,
            )
        },
    ) {
        DriverPicker(
            drivers = state.availableDrivers.ifEmpty { state.drivers },
            chosenId = editor.driverId,
            onPick = { onEvent(HubEvent.AssignEditorChanged(editor.copy(driverId = it))) },
        )
        VehiclePicker(
            vehicles = state.availableVehicles.ifEmpty { state.vehicles },
            chosenId = editor.vehicleId,
            onPick = { onEvent(HubEvent.AssignEditorChanged(editor.copy(vehicleId = it))) },
        )
    }
}

@Composable
private fun ActiveCarsDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    if (!state.showActiveCars) return
    ZillitDialogShell(
        title = str(S.desktop_hub_active_cars),
        visible = true,
        onDismiss = { onEvent(HubEvent.HideActiveCars) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(HubEvent.HideActiveCars) },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        if (state.activeCars.isEmpty()) {
            ZillitText(
                text = str(S.desktop_hub_no_active_cars),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
        state.activeCars.forEach { car -> ActiveCarRow(car, state) }
    }
}

@Composable
private fun ActiveCarRow(car: ActiveCar, state: HubUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = car.driverName.ifBlank { state.driverName(car.driverId) }.ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = car.status.label(), tone = car.status.tone(), dot = true)
        }
        HubRouteLine(car.pickup, car.dropoff)
    }
}

/**
 * Offered when a half-filled new allocation is being closed.
 *
 * Only on a create, and only when something was typed: a draft of an edit to
 * a live allocation means nothing, and a prompt over an untouched form is
 * just a second click to leave.
 */
@Composable
private fun DraftPromptDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.draftPrompt ?: return
    ZillitDialogShell(
        title = str(S.desktop_hub_keep_draft_title),
        visible = true,
        onDismiss = { onEvent(HubEvent.DiscardAllocation) },
        actions = {
            ZillitButton(
                text = str(S.ah_discard),
                onClick = { onEvent(HubEvent.DiscardAllocation) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.desktop_hub_keep_draft),
                onClick = { onEvent(HubEvent.SaveAllocationAsDraft) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_hub_keep_draft_body),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConfirmDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val confirm = state.confirm ?: return
    ZillitDialogShell(
        title = confirm.title,
        visible = true,
        onDismiss = { onEvent(HubEvent.DismissConfirm) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.DismissConfirm) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = confirm.confirmLabel,
                onClick = { onEvent(HubEvent.Confirmed(confirm.action)) },
                variant = if (confirm.isDestructive) ButtonVariant.Danger else ButtonVariant.Primary,
            )
        },
    ) {
        ZillitText(text = confirm.body, style = ZillitTheme.typography.bodyMedium)
    }
}

/*
 * The shared field types. A location is typed as an address here rather than
 * picked on a map: the hub has no map widget of its own, and the server takes
 * `{ address, lat, long }` with zeroes when no pin was dropped.
 */

@Composable
private fun LocationField(label: String, value: HubLocation, onChange: (HubLocation) -> Unit) {
    ZillitTextField(
        value = value.address,
        onValueChange = { onChange(value.copy(address = it)) },
        label = label,
    )
}

@Composable
private fun DriverPicker(drivers: List<Driver>, chosenId: String?, onPick: (String?) -> Unit) {
    ZillitSelect(
        value = drivers.firstOrNull { it.id == chosenId },
        options = listOf(null) + drivers,
        onSelect = { onPick(it?.id) },
        label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
        subtitle = { it?.designation?.takeIf { d -> d.isNotBlank() } },
    )
}

@Composable
private fun VehiclePicker(vehicles: List<Vehicle>, chosenId: String?, onPick: (String?) -> Unit) {
    ZillitSelect(
        value = vehicles.firstOrNull { it.id == chosenId },
        options = listOf(null) + vehicles,
        onSelect = { onPick(it?.id) },
        label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
        subtitle = { it?.specLine?.takeIf { spec -> spec.isNotBlank() } },
        showInitials = false,
    )
}

@Composable
private fun PassengerPicker(
    people: List<ProjectPerson>,
    chosen: List<String>,
    onToggle: (ProjectPerson) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(text = str(S.txt_passengers))
        people.take(PASSENGER_PICKER_LIMIT).forEach { person ->
            ZillitCheckbox(
                checked = person.userId in chosen,
                onCheckedChange = { onToggle(person) },
                label = person.name,
            )
        }
    }
}

@Composable
private fun DayPicker(chosen: List<HubDay>, onToggle: (HubDay) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSectionLabel(text = str(S.desktop_hub_active_days))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            HubDay.weekOrder.forEach { day ->
                ZillitCheckbox(
                    checked = day in chosen,
                    onCheckedChange = { onToggle(day) },
                    label = day.shortLabel,
                )
            }
        }
    }
}

private fun StopAction.label(): String = when (this) {
    StopAction.Pickup -> str(S.pickup)
    StopAction.Dropoff -> str(S.desktop_hub_dropoff)
    StopAction.Waypoint -> str(S.desktop_hub_waypoint)
}

/**
 * How many project members the checkbox list offers before it stops.
 *
 * A production can carry hundreds, and a list that long inside a dialog is
 * unusable either way — this keeps it from also being slow. A fuller picker
 * belongs with the rest of the create form in phase 2.
 */
private const val PASSENGER_PICKER_LIMIT = 40

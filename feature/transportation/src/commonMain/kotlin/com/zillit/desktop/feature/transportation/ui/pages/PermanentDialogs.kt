// The permanent-allocation editor — the web's AssignVehicle in its create, edit and view-details modes.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.feature.transportation.domain.PermanentStatus
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.PermanentEditor
import com.zillit.desktop.feature.transportation.ui.PickPurpose
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState

@Composable
internal fun PermanentDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.permanentEditor ?: return
    val coordinator = state.viewer.isCoordinator
    val change = { updated: PermanentEditor -> onEvent(TransportEvent.PermanentChanged(updated)) }
    val me = state.viewer.userId
    val rideIn = editor.passengers.any { it.userId == me }
    ZillitDialogShell(
        title = when {
            editor.isNew -> "Permanent allocation"
            editor.viewOnly -> "Allocation details"
            else -> "Edit allocation"
        },
        subtitle = if (editor.isNew) null else editor.status.label,
        onDismiss = { onEvent(TransportEvent.CancelPermanent) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = if (editor.viewOnly) "Close" else "Cancel",
                onClick = { onEvent(TransportEvent.CancelPermanent) }, variant = ButtonVariant.Tertiary)
            if (editor.isNew) {
                ZillitButton(text = "Save as draft",
                    onClick = { onEvent(TransportEvent.SavePermanent(asDraft = true)) },
                    variant = ButtonVariant.Secondary, loading = editor.saving)
                ZillitButton(text = "Submit", onClick = { onEvent(TransportEvent.SavePermanent(asDraft = false)) },
                    loading = editor.saving)
            } else {
                // A draft can be submitted from either mode; only an open editor can update in place.
                if (editor.status == PermanentStatus.Draft) {
                    ZillitButton(text = "Submit", onClick = { onEvent(TransportEvent.SavePermanent(asDraft = false)) },
                        variant = if (editor.viewOnly) ButtonVariant.Primary else ButtonVariant.Secondary,
                        loading = editor.saving)
                }
                if (!editor.viewOnly) {
                    ZillitButton(text = "Update",
                        onClick = { onEvent(TransportEvent.SavePermanent(asDraft = editor.status == PermanentStatus
                            .Draft)) },
                        loading = editor.saving)
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitDateField(value = editor.startYmd, onValueChange = { change(editor.copy(startYmd = it)) },
                    label = "Start date", modifier = Modifier.width(FIELD), enabled = editor.peopleEditable)
                ZillitDateField(value = editor.endYmd, onValueChange = { change(editor.copy(endYmd = it)) },
                    label = "End date (optional)", modifier = Modifier.width(FIELD), enabled = !editor.viewOnly)
            }
            SwitchRow("Full day", "The driver and vehicle stay with these passengers all day",
                checked = editor.fullDay, enabled = !editor.viewOnly) { change(editor.copy(fullDay = it)) }
            if (editor.isNew) {
                SwitchRow("Self assign", "Ride in this allocation yourself", checked = rideIn) {
                    onEvent(TransportEvent.PermanentSelfAssign(it))
                }
            }
            if (!editor.isNew && !coordinator) {
                SwitchRow(
                    "Self-manage driver",
                    "Direct the driver yourself instead of through the transport office",
                    checked = editor.selfManage,
                    enabled = rideIn && editor.status != PermanentStatus.Completed && !state.busy,
                ) { onEvent(TransportEvent.SelfManage(it)) }
            }
            Block(title = "Passengers", action = {
                if (editor.peopleEditable) {
                    AddLink("Add passenger") {
                        onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.PermanentPassengers))
                    }
                }
            }) {
                if (editor.passengers.isEmpty()) EmptyLine("Add at least one passenger")
                editor.passengers.forEach { p ->
                    PersonRow(state, p.userId, trailing = {
                        if (editor.peopleEditable) {
                            RemoveButton({ onEvent(TransportEvent.PermanentRemovePassenger(p.userId)) })
                        }
                    })
                    ZillitDivider()
                }
            }
            Block(title = "CC users", action = {
                if (editor.peopleEditable) {
                    AddLink("Add CC user") { onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.PermanentCc)) }
                }
            }) {
                if (editor.ccUsers.isEmpty()) EmptyLine("No CC users")
                editor.ccUsers.forEach { id ->
                    PersonRow(state, id, trailing = {
                        if (editor.peopleEditable) RemoveButton({ onEvent(TransportEvent.PermanentRemoveCc(id)) })
                    })
                }
            }
            VehicleBlock(state, editor.vehicleId, editable = !editor.viewOnly, AssignTarget.Permanent, onEvent)
            DriverBlock(state, editor.driverId, editable = !editor.viewOnly, AssignTarget.Permanent, onEvent)
        }
    }
}

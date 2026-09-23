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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
            editor.isNew -> str(S.txt_permanent_allocation)
            editor.viewOnly -> str(S.desktop_transport_allocation_details)
            else -> str(S.desktop_transport_edit_allocation)
        },
        subtitle = if (editor.isNew) null else editor.status.label,
        onDismiss = { onEvent(TransportEvent.CancelPermanent) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = if (editor.viewOnly) str(S.close) else str(S.cancel),
                onClick = { onEvent(TransportEvent.CancelPermanent) }, variant = ButtonVariant.Tertiary)
            if (editor.isNew) {
                ZillitButton(text = str(S.txt_save_as_draft),
                    onClick = { onEvent(TransportEvent.SavePermanent(asDraft = true)) },
                    variant = ButtonVariant.Secondary, loading = editor.saving)
                ZillitButton(text = str(S.submit), onClick = { onEvent(TransportEvent.SavePermanent(asDraft = false)) },
                    loading = editor.saving)
            } else {
                // A draft can be submitted from either mode; only an open editor can update in place.
                if (editor.status == PermanentStatus.Draft) {
                    ZillitButton(text = str(S.submit),
                        onClick = { onEvent(TransportEvent.SavePermanent(asDraft = false)) },
                        variant = if (editor.viewOnly) ButtonVariant.Primary else ButtonVariant.Secondary,
                        loading = editor.saving)
                }
                if (!editor.viewOnly) {
                    ZillitButton(text = str(S.update),
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
                    label = str(S.start_date), modifier = Modifier.width(FIELD), enabled = editor.peopleEditable)
                ZillitDateField(value = editor.endYmd, onValueChange = { change(editor.copy(endYmd = it)) },
                    label = str(S.end_date_optional), modifier = Modifier.width(FIELD), enabled = !editor.viewOnly)
            }
            SwitchRow(str(S.full_day), str(S.desktop_transport_full_day_hint),
                checked = editor.fullDay, enabled = !editor.viewOnly) { change(editor.copy(fullDay = it)) }
            if (editor.isNew) {
                SwitchRow(str(S.txt_self_assign), str(S.desktop_transport_ride_in_allocation), checked = rideIn) {
                    onEvent(TransportEvent.PermanentSelfAssign(it))
                }
            }
            if (!editor.isNew && !coordinator) {
                SwitchRow(
                    str(S.desktop_transport_self_manage_driver),
                    str(S.desktop_transport_self_manage_hint),
                    checked = editor.selfManage,
                    enabled = rideIn && editor.status != PermanentStatus.Completed && !state.busy,
                ) { onEvent(TransportEvent.SelfManage(it)) }
            }
            Block(title = str(S.txt_passengers), action = {
                if (editor.peopleEditable) {
                    AddLink(str(S.txt_add_passenger)) {
                        onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.PermanentPassengers))
                    }
                }
            }) {
                if (editor.passengers.isEmpty()) EmptyLine(str(S.desktop_transport_add_at_least_one_passenger))
                editor.passengers.forEach { p ->
                    PersonRow(state, p.userId, trailing = {
                        if (editor.peopleEditable) {
                            RemoveButton({ onEvent(TransportEvent.PermanentRemovePassenger(p.userId)) })
                        }
                    })
                    ZillitDivider()
                }
            }
            Block(title = str(S.txt_cc_users), action = {
                if (editor.peopleEditable) {
                    AddLink(str(S.desktop_transport_add_cc_user)) {
                        onEvent(TransportEvent.OpenPeoplePicker(PickPurpose.PermanentCc))
                    }
                }
            }) {
                if (editor.ccUsers.isEmpty()) EmptyLine(str(S.desktop_transport_no_cc_users))
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

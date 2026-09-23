// Fill in details (web's FillInDetailsModal, both roles), the document reminder, and temporary drivers.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transportation.domain.DriverDetailsUpdate
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.DriverDetailsEditor
import com.zillit.desktop.feature.transportation.ui.TempDriverSource
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState

/**
 * Fill in details, as a dialog: the coordinator opening a driver's record.
 * The driver's own copy is a page ([DriverDetailsBody] inside the section).
 */
@Composable
internal fun DriverDetailsDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.driverDetails ?: return
    if (editor.self) return
    val user = state.user(editor.userId)
    ZillitDialogShell(
        title = user?.fullName ?: str(S.driver),
        subtitle = user?.designationLabel,
        onDismiss = { onEvent(TransportEvent.CloseDriverDetails) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = str(S.close), onClick = { onEvent(TransportEvent.CloseDriverDetails) },
                variant = ButtonVariant.Tertiary)
            if (user?.isTempDriver == true && state.viewer.isCoordinator) {
                ZillitButton(text = str(S.txt_unassign), onClick = { onEvent(TransportEvent.UnassignTempDriver(user)) },
                    variant = ButtonVariant.Danger, loading = state.busy)
            }
            if (state.viewer.isCoordinator) {
                ZillitButton(text = str(S.update), onClick = { onEvent(TransportEvent.SaveDriverDetails) },
                    loading = editor.saving)
            }
        },
    ) {
        DriverDetailsBody(state, editor, onEvent)
    }
}

/** The form itself — shared by the coordinator's dialog and the driver's own page. */
@Composable
internal fun DriverDetailsBody(
    state: TransportUiState,
    editor: DriverDetailsEditor,
    onEvent: (TransportEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val user = state.user(editor.userId)
    val coordinator = state.viewer.isCoordinator && !editor.self
    val change = { updated: DriverDetailsEditor -> onEvent(TransportEvent.DriverDetailsChanged(updated)) }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        if (editor.self && user?.tripReminderMessage?.isNotBlank() == true) {
            // ZL-15575: what the coordinator asked for, until the driver uploads it.
            ZillitNotice(text = str(S.desktop_transport_coordinator_requested, user.tripReminderMessage),
                tone = StatusTone.Rejected)
        }
        ZillitTextField(value = user?.fullName.orEmpty(), onValueChange = {}, label = str(S.name), readOnly = true,
            modifier = Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            CountryCodeField(state.countries, editor.countryCode, Modifier.width(COUNTRY), enabled = editor.self) {
                change(editor.copy(countryCode = it))
            }
            ZillitTextField(
                value = editor.phone,
                onValueChange = { change(editor.copy(phone = it.filter(Char::isDigit))) },
                label = str(S.phone),
                modifier = Modifier.weight(1f),
                enabled = editor.self,
            )
        }
        ZillitTextField(value = editor.address, onValueChange = { change(editor.copy(address = it)) },
            label = str(S.address), modifier = Modifier.fillMaxWidth(), enabled = editor.self)
        if (editor.available != null) {
            Column {
                ZillitText(text = str(S.status), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                ZillitSelect(
                    value = editor.available,
                    options = listOf(true, false),
                    onSelect = { change(editor.copy(available = it)) },
                    label = { if (it) str(S.available) else str(S.unavailable) },
                    modifier = Modifier.width(FIELD),
                )
            }
        }
        val vehicle = state.vehicle(editor.vehicleId)
        Block(title = str(S.txt_vehicle_details), action = {
            if (coordinator && vehicle?.isPrivate != true) {
                AddLink(if (vehicle == null) str(S.desktop_transport_assign_vehicle_link)
                    else str(S.desktop_transport_update_vehicle)) {
                    onEvent(TransportEvent.OpenVehiclePicker(AssignTarget.DriverDetails))
                }
            }
        }) {
            if (vehicle == null) EmptyLine(str(S.txt_vehicle_not_assigned))
            else VehicleRow(state, vehicle, forTrip = true)
        }
        Block(title = str(S.tv_licence), action = {
            if (coordinator && editor.licencePictures.size < 2) {
                ZillitButton(text = str(S.txt_request_licence),
                    onClick = { onEvent(TransportEvent.LicenceReminder(editor.userId)) },
                    variant = ButtonVariant.Tertiary, size = ButtonSize.Small, enabled = !state.busy)
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                LicenceSlot(state, str(S.txt_front_view), editor.licenceFront, editor.self, editor.uploading,
                    Modifier.weight(1f)) { onEvent(TransportEvent.UploadLicence(back = false)) }
                LicenceSlot(state, str(S.txt_back_view), editor.licenceBack, editor.self, editor.uploading,
                    Modifier.weight(1f)) { onEvent(TransportEvent.UploadLicence(back = true)) }
            }
        }
        Block(title = str(S.txt_documents), action = {
            if (coordinator) {
                ZillitButton(text = str(S.txt_request_documents),
                    onClick = { onEvent(TransportEvent.OpenDocumentReminder(editor.userId)) },
                    variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            }
            if (editor.self) {
                AddLink(if (editor.uploading) str(S.ah_uploading) else str(S.upload_document),
                    enabled = !editor.uploading && editor.documents.size < DriverDetailsUpdate.DOCUMENTS_MAX) {
                    onEvent(TransportEvent.UploadDocuments)
                }
            }
        }) {
            if (editor.documents.isEmpty()) {
                EmptyLine(
                    if (editor.self) str(S.desktop_transport_no_documents_yet, DriverDetailsUpdate.DOCUMENTS_MAX)
                    else str(S.desktop_transport_no_documents_submitted),
                )
            } else {
                MediaStrip(state, editor.documents,
                    onRemove = if (editor.self) { { onEvent(TransportEvent.RemoveDocument(it)) } } else null)
            }
        }
    }
}

/** One side of the licence: the picture, or the drop-well that asks for it. */
@Composable
private fun LicenceSlot(
    state: TransportUiState,
    label: String,
    media: StoredMedia?,
    editable: Boolean,
    uploading: Boolean,
    modifier: Modifier,
    onUpload: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        if (media != null) {
            StoredPicture(state, media, Modifier.fillMaxWidth().height(LICENCE_HEIGHT))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LICENCE_HEIGHT)
                    .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .then(if (editable && !uploading) Modifier.clickable(onClick = onUpload) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitIcon(ZillitIcons.Photo, tint = colors.textMuted)
                    ZillitText(
                        text = if (editable) str(S.desktop_transport_click_to_upload_licence, label)
                            else str(S.desktop_transport_not_submitted),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
        if (editable && media != null) {
            ZillitButton(text = if (uploading) str(S.ah_uploading) else str(S.replace), onClick = onUpload,
                variant = ButtonVariant.Tertiary, size = ButtonSize.Small, enabled = !uploading)
        }
    }
}

/** The web's `DocumentReminderMessageModal` (ZL-15575): name the documents, then send. */
@Composable
internal fun ReminderDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val reminder = state.reminder ?: return
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.txt_driver_upload_document_title),
        subtitle = state.userName(reminder.userId),
        onDismiss = { onEvent(TransportEvent.CancelReminder) },
        visible = true,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(TransportEvent.CancelReminder) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = str(S.txt_send_reminder), onClick = { onEvent(TransportEvent.SendDocumentReminder) },
                loading = reminder.sending, enabled = reminder.message.isNotBlank())
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = str(S.desktop_transport_reminder_documents_hint),
                style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitTextField(
                value = reminder.message,
                onValueChange = { onEvent(TransportEvent.ReminderChanged(it)) },
                placeholder = str(S.desktop_transport_reminder_placeholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = MESSAGE_HEIGHT),
            )
        }
    }
}

/** The web's `AssignTemporaryDriversModal` → `AssignNewDriver`: a switch per crew member. */
@Composable
internal fun TempDriversDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val dialog = state.tempDrivers ?: return
    val rows = state.tempDriverRows(dialog)
    ZillitDialogShell(
        title = str(S.desktop_transport_temporary_drivers),
        subtitle = str(S.desktop_transport_temp_drivers_subtitle),
        onDismiss = { onEvent(TransportEvent.CloseTempDrivers) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(text = str(S.done_text), onClick = { onEvent(TransportEvent.CloseTempDrivers) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                TempDriverSource.entries.forEach { source ->
                    ZillitChoiceChip(
                        label = source.label,
                        selected = dialog.source == source,
                        onClick = { onEvent(TransportEvent.TempDriversChanged(dialog.copy(source = source))) },
                    )
                }
            }
            ZillitSearchField(
                value = dialog.query,
                onValueChange = { onEvent(TransportEvent.TempDriversChanged(dialog.copy(query = it))) },
                placeholder = str(S.invitees_search_users),
                modifier = Modifier.fillMaxWidth(),
            )
            if (rows.isEmpty()) {
                EmptyLine(str(S.desktop_transport_nobody_here))
            } else {
                LazyColumn(Modifier.heightIn(max = LIST_MAX)) {
                    items(rows, key = { it.userId }) { user ->
                        PersonRow(state, user.userId, trailing = {
                            ZillitSwitch(
                                checked = user.isTempDriver,
                                onCheckedChange = { onEvent(TransportEvent.ToggleTempDriver(user, it)) },
                                enabled = !state.busy,
                            )
                        })
                        ZillitDivider()
                    }
                }
            }
        }
    }
}

private val COUNTRY = 220.dp
private val LICENCE_HEIGHT = 180.dp
private val MESSAGE_HEIGHT = 96.dp

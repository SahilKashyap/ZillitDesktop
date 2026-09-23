// The vehicle form (web's VehicleForm) and the vehicle drawer (web's VehicleDetailsDrawer).
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transportation.domain.DialCountry
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.VehicleDraft
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.LocalTransportSlots
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.ui.VehicleEditor
import kotlinx.coroutines.launch

@Composable
internal fun VehicleDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val editor = state.vehicleEditor ?: return
    val colors = ZillitTheme.colors
    val change = { updated: VehicleEditor -> onEvent(TransportEvent.VehicleChanged(updated)) }
    val tempDriver = editor.forTempDriverId != null
    ZillitDialogShell(
        title = when {
            tempDriver -> str(S.desktop_transport_add_users_vehicle, state.userName(editor.forTempDriverId))
            editor.id == null -> str(S.add_vehicle)
            else -> str(S.desktop_transport_edit_vehicle)
        },
        onDismiss = { onEvent(TransportEvent.CancelVehicle) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(TransportEvent.CancelVehicle) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = if (editor.id == null) str(S.add_vehicle) else str(S.update),
                onClick = { onEvent(TransportEvent.SaveVehicle) }, loading = editor.saving,
                enabled = !editor.uploading)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel(str(S.txt_vehicle_details))
            ZillitTextField(value = editor.name, onValueChange = { change(editor.copy(name = it)) },
                label = str(S.txt_vehicle_name), modifier = Modifier.fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitTextField(value = editor.number,
                    onValueChange = { change(editor.copy(number = it.uppercase())) }, label = str(S.txt_vehicle_number),
                    modifier = Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    ZillitText(text = str(S.txt_vehicle_type), style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted)
                    ZillitSelect(
                        value = editor.type,
                        options = state.vehicleTypes.ifEmpty { listOf(editor.type) },
                        onSelect = { change(editor.copy(type = it)) },
                        label = { it.ifBlank { str(S.desktop_transport_pick_a_type) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(value = editor.seats,
                    onValueChange = { change(editor.copy(seats = it.filter(Char::isDigit))) },
                        label = str(S.txt_vehicle_seats),
                    modifier = Modifier.width(SMALL))
            }
            Block(title = str(S.txt_vehicle_image), action = {
                AddLink(if (editor.uploading) str(S.ah_uploading) else str(S.txt_vehicle_upload),
                    enabled = !editor.uploading && editor.attachments.size < VehicleDraft.IMAGES_MAX) {
                    onEvent(TransportEvent.AddVehicleImages)
                }
            }) {
                if (editor.attachments.isEmpty()) {
                    EmptyLine(str(S.desktop_transport_no_images_yet, VehicleDraft.IMAGES_MAX))
                } else {
                    MediaStrip(state, editor.attachments) { onEvent(TransportEvent.RemoveVehicleImage(it)) }
                }
            }
            ZillitSectionLabel(str(S.txt_owner_details))
            ZillitTextField(value = editor.ownerName, onValueChange = { change(editor.copy(ownerName = it)) },
                label = str(S.name), modifier = Modifier.fillMaxWidth(), readOnly = tempDriver)
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                CountryCodeField(state.countries, editor.countryCode, Modifier.width(COUNTRY)) {
                    change(editor.copy(countryCode = it))
                }
                ZillitTextField(value = editor.ownerContact,
                    onValueChange = { change(editor.copy(ownerContact = it.filter(Char::isDigit))) },
                    label = str(S.dm_step2_mobile_hint), modifier = Modifier.weight(1f))
            }
            ZillitTextField(value = editor.ownerAddress, onValueChange = { change(editor.copy(ownerAddress = it)) },
                label = str(S.address), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A dialling-code select backed by the preset list; typing is the fallback when the list is empty. */
@Composable
internal fun CountryCodeField(
    countries: List<DialCountry>,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    if (countries.isEmpty()) {
        ZillitTextField(value = value, onValueChange = onChange, label = str(S.dm_loanout_country_code),
            placeholder = "+44",
            modifier = modifier, enabled = enabled)
        return
    }
    Column(modifier) {
        ZillitText(text = str(S.dm_loanout_country_code), style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted)
        val options = listOf<DialCountry?>(null) + countries
        ZillitSelect(
            value = countries.firstOrNull { it.dialCode == value },
            options = options,
            onSelect = { onChange(it?.dialCode.orEmpty()) },
            label = { it?.label ?: value.ifBlank { str(S.desktop_transport_select_country_code) } },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}

/** Thumbnails side by side, each with its remove button. */
@Composable
internal fun MediaStrip(
    state: TransportUiState,
    items: List<StoredMedia>,
    onRemove: ((StoredMedia) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items.forEach { media -> MediaThumb(state, media, onRemove?.let { remove -> { remove(media) } }) }
    }
}

/** One stored file: a picture, or a document card that opens through the host. */
@Composable
internal fun MediaThumb(
    state: TransportUiState,
    media: StoredMedia,
    onRemove: (() -> Unit)?,
    size: Dp = THUMB_LARGE,
) {
    val colors = ZillitTheme.colors
    val slots = LocalTransportSlots.current
    val scope = rememberCoroutineScope()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            if (media.isImage) {
                StoredPicture(state, media, Modifier.size(size))
            } else {
                Box(
                    modifier = Modifier.size(size),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitButton(text = media.extension.uppercase().ifBlank { "FILE" },
                        onClick = { scope.launch { slots.openDocument?.invoke(media) } },
                        variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.File)
                }
            }
            if (onRemove != null) {
                ZillitIconButton(icon = ZillitIcons.Close, contentDescription = str(S.remove), onClick = onRemove,
                    tint = colors.danger, filled = true, size = REMOVE_SIZE)
            }
        }
        ZillitText(
            text = media.displayName.let { if (it.length > NAME_MAX) it.take(NAME_MAX) + "…" else it },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

/** The web's `VehicleDetailsDrawer`: pictures, the two description tables, and the driver block. */
@Composable
internal fun VehicleDetailsDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val details = state.vehicleDetails ?: return
    val vehicle = state.vehicle(details.vehicleId) ?: return
    val colors = ZillitTheme.colors
    val driverId = details.pendingDriverId ?: vehicle.driverId
    val driver = state.user(driverId)
    ZillitDialogShell(
        title = str(S.txt_vehicle_details),
        subtitle = vehicle.label,
        onDismiss = { onEvent(TransportEvent.CloseVehicle) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = str(S.close), onClick = { onEvent(TransportEvent.CloseVehicle) },
                variant = ButtonVariant.Tertiary)
            if (vehicle.editable) {
                ZillitButton(text = str(S.edit), onClick = { onEvent(TransportEvent.EditVehicle(vehicle)) },
                    variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Edit)
            }
            if (vehicle.deletable) {
                ZillitButton(text = str(S.delete), onClick = { onEvent(TransportEvent.DeleteVehicle(vehicle)) },
                    variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Trash)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (vehicle.attachments.isNotEmpty()) MediaStrip(state, vehicle.attachments, onRemove = null)
            Block(title = str(S.txt_vehicle_details), action = {
                ZillitStatusPill(label = vehicle.allocation.label, tone = allocationTone(vehicle.allocation))
            }) {
                DetailRow(str(S.desktop_transport_brand_name), vehicle.name.ifBlank { "—" })
                DetailRow(str(S.txt_vehicle_number), vehicle.number.ifBlank { "—" })
                DetailRow(str(S.txt_vehicle_type), vehicle.type.ifBlank { "—" })
                DetailRow(str(S.txt_vehicle_capacity), vehicle.seats.takeIf { it > 0 }?.toString() ?: "—")
            }
            val hasOwner = listOf(vehicle.ownerName, vehicle.ownerContact, vehicle.ownerAddress).any { it.isNotBlank() }
            if (hasOwner) {
                Block(title = str(S.txt_owner_details)) {
                    if (vehicle.ownerName.isNotBlank()) DetailRow(str(S.desktop_transport_owner_name),
                        vehicle.ownerName)
                    if (vehicle.ownerContact.isNotBlank()) {
                        DetailRow(str(S.desktop_transport_owner_number),
                            listOf(vehicle.countryCode, vehicle.ownerContact)
                            .filter { it.isNotBlank() }.joinToString(" "))
                    }
                    if (vehicle.ownerAddress.isNotBlank()) DetailRow(str(S.desktop_transport_owner_address),
                        vehicle.ownerAddress)
                }
            }
            Block(title = str(S.txt_driver_details), action = {
                if (vehicle.editable && !vehicle.isPrivate) {
                    AddLink(if (driver == null) str(S.txt_assign_driver)
                        else str(S.desktop_transport_update_driver)) {
                        onEvent(TransportEvent.OpenDriverPicker(AssignTarget.VehicleDetails))
                    }
                }
            }) {
                if (driver == null) {
                    EmptyLine(str(S.txt_driver_not_assigned))
                } else {
                    DriverRow(state, driver, onEvent, forTrip = true)
                }
                if (details.pendingDriverId != null && details.pendingDriverId != vehicle.driverId) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ZillitButton(text = str(S.submit), onClick = { onEvent(TransportEvent.SubmitVehicleDriver) },
                            loading = details.busy)
                    }
                }
            }
            if (!vehicle.editable) {
                ZillitText(
                    text = str(S.desktop_transport_assigned_vehicle_locked),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

private val COUNTRY = 220.dp
private val THUMB_LARGE = 88.dp
private val REMOVE_SIZE = 22.dp
private const val NAME_MAX = 16

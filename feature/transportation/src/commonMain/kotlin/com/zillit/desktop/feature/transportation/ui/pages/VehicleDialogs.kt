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
            tempDriver -> "Add ${state.userName(editor.forTempDriverId)}'s vehicle"
            editor.id == null -> "Add vehicle"
            else -> "Edit vehicle"
        },
        onDismiss = { onEvent(TransportEvent.CancelVehicle) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Cancel", onClick = { onEvent(TransportEvent.CancelVehicle) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = if (editor.id == null) "Add vehicle" else "Update",
                onClick = { onEvent(TransportEvent.SaveVehicle) }, loading = editor.saving,
                enabled = !editor.uploading)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSectionLabel("Vehicle details")
            ZillitTextField(value = editor.name, onValueChange = { change(editor.copy(name = it)) },
                label = "Vehicle brand name", modifier = Modifier.fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                ZillitTextField(value = editor.number,
                    onValueChange = { change(editor.copy(number = it.uppercase())) }, label = "Vehicle number",
                    modifier = Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    ZillitText(text = "Vehicle type", style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted)
                    ZillitSelect(
                        value = editor.type,
                        options = state.vehicleTypes.ifEmpty { listOf(editor.type) },
                        onSelect = { change(editor.copy(type = it)) },
                        label = { it.ifBlank { "Pick a type" } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(value = editor.seats,
                    onValueChange = { change(editor.copy(seats = it.filter(Char::isDigit))) }, label = "Seats",
                    modifier = Modifier.width(SMALL))
            }
            Block(title = "Vehicle images", action = {
                AddLink(if (editor.uploading) "Uploading…" else "Upload images",
                    enabled = !editor.uploading && editor.attachments.size < VehicleDraft.IMAGES_MAX) {
                    onEvent(TransportEvent.AddVehicleImages)
                }
            }) {
                if (editor.attachments.isEmpty()) {
                    EmptyLine("No images yet — up to ${VehicleDraft.IMAGES_MAX}")
                } else {
                    MediaStrip(state, editor.attachments) { onEvent(TransportEvent.RemoveVehicleImage(it)) }
                }
            }
            ZillitSectionLabel("Owner details")
            ZillitTextField(value = editor.ownerName, onValueChange = { change(editor.copy(ownerName = it)) },
                label = "Name", modifier = Modifier.fillMaxWidth(), readOnly = tempDriver)
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                CountryCodeField(state.countries, editor.countryCode, Modifier.width(COUNTRY)) {
                    change(editor.copy(countryCode = it))
                }
                ZillitTextField(value = editor.ownerContact,
                    onValueChange = { change(editor.copy(ownerContact = it.filter(Char::isDigit))) },
                    label = "Mobile number", modifier = Modifier.weight(1f))
            }
            ZillitTextField(value = editor.ownerAddress, onValueChange = { change(editor.copy(ownerAddress = it)) },
                label = "Address", modifier = Modifier.fillMaxWidth())
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
        ZillitTextField(value = value, onValueChange = onChange, label = "Country code", placeholder = "+44",
            modifier = modifier, enabled = enabled)
        return
    }
    Column(modifier) {
        ZillitText(text = "Country code", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        val options = listOf<DialCountry?>(null) + countries
        ZillitSelect(
            value = countries.firstOrNull { it.dialCode == value },
            options = options,
            onSelect = { onChange(it?.dialCode.orEmpty()) },
            label = { it?.label ?: value.ifBlank { "Select country code" } },
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
                ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Remove", onClick = onRemove,
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
        title = "Vehicle details",
        subtitle = vehicle.label,
        onDismiss = { onEvent(TransportEvent.CloseVehicle) },
        visible = true,
        width = DIALOG_WIDE,
        actions = {
            ZillitButton(text = "Close", onClick = { onEvent(TransportEvent.CloseVehicle) },
                variant = ButtonVariant.Tertiary)
            if (vehicle.editable) {
                ZillitButton(text = "Edit", onClick = { onEvent(TransportEvent.EditVehicle(vehicle)) },
                    variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Edit)
            }
            if (vehicle.deletable) {
                ZillitButton(text = "Delete", onClick = { onEvent(TransportEvent.DeleteVehicle(vehicle)) },
                    variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Trash)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (vehicle.attachments.isNotEmpty()) MediaStrip(state, vehicle.attachments, onRemove = null)
            Block(title = "Vehicle details", action = {
                ZillitStatusPill(label = vehicle.allocation.label, tone = allocationTone(vehicle.allocation))
            }) {
                DetailRow("Brand name", vehicle.name.ifBlank { "—" })
                DetailRow("Vehicle number", vehicle.number.ifBlank { "—" })
                DetailRow("Vehicle type", vehicle.type.ifBlank { "—" })
                DetailRow("Seating capacity", vehicle.seats.takeIf { it > 0 }?.toString() ?: "—")
            }
            val hasOwner = listOf(vehicle.ownerName, vehicle.ownerContact, vehicle.ownerAddress).any { it.isNotBlank() }
            if (hasOwner) {
                Block(title = "Owner details") {
                    if (vehicle.ownerName.isNotBlank()) DetailRow("Owner name", vehicle.ownerName)
                    if (vehicle.ownerContact.isNotBlank()) {
                        DetailRow("Owner number", listOf(vehicle.countryCode, vehicle.ownerContact)
                            .filter { it.isNotBlank() }.joinToString(" "))
                    }
                    if (vehicle.ownerAddress.isNotBlank()) DetailRow("Owner address", vehicle.ownerAddress)
                }
            }
            Block(title = "Driver details", action = {
                if (vehicle.editable && !vehicle.isPrivate) {
                    AddLink(if (driver == null) "Assign driver" else "Update driver") {
                        onEvent(TransportEvent.OpenDriverPicker(AssignTarget.VehicleDetails))
                    }
                }
            }) {
                if (driver == null) {
                    EmptyLine("Driver not assigned yet")
                } else {
                    DriverRow(state, driver, onEvent, forTrip = true)
                }
                if (details.pendingDriverId != null && details.pendingDriverId != vehicle.driverId) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ZillitButton(text = "Submit", onClick = { onEvent(TransportEvent.SubmitVehicleDriver) },
                            loading = details.busy)
                    }
                }
            }
            if (!vehicle.editable) {
                ZillitText(
                    text = "Assigned vehicles cannot be edited or deleted until they are free.",
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

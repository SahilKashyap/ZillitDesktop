// Shared rows and pickers — the web's DriverListItem, VehicleItem, PassengerListModal, ShowDriversModal.
@file:Suppress("LongMethod", "TooManyFunctions")

package com.zillit.desktop.feature.transportation.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transportation.domain.AllocationType
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportUser
import com.zillit.desktop.feature.transportation.domain.TripStatus
import com.zillit.desktop.feature.transportation.domain.Vehicle
import com.zillit.desktop.feature.transportation.ui.AssignTarget
import com.zillit.desktop.feature.transportation.ui.LocalTransportSlots
import com.zillit.desktop.feature.transportation.ui.PickPurpose
import com.zillit.desktop.feature.transportation.ui.TransportEvent
import com.zillit.desktop.feature.transportation.ui.TransportUiState
import com.zillit.desktop.feature.transportation.ui.localBytes

// Tones ----------------------------------------------------------------------

internal fun priorityTone(priority: String): StatusTone = when (priority.lowercase()) {
    "urgent" -> StatusTone.Rejected
    "high" -> StatusTone.Escalated
    "medium" -> StatusTone.Progress
    else -> StatusTone.Done
}

internal fun statusTone(status: TripStatus): StatusTone = when (status) {
    TripStatus.Pending -> StatusTone.Pending
    TripStatus.Assigned -> StatusTone.Progress
    TripStatus.InProgress -> StatusTone.Escalated
    TripStatus.Completed -> StatusTone.Done
    TripStatus.Cancelled -> StatusTone.Rejected
}

internal fun allocationTone(type: AllocationType): StatusTone = when (type) {
    AllocationType.Permanent -> StatusTone.Escalated
    AllocationType.Assigned -> StatusTone.Progress
    AllocationType.Remained, AllocationType.Allocated -> StatusTone.Done
}

// Pictures -------------------------------------------------------------------

/** A crew member's face — their picture when the host has one, initials otherwise. */
@Composable
internal fun Face(user: TransportUser?, name: String, size: Dp = FACE) {
    val slots = LocalTransportSlots.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = user?.userId, key2 = user?.avatar?.media) {
        value = user?.takeIf { it.avatar != null }?.let { slots.loadAvatar(it.userId) }
    }
    ZillitAvatar(name = name.ifBlank { "?" }, size = size, image = image, userId = user?.userId)
}

/**
 * A stored picture: from this session's own picks first (no round trip for
 * a file just uploaded), then the store through the host.
 */
@Composable
internal fun StoredPicture(state: TransportUiState, media: StoredMedia, modifier: Modifier = Modifier) {
    val slots = LocalTransportSlots.current
    val local = state.localBytes(media)
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = media.media, key2 = local != null) {
        value = local?.let(::decodeImageBitmap) ?: slots.loadMedia(media)
    }
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier.clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = media.displayName, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(min = THUMB))
        } else {
            ZillitIcon(ZillitIcons.Photo, tint = colors.textMuted)
        }
    }
}

/** A vehicle's picture: its first photograph, or the fleet glyph. */
@Composable
internal fun VehiclePicture(state: TransportUiState, vehicle: Vehicle, size: Dp = THUMB) {
    val first = vehicle.attachments.firstOrNull { it.isImage || it.contentType.isBlank() }
    if (first != null) {
        StoredPicture(state, first, Modifier.size(size))
    } else {
        Box(
            modifier = Modifier.size(size).clip(ZillitTheme.shapes.medium).background(ZillitTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.Transport, tint = ZillitTheme.colors.accentText)
        }
    }
}

// Rows -----------------------------------------------------------------------

/** A titled block inside a dialog — the web's bordered `shadow-md` cards. */
@Composable
internal fun Block(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = colors.textSecondary,
                modifier = Modifier.weight(1f))
            action?.invoke(this)
        }
        content()
    }
}

/** The web's `+ Add …` link on a block's corner. */
@Composable
internal fun AddLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    ZillitButton(text = text, onClick = onClick, variant = ButtonVariant.Tertiary, size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Add, enabled = enabled)
}

@Composable
internal fun EmptyLine(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = EMPTY_MIN), contentAlignment = Alignment.Center) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** A red round "×" — the web's delete button on every passenger and CC row. */
@Composable
internal fun RemoveButton(onClick: () -> Unit, description: String = str(S.remove)) {
    ZillitIconButton(icon = ZillitIcons.Trash, contentDescription = description, onClick = onClick,
        tint = ZillitTheme.colors.danger)
}

/** A person in a list: face, name, designation, and whatever sits at the end. */
@Composable
internal fun PersonRow(
    state: TransportUiState,
    userId: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val user = state.user(userId)
    val name = user?.fullName ?: state.userName(userId)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Face(user, name, size = FACE_SMALL)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                ZillitText(text = name, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
                if (user?.isGone == true) ZillitTag(label = str(S.txt_disabled), tone = TagTone.Danger)
            }
            val line = subtitle ?: user?.designationLabel.orEmpty()
            if (line.isNotBlank()) {
                ZillitText(text = line, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * A driver as the web's `DriverListItem` shows one: face, name with a
 * *Temp driver* tag, designation, availability; and — outside a trip form —
 * whether their documents, licence and vehicle are in.
 */
@Composable
internal fun DriverRow(
    state: TransportUiState,
    user: TransportUser,
    onEvent: (TransportEvent) -> Unit,
    modifier: Modifier = Modifier,
    forTrip: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val slots = LocalTransportSlots.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Face(user, user.fullName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                ZillitText(text = user.fullName, style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
                if (user.isTempDriver) ZillitTag(label = str(S.desktop_transport_temp_driver_tag),
                    tone = TagTone.Warning)
                if (user.isGone) ZillitTag(label = str(S.txt_disabled), tone = TagTone.Danger)
            }
            ZillitText(text = user.designationLabel, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitText(
                text = user.availability,
                style = ZillitTheme.typography.labelSmall,
                color = if (user.isAvailable) colors.success else colors.danger,
            )
            if (!forTrip) {
                DetailLine(str(S.txt_documents),
                    if (user.hasDocuments) str(S.txt_submitted) else str(S.docusign_request_access),
                    ok = user.hasDocuments) { onEvent(TransportEvent.OpenDocumentReminder(user.userId)) }
                DetailLine(str(S.tv_licence),
                    if (user.hasLicence) str(S.txt_submitted) else str(S.docusign_request_access),
                    ok = user.hasLicence) { onEvent(TransportEvent.LicenceReminder(user.userId)) }
                val vehicle = state.vehicle(user.vehicleId)
                DetailLine(str(S.vehicle), vehicle?.label ?: str(S.not_assigned),
                    ok = vehicle != null, onRequest = null)
            }
        }
        val call = slots.call
        if (call != null) {
            ZillitIconButton(icon = ZillitIcons.Phone,
                contentDescription = str(S.desktop_transport_call_user, user.fullName),
                onClick = { call(user) }, tint = colors.accent)
        }
        trailing?.invoke(this)
    }
}

/** "Licence — Submitted" in green, or "Licence — Request" as a link that sends the reminder. */
@Composable
private fun DetailLine(label: String, value: String, ok: Boolean, onRequest: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text = "$label —", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.labelSmall,
            color = when {
                ok -> colors.success
                onRequest != null -> colors.accentText
                else -> colors.danger
            },
            modifier = if (!ok && onRequest != null) Modifier.clickable(onClick = onRequest) else Modifier,
        )
    }
}

/** A vehicle as the web's `VehicleItem` shows one: picture, name, number, status, seats, driver. */
@Composable
internal fun VehicleRow(
    state: TransportUiState,
    vehicle: Vehicle,
    modifier: Modifier = Modifier,
    forTrip: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        VehiclePicture(state, vehicle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                ZillitText(text = vehicle.name.ifBlank { vehicle.number }, style = ZillitTheme.typography.titleSmall,
                    color = colors.textPrimary)
                if (vehicle.isPrivate) ZillitTag(label = str(S.drivers_badge_private), tone = TagTone.Info)
            }
            ZillitText(text = vehicle.number, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            if (!forTrip) ZillitStatusPill(label = vehicle.allocation.label, tone = allocationTone(vehicle.allocation))
            if (vehicle.seats > 0) {
                ZillitText(text = str(S.desktop_transport_seating_capacity_n, vehicle.seats),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary)
            }
            if (!forTrip) {
                val driver = state.user(vehicle.driverId)
                ZillitText(
                    text = str(S.desktop_transport_driver_dash, driver?.fullName ?: str(S.not_assigned)),
                    style = ZillitTheme.typography.bodySmall,
                    color = if (driver == null) colors.danger else colors.textSecondary,
                )
            }
        }
        trailing?.invoke(this)
    }
}

// Dialogs --------------------------------------------------------------------

/** One question, two answers — and a third for the temp-driver ask. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    danger: Boolean = true,
    busy: Boolean = false,
    cancelLabel: String = str(S.cancel),
    /** Closing without answering — the scrim, Escape. Defaults to the cancel answer. */
    onDismiss: () -> Unit = onCancel,
) {
    ZillitDialogShell(
        title = title,
        onDismiss = onDismiss,
        visible = true,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(text = cancelLabel, onClick = onCancel, variant = ButtonVariant.Tertiary)
            ZillitButton(text = confirmLabel, onClick = onConfirm, loading = busy,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary)
        },
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.bodyMedium)
    }
}

/** The web's `PassengerListModal`: search, tick several, submit. */
@Composable
internal fun PeoplePickerDialog(state: TransportUiState, onEvent: (TransportEvent) -> Unit) {
    val picker = state.peoplePicker ?: return
    val candidates = state.peopleChoices(picker)
    val title = when (picker.purpose) {
        PickPurpose.PermanentPassengers -> str(S.desktop_transport_add_passengers)
        else -> str(S.txt_add_cc_users)
    }
    ZillitDialogShell(
        title = title,
        onDismiss = { onEvent(TransportEvent.CancelPeoplePicker) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(TransportEvent.CancelPeoplePicker) },
                variant = ButtonVariant.Tertiary)
            ZillitButton(text = str(S.desktop_transport_add_count, picker.chosen.size),
                onClick = { onEvent(TransportEvent.SubmitPeoplePicker) },
                enabled = picker.chosen.isNotEmpty())
        },
    ) {
        ZillitSearchField(
            value = picker.query,
            onValueChange = { onEvent(TransportEvent.PeoplePickerChanged(picker.copy(query = it))) },
            placeholder = str(S.desktop_transport_search_name_or_designation),
            modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.sm),
        )
        if (candidates.isEmpty()) {
            EmptyLine(str(S.desktop_transport_nobody_to_add))
            return@ZillitDialogShell
        }
        LazyColumn(Modifier.heightIn(max = LIST_MAX)) {
            items(candidates, key = { it.userId }) { user ->
                val checked = user.userId in picker.chosen
                PersonRow(
                    state = state,
                    userId = user.userId,
                    onClick = {
                        onEvent(TransportEvent.PeoplePickerChanged(picker.copy(
                            chosen = if (checked) picker.chosen - user.userId else picker.chosen + user.userId)))
                    },
                    trailing = {
                        ZillitCheckbox(checked = checked, onCheckedChange = { on ->
                            onEvent(TransportEvent.PeoplePickerChanged(picker.copy(
                                chosen = if (on) picker.chosen + user.userId else picker.chosen - user.userId)))
                        })
                    },
                )
                ZillitDivider()
            }
        }
    }
}

/** The web's `VehicleListModal`: the vehicles a form may take, one click picks. */
@Composable
internal fun VehiclePickerDialog(state: TransportUiState, target: AssignTarget, onEvent: (TransportEvent) -> Unit) {
    val choices = state.vehicleChoices(target)
    ZillitDialogShell(
        title = str(S.txt_vehicle_list),
        onDismiss = { onEvent(TransportEvent.CancelVehiclePicker) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(TransportEvent.CancelVehiclePicker) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        if (choices.isEmpty()) {
            EmptyLine(
                if (target == AssignTarget.DriverDetails) str(S.desktop_transport_every_vehicle_has_driver)
                else str(S.desktop_transport_no_available_vehicles),
            )
            if (state.viewer.isCoordinator) {
                ZillitButton(text = str(S.desktop_transport_create_vehicle), onClick = {
                    onEvent(TransportEvent.CancelVehiclePicker)
                    onEvent(TransportEvent.NewVehicle)
                }, variant = ButtonVariant.Secondary)
            }
            return@ZillitDialogShell
        }
        LazyColumn(Modifier.heightIn(max = LIST_MAX)) {
            items(choices, key = { it.id }) { vehicle ->
                VehicleRow(state, vehicle, onClick = { onEvent(TransportEvent.PickVehicle(vehicle.id)) })
                ZillitDivider()
            }
        }
    }
}

/** The web's `ShowDriversModal`: searchable, one click picks. */
@Composable
internal fun DriverPickerDialog(state: TransportUiState, target: AssignTarget, onEvent: (TransportEvent) -> Unit) {
    val choices = state.driverChoices(target)
    ZillitDialogShell(
        title = str(S.drivers),
        onDismiss = { onEvent(TransportEvent.CancelDriverPicker) },
        visible = true,
        scrollable = false,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = { onEvent(TransportEvent.CancelDriverPicker) },
                variant = ButtonVariant.Tertiary)
        },
    ) {
        ZillitSearchField(
            value = state.driverPickerQuery,
            onValueChange = { onEvent(TransportEvent.DriverPickerQuery(it)) },
            placeholder = str(S.desktop_transport_search_driver_by_name),
            modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.sm),
        )
        if (choices.isEmpty()) {
            EmptyLine(str(S.desktop_transport_no_drivers_to_choose))
            return@ZillitDialogShell
        }
        LazyColumn(Modifier.heightIn(max = LIST_MAX)) {
            items(choices, key = { it.userId }) { user ->
                DriverRow(state, user, onEvent, forTrip = true,
                    onClick = { onEvent(TransportEvent.PickDriver(user.userId)) })
                ZillitDivider()
            }
        }
    }
}

internal val FACE: Dp = 44.dp
internal val FACE_SMALL: Dp = 36.dp
internal val THUMB: Dp = 72.dp
internal val LIST_MAX: Dp = 440.dp
internal val EMPTY_MIN: Dp = 64.dp
internal val CONFIRM_WIDTH: Dp = 420.dp
internal val DIALOG_WIDE: Dp = 760.dp
internal val FIELD: Dp = 180.dp
internal val SMALL: Dp = 96.dp

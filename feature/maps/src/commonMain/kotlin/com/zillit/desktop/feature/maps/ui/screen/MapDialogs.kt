package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.maps.domain.BoundaryActionKind
import com.zillit.desktop.feature.maps.domain.toFixed
import com.zillit.desktop.feature.maps.ui.AddCityState
import com.zillit.desktop.feature.maps.ui.MapDialog
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.ShareState

/** Whichever modal is open. The map surface has already stepped aside for it. */
@Composable
@Suppress("LongMethod") // One branch per dialog; the list is the catalogue.
internal fun MapDialogHost(dialog: MapDialog?, onEvent: (MapEvent) -> Unit) {
    val dismiss = { onEvent(MapEvent.Dialogs.Dismiss) }
    when (dialog) {
        null -> Unit
        is MapDialog.Boundary -> BoundaryDialog(dialog, onEvent)
        is MapDialog.Confirm -> ZillitDialogShell(
            title = dialog.title,
            onDismiss = dismiss,
            visible = true,
            icon = if (dialog.danger) MapIcons.AlertTriangle else null,
            width = 440.dp,
            actions = {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = dismiss,
                    variant = ButtonVariant.Secondary,
                    enabled = !dialog.busy,
                )
                ZillitButton(
                    text = dialog.confirmLabel,
                    onClick = { onEvent(MapEvent.Dialogs.Confirm) },
                    variant = if (dialog.danger) ButtonVariant.Danger else ButtonVariant.Primary,
                    loading = dialog.busy,
                )
            },
        ) {
            ZillitText(
                text = dialog.message,
                style = ZillitTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        is MapDialog.AddCity -> AddCityDialog(dialog.state, onEvent)
        is MapDialog.NewType -> ZillitDialogShell(
            title = str(S.desktop_map_new_type),
            onDismiss = dismiss,
            visible = true,
            icon = MapIcons.Layers,
            width = 480.dp,
            actions = {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(MapEvent.Types.Cancel) },
                    variant = ButtonVariant.Secondary,
                )
                ZillitButton(
                    text = str(S.create),
                    onClick = { onEvent(MapEvent.Types.Save) },
                    leadingIcon = ZillitIcons.Check,
                    loading = dialog.form.saving,
                    enabled = !dialog.form.saving && dialog.form.name.isNotBlank(),
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TypeFormFields(dialog.form, onEvent, showActions = false)
            }
        }
        is MapDialog.AddressOutside -> ZillitDialogShell(
            title = str(S.desktop_map_outside_area, dialog.areaName),
            onDismiss = dismiss,
            visible = true,
            icon = MapIcons.AlertTriangle,
            width = 420.dp,
            actions = {
                ZillitButton(text = str(S.cancel), onClick = dismiss, variant = ButtonVariant.Secondary)
                ZillitButton(
                    text = str(S.desktop_map_use_anyway),
                    onClick = { onEvent(MapEvent.LocationForm.UseAddressAnyway) },
                )
            },
        ) {
            ZillitText(
                text = str(S.desktop_map_address_outside, dialog.areaName),
                style = ZillitTheme.typography.bodyLarge,
                color = ZillitTheme.colors.textSecondary,
            )
            AddressBox(dialog.pick.address)
        }
        is MapDialog.Share -> ShareDialog(dialog.state, onEvent)
        is MapDialog.Photo -> PhotoDialog(dialog, onEvent)
    }
}

/**
 * The out-of-bounds question (reqs I + J). Three options stack one per line —
 * their labels name outcomes, and a clipped outcome defeats the wording.
 */
@Composable
private fun BoundaryDialog(dialog: MapDialog.Boundary, onEvent: (MapEvent) -> Unit) {
    val prompt = dialog.prompt
    ZillitDialogShell(
        title = prompt.title,
        onDismiss = { onEvent(MapEvent.Dialogs.Dismiss) },
        visible = true,
        icon = MapIcons.AlertTriangle,
        width = if (prompt.stacked) 460.dp else 420.dp,
        actions = if (prompt.stacked) {
            null
        } else {
            {
                prompt.actions.forEach { action ->
                    ZillitButton(
                        text = action.label,
                        onClick = { onEvent(MapEvent.Dialogs.Boundary(action.choice)) },
                        variant = if (action.kind == BoundaryActionKind.Primary) {
                            ButtonVariant.Primary
                        } else {
                            ButtonVariant.Secondary
                        },
                    )
                }
            }
        },
    ) {
        ZillitText(
            text = prompt.message,
            style = ZillitTheme.typography.bodyLarge,
            color = ZillitTheme.colors.textSecondary,
        )
        prompt.address?.let { AddressBox(it) }
        if (prompt.stacked) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.actions.forEach { action ->
                    ZillitButton(
                        text = action.label,
                        onClick = { onEvent(MapEvent.Dialogs.Boundary(action.choice)) },
                        variant = when (action.kind) {
                            BoundaryActionKind.Primary -> ButtonVariant.Primary
                            BoundaryActionKind.Neutral -> ButtonVariant.Secondary
                            BoundaryActionKind.Cancel -> ButtonVariant.Tertiary
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressBox(address: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(
            icon = MapIcons.MapPin,
            tint = colors.textMuted,
            size = 15.dp,
            modifier = Modifier.padding(top = 1.dp),
        )
        ZillitText(text = address, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}

/**
 * Add City (`cities/AddCityModal.jsx`) — the city only, since req E hid the
 * centre-and-zone section on every client. Opened on a place already known
 * (Current Location, a search, a pin outside the city) it skips the search
 * (req B).
 */
@Composable
@Suppress("LongMethod") // A form; read top to bottom.
private fun AddCityDialog(state: AddCityState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.desktop_map_add_city),
        onDismiss = { onEvent(MapEvent.Cities.AddCancel) },
        visible = true,
        icon = MapIcons.MapPin,
        width = 560.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(MapEvent.Cities.AddCancel) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (state.saving) str(S.ah_saving) else str(S.desktop_map_add_city),
                onClick = { onEvent(MapEvent.Cities.AddSave) },
                loading = state.saving,
                enabled = !state.saving && state.name.isNotBlank(),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitIcon(icon = MapIcons.MapPin, tint = MapColors.Info, size = 15.dp)
                ZillitText(text = str(S.city), style = ZillitTheme.typography.titleSmall, color = colors.textPrimary)
            }
            if (!state.prefilled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(
                        text = str(S.desktop_weather_search_city) + " *",
                        style = labelBold(11.sp),
                        color = colors.textSecondary,
                    )
                    ZillitTextField(
                        value = state.query,
                        onValueChange = { onEvent(MapEvent.Cities.AddQuery(it)) },
                        placeholder = str(S.desktop_map_search_for_city),
                        leadingIcon = ZillitIcons.Search,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SuggestionList(state.suggestions, onPick = { onEvent(MapEvent.Cities.AddPick(it)) })
                }
            }
            if (state.name.isNotBlank()) {
                SoftBanner(
                    accent = MapColors.Info,
                    icon = MapIcons.MapPin,
                    title = state.name,
                    body = state.description,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(text = str(S.desktop_latitude), style = labelBold(11.sp), color = colors.textSecondary)
                    ReadOnlyBox(state.point?.let { toFixed(it.lat, 6) }.orEmpty(), str(S.desktop_map_auto_filled))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(
                        text = str(S.desktop_longitude),
                        style = labelBold(11.sp),
                        color = colors.textSecondary,
                    )
                    ReadOnlyBox(state.point?.let { toFixed(it.lng, 6) }.orEmpty(), str(S.desktop_map_auto_filled))
                }
            }
        }
    }
}

/**
 * Share (req N): the message the three platforms send, and where it can go
 * from here — people in this production, the clipboard, or Google Maps.
 */
@Composable
@Suppress("LongMethod") // A form; read top to bottom.
private fun ShareDialog(state: ShareState, onEvent: (MapEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = state.title,
        onDismiss = { onEvent(MapEvent.Dialogs.Dismiss) },
        visible = true,
        icon = MapIcons.Share,
        subtitle = str(S.share),
        width = 520.dp,
        actions = {
            ZillitButton(
                text = str(S.desktop_map_copy_text),
                onClick = { onEvent(MapEvent.Dialogs.ShareCopy) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = MapIcons.Copy,
            )
            ZillitButton(
                text = str(S.recce_open_in_maps),
                onClick = { onEvent(MapEvent.Dialogs.ShareOpenMaps) },
                variant = ButtonVariant.Secondary,
                leadingIcon = MapIcons.Map,
            )
            if (state.people.isNotEmpty()) {
                ZillitButton(
                    text = if (state.selected.isEmpty()) {
                        str(S.send)
                    } else {
                        str(S.desktop_send_n, state.selected.size)
                    },
                    onClick = { onEvent(MapEvent.Dialogs.ShareSend) },
                    leadingIcon = ZillitIcons.Send,
                    enabled = state.selected.isNotEmpty() && !state.sending,
                    loading = state.sending,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                ZillitText(text = state.text, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
            }
            if (state.people.isNotEmpty()) {
                ZillitText(
                    text = str(S.desktop_map_send_in_zillit),
                    style = labelBold(11.sp),
                    color = colors.textSecondary,
                )
                ZillitTextField(
                    value = state.query,
                    onValueChange = { onEvent(MapEvent.Dialogs.ShareQuery(it)) },
                    placeholder = str(S.dd_history_sender_picker_search_hint),
                    leadingIcon = ZillitIcons.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.visiblePeople.forEach { person ->
                        val (source, hovered) = rememberHover()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (hovered) colors.surfaceHover else Color.Transparent)
                                .hoverable(source)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(interactionSource = source, indication = null) {
                                    onEvent(MapEvent.Dialogs.ShareToggle(person.userId))
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ZillitCheckbox(
                                checked = person.userId in state.selected,
                                onCheckedChange = { onEvent(MapEvent.Dialogs.ShareToggle(person.userId)) },
                            )
                            Column(Modifier.weight(1f)) {
                                ZillitText(
                                    text = person.name,
                                    style = labelBold(13.sp, FontWeight.Medium),
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                )
                                if (person.designation.isNotBlank()) {
                                    ZillitText(
                                        text = person.designation,
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoDialog(dialog: MapDialog.Photo, onEvent: (MapEvent) -> Unit) {
    ZillitDialogShell(
        title = dialog.attachment?.name?.ifBlank { null } ?: dialog.added?.photo?.name ?: str(S.photo),
        onDismiss = { onEvent(MapEvent.Dialogs.Dismiss) },
        visible = true,
        icon = MapIcons.Camera,
        width = 900.dp,
        maxHeight = 760.dp,
        scrollable = false,
    ) {
        val attachment = dialog.attachment
        val added = dialog.added
        Box(Modifier.fillMaxWidth().height(600.dp).clip(RoundedCornerShape(10.dp))) {
            when {
                attachment != null -> AsyncPicture(
                    key = "full:${attachment.media}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                ) {
                    photo(attachment, preview = false)
                }
                added != null -> AsyncPicture(
                    key = "full:${added.key}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                ) {
                    decode(added.photo)
                }
            }
        }
    }
}

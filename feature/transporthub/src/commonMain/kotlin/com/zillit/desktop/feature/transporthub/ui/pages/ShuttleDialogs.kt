// A route form is a long list of fields; splitting it hides the layout.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteType
import com.zillit.desktop.feature.transporthub.domain.ShuttleStop
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Shuttles → New / Edit Route.
 *
 * The name is collected as From and To and joined with an arrow on save: the
 * server stores one string, and nobody should have to type that character.
 */
@Composable
internal fun ShuttleRouteDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.routeEditor ?: return
    ZillitDialogShell(
        title = if (editor.isEdit) {
            str(S.desktop_hub_edit_route)
        } else {
            str(S.desktop_hub_create_route)
        },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseRouteEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseRouteEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveShuttleRoute) },
                loading = editor.saving,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = editor.from,
                onValueChange = { onEvent(HubEvent.RouteEditorChanged(editor.copy(from = it))) },
                label = str(S.fromText),
                placeholder = str(S.desktop_hub_base_camp_hint),
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = "→", style = ZillitTheme.typography.titleMedium)
            ZillitTextField(
                value = editor.to,
                onValueChange = { onEvent(HubEvent.RouteEditorChanged(editor.copy(to = it))) },
                label = str(S.toText),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSelect(
                value = editor.type,
                options = ShuttleRouteType.entries,
                onSelect = { onEvent(HubEvent.RouteEditorChanged(editor.copy(type = it))) },
                label = { it.label() },
                showInitials = false,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.frequencyMinutes,
                onValueChange = {
                    onEvent(HubEvent.RouteEditorChanged(editor.copy(frequencyMinutes = it)))
                },
                label = str(S.desktop_frequency),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.capacity,
                onValueChange = { onEvent(HubEvent.RouteEditorChanged(editor.copy(capacity = it))) },
                label = str(S.hint_capacity),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = editor.startTime,
                onValueChange = {
                    onEvent(HubEvent.RouteEditorChanged(editor.copy(startTime = it)))
                },
                label = str(S.desktop_hub_first_departure),
                placeholder = "HH:mm",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = editor.endTime,
                onValueChange = { onEvent(HubEvent.RouteEditorChanged(editor.copy(endTime = it))) },
                label = str(S.desktop_hub_last_departure),
                placeholder = "HH:mm",
                modifier = Modifier.weight(1f),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitSectionLabel(text = str(S.desktop_hub_active_days))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                HubDay.weekOrder.forEach { day ->
                    ZillitCheckbox(
                        checked = day in editor.daysActive,
                        onCheckedChange = {
                            val next = if (day in editor.daysActive) {
                                editor.daysActive - day
                            } else {
                                editor.daysActive + day
                            }
                            onEvent(
                                HubEvent.RouteEditorChanged(
                                    editor.copy(
                                        daysActive = HubDay.weekOrder.filter { it in next },
                                    ),
                                ),
                            )
                        },
                        label = day.shortLabel,
                    )
                }
            }
        }
        // Only what can actually be assigned: a route without both of these
        // cannot generate runs at all, so the pickers come from /available.
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            val drivers = state.availableDrivers.ifEmpty { state.drivers }
            ZillitSelect(
                value = drivers.firstOrNull { it.id == editor.driverId },
                options = listOf(null) + drivers,
                onSelect = { onEvent(HubEvent.RouteEditorChanged(editor.copy(driverId = it?.id))) },
                label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
                subtitle = { it?.designation?.takeIf { d -> d.isNotBlank() } },
                modifier = Modifier.weight(1f),
            )
            val vehicles = state.availableVehicles.ifEmpty { state.vehicles }
            ZillitSelect(
                value = vehicles.firstOrNull { it.id == editor.vehicleId },
                options = listOf(null) + vehicles,
                onSelect = {
                    onEvent(HubEvent.RouteEditorChanged(editor.copy(vehicleId = it?.id)))
                },
                label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
                subtitle = { it?.specLine?.takeIf { spec -> spec.isNotBlank() } },
                showInitials = false,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitSectionLabel(text = str(S.recce_label_stops))
        editor.stops.forEachIndexed { index, stop ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitTextField(
                        value = stop.name,
                        onValueChange = { typed ->
                            onEvent(
                                HubEvent.RouteEditorChanged(
                                    editor.copy(stops = editor.stops.replace(index, stop.copy(name = typed))),
                                ),
                            )
                        },
                        label = str(S.desktop_hub_stop_number, (index + 1).toString()),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = stop.estimatedTime,
                        onValueChange = { typed ->
                            onEvent(
                                HubEvent.RouteEditorChanged(
                                    editor.copy(
                                        stops = editor.stops.replace(
                                            index,
                                            stop.copy(estimatedTime = typed),
                                        ),
                                    ),
                                ),
                            )
                        },
                        label = str(S.hint_eta),
                        placeholder = "HH:mm",
                        modifier = Modifier.weight(1f),
                    )
                    if (editor.stops.size > MIN_STOPS) {
                        ZillitButton(
                            text = str(S.remove),
                            onClick = {
                                onEvent(
                                    HubEvent.RouteEditorChanged(
                                        editor.copy(
                                            stops = editor.stops
                                                .filterIndexed { i, _ -> i != index },
                                        ),
                                    ),
                                )
                            },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }
                }
                HubLocationField(
                    label = str(S.av_location),
                    value = stop.location,
                    onChange = { picked ->
                        onEvent(
                            HubEvent.RouteEditorChanged(
                                editor.copy(
                                    stops = editor.stops.replace(
                                        index,
                                        stop.copy(location = picked),
                                    ),
                                ),
                            ),
                        )
                    },
                )
            }
        }
        ZillitButton(
            text = str(S.recce_add_stop),
            onClick = {
                onEvent(HubEvent.RouteEditorChanged(editor.copy(stops = editor.stops + ShuttleStop())))
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitTextField(
            value = editor.notes,
            onValueChange = { onEvent(HubEvent.RouteEditorChanged(editor.copy(notes = it))) },
            label = str(S.notes),
            singleLine = false,
        )
    }
}

private fun List<ShuttleStop>.replace(index: Int, stop: ShuttleStop): List<ShuttleStop> =
    toMutableList().also { it[index] = stop }

/** A route needs two ends; below that the form keeps the rows it has. */
private const val MIN_STOPS = 2

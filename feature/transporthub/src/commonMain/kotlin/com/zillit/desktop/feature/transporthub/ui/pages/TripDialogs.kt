// One composable per dialog; a form is a long list of fields.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.domain.HubRole
import com.zillit.desktop.feature.transporthub.domain.ProjectPerson
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.TripResourceKind
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.TripEditor
import com.zillit.desktop.feature.transporthub.ui.TripPassengerRow

/**
 * New Trip and the trip's own detail view.
 *
 * Both were missing: the Trips page offered the buttons and neither did
 * anything, because nothing rendered `tripEditor` or `openTrip`. The web gives
 * the first a modal and the second a whole route
 * (`coordinator/TripDetail.jsx`); on the desktop both are dialogs over the
 * list, which is how every other hub form opens here.
 */
@Composable
internal fun TripDialogs(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    TripEditorDialog(state, onEvent)
    TripDetailDialog(state, onEvent)
}

@Composable
private fun TripEditorDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.tripEditor ?: return
    ZillitDialogShell(
        title = str(S.desktop_hub_new_trip),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseTripEditor) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseTripEditor) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(HubEvent.SaveTrip) },
                loading = editor.saving,
            )
        },
    ) {
        ZillitTextField(
            value = editor.title,
            onValueChange = { onEvent(HubEvent.TripEditorChanged(editor.copy(title = it))) },
            label = str(S.title),
            placeholder = str(S.desktop_hub_trip_title_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSelect(
                value = editor.tripType,
                options = TripEditor.TRIP_TYPES,
                onSelect = { onEvent(HubEvent.TripEditorChanged(editor.copy(tripType = it))) },
                label = { it.tripTypeLabel() },
                showInitials = false,
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = editor.priority,
                options = RidePriority.entries,
                onSelect = { onEvent(HubEvent.TripEditorChanged(editor.copy(priority = it))) },
                label = { it.label() },
                showInitials = false,
                modifier = Modifier.weight(1f),
            )
        }
        // The day every passenger's time is read against: the server stores one
        // instant per passenger, and the form collects a day once.
        ZillitDateField(
            value = editor.date,
            onValueChange = { onEvent(HubEvent.TripEditorChanged(editor.copy(date = it))) },
            label = str(S.date),
        )
        TripDriverAndVehicle(state, editor, onEvent)
        ZillitSectionLabel(text = str(S.txt_passengers))
        editor.passengers.forEachIndexed { index, row ->
            TripPassengerFields(
                row = row,
                index = index,
                people = state.people,
                canRemove = editor.passengers.size > 1,
                onChange = { next ->
                    onEvent(
                        HubEvent.TripEditorChanged(
                            editor.copy(
                                passengers = editor.passengers.toMutableList()
                                    .also { it[index] = next },
                            ),
                        ),
                    )
                },
                onRemove = {
                    onEvent(
                        HubEvent.TripEditorChanged(
                            editor.copy(
                                passengers = editor.passengers.filterIndexed { i, _ -> i != index },
                            ),
                        ),
                    )
                },
            )
        }
        ZillitButton(
            text = str(S.desktop_hub_add_passenger),
            onClick = {
                onEvent(
                    HubEvent.TripEditorChanged(
                        editor.copy(passengers = editor.passengers + TripPassengerRow()),
                    ),
                )
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitTextField(
            value = editor.notes,
            onValueChange = { onEvent(HubEvent.TripEditorChanged(editor.copy(notes = it))) },
            label = str(S.notes),
            singleLine = false,
        )
    }
}

@Composable
private fun TripDriverAndVehicle(
    state: HubUiState,
    editor: TripEditor,
    onEvent: (HubEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        // Only what can actually be assigned right now: `/driver/available`
        // already excludes off-duty, on-trip, permanent and full-day drivers,
        // and the roster's own `is_on_duty` can be stale.
        val drivers = state.availableDrivers.ifEmpty { state.drivers }
        ZillitSelect(
            value = drivers.firstOrNull { it.id == editor.driverId },
            options = listOf(null) + drivers,
            onSelect = { onEvent(HubEvent.TripEditorChanged(editor.copy(driverId = it?.id))) },
            label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
            subtitle = { it?.designation?.takeIf { d -> d.isNotBlank() } },
            modifier = Modifier.weight(1f),
        )
        val vehicles = state.availableVehicles.ifEmpty { state.vehicles }
        ZillitSelect(
            value = vehicles.firstOrNull { it.id == editor.vehicleId },
            options = listOf(null) + vehicles,
            onSelect = { onEvent(HubEvent.TripEditorChanged(editor.copy(vehicleId = it?.id))) },
            label = { it?.displayName ?: str(S.desktop_hub_assign_later) },
            subtitle = { it?.specLine?.takeIf { spec -> spec.isNotBlank() } },
            showInitials = false,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One passenger's row.
 *
 * Picking a project member fills the name and the number and keeps their
 * `user_id`; a guest's row is typed and carries none, because a blank
 * `user_id` makes the server's required-field validator refuse the whole trip.
 */
@Composable
private fun TripPassengerFields(
    row: TripPassengerRow,
    index: Int,
    people: List<ProjectPerson>,
    canRemove: Boolean,
    onChange: (TripPassengerRow) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = str(S.desktop_hub_passenger_number, (index + 1).toString()),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (canRemove) {
                ZillitButton(
                    text = str(S.remove),
                    onClick = onRemove,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        // Searchable because a production carries hundreds of members — the
        // select turns its own search on past a threshold.
        ZillitSelect(
            value = people.firstOrNull { it.userId == row.userId },
            options = listOf(null) + people,
            onSelect = { person ->
                onChange(
                    row.copy(
                        userId = person?.userId,
                        name = person?.name ?: row.name,
                        contactNumber = person?.phone?.takeIf { it.isNotBlank() }
                            ?: row.contactNumber,
                    ),
                )
            },
            label = { it?.name ?: str(S.desktop_hub_pick_from_project) },
            subtitle = { it?.designation?.takeIf { d -> d.isNotBlank() } },
            searchable = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = row.name,
                onValueChange = { onChange(row.copy(name = it)) },
                label = str(S.name),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = row.contactNumber,
                onValueChange = { onChange(row.copy(contactNumber = it)) },
                label = str(S.contact_number),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = row.time,
                onValueChange = { onChange(row.copy(time = it)) },
                label = str(S.desktop_hub_pickup_time),
                placeholder = "HH:mm",
                modifier = Modifier.weight(1f),
            )
        }
        HubLocationField(
            label = str(S.pickup),
            value = row.pickup,
            onChange = { onChange(row.copy(pickup = it)) },
        )
        HubLocationField(
            label = str(S.desktop_hub_dropoff),
            value = row.dropoff,
            onChange = { onChange(row.copy(dropoff = it)) },
        )
    }
}

/**
 * The trip's detail view — the web's `TripDetail` route, as a dialog.
 *
 * What a coordinator comes here for: who is on it, where it is going, who is
 * driving, the lifecycle log, and the status transitions. Open from any list,
 * including the Dashboard's Recent Trips.
 */
@Composable
private fun TripDetailDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val trip = state.openTrip ?: return
    val coordinator = state.role == HubRole.Coordinator
    ZillitDialogShell(
        title = trip.title.ifBlank { str(S.desktop_hub_untitled_trip) },
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseTrip) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(HubEvent.CloseTrip) },
                variant = ButtonVariant.Secondary,
            )
            if (coordinator && !trip.status.isTerminal) {
                ZillitButton(
                    text = str(S.assign),
                    onClick = { onEvent(HubEvent.AssignTrip(trip.id)) },
                )
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitStatusPill(label = trip.status.label(), tone = trip.status.tone(), dot = true)
            if (trip.kind == TripResourceKind.RideRequest) {
                ZillitStatusPill(label = str(S.ride_requests), tone = StatusTone.Progress)
            }
            ZillitStatusPill(label = trip.priority.label(), tone = trip.priority.tone())
        }
        HubRouteLine(trip.pickup, trip.dropoff)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            HubFact(
                label = str(S.driver),
                value = trip.driverName.ifBlank { state.driverName(trip.driverId) },
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.vehicle),
                value = trip.vehicleLabel.ifBlank { state.vehicleLabel(trip.vehicleId) },
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.desktop_hub_pickup_time),
                value = (trip.scheduledPickupTimeMs ?: trip.createdMs).hubDateTime(),
                modifier = Modifier.weight(1f),
            )
        }
        if (trip.activeDays.isNotEmpty()) {
            HubFact(label = str(S.desktop_hub_active_days), value = trip.activeDays.describe())
        }
        if (trip.notes.isNotBlank()) {
            HubFact(label = str(S.notes), value = trip.notes)
        }

        ZillitSectionLabel(text = str(S.txt_passengers))
        if (trip.passengers.isEmpty()) {
            ZillitText(
                text = "—",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        trip.passengers.forEach { passenger ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                HubFact(
                    label = passenger.name.ifBlank { str(S.txt_badge_guest) },
                    value = passenger.contactNumber,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.pickup),
                    value = passenger.pickup.display(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_dropoff),
                    value = passenger.dropoff.display(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (coordinator) {
            ZillitSectionLabel(text = str(S.status))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                TRIP_TRANSITIONS.filter { it != trip.status }.forEach { next ->
                    ZillitButton(
                        text = next.label(),
                        onClick = { onEvent(HubEvent.SetTripStatus(trip.id, next)) },
                        variant = if (next == TripStatus.Cancelled) {
                            ButtonVariant.Danger
                        } else {
                            ButtonVariant.Tertiary
                        },
                        size = ButtonSize.Small,
                        enabled = state.busyRowId != trip.id,
                    )
                }
            }
            ZillitButton(
                text = str(S.docusign_action_remind),
                onClick = { onEvent(HubEvent.RemindTrip(trip.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = state.busyRowId != trip.id,
            )
        }

        // The lifecycle log. A shuttle run has none and the route answers 400
        // for its ids, so an empty list is normal rather than a failure.
        if (state.openTripEvents.isNotEmpty()) {
            ZillitSectionLabel(text = str(S.timeline))
            state.openTripEvents.forEach { entry ->
                HubFact(
                    label = entry.atMs.hubDateTime(),
                    value = listOfNotNull(
                        entry.description.takeIf { it.isNotBlank() }
                            ?: entry.type.replace('_', ' '),
                        entry.actorName.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
        }
    }
}

private fun String.tripTypeLabel(): String = when (this) {
    "permanent" -> str(S.desktop_hub_vehicle_permanent)
    "recurring" -> str(S.recurring)
    else -> str(S.desktop_hub_trip_type_one_time)
}

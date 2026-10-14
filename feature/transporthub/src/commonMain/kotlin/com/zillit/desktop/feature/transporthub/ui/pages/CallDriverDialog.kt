// A form is a long list of fields; splitting it hides the layout.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDateTime
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.data.toEpochMillisOrNull
import com.zillit.desktop.feature.transporthub.data.toIsoDate
import com.zillit.desktop.feature.transporthub.domain.HubPassenger
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.RideEditor
import com.zillit.desktop.feature.transporthub.ui.kit.HubButton
import com.zillit.desktop.feature.transporthub.ui.kit.HubButtonStyle
import com.zillit.desktop.feature.transporthub.ui.kit.HubDriverStrip
import com.zillit.desktop.feature.transporthub.ui.kit.HubGlyphs
import com.zillit.desktop.feature.transporthub.ui.kit.HubIconTile
import com.zillit.desktop.feature.transporthub.ui.kit.HubPriorityPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubRouteBlock
import com.zillit.desktop.feature.transporthub.ui.kit.HubStatusPill
import com.zillit.desktop.feature.transporthub.ui.kit.HubText
import com.zillit.desktop.feature.transporthub.ui.kit.hub
import com.zillit.desktop.feature.transporthub.ui.kit.rgb

/**
 * Call a Driver — the web's `passenger/CallDriverModal.jsx`.
 *
 * A passenger's or a driver's own ride, in the web's order: how urgent, where
 * from, where to, when, who rides, and anything the driver should know. The
 * caller is the first passenger and cannot be taken off — the request is
 * theirs — and anyone without a Zillit account rides as a typed-in guest.
 * Saving sends `call_type: self`; an edit sends no call type at all.
 */
@Composable
internal fun CallDriverDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val editor = state.rideEditor?.takeIf { it.isSelf } ?: return
    val p = hub
    val change = { next: RideEditor -> onEvent(HubEvent.RideEditorChanged(next)) }
    ZillitDialogShell(
        title = if (editor.isEdit) str(S.desktop_hub_edit_ride_request) else str(S.desktop_map_call_a_driver),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseRideEditor) },
        actions = {
            HubButton(
                text = str(S.cancel),
                onClick = { onEvent(HubEvent.CloseRideEditor) },
                style = HubButtonStyle.Outline,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
            HubButton(
                text = if (editor.isEdit) str(S.ah_save_changes) else str(S.desktop_map_call_a_driver),
                onClick = { onEvent(HubEvent.SaveRide) },
                loading = editor.saving,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Group(str(S.priority)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRIORITIES.forEach { (priority, colour) ->
                    val active = editor.priority == priority
                    HubText(
                        priority.label(),
                        13.sp,
                        if (active) colour else p.textMuted,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) colour.copy(alpha = 0.08f) else Color.Transparent)
                            .border(2.dp, if (active) colour else p.border, RoundedCornerShape(10.dp))
                            .clickable { change(editor.copy(priority = priority)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        EndCard(
            label = str(S.txt_pickup_location),
            hint = str(S.desktop_hub_pickup_where),
            fg = rgb(0x05603A),
            bg = rgb(0xECFDF3),
            border = rgb(0xABEFC6),
        ) {
            HubLocationField(
                label = str(S.pickup),
                value = editor.pickup,
                onChange = { change(editor.copy(pickup = it)) },
            )
        }
        EndCard(
            label = str(S.desktop_hub_dropoff_location),
            hint = str(S.desktop_hub_dropoff_where),
            fg = rgb(0x912018),
            bg = rgb(0xFEF3F2),
            border = rgb(0xFECDCA),
        ) {
            HubLocationField(
                label = str(S.desktop_hub_dropoff),
                value = editor.dropoff,
                onChange = { change(editor.copy(dropoff = it)) },
            )
        }
        Group(str(S.section_when)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WhenButton(str(S.desktop_hub_pick_up_now), editor.asSoonAsPossible) {
                    change(editor.copy(asSoonAsPossible = true))
                }
                WhenButton(str(S.desktop_hub_schedule_time_btn), !editor.asSoonAsPossible) {
                    change(editor.copy(asSoonAsPossible = false))
                }
            }
            // The day and the time, joined into one instant on the way out.
            if (!editor.asSoonAsPossible) {
                val at = editor.scheduledPickupTimeMs ?: 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZillitDateField(
                        value = at.toIsoDate(),
                        onValueChange = { typed ->
                            val time = at.hubTime().ifBlank { "09:00" }
                            change(editor.copy(scheduledPickupTimeMs = "${typed}T$time".toEpochMillisOrNull()))
                        },
                        label = str(S.date),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = at.hubTime(),
                        onValueChange = { typed ->
                            val day = at.takeIf { it > 0 }?.toIsoDate()
                            change(
                                editor.copy(scheduledPickupTimeMs = day?.let { "${it}T$typed".toEpochMillisOrNull() }),
                            )
                        },
                        label = str(S.desktop_hub_pickup_time),
                        placeholder = "HH:mm",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Passengers(state, editor, change)
        Group(str(S.notes)) {
            ZillitTextField(
                value = editor.notes,
                onValueChange = { change(editor.copy(notes = it)) },
                singleLine = false,
            )
        }
    }
}

/**
 * Who rides: the caller first and fixed, then anyone picked from the
 * production, then guests typed in by name and number.
 */
@Composable
private fun Passengers(state: HubUiState, editor: RideEditor, change: (RideEditor) -> Unit) {
    val p = hub
    val me = state.viewer.userId
    Group(str(S.txt_passengers)) {
        editor.passengers.firstOrNull { it.userId == me }?.let { caller ->
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(p.accentBg)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HubText(caller.name, 13.sp, p.textPrimary, weight = FontWeight.SemiBold)
                HubText(str(S.desktop_tasks_you), 13.sp, p.accentDark, weight = FontWeight.Bold)
            }
        }
        val others = state.people.filter { it.userId != me }
        ZillitMultiSelect(
            selected = others.filter { person -> editor.passengers.any { it.userId == person.userId } },
            options = others,
            label = { it.name },
            onChange = { picked ->
                // The caller stays first whatever the picker hands back.
                val caller = editor.passengers.filter { it.userId == me }
                val members = picked.map { HubPassenger(userId = it.userId, name = it.name) }
                change(editor.copy(passengers = caller + members))
            },
            searchText = { "${it.name} ${it.designation} ${it.phone} ${it.email}" },
            subtitle = { it.designation.takeIf { d -> d.isNotBlank() } },
            placeholder = str(S.desktop_hub_add_project_users),
        )
        editor.guests.forEachIndexed { index, guest ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitTextField(
                    value = guest.name,
                    onValueChange = { typed ->
                        change(editor.copy(guests = editor.guests.replaced(index, guest.copy(name = typed))))
                    },
                    label = str(S.desktop_hub_guest_name),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = guest.contactNumber,
                    onValueChange = { typed ->
                        change(editor.copy(guests = editor.guests.replaced(index, guest.copy(contactNumber = typed))))
                    },
                    label = str(S.contact_number),
                    modifier = Modifier.weight(1f),
                )
                HubButton(
                    text = str(S.remove),
                    onClick = { change(editor.copy(guests = editor.guests.filterIndexed { i, _ -> i != index })) },
                    style = HubButtonStyle.Link,
                    height = 32.dp,
                )
            }
        }
        HubButton(
            text = str(S.desktop_hub_add_guest),
            onClick = { change(editor.copy(guests = editor.guests + HubPassenger())) },
            style = HubButtonStyle.Outline,
            icon = HubGlyphs.Plus,
            height = 34.dp,
            fontSize = 13.sp,
        )
    }
}

/** A labelled block — the web's `rqGroup` with its capital label. */
@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HubText(label.uppercase(), 11.sp, hub.textMuted, weight = FontWeight.Bold, spacing = 0.5.sp)
        content()
    }
}

/** The tinted pickup or drop-off card: a pin, what it is, and a prompt. */
@Composable
private fun EndCard(
    label: String,
    hint: String,
    fg: Color,
    bg: Color,
    border: Color,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubIconTile(
                HubGlyphs.MapPin,
                background = fg,
                tint = Color.White,
                size = 32.dp,
                radius = 8.dp,
                iconSize = 16.dp,
            )
            Column {
                HubText(label.uppercase(), 13.sp, fg, weight = FontWeight.Bold, spacing = 0.4.sp)
                HubText(hint, 11.sp, fg.copy(alpha = 0.85f))
            }
        }
        content()
    }
}

@Composable
private fun WhenButton(label: String, active: Boolean, onClick: () -> Unit) {
    val p = hub
    HubText(
        label,
        13.sp,
        if (active) Color.White else p.textPrimary,
        weight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) p.accentDark else p.surface)
            .border(1.dp, if (active) p.accentDark else p.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun List<HubPassenger>.replaced(index: Int, next: HubPassenger): List<HubPassenger> =
    toMutableList().also { it[index] = next }

/** The four priorities and the web's colour for each. */
private val PRIORITIES: List<Pair<RidePriority, Color>> = listOf(
    RidePriority.Urgent to rgb(0xDC2626),
    RidePriority.High to rgb(0xEA580C),
    RidePriority.Normal to rgb(0xD97706),
    RidePriority.Low to rgb(0x6B7280),
)

/**
 * A ride, whole — the web's `RideDetailModal`, which a passenger's View
 * Details and a driver's Details both open.
 *
 * Read-only on purpose: what a passenger can do to a ride sits on its card,
 * and a driver moves it with the step button. This is for reading — who is
 * on it, where it goes, who is driving, and why it was refused if it was.
 */
@Composable
internal fun RideDetailDialog(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val ride = state.ride(state.openRide) ?: return
    val p = hub
    ZillitDialogShell(
        title = str(S.desktop_hub_ride_number, ride.id.takeLast(DETAIL_SHORT_ID)),
        visible = true,
        onDismiss = { onEvent(HubEvent.CloseRide) },
        actions = {
            HubButton(
                text = str(S.close),
                onClick = { onEvent(HubEvent.CloseRide) },
                style = HubButtonStyle.Outline,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubStatusPill(ride.status.wire)
            HubPriorityPill(ride.priority.wire)
        }
        HubRouteBlock(ride.pickup, ride.dropoff)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HubFact(
                label = str(S.desktop_hub_pickup_time),
                value = ride.scheduledPickupTimeMs?.takeIf { it > 0 }?.hubDateTime().orEmpty(),
                modifier = Modifier.weight(1f),
            )
            HubFact(
                label = str(S.desktop_hub_created_date),
                value = ride.createdMs.takeIf { it > 0 }?.hubDateTime().orEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
        val driver = state.driverLabel(ride.assignedDriverId, ride.assignedDriverName)
        val vehicle = state.vehicleLabelOrId(ride.assignedVehicleId, ride.assignedVehicleLabel)
        if (driver.isNotBlank() || vehicle.isNotBlank()) {
            HubDriverStrip(
                driver.ifBlank { str(S.desktop_hub_driver_not_assigned) },
                vehicle,
            )
        }
        Group(str(S.txt_passengers)) {
            ride.passengers.forEach { passenger ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HubText(passenger.name.ifBlank { "—" }, 13.sp, p.textPrimary, weight = FontWeight.SemiBold)
                    if (passenger.userId != null && passenger.userId == state.viewer.userId) {
                        HubText(str(S.desktop_tasks_you), 13.sp, p.accentDark, weight = FontWeight.Bold)
                    }
                    // A guest has no account behind them — the web tags them so.
                    if (passenger.userId.isNullOrBlank()) {
                        HubText(
                            str(S.txt_badge_guest).uppercase(),
                            10.sp,
                            rgb(0x047857),
                            weight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1F10B981))
                                .padding(horizontal = 6.dp),
                        )
                    }
                    if (passenger.contactNumber.isNotBlank()) HubText(passenger.contactNumber, 11.sp, p.textMuted)
                }
            }
        }
        if (ride.notes.isNotBlank()) HubFact(label = str(S.notes), value = ride.notes)
        ride.rejectionReason.ifBlank { ride.cancellationReason }.takeIf { it.isNotBlank() }
            ?.let { HubFact(label = str(S.reason), value = it) }
    }
}

private const val DETAIL_SHORT_ID = 6

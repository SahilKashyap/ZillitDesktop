package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.HubDay
import com.zillit.desktop.feature.transporthub.domain.HubLocation
import com.zillit.desktop.feature.transporthub.domain.PermanentState
import com.zillit.desktop.feature.transporthub.domain.RidePriority
import com.zillit.desktop.feature.transporthub.domain.RideStatus
import com.zillit.desktop.feature.transporthub.domain.TripStatus
import com.zillit.desktop.feature.transporthub.domain.VehicleAllocation

/*
 * Pieces every hub page draws, and the one place each status vocabulary is
 * turned into a tone. The web keeps four separate colour maps for these; a
 * single mapping per enum is what stops a ride reading green on one page and
 * blue on the next.
 */

/** The hue a ride's status is drawn in. */
internal fun RideStatus.tone(): StatusTone = when (this) {
    RideStatus.Pending -> StatusTone.Pending
    RideStatus.Approved -> StatusTone.Ready
    RideStatus.Assigned -> StatusTone.Progress
    RideStatus.EnRoutePickup, RideStatus.AtPickup, RideStatus.AtDropoff -> StatusTone.InTransit
    RideStatus.InTransit -> StatusTone.InTransit
    RideStatus.Completed -> StatusTone.Done
    RideStatus.Rejected, RideStatus.Cancelled -> StatusTone.Rejected
    RideStatus.Unknown -> StatusTone.Neutral
}

internal fun RideStatus.label(): String = when (this) {
    RideStatus.Pending -> str(S.pending)
    RideStatus.Approved -> str(S.approved)
    RideStatus.Assigned -> str(S.assigned)
    RideStatus.EnRoutePickup -> str(S.desktop_hub_en_route_pickup)
    RideStatus.AtPickup -> str(S.desktop_hub_at_pickup)
    RideStatus.InTransit -> str(S.in_progress)
    RideStatus.AtDropoff -> str(S.desktop_hub_at_dropoff)
    RideStatus.Completed -> str(S.completed)
    RideStatus.Rejected -> str(S.rejected)
    RideStatus.Cancelled -> str(S.cancelled)
    // A status this build has not met yet. Showing nothing would read as a
    // bug; showing the word "unknown" at least says the row is fine.
    RideStatus.Unknown -> str(S.desktop_unknown)
}

internal fun TripStatus.tone(): StatusTone = when (this) {
    TripStatus.Draft -> StatusTone.Neutral
    TripStatus.Pending -> StatusTone.Pending
    TripStatus.Approved -> StatusTone.Ready
    TripStatus.Assigned -> StatusTone.Progress
    TripStatus.EnRoutePickup, TripStatus.AtPickup, TripStatus.AtDropoff -> StatusTone.InTransit
    TripStatus.InTransit -> StatusTone.InTransit
    TripStatus.Completed -> StatusTone.Done
    TripStatus.Cancelled, TripStatus.Rejected -> StatusTone.Rejected
    TripStatus.Unknown -> StatusTone.Neutral
}

internal fun TripStatus.label(): String = when (this) {
    TripStatus.Draft -> str(S.draft)
    TripStatus.Pending -> str(S.pending)
    TripStatus.Approved -> str(S.approved)
    TripStatus.Assigned -> str(S.assigned)
    TripStatus.EnRoutePickup -> str(S.desktop_hub_en_route_pickup)
    TripStatus.AtPickup -> str(S.desktop_hub_at_pickup)
    TripStatus.InTransit -> str(S.in_progress)
    TripStatus.AtDropoff -> str(S.desktop_hub_at_dropoff)
    TripStatus.Completed -> str(S.completed)
    TripStatus.Cancelled -> str(S.cancelled)
    TripStatus.Rejected -> str(S.rejected)
    TripStatus.Unknown -> str(S.desktop_unknown)
}

internal fun RidePriority.tone(): StatusTone = when (this) {
    RidePriority.Urgent -> StatusTone.Rejected
    RidePriority.High -> StatusTone.Escalated
    RidePriority.Normal -> StatusTone.Progress
    RidePriority.Low -> StatusTone.Neutral
}

internal fun RidePriority.label(): String = when (this) {
    RidePriority.Urgent -> str(S.ah_topup_filter_urgent)
    RidePriority.High -> str(S.desktop_weather_uv_high)
    RidePriority.Normal -> str(S.nda_style_normal)
    RidePriority.Low -> str(S.desktop_weather_uv_low)
}

internal fun VehicleAllocation.tone(): StatusTone = when (this) {
    VehicleAllocation.Available -> StatusTone.Done
    VehicleAllocation.Allocated, VehicleAllocation.Assigned -> StatusTone.Progress
    VehicleAllocation.Permanent -> StatusTone.InTransit
    VehicleAllocation.Maintenance -> StatusTone.Escalated
    VehicleAllocation.Unknown -> StatusTone.Neutral
}

internal fun VehicleAllocation.label(): String = when (this) {
    VehicleAllocation.Available -> str(S.available)
    VehicleAllocation.Allocated -> str(S.txt_vehicle_status_allocated)
    VehicleAllocation.Assigned -> str(S.assigned)
    VehicleAllocation.Permanent -> str(S.desktop_hub_vehicle_permanent)
    VehicleAllocation.Maintenance -> str(S.maintenance)
    VehicleAllocation.Unknown -> str(S.desktop_unknown)
}

internal fun PermanentState.tone(): StatusTone = when (this) {
    PermanentState.Draft -> StatusTone.Neutral
    PermanentState.Active -> StatusTone.InTransit
    PermanentState.Paused -> StatusTone.Rejected
    PermanentState.Completed -> StatusTone.Done
    PermanentState.Archived, PermanentState.Unknown -> StatusTone.Neutral
}

internal fun PermanentState.label(): String = when (this) {
    PermanentState.Draft -> str(S.draft)
    PermanentState.Active -> str(S.desktop_hub_in_use)
    PermanentState.Paused -> str(S.desktop_drive_upload_paused)
    PermanentState.Completed -> str(S.completed)
    PermanentState.Archived -> str(S.desktop_archived)
    PermanentState.Unknown -> str(S.desktop_unknown)
}

/**
 * A weekly schedule as one phrase.
 *
 * Collapses the three patterns a shoot week actually comes in, and falls back
 * to the short day names in calendar order — never the set's own order, which
 * arrives however the server stored it.
 */
internal fun List<HubDay>.describe(): String {
    if (isEmpty()) return ""
    val ordered = HubDay.weekOrder.filter { it in this }
    return when {
        ordered == HubDay.weekdays -> str(S.desktop_hub_days_mon_fri)
        ordered.size == HubDay.weekOrder.size -> str(S.desktop_hub_days_every_day)
        ordered == listOf(HubDay.Saturday, HubDay.Sunday) -> str(S.desktop_hub_days_weekend)
        else -> ordered.joinToString(", ") { it.shortLabel }
    }
}

/** An address, or an em dash when there is none, so a row never has a hole. */
internal fun HubLocation.display(): String = address.ifBlank { "—" }

/** A row's search box, sized the same on every page. */
@Composable
internal fun HubSearch(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier.width(SEARCH_WIDTH),
    )
}

/** A label above a value, as the detail cards stack them. */
@Composable
internal fun HubFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            icon?.let {
                androidx.compose.material3.Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    modifier = Modifier.width(FACT_ICON),
                )
            }
            ZillitText(
                text = label,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = value.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A "from → to" line, the shape every ride and trip row leads with. */
@Composable
internal fun HubRouteLine(
    pickup: HubLocation,
    dropoff: HubLocation,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = pickup.display(),
            style = ZillitTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = "→",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = dropoff.display(),
            style = ZillitTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private val SEARCH_WIDTH = 320.dp
private val FACT_ICON = 14.dp

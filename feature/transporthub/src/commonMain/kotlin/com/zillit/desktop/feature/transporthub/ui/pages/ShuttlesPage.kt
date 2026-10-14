// A route card lays out a dozen fields and three lists; splitting it hides the layout.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.hubDate
import com.zillit.desktop.feature.transporthub.data.hubTime
import com.zillit.desktop.feature.transporthub.domain.ShuttleRoute
import com.zillit.desktop.feature.transporthub.domain.ShuttleRouteType
import com.zillit.desktop.feature.transporthub.domain.ShuttleRun
import com.zillit.desktop.feature.transporthub.domain.ShuttleRunStatus
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState
import com.zillit.desktop.feature.transporthub.ui.ShuttleTab

/**
 * Coordinator → Shuttles.
 *
 * Two tabs, as the web has: the standing **Routes**, and the **Runs**
 * generated from them. The two live under different prefixes on the service —
 * `/shuttle/routes` and `/shuttles/schedule` — which is the one thing about
 * this page that is easy to get wrong.
 *
 * A route needs both a driver and a vehicle before runs can be generated, and
 * the button says so rather than being dead: a coordinator who clicks it
 * learns why nothing happened.
 */
@Composable
internal fun ShuttlesPage(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.shuttles),
            description = when (state.shuttleTab) {
                ShuttleTab.Routes ->
                    str(S.desktop_hub_route_count, state.visibleShuttleRoutes.size.toString())

                ShuttleTab.Runs ->
                    str(S.desktop_hub_run_count, state.visibleShuttleRuns.size.toString())
            },
            actions = {
                if (state.shuttleTab == ShuttleTab.Routes) {
                    ZillitButton(
                        text = str(S.desktop_hub_new_route),
                        onClick = { onEvent(HubEvent.NewShuttleRoute) },
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            },
        )
        ZillitTabStrip(
            tabs = listOf(
                ZillitTab(ShuttleTab.Routes.name, str(S.routes), state.shuttleRoutes.size),
                ZillitTab(ShuttleTab.Runs.name, str(S.desktop_runs), state.shuttleRuns.size),
            ),
            activeId = state.shuttleTab.name,
            onSelect = { id -> onEvent(HubEvent.ShuttleTabChanged(ShuttleTab.valueOf(id))) },
        )
        HubSearch(
            value = state.shuttleSearch,
            placeholder = str(S.desktop_hub_shuttle_search_hint),
            onValueChange = { onEvent(HubEvent.ShuttleSearchChanged(it)) },
        )
        when (state.shuttleTab) {
            ShuttleTab.Routes -> RouteList(state, onEvent)
            ShuttleTab.Runs -> RunList(state, onEvent)
        }
    }
}

@Composable
private fun RouteList(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    if (state.visibleShuttleRoutes.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_routes),
            icon = ZillitIcons.Transport,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.visibleShuttleRoutes, key = { it.id }) { route ->
            RouteCard(
                route = route,
                busy = state.busyRowId == route.id,
                driverName = route.driverName.ifBlank { state.driverName(route.driverId) },
                vehicleLabel = route.vehicleLabel.ifBlank { state.vehicleLabel(route.vehicleId) },
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun RouteCard(
    route: ShuttleRoute,
    busy: Boolean,
    driverName: String,
    vehicleLabel: String,
    onEvent: (HubEvent) -> Unit,
) {
    ZillitSectionCard(
        // A disabled route is dimmed rather than hidden, so the list can be
        // triaged at a glance and a retired route is still readable.
        modifier = if (route.isActive) Modifier else Modifier.alpha(DISABLED_ALPHA),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = route.displayName,
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatusPill(label = route.type.label(), tone = StatusTone.Neutral)
                ZillitStatusPill(
                    label = if (route.isActive) str(S.active) else str(S.desktop_inactive),
                    tone = if (route.isActive) StatusTone.Done else StatusTone.Neutral,
                    dot = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.desktop_frequency),
                    value = route.frequencyMinutes?.let {
                        str(S.desktop_hub_every_minutes, it.toString())
                    }.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.dm_ds_unit_hours),
                    value = route.timeWindow,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.hint_capacity),
                    value = route.capacity?.toString().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.recce_label_stops),
                    value = route.stops.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(label = str(S.driver), value = driverName, modifier = Modifier.weight(1f))
                HubFact(
                    label = str(S.vehicle),
                    value = vehicleLabel,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_active_days),
                    value = route.daysActive.describe(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (route.stops.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitSectionLabel(text = str(S.recce_label_stops))
                    route.stops.forEach { stop ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = ZillitTheme.spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        ) {
                            ZillitText(
                                text = "${stop.order + 1}. " +
                                    stop.name.ifBlank { stop.location.display() },
                                style = ZillitTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (stop.estimatedTime.isNotBlank()) {
                                ZillitText(
                                    text = stop.estimatedTime,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = ZillitTheme.colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
            if (route.notes.isNotBlank()) {
                HubFact(label = str(S.notes), value = route.notes)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                // Not disabled when the route has no driver or vehicle: the
                // click explains what is missing, where a dead button would
                // only leave the coordinator guessing.
                ZillitButton(
                    text = str(S.desktop_hub_generate_runs),
                    onClick = { onEvent(HubEvent.GenerateShuttleRuns(route)) },
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = if (route.isActive) str(S.desktop_hub_disable) else str(S.desktop_enable),
                    onClick = { onEvent(HubEvent.ToggleShuttleRoute(route)) },
                    variant = if (route.isActive) ButtonVariant.Danger else ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(HubEvent.EditShuttleRoute(route)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(HubEvent.DeleteShuttleRoute(route)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun RunList(state: HubUiState, onEvent: (HubEvent) -> Unit) {
    val options = state.runRouteOptions
    // Only worth a filter once the schedule spans more than one route.
    if (options.size > 1) {
        ZillitSelect(
            value = options.firstOrNull { it.first == state.runRouteFilter },
            options = listOf(null) + options,
            onSelect = { onEvent(HubEvent.RunRouteFilterChanged(it?.first.orEmpty())) },
            label = { it?.second ?: str(S.desktop_hub_all_routes) },
            showInitials = false,
        )
    }
    if (state.visibleShuttleRuns.isEmpty()) {
        ZillitEmptyState(
            title = str(S.desktop_hub_no_runs),
            icon = ZillitIcons.Calendar,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        items(state.visibleShuttleRuns, key = { it.id }) { run ->
            RunCard(run = run, busy = state.busyRowId == run.id, onEvent = onEvent)
        }
    }
}

@Composable
private fun RunCard(run: ShuttleRun, busy: Boolean, onEvent: (HubEvent) -> Unit) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = run.title,
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                run.routeType?.let {
                    ZillitStatusPill(label = it.label(), tone = StatusTone.Neutral)
                }
                ZillitText(
                    text = "${run.occupancy}/${run.capacity}",
                    style = ZillitTheme.typography.titleSmall,
                )
                ZillitStatusPill(label = run.status.label(), tone = run.status.tone(), dot = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                // The date as well as the time: runs span several days, and
                // the time alone makes two of them look like one.
                HubFact(
                    label = str(S.date),
                    value = run.departureMs.hubDate(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_departs),
                    value = run.departureMs.hubTime(),
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.driver),
                    value = run.driverName.ifBlank { str(S.unassigned) },
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.vehicle),
                    value = run.vehicleLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            if (run.status.transitions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    run.status.transitions.forEach { next ->
                        ZillitButton(
                            text = next.actionLabel(),
                            onClick = { onEvent(HubEvent.SetShuttleRunStatus(run, next)) },
                            variant = if (next == ShuttleRunStatus.Cancelled) {
                                ButtonVariant.Danger
                            } else {
                                ButtonVariant.Primary
                            },
                            size = ButtonSize.Small,
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

internal fun ShuttleRouteType.label(): String = when (this) {
    ShuttleRouteType.Crew -> str(S.crew)
    ShuttleRouteType.Cast -> str(S.desktop_draft_cast)
    ShuttleRouteType.Vip -> str(S.desktop_hub_vip)
    ShuttleRouteType.Background -> str(S.desktop_hub_background)
    ShuttleRouteType.Other -> str(S.other)
}

internal fun ShuttleRunStatus.label(): String = when (this) {
    ShuttleRunStatus.Scheduled -> str(S.desktop_csync_when_scheduled)
    ShuttleRunStatus.Boarding -> str(S.desktop_hub_boarding)
    ShuttleRunStatus.Departed -> str(S.desktop_hub_departed)
    ShuttleRunStatus.InTransit -> str(S.in_progress)
    ShuttleRunStatus.Completed -> str(S.completed)
    ShuttleRunStatus.Cancelled -> str(S.cancelled)
}

/** What the button that moves a run *into* this status says. */
private fun ShuttleRunStatus.actionLabel(): String = when (this) {
    ShuttleRunStatus.Boarding -> str(S.desktop_hub_start_boarding)
    ShuttleRunStatus.InTransit -> str(S.desktop_hub_depart)
    ShuttleRunStatus.Completed -> str(S.dm_action_complete)
    ShuttleRunStatus.Cancelled -> str(S.cancel)
    ShuttleRunStatus.Scheduled, ShuttleRunStatus.Departed -> label()
}

internal fun ShuttleRunStatus.tone(): StatusTone = when (this) {
    ShuttleRunStatus.Scheduled -> StatusTone.Ready
    ShuttleRunStatus.Boarding -> StatusTone.Pending
    ShuttleRunStatus.Departed -> StatusTone.Progress
    ShuttleRunStatus.InTransit -> StatusTone.InTransit
    ShuttleRunStatus.Completed -> StatusTone.Done
    ShuttleRunStatus.Cancelled -> StatusTone.Rejected
}

/** How far a retired route is faded — readable, but out of the way. */
private const val DISABLED_ALPHA = 0.62f

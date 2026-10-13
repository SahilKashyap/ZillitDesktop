@file:Suppress("LongMethod") // A card lays out a dozen fields; splitting it hides the layout.

package com.zillit.desktop.feature.transporthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyVerticalGrid
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.domain.Driver
import com.zillit.desktop.feature.transporthub.ui.DriverTab
import com.zillit.desktop.feature.transporthub.ui.HubEvent
import com.zillit.desktop.feature.transporthub.ui.HubUiState

/**
 * Coordinator → Drivers.
 *
 * The four stat tiles double as the filter, as the web's do: a count that
 * cannot be clicked through is a count a coordinator has to go looking for.
 * The last one is not a slice of the list — it is the chase-list, which has
 * its own query so the server can resolve what each record is missing.
 */
@Composable
internal fun DriversPage(
    state: HubUiState,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit = {},
) {
    val counts = state.driverCounts
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = str(S.drivers),
            description = str(S.desktop_hub_driver_count, state.visibleDrivers.size.toString()),
            actions = {
                ZillitButton(
                    text = str(S.driver_title_new),
                    onClick = { onEvent(HubEvent.NewDriver) },
                    leadingIcon = ZillitIcons.UserPlus,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            DriverTab.entries.forEach { tab ->
                ZillitStatTile(
                    label = tab.label(),
                    value = counts[tab].orZero(),
                    sub = tab.hint(counts[DriverTab.Incomplete] ?: 0),
                    tone = tab.tone(state.driverTab == tab),
                    icon = tab.icon(),
                    onClick = { onEvent(HubEvent.DriverTabChanged(tab)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // The chase-list carries its own rows and its own columns, so it has
        // no use for the free-text search over the main list.
        if (state.driverTab != DriverTab.Incomplete) {
            HubSearch(
                value = state.driverSearch,
                placeholder = str(S.desktop_hub_driver_search_hint),
                onValueChange = { onEvent(HubEvent.DriverSearchChanged(it)) },
            )
        }

        if (state.visibleDrivers.isEmpty()) {
            ZillitEmptyState(
                title = if (state.driverTab == DriverTab.Incomplete) {
                    str(S.desktop_hub_driver_all_complete)
                } else {
                    str(S.desktop_hub_no_drivers)
                },
                icon = ZillitIcons.Users,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZillitLazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_MIN_WIDTH),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                items(state.visibleDrivers, key = { it.id }) { driver ->
                    DriverCard(
                        driver = driver,
                        chaseList = state.driverTab == DriverTab.Incomplete,
                        busy = state.busyRowId == driver.id,
                        onEvent = onEvent,
                        openLink = openLink,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverCard(
    driver: Driver,
    chaseList: Boolean,
    busy: Boolean,
    onEvent: (HubEvent) -> Unit,
    openLink: (String) -> Unit,
) {
    ZillitSectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = driver.displayName.ifBlank { "—" },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (driver.isTempDriver) {
                    ZillitStatusPill(
                        label = str(S.desktop_hub_temp_driver),
                        tone = StatusTone.Escalated,
                    )
                }
                ZillitStatusPill(
                    label = if (driver.isOnDuty) {
                        str(S.desktop_hub_on_duty)
                    } else {
                        str(S.desktop_hub_off_duty)
                    },
                    tone = if (driver.isOnDuty) StatusTone.Done else StatusTone.Neutral,
                    dot = true,
                )
            }
            if (driver.designation.isNotBlank()) {
                ZillitText(
                    text = driver.designation,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HubFact(
                    label = str(S.phone),
                    value = driver.phone,
                    icon = ZillitIcons.Phone,
                    modifier = Modifier.weight(1f),
                )
                HubFact(
                    label = str(S.desktop_hub_licence_number),
                    value = driver.licenseNumber,
                    modifier = Modifier.weight(1f),
                )
            }
            // On the chase-list the point of the row is what is missing, so
            // the server's own list of fields is shown rather than inferred.
            if (chaseList && driver.missingFields.isNotEmpty()) {
                ZillitText(
                    text = str(
                        S.desktop_hub_missing_fields,
                        driver.missingFields.joinToString(", "),
                    ),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                if (chaseList) {
                    ZillitButton(
                        text = str(S.drivers_review_complete),
                        onClick = { onEvent(HubEvent.CompleteDriverDetails(driver)) },
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                } else {
                    ZillitButton(
                        text = if (driver.isOnDuty) {
                            str(S.desktop_hub_go_off_duty)
                        } else {
                            str(S.desktop_hub_go_on_duty)
                        },
                        onClick = { onEvent(HubEvent.ToggleDriverDuty(driver)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !busy,
                    )
                }
                // The hub has no map of its own, so a driver's last reported
                // position opens in the browser. A `{0,0}` fix is dropped by
                // the parser, so a button here means a real position.
                driver.lastLocation?.let { position ->
                    ZillitButton(
                        text = str(S.desktop_hub_locate),
                        onClick = {
                            openLink(
                                "https://www.google.com/maps/search/?api=1&query=" +
                                    "${position.lat},${position.long}",
                            )
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(HubEvent.DeleteDriver(driver)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

internal fun DriverTab.label(): String = when (this) {
    DriverTab.All -> str(S.asset_total)
    DriverTab.Available -> str(S.available)
    DriverTab.Unavailable -> str(S.unavailable)
    DriverTab.Incomplete -> str(S.drivers_incomplete)
}

private fun DriverTab.hint(incompleteCount: Int): String = when (this) {
    DriverTab.All -> str(S.desktop_hub_driver_total_hint)
    DriverTab.Available -> str(S.desktop_hub_driver_available_hint)
    DriverTab.Unavailable -> str(S.desktop_hub_driver_unavailable_hint)
    DriverTab.Incomplete -> if (incompleteCount > 0) {
        str(S.drivers_incomplete_caption)
    } else {
        str(S.desktop_hub_driver_all_complete)
    }
}

private fun DriverTab.icon() = when (this) {
    DriverTab.All -> ZillitIcons.Users
    DriverTab.Available -> ZillitIcons.Check
    DriverTab.Unavailable -> ZillitIcons.Close
    DriverTab.Incomplete -> ZillitIcons.Warning
}

/**
 * The tile's hue.
 *
 * Only the chosen tile is tinted, so the strip reads as a filter rather than
 * four permanently coloured badges — except the chase-list, which stays
 * amber whenever it has rows, because it is asking to be dealt with.
 */
private fun DriverTab.tone(active: Boolean): StatusTone? = when {
    this == DriverTab.Incomplete -> StatusTone.Pending
    !active -> null
    this == DriverTab.Available -> StatusTone.Done
    this == DriverTab.Unavailable -> StatusTone.Rejected
    else -> StatusTone.Progress
}

private fun Int?.orZero(): String = (this ?: 0).toString()

private val CARD_MIN_WIDTH = 340.dp

@file:Suppress("LongMethod") // Screens are linear layouts; splitting hides the page.

package com.zillit.desktop.feature.recce.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.ui.pages.RecceDetailPage
import com.zillit.desktop.feature.recce.ui.pages.RecceFormPage

/** The recce tool: the list, one recce, or the form — the web's three routes in one window. */
@Composable
fun RecceScreen(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (val page = state.page) {
            ReccePage.Index -> IndexPage(state, onEvent)
            is ReccePage.Detail -> RecceDetailPage(state, onEvent)
            is ReccePage.Form -> RecceFormPage(state, onEvent, editing = page.id != null)
        }
        state.confirmDelete?.let { id ->
            val recce = state.recces.firstOrNull { it.id == id }
            ZillitDialogShell(
                title = "Delete recce",
                onDismiss = { onEvent(RecceEvent.CancelDelete) },
                visible = true,
                actions = {
                    ZillitButton(
                        text = "Cancel",
                        onClick = { onEvent(RecceEvent.CancelDelete) },
                        variant = ButtonVariant.Tertiary,
                    )
                    ZillitButton(
                        text = "Delete",
                        onClick = { onEvent(RecceEvent.ConfirmDelete) },
                        variant = ButtonVariant.Danger,
                        loading = state.busy,
                    )
                },
            ) {
                ZillitText(
                    text = "Delete \"${recce?.title?.ifBlank { "Untitled recce" } ?: "this recce"}\"? " +
                        "The crew will no longer see it.",
                    style = ZillitTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun IndexPage(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            title = "Recce",
            description = "Location scout schedules — personnel, timings and locations for every stop.",
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(RecceEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    loading = state.loading,
                )
                ZillitButton(
                    text = "Create Recce",
                    onClick = { onEvent(RecceEvent.New) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )
        if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Recce tool.")
        ErrorNotice(state, onEvent)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitTabStrip(
                tabs = listOf(
                    ZillitTab(RecceFilter.All.name, "All (${state.recces.size})"),
                    ZillitTab(RecceFilter.Published.name, "Published (${state.publishedCount})"),
                    ZillitTab(RecceFilter.Drafts.name, "Drafts (${state.draftCount})"),
                ),
                activeId = state.filter.name,
                onSelect = { id -> onEvent(RecceEvent.Filter(RecceFilter.valueOf(id))) },
                modifier = Modifier.width(TABS_WIDTH),
            )
            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(RecceEvent.Search(it)) },
                placeholder = "Search location or rendezvous…",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (state.unitsInList.isNotEmpty()) {
                ZillitSelect(
                    value = state.unitFilter?.let { id -> state.unitsInList.firstOrNull { it.id == id } },
                    options = listOf<ProductionUnit?>(null) + state.unitsInList,
                    onSelect = { onEvent(RecceEvent.FilterUnit(it?.id)) },
                    label = { it?.name ?: "All units" },
                    modifier = Modifier.width(UNIT_WIDTH),
                )
            }
        }
        when {
            state.loading && state.recces.isEmpty() ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ZillitSpinner() }
            state.recces.isEmpty() -> EmptyLine("No recces yet")
            state.visible.isEmpty() -> EmptyLine("No recces match your filters")
            else -> RecceTable(state, onEvent)
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
}

@Composable
internal fun ErrorNotice(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            action = {
                ZillitButton(
                    text = "Dismiss",
                    onClick = { onEvent(RecceEvent.DismissError) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
    }
}

@Composable
private fun RecceTable(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        ) {
            HeaderCell("Recce", Modifier.weight(TITLE_WEIGHT))
            HeaderCell("Unit", Modifier.weight(1f))
            HeaderCell("Date", Modifier.weight(1f))
            HeaderCell("Rendezvous", Modifier.weight(1f))
            HeaderCell("Stops", Modifier.width(NARROW))
            HeaderCell("Personnel", Modifier.width(NARROW))
            Box(Modifier.width(ACTION_WIDTH))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            items(state.visible, key = { it.id }) { recce -> RecceRow(state, recce, onEvent, colors) }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

@Composable
private fun RecceRow(
    state: RecceUiState,
    recce: Recce,
    onEvent: (RecceEvent) -> Unit,
    colors: com.zillit.desktop.core.designsystem.ZillitColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.medium)
            .clickable { onEvent(RecceEvent.Open(recce.id)) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(TITLE_WEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = recce.title.ifBlank { "Untitled recce" },
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            if (!recce.isPublished) ZillitStatusPill(label = "Draft", tone = StatusTone.Pending)
        }
        ZillitText(
            text = state.unitName(recce.unit).ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = RecceClock.dateLabel(recce.dateMs).ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = RecceClock.hm(recce.rdv.timeMs).ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
            )
            if (recce.station.isNotBlank()) {
                ZillitText(text = recce.station, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        ZillitText(
            text = recce.locationCount.toString(),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.width(NARROW),
        )
        ZillitText(
            text = recce.realPersonnel.size.toString(),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.width(NARROW),
        )
        Row(Modifier.width(ACTION_WIDTH), horizontalArrangement = Arrangement.End) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = { onEvent(RecceEvent.Delete(recce.id)) },
                tint = colors.textMuted,
            )
        }
    }
}

private val TABS_WIDTH = 380.dp
private val SEARCH_WIDTH = 280.dp
private val UNIT_WIDTH = 200.dp
private val NARROW = 80.dp
private val ACTION_WIDTH = 48.dp
private const val TITLE_WEIGHT = 2f

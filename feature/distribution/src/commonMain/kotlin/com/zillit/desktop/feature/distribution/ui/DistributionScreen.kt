package com.zillit.desktop.feature.distribution.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser

/**
 * The Distribution List: the crew roster on the left in the server's
 * department-priority order, and the picked person's unit switches on the
 * right — "make selection to distribute content, via email, that is uploaded
 * in various sections of Home & Tools."
 */
@Composable
fun DistributionScreen(
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.isBlocked -> Centred("You don't have access to the Distribution List.")
            state.isLoading && state.users.isEmpty() -> Centred("Loading…")
            else -> Panes(state, onEvent)
        }
    }
}

@Composable
private fun Panes(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Roster(state, onEvent)
        AccessPanel(state, onEvent)
    }
}

@Composable
private fun Roster(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .width(ROSTER_WIDTH)
            .fillMaxHeight()
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "Distribution",
            style = ZillitTheme.typography.titleLarge,
            modifier = Modifier.padding(ZillitTheme.spacing.xs),
        )
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(DistributionEvent.Search(it)) },
            placeholder = "Search",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitCheckbox(
                checked = state.externalOnly,
                onCheckedChange = { onEvent(DistributionEvent.ExternalOnly(it)) },
            )
            ZillitText(
                text = "External users only",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(rememberWheelScroll(listState)),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            items(state.listed, key = DistributionUser::userId) { user ->
                RosterRow(
                    user = user,
                    isSelected = user.userId == state.selected?.userId,
                    onClick = { onEvent(DistributionEvent.Select(user.userId)) },
                )
            }
        }
    }
}

@Composable
private fun RosterRow(user: DistributionUser, isSelected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isSelected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> colors.surface
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = user.userName.ifBlank { user.userId })
        ZillitText(
            text = user.userName.ifBlank { user.userId },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (user.isExternal) ZillitTag("Outsider", tone = TagTone.Success)
    }
}

@Composable
private fun AccessPanel(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val user = state.selected

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (user == null) {
            Centred("Pick someone to see their distribution.")
            return
        }

        PanelHeading(user, state, onEvent)

        ColumnHeadings()

        val rows = user.units
            .filter { if (state.section == DistributionSection.Home) it.isHome else it.isTool }
            .sortedBy { it.unitName.localised().lowercase() }

        if (rows.isEmpty()) {
            Centred("Nothing under ${state.section.label} for this user.")
            return
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(rememberWheelScroll(listState)),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            items(rows, key = DistributionUnit::unitId) { unit ->
                UnitRow(
                    unit = unit,
                    enabled = state.viewer.mayToggle &&
                        unit.toUpdatable &&
                        "${user.userId}:${unit.unitId}" !in state.pending,
                    onToggle = { on ->
                        onEvent(DistributionEvent.Toggle(user.userId, unit.unitId, on))
                    },
                )
            }
        }
    }
}

/** Who is selected, and which section of their distribution is showing. */
@Composable
private fun PanelHeading(
    user: DistributionUser,
    state: DistributionUiState,
    onEvent: (DistributionEvent) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = user.userName.ifBlank { user.userId },
            style = ZillitTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        DistributionSection.entries.forEach { section ->
            ZillitChoiceChip(
                label = section.label,
                selected = state.section == section,
                onClick = { onEvent(DistributionEvent.Section(section)) },
            )
        }
    }
}

/** The two column captions over the list. */
@Composable
private fun ColumnHeadings() {
    Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.sm)) {
        ZillitText(
            text = "DISTRIBUTION",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = "TO",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun UnitRow(unit: DistributionUnit, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = unit.unitName.localised(),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitCheckbox(
            checked = unit.toEnabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val ROSTER_WIDTH = androidx.compose.ui.unit.Dp(280f)

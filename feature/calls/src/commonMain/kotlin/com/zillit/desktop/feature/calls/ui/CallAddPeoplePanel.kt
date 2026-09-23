package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallCrewEntry

/**
 * The add-people side panel — the roster panel's sibling, not a modal.
 *
 * A dialog over a live call steals the stage exactly when the user wants to
 * watch it; Android learned the same and uses a bottom sheet. Search first,
 * because a feature-film crew runs to hundreds and the picker's whole job is
 * getting one name out of them.
 */
@Composable
internal fun CallAddPeoplePanel(
    crew: List<CallCrewEntry>,
    onPick: (CallCrewEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val shown = remember(crew, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            crew
        } else {
            crew.filter {
                it.name.contains(needle, ignoreCase = true) ||
                    it.designation.contains(needle, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PANEL_RADIUS))
            .background(colors.surfaceRaised)
            .border(PANEL_HAIRLINE, colors.border, RoundedCornerShape(PANEL_RADIUS))
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.desktop_call_add_people),
            style = ZillitTheme.typography.titleSmall,
            color = colors.textPrimary,
        )
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.desktop_search_crew),
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZillitText(
                    text = if (crew.isEmpty()) {
                        str(S.desktop_call_everyone_already_here)
                    } else {
                        str(S.dm_nda_no_one_matches)
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        } else {
            ZillitLazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = CallCrewEntry::userId) { entry ->
                    CrewRow(entry, onPick)
                }
            }
        }
    }
}

@Composable
private fun CrewRow(entry: CallCrewEntry, onPick: (CallCrewEntry) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_RADIUS))
            .clickable { onPick(entry) }
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = entry.name, userId = entry.userId, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = entry.name,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
            )
            if (entry.designation.isNotBlank()) {
                ZillitText(
                    text = entry.designation,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val PANEL_RADIUS = 16.dp
private val PANEL_HAIRLINE = 1.dp
private val ROW_RADIUS = 10.dp
private val ROW_AVATAR = 32.dp

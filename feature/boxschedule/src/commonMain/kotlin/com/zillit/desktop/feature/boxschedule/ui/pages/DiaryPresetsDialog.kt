package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import com.zillit.desktop.feature.boxschedule.ui.PresetForm
import com.zillit.desktop.feature.boxschedule.ui.PresetsPanel

/**
 * "Presets" — `PresetListModal`: saved groups of users to distribute to.
 * Anyone may browse them; creating, editing and deleting need posting rights.
 */
@Composable
internal fun PresetsDialog(state: BoxScheduleUiState, panel: PresetsPanel, onEvent: (BoxScheduleEvent) -> Unit) {
    val form = panel.form
    if (form != null) {
        PresetFormDialog(state, form, onEvent)
    } else {
        PresetListDialog(state, panel, onEvent)
    }
    panel.confirmDelete?.let {
        ConfirmDialog(
            title = "Delete preset?",
            message = "This preset will no longer be offered when distributing events and notes.",
            confirm = "Delete",
            working = panel.deleting,
            onConfirm = { onEvent(PanelEvent.ConfirmDeletePreset) },
            onCancel = { onEvent(PanelEvent.CancelDeletePreset) },
        )
    }
}

@Composable
private fun PresetListDialog(state: BoxScheduleUiState, panel: PresetsPanel, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val q = panel.search.trim()
    val visible = panel.presets.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    ZillitDialogShell(
        title = "Presets",
        icon = ZillitIcons.StarOutline,
        onDismiss = { onEvent(PanelEvent.ClosePresets) },
        visible = true,
        width = 560.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZillitSearchField(
                value = panel.search,
                onValueChange = { onEvent(PanelEvent.SearchPresets(it)) },
                placeholder = "Search presets...",
                modifier = Modifier.weight(1f),
            )
            if (state.mayEdit) {
                ZillitButton("New Preset", onClick = { onEvent(PanelEvent.NewPreset) }, leadingIcon = ZillitIcons.Add)
            }
        }
        when {
            panel.loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner() }
            visible.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZillitText(
                    if (panel.presets.isEmpty()) "No presets yet" else "No matches",
                    style = ZillitTheme.typography.titleSmall,
                    color = colors.textSecondary,
                )
                ZillitText(
                    text = when {
                        panel.presets.isNotEmpty() -> "Try a different search term."
                        state.mayEdit -> "Create your first preset to group users."
                        else -> "No presets have been created."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { preset ->
                    PresetRow(state, preset, expanded = panel.membersOf == preset.id, onEvent = onEvent)
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    state: BoxScheduleUiState,
    preset: UserPreset,
    expanded: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column {
        SelectRow(
            selected = false,
            onClick = { onEvent(PanelEvent.ShowPresetMembers(if (expanded) null else preset.id)) },
        ) {
            ZillitIcon(icon = ZillitIcons.StarOutline, tint = colors.accentText, size = 20.dp)
            Column(Modifier.weight(1f)) {
                ZillitText(preset.name, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                ZillitText(
                    "${preset.memberCount} member${if (preset.memberCount == 1) "" else "s"}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            if (state.mayEdit) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit preset",
                    onClick = { onEvent(PanelEvent.EditPreset(preset.id)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete preset",
                    onClick = { onEvent(PanelEvent.AskDeletePreset(preset.id)) },
                    tint = colors.danger,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Info,
                contentDescription = "View members",
                onClick = { onEvent(PanelEvent.ShowPresetMembers(if (expanded) null else preset.id)) },
            )
        }
        if (expanded) PresetMembers(preset)
    }
}

@Composable
private fun PresetFormDialog(state: BoxScheduleUiState, form: PresetForm, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = if (form.isEdit) "Edit Preset" else "New Preset",
        icon = ZillitIcons.StarOutline,
        onDismiss = { onEvent(PanelEvent.BackToPresets) },
        visible = true,
        width = 560.dp,
        actions = {
            ZillitButton("Cancel", onClick = { onEvent(PanelEvent.BackToPresets) }, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (form.isEdit) "Update Preset" else "Save Preset",
                onClick = { onEvent(PanelEvent.SavePreset) },
                enabled = form.canSave,
                loading = form.saving,
            )
        },
    ) {
        ZillitButton(
            "Back to presets",
            onClick = { onEvent(PanelEvent.BackToPresets) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Preset Name")
            ZillitTextField(
                value = form.name,
                onValueChange = { onEvent(PanelEvent.SetPresetName(it)) },
                placeholder = "e.g., My Crew",
                maxLength = PresetForm.NAME_LIMIT,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PresetMemberPicker(state, form, onEvent)
    }
}

/** "Select Users" — search, Select All over the matches, and a tick per person. */
@Composable
private fun PresetMemberPicker(state: BoxScheduleUiState, form: PresetForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val q = form.query.trim()
    val visible = state.people.filter { it.matches(q) }
    val allIds = visible.map { it.id }
    val allSelected = allIds.isNotEmpty() && form.userIds.containsAll(allIds)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("Select Users (${form.userIds.size})")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZillitSearchField(
                value = form.query,
                onValueChange = { onEvent(PanelEvent.SetPresetQuery(it)) },
                placeholder = "Select users...",
                modifier = Modifier.weight(1f),
            )
            if (allIds.isNotEmpty()) {
                ZillitButton(
                    text = if (allSelected) "Clear All" else "Select All (${allIds.size})",
                    onClick = { onEvent(PanelEvent.SetPresetUsers(toggleAll(form.userIds, allIds, allSelected))) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).zillitVerticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visible.isEmpty()) {
                ZillitText(
                    if (state.people.isEmpty()) "No users yet" else "No users match your search",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            visible.forEach { person ->
                PersonRow(person, person.id in form.userIds) { onEvent(PanelEvent.TogglePresetUser(person.id)) }
            }
        }
    }
}

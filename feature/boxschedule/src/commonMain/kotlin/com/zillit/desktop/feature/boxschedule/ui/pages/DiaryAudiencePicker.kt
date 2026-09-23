package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.plural
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryPerson
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import com.zillit.desktop.feature.boxschedule.ui.AudiencePicker
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.GuestsDialog
import com.zillit.desktop.feature.boxschedule.ui.rememberDiaryFace

private data class AudienceTabSpec(val mode: AudienceMode, val labelKey: String, val icon: ImageVector)

private val TABS = listOf(
    AudienceTabSpec(AudienceMode.AllDepartments, S.invitees_tab_all_depts, ZillitIcons.Grid),
    AudienceTabSpec(AudienceMode.Departments, S.invitees_tab_departments, ZillitIcons.Users),
    AudienceTabSpec(AudienceMode.Users, S.invitees_tab_users, ZillitIcons.User),
    AudienceTabSpec(AudienceMode.Presets, S.invitees_tab_preset, ZillitIcons.StarOutline),
    AudienceTabSpec(AudienceMode.Self, S.invitees_tab_self, ZillitIcons.User),
)

/** "Select Invitees" — `SelectInviteesModal`: five tabs, one choice, Done (N). */
@Composable
internal fun AudiencePickerDialog(
    state: BoxScheduleUiState,
    picker: AudiencePicker,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val done = picker.doneCount(state.people.size)
    ZillitDialogShell(
        title = str(S.invitees_title),
        onDismiss = { onEvent(EntryEvent.CloseAudience) },
        visible = true,
        width = 760.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(EntryEvent.CloseAudience) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                str(S.invitees_done_count, done),
                onClick = { onEvent(EntryEvent.AudienceDone) },
                enabled = done > 0,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TABS.forEach { tab ->
                TabPill(tab, active = picker.tab == tab.mode) { onEvent(EntryEvent.AudienceTab(tab.mode)) }
            }
        }
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = BODY_MIN)) {
            when (picker.tab) {
                AudienceMode.AllDepartments -> ConfirmCard(
                    icon = ZillitIcons.Grid,
                    title = str(S.invitees_all_dept_title, state.people.size),
                    subtitle = str(S.invitees_all_dept_subtitle),
                    on = picker.allDepartments,
                    offLabel = str(S.invitees_select_all),
                    onToggle = { onEvent(EntryEvent.AudienceToggleAllDepartments) },
                )
                AudienceMode.Departments -> DepartmentsTab(picker, onEvent)
                AudienceMode.Users -> UsersTab(state, picker, onEvent)
                AudienceMode.Presets -> PresetsTab(picker, onEvent)
                AudienceMode.Self -> ConfirmCard(
                    icon = ZillitIcons.User,
                    title = str(S.invitees_self_title),
                    subtitle = str(S.invitees_self_subtitle),
                    on = picker.self,
                    offLabel = str(S.invitees_select_me),
                    onToggle = { onEvent(EntryEvent.AudienceToggleSelf) },
                )
                AudienceMode.None -> Unit
            }
        }
    }
}

@Composable
private fun TabPill(tab: AudienceTabSpec, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) colors.accent else colors.surfaceSunken)
            .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(icon = tab.icon, tint = if (active) colors.textOnAccent else colors.textSecondary, size = 12.dp)
        ZillitText(
            str(tab.labelKey),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) colors.textOnAccent else colors.textSecondary,
        )
    }
}

@Composable
private fun ConfirmCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    on: Boolean,
    offLabel: String,
    onToggle: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            ZillitIcon(icon = icon, tint = colors.accentText, size = 28.dp)
        }
        ZillitText(title, style = ZillitTheme.typography.titleMedium, textAlign = TextAlign.Center)
        ZillitText(
            subtitle,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        ZillitButton(
            text = if (on) str(S.invitees_selected_check) else offLabel,
            onClick = onToggle,
            variant = if (on) ButtonVariant.Primary else ButtonVariant.Secondary,
        )
    }
}

@Composable
private fun SearchWithAll(
    query: String,
    placeholder: String,
    allSelected: Boolean,
    showAll: Boolean,
    onQuery: (String) -> Unit,
    onAll: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        if (showAll) {
            ZillitButton(
                str(if (allSelected) S.txt_clear_all else S.invitees_select_all),
                onClick = onAll,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun DepartmentsTab(picker: AudiencePicker, onEvent: (BoxScheduleEvent) -> Unit) {
    val q = picker.departmentQuery.trim()
    val visible = picker.departments.filter { q.isEmpty() || it.name.localised().contains(q, ignoreCase = true) }
    val visibleIds = visible.map { it.id }
    val allSelected = visibleIds.isNotEmpty() && picker.departmentIds.containsAll(visibleIds)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchWithAll(
            query = picker.departmentQuery,
            placeholder = str(S.invitees_search_departments),
            allSelected = allSelected,
            showAll = visibleIds.isNotEmpty(),
            onQuery = { onEvent(EntryEvent.AudienceQuery(it)) },
            onAll = {
                onEvent(EntryEvent.AudienceSetDepartments(toggleAll(picker.departmentIds, visibleIds, allSelected)))
            },
        )
        when {
            picker.departmentsLoading -> Loading()
            picker.departments.isEmpty() -> Empty(str(S.invitees_no_departments))
            visible.isEmpty() -> Empty(str(S.desktop_hub_no_departments_match_your_search))
            else -> ScrollList {
                visible.forEach { department ->
                    SelectRow(
                        selected = department.id in picker.departmentIds,
                        onClick = { onEvent(EntryEvent.AudienceToggleDepartment(department.id)) },
                    ) {
                        ZillitCheckbox(
                            checked = department.id in picker.departmentIds,
                            onCheckedChange = { onEvent(EntryEvent.AudienceToggleDepartment(department.id)) },
                        )
                        ZillitIcon(icon = ZillitIcons.Users, tint = ZillitTheme.colors.textMuted, size = 14.dp)
                        ZillitText(
                            department.name.localised().ifBlank { str(S.desktop_unnamed) },
                            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsersTab(state: BoxScheduleUiState, picker: AudiencePicker, onEvent: (BoxScheduleEvent) -> Unit) {
    val q = picker.userQuery.trim()
    val visible = remember(state.people, q, picker.pinned) {
        state.people
            .filter { it.matches(q) }
            .sortedBy { if (it.id in picker.pinned) 0 else 1 }
    }
    val visibleIds = visible.map { it.id }
    val allSelected = visibleIds.isNotEmpty() && picker.userIds.containsAll(visibleIds)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchWithAll(
            query = picker.userQuery,
            placeholder = str(S.invitees_search_users),
            allSelected = allSelected,
            showAll = visibleIds.isNotEmpty(),
            onQuery = { onEvent(EntryEvent.AudienceQuery(it)) },
            onAll = {
                onEvent(EntryEvent.AudienceSetUsers(toggleAll(picker.userIds, visibleIds, allSelected)))
            },
        )
        when {
            state.people.isEmpty() -> Empty(str(S.invitees_no_users))
            visible.isEmpty() -> Empty(str(S.desktop_bs_no_users_match_search))
            else -> ScrollList {
                visible.forEach { person ->
                    PersonRow(person, selected = person.id in picker.userIds) {
                        onEvent(EntryEvent.AudienceToggleUser(person.id))
                    }
                }
            }
        }
    }
}

/** A crew member to tick — face, name, an ADMIN tag, department and role. */
@Composable
internal fun PersonRow(person: DiaryPerson, selected: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    SelectRow(selected = selected, onClick = onToggle) {
        ZillitCheckbox(checked = selected, onCheckedChange = { onToggle() })
        ZillitAvatar(name = person.fullName, image = rememberDiaryFace(person.id), userId = person.id, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    person.fullName,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                if (person.isAdmin) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentSoft)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        ZillitText(
                            str(S.desktop_bs_admin_upper),
                            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.accentText,
                        )
                    }
                }
            }
            val subtitle = listOf(
                person.department.localised(),
                person.designation.localised(),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                ZillitText(
                    subtitle,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PresetsTab(picker: AudiencePicker, onEvent: (BoxScheduleEvent) -> Unit) {
    val q = picker.presetQuery.trim()
    val visible = picker.presets.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitSearchField(
            value = picker.presetQuery,
            onValueChange = { onEvent(EntryEvent.AudienceQuery(it)) },
            placeholder = str(S.invitees_search_presets),
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitText(
            str(S.desktop_bs_preset_info_hint),
            style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
            color = ZillitTheme.colors.textMuted,
        )
        when {
            picker.presetsLoading -> Loading()
            visible.isEmpty() ->
                Empty(str(if (q.isNotEmpty()) S.desktop_bs_no_presets_match_search else S.invitees_no_presets))
            else -> ScrollList {
                visible.forEach { preset ->
                    PresetChoice(
                        preset,
                        selected = picker.presetId == preset.id,
                        expanded = picker.membersOf == preset.id,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChoice(
    preset: UserPreset,
    selected: Boolean,
    expanded: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column {
        SelectRow(selected = selected, onClick = { onEvent(EntryEvent.AudiencePreset(preset.id)) }) {
            RadioMark(selected)
            ZillitIcon(icon = ZillitIcons.StarOutline, tint = colors.accentText, size = 14.dp)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    preset.name,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    plural(S.invitees_preset_members, preset.memberCount),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Info,
                contentDescription = str(S.desktop_bs_view_members),
                onClick = { onEvent(EntryEvent.AudienceMembers(if (expanded) null else preset.id)) },
            )
        }
        if (expanded) PresetMembers(preset)
    }
}

/** A preset's members, under its row. */
@Composable
internal fun PresetMembers(preset: UserPreset) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (preset.members.isEmpty()) {
            ZillitText(str(S.desktop_no_members), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        preset.members.forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitAvatar(
                    name = member.fullName,
                    image = rememberDiaryFace(member.id),
                    userId = member.id,
                    size = 26.dp,
                )
                Column {
                    ZillitText(
                        member.fullName.ifBlank { str(S.desktop_unnamed_user) },
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    )
                    member.designation.localised().takeIf { it.isNotBlank() }?.let {
                        ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                if (selected) colors.accent.tint(SELECTED_BORDER) else colors.border,
                RoundedCornerShape(8.dp),
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun ScrollList(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = LIST_MAX).zillitVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { ZillitSpinner() }
}

@Composable
private fun Empty(text: String) {
    ZillitText(
        text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

/** "External Guests" — addresses typed one at a time, each removable. */
@Composable
internal fun GuestsDialogView(dialog: GuestsDialog, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.external_guests),
        subtitle = str(S.desktop_bs_external_guests_subtitle),
        onDismiss = { onEvent(EntryEvent.CloseGuests) },
        visible = true,
        width = 460.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(EntryEvent.CloseGuests) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(str(S.done_text), onClick = { onEvent(EntryEvent.GuestsDone) })
        },
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitTextField(
                value = dialog.draft,
                onValueChange = { onEvent(EntryEvent.GuestDraft(it)) },
                placeholder = "name@example.com",
                errorText = dialog.error,
                leadingIcon = ZillitIcons.Mail,
                onImeAction = { onEvent(EntryEvent.AddGuest) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                str(S.add),
                onClick = { onEvent(EntryEvent.AddGuest) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
                enabled = dialog.draft.isNotBlank(),
            )
        }
        if (dialog.emails.isEmpty()) {
            ZillitText(
                str(S.desktop_bs_no_external_guests),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        dialog.emails.forEach { mail -> GuestRow(mail) { onEvent(EntryEvent.RemoveGuest(mail)) } }
    }
}

@Composable
private fun GuestRow(mail: String, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Mail, tint = colors.textMuted, size = 14.dp)
        ZillitText(mail, style = ZillitTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.bs_chip_remove, mail),
            onClick = onRemove,
            tint = colors.danger,
        )
    }
}

/** "Set Reminder on Home Calendar?" — asked once, before a new event is sent. Closing it is No. */
@Composable
internal fun CalendarReminderPrompt(onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_bs_home_calendar_reminder_title),
        onDismiss = { onEvent(EntryEvent.AnswerCalendar(mirror = false)) },
        visible = true,
        icon = ZillitIcons.Bell,
        width = 420.dp,
        actions = {
            ZillitButton(
                str(S.no),
                onClick = { onEvent(EntryEvent.AnswerCalendar(mirror = false)) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(str(S.yes), onClick = { onEvent(EntryEvent.AnswerCalendar(mirror = true)) })
        },
    ) {
        ZillitText(
            str(S.desktop_bs_home_calendar_reminder_body),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The pickers' search: name, department and role read as one line, so "asha camera" finds Asha in Camera. */
internal fun DiaryPerson.matches(query: String): Boolean =
    query.isEmpty() || listOf(fullName, department, designation).joinToString(" ").contains(query, ignoreCase = true)

/** Select All over the rows on screen, or clear exactly those rows — the rest of the choice stays. */
internal fun toggleAll(current: List<String>, visible: List<String>, allSelected: Boolean): List<String> =
    if (allSelected) current - visible.toSet() else (current + visible).distinct()

private val BODY_MIN = 360.dp
private val LIST_MAX = 340.dp
private const val SELECTED_BORDER = 0.5f

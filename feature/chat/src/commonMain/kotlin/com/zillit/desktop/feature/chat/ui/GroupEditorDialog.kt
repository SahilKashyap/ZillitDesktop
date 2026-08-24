package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.searchCrew

/**
 * The create-group dialog and its view model's lifetime, in one place.
 *
 * Kept composed by the caller with [visible] driven, as [ZillitDialogShell]
 * asks; each opening resets the editor so a dismissed draft never haunts the
 * next group. [onCreated] fires only after the server said yes — the caller
 * closes the dialog and refreshes the listing there.
 */
@Composable
@Suppress("LongParameterList") // The dialog's whole seam with its host, one each.
fun GroupEditorHost(
    contacts: List<CrewContact>,
    createRoom: suspend (name: String, memberIds: List<String>) -> ZillitResult<GroupRoom>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onCreated: (GroupRoom) -> Unit,
) {
    val editor = remember { GroupEditorViewModel(createRoom, onCreated) }
    LaunchedEffect(visible) { if (visible) editor.onEvent(GroupEditorEvent.Reset) }
    val state by editor.state.collectAsState()
    GroupEditorDialog(
        contacts = contacts,
        state = state,
        onEvent = editor::onEvent,
        visible = visible,
        onDismiss = onDismiss,
    )
}

/**
 * Android's `CreateGroupPage` as a dialog (the web keeps it in a drawer,
 * `ChatsComponent.jsx:460-540`): the group's name, a searchable multi-select
 * over the production's contacts, Create with Android's exact refusals.
 */
@Composable
@Suppress("LongParameterList") // One dialog's display state, one each.
fun GroupEditorDialog(
    contacts: List<CrewContact>,
    state: GroupEditorState,
    onEvent: (GroupEditorEvent) -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = "Create new group",
        icon = ZillitIcons.Users,
        onDismiss = onDismiss,
        visible = visible,
        // The member list scrolls itself; the shell must not wrap it in a
        // second scroller (a lazy list against an unbounded height refuses
        // to measure at all).
        scrollable = false,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Create",
                onClick = { onEvent(GroupEditorEvent.Create) },
                loading = state.isBusy,
            )
        },
    ) {
        GroupEditorForm(contacts, state, onEvent)
    }
}

/** Name, member picker and the complaint line — the dialog's body. */
@Composable
private fun GroupEditorForm(
    contacts: List<CrewContact>,
    state: GroupEditorState,
    onEvent: (GroupEditorEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = state.name,
            onValueChange = { onEvent(GroupEditorEvent.NameChanged(it)) },
            label = "Group name",
            placeholder = "Please enter group name",
            modifier = Modifier.fillMaxWidth().testTag("group-name"),
        )
        ZillitSearchField(
            value = state.memberQuery,
            onValueChange = { onEvent(GroupEditorEvent.QueryChanged(it)) },
            placeholder = "Search members",
        )
        if (state.selected.isNotEmpty()) {
            ZillitText(
                text = "${state.selected.size} selected",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        MemberPickList(contacts, state, onEvent)
        state.error?.let { complaint ->
            ZillitText(
                text = complaint,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/**
 * The multi-select roll: the contacts the screen already has, narrowed by
 * the picker's search ([searchCrew] — name, role or department), a checkbox
 * each. FIXED height on purpose: a lazy list handed unbounded or intrinsic
 * height inside a dialog crashes on open, and only a menu-opening test
 * catches it.
 */
@Composable
private fun MemberPickList(
    contacts: List<CrewContact>,
    state: GroupEditorState,
    onEvent: (GroupEditorEvent) -> Unit,
) {
    val listState = rememberLazyListState()
    ZillitLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(MEMBER_LIST_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        items(
            contacts.searchCrew(state.memberQuery).sortedBy { it.fullName.lowercase() },
            key = CrewContact::userId,
        ) { contact ->
            ZillitCheckbox(
                checked = contact.userId in state.selected,
                onCheckedChange = { onEvent(GroupEditorEvent.ToggleMember(contact.userId)) },
                label = contact.fullName,
            )
        }
    }
}

private val MEMBER_LIST_HEIGHT = 240.dp

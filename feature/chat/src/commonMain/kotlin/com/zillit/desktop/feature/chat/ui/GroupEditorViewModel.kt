package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.chat.domain.GroupRoom

/**
 * What the create-group dialog holds while it is open — Android's
 * `CreateGroupPage` reduced to its CNC create path: a name, a searchable
 * member pick, one busy flag and one complaint line.
 */
data class GroupEditorState(
    val name: String = "",
    /** The member picker's own search box; never sent anywhere. */
    val memberQuery: String = "",
    /** User ids ticked so far — `CreateGroupPage.selectedUsers`. */
    val selected: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null,
)

sealed interface GroupEditorEvent {
    data class NameChanged(val text: String) : GroupEditorEvent
    data class QueryChanged(val text: String) : GroupEditorEvent
    data class ToggleMember(val userId: String) : GroupEditorEvent
    data object Create : GroupEditorEvent

    /** The dialog reopened: yesterday's half-typed group must not greet it. */
    data object Reset : GroupEditorEvent
}

/**
 * Group creation, kept apart from [ChatViewModel] on purpose: the dialog is
 * a self-contained write with its own validation and busy state, and the
 * listing only needs to hear that it succeeded (the host then refreshes via
 * the same [ChatEvent.RefreshRecents] path the Chats tab already rides).
 *
 * [createRoom] is a seam rather than the repository itself because
 * [ChatScreen] receives no repository — the host passes
 * `ChatRepository.createRoom` through (see `ChatToolProvider`).
 */
class GroupEditorViewModel(
    private val createRoom: suspend (name: String, memberIds: List<String>) -> ZillitResult<GroupRoom>,
    private val onCreated: (GroupRoom) -> Unit,
) : ZillitViewModel<GroupEditorState, GroupEditorEvent, Nothing>(GroupEditorState()) {

    override fun onEvent(event: GroupEditorEvent) {
        when (event) {
            is GroupEditorEvent.NameChanged -> setState { copy(name = event.text, error = null) }
            is GroupEditorEvent.QueryChanged -> setState { copy(memberQuery = event.text) }
            is GroupEditorEvent.ToggleMember -> toggle(event.userId)
            GroupEditorEvent.Create -> create()
            GroupEditorEvent.Reset -> setState { GroupEditorState() }
        }
    }

    private fun toggle(userId: String) = setState {
        copy(
            selected = if (userId in selected) selected - userId else selected + userId,
            error = null,
        )
    }

    private fun create() {
        if (currentState.isBusy) return
        val complaint = groupComplaint(currentState.name, currentState.selected)
        if (complaint != null) {
            setState { copy(error = complaint) }
            return
        }
        setState { copy(isBusy = true, error = null) }
        launchResult(
            block = { createRoom(currentState.name.trim(), currentState.selected.toList()) },
            onSuccess = { room ->
                setState { copy(isBusy = false) }
                onCreated(room)
            },
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }
}

/**
 * Android's refusals, word for word (`CreateGroupPage.onSubmitClick`,
 * `CreateGroupPage.kt:383-391`; `strings.xml:726-727, 1375`): a name, of
 * 3..40 characters, and at least one member. Checked in Android's order so
 * the same mistake earns the same words.
 */
internal fun groupComplaint(name: String, selected: Set<String>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Group name is required."
        trimmed.length !in GROUP_NAME_MIN..GROUP_NAME_MAX ->
            "Please Enter Group Name of length at least 3 characters or at most 40 characters."
        selected.isEmpty() -> "Group members are required."
        else -> null
    }
}

/** Android's bounds on the trimmed name (`CreateGroupPage.kt:385-387`). */
internal const val GROUP_NAME_MIN = 3
internal const val GROUP_NAME_MAX = 40

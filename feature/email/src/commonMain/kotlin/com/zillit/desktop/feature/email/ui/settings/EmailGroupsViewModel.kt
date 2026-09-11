package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.email.data.EMAIL_GROUPS_SYNC_EVENTS
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailGroup
import com.zillit.desktop.feature.email.domain.EmailGroupRepository
import com.zillit.desktop.feature.email.domain.looksLikeAddress
import com.zillit.desktop.feature.email.domain.suggestionsFor

/**
 * The group being written, if any.
 *
 * [id] is null for a new one. Members are addresses, because that is what the
 * server takes (`members_email: [String]`); the crew picker is a convenience
 * for finding them, not the only way in — Android's picker is crew-only
 * (`EditEmailGroupActivity.kt:213`), but a group that cannot include the
 * production accountant's outside address is a group nobody can use.
 */
data class GroupDraft(
    val id: String? = null,
    val name: String = "",
    val members: List<String> = emptyList(),
    /** What is being typed into the member field. */
    val memberInput: String = "",
    /** Crew matching [memberInput], for the drop-down under it. */
    val suggestions: List<EmailContact> = emptyList(),
    val inputError: String? = null,
) {
    val isNew: Boolean get() = id == null

    /** Android requires both (`EditEmailGroupActivity.kt:198-209`). */
    val canSave: Boolean get() = name.isNotBlank() && members.isNotEmpty()

    /** Never prints the members. */
    override fun toString(): String = "GroupDraft(id=$id, name=$name, members=${members.size})"
}

data class EmailGroupsUiState(
    val groups: List<EmailGroup> = emptyList(),
    val draft: GroupDraft? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Deleting a distribution list is irreversible, so it stops to ask. */
    val pendingDelete: EmailGroup? = null,
    val error: String? = null,
)

sealed interface EmailGroupsEvent {
    data object Load : EmailGroupsEvent

    /** Opens the editor. Null starts a new group. */
    data class Edit(val group: EmailGroup?) : EmailGroupsEvent

    data class NameChanged(val value: String) : EmailGroupsEvent
    data class MemberInputChanged(val value: String) : EmailGroupsEvent

    /** Adds what is typed in the member field, if it is an address. */
    data object AddTypedMember : EmailGroupsEvent

    /** Adds a crew member from the drop-down. */
    data class AddMember(val address: String) : EmailGroupsEvent
    data class RemoveMember(val address: String) : EmailGroupsEvent

    data object Save : EmailGroupsEvent
    data object CancelEdit : EmailGroupsEvent

    data class AskDelete(val group: EmailGroup) : EmailGroupsEvent
    data object ConfirmDelete : EmailGroupsEvent
    data object DismissDelete : EmailGroupsEvent
}

/**
 * Distribution groups — Android `EmailGroupsViewModel`
 * (`ui/settings/EmailGroupsViewModel.kt`), with the editor folded in.
 */
class EmailGroupsViewModel(
    private val repository: EmailGroupRepository,
    /** Crew on this production, for the member picker. */
    private val crew: () -> List<EmailContact> = { emptyList() },
    /** Live group changes. Null keeps the page load-once, as it was. */
    private val events: SocketEventBus? = null,
) : ZillitViewModel<EmailGroupsUiState, EmailGroupsEvent, Nothing>(EmailGroupsUiState()) {

    init {
        // A group saved or deleted on a phone. The editor is deliberately not
        // touched: re-reading under somebody typing would discard their work,
        // and the save that follows is the server's own last-write-wins.
        events?.let { bus -> launch { bus.onAny(EMAIL_GROUPS_SYNC_EVENTS).collect { load() } } }
    }

    override fun onEvent(event: EmailGroupsEvent) {
        when (event) {
            EmailGroupsEvent.Load -> load()
            is EmailGroupsEvent.Edit -> setState {
                copy(
                    draft = GroupDraft(
                        id = event.group?.id,
                        name = event.group?.name.orEmpty(),
                        members = event.group?.members.orEmpty(),
                    ),
                    error = null,
                )
            }
            is EmailGroupsEvent.NameChanged -> setState { copy(draft = draft?.copy(name = event.value)) }
            is EmailGroupsEvent.MemberInputChanged -> memberInput(event.value)
            EmailGroupsEvent.AddTypedMember -> addTyped()
            is EmailGroupsEvent.AddMember -> addMember(event.address)
            is EmailGroupsEvent.RemoveMember -> setState {
                copy(draft = draft?.copy(members = draft.members.filterNot { it.equals(event.address, true) }))
            }
            EmailGroupsEvent.Save -> save()
            EmailGroupsEvent.CancelEdit -> setState { copy(draft = null) }
            is EmailGroupsEvent.AskDelete -> setState { copy(pendingDelete = event.group) }
            EmailGroupsEvent.DismissDelete -> setState { copy(pendingDelete = null) }
            EmailGroupsEvent.ConfirmDelete -> delete()
        }
    }

    private fun load() {
        setState { copy(isLoading = groups.isEmpty(), error = null) }
        launchResult(
            block = { repository.groups() },
            onSuccess = { all -> setState { copy(isLoading = false, groups = all) } },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun memberInput(value: String) {
        val draft = currentState.draft ?: return
        // Suggestions exclude what is already on the group; offering a member
        // twice is noise, and the server would keep the duplicate.
        val suggestions = crew().suggestionsFor(value, exclude = draft.members)
        setState {
            copy(draft = draft.copy(memberInput = value, suggestions = suggestions, inputError = null))
        }
    }

    private fun addTyped() {
        val draft = currentState.draft ?: return
        val typed = draft.memberInput.trim()
        if (typed.isEmpty()) return
        if (!typed.looksLikeAddress()) {
            setState { copy(draft = draft.copy(inputError = "Please enter a valid email")) }
            return
        }
        addMember(typed)
    }

    private fun addMember(address: String) {
        val draft = currentState.draft ?: return
        val trimmed = address.trim()
        val already = draft.members.any { it.equals(trimmed, ignoreCase = true) }
        setState {
            copy(
                draft = draft.copy(
                    members = if (already) draft.members else draft.members + trimmed,
                    memberInput = "",
                    suggestions = emptyList(),
                    inputError = null,
                ),
            )
        }
    }

    private fun save() {
        val draft = currentState.draft?.takeIf { it.canSave } ?: return
        setState { copy(isSaving = true, error = null) }
        launchResult(
            block = {
                val name = draft.name.trim()
                draft.id?.let { id -> repository.update(id, name, draft.members) }
                    ?: repository.create(name, draft.members)
            },
            onSuccess = {
                setState { copy(isSaving = false, draft = null) }
                // Reloaded rather than patched: a new group's id — and its
                // mailbox address, if the server minted one — are the
                // server's to assign (Android does the same, EmailGroupsViewModel.kt:99).
                load()
            },
            // The editor stays open with the group still in it.
            onError = { setState { copy(isSaving = false, error = it.localised()) } },
        )
    }

    private fun delete() {
        val group = currentState.pendingDelete ?: return
        setState { copy(pendingDelete = null) }
        launchResult(
            block = { repository.delete(group.id) },
            onSuccess = { setState { copy(groups = groups.filterNot { it.id == group.id }) } },
            onError = { setState { copy(error = it.localised()) } },
        )
    }
}

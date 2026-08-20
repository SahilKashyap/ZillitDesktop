package com.zillit.desktop.feature.externalusers.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.externalusers.domain.CREW_TYPE
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUserBucket
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersRepository
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
import com.zillit.desktop.feature.externalusers.domain.LabeledValue
import com.zillit.desktop.feature.externalusers.domain.validationErrors

/** A production department, for the crew-type pickers. */
data class DepartmentOption(
    val id: String,
    val name: String,
    val designations: List<DesignationOption> = emptyList(),
)

data class DesignationOption(val id: String, val name: String)

data class ExternalUsersUiState(
    val users: List<ExternalUser> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val bucket: ExternalUserBucket = ExternalUserBucket.All,
    val query: String = "",
    val viewer: ExternalUsersViewer = ExternalUsersViewer(),
    val departments: List<DepartmentOption> = emptyList(),
    /** The form when open; null otherwise. */
    val editing: EditingUser? = null,
    val confirmDelete: ExternalUser? = null,
    /** The read-only card when open. */
    val details: ExternalUser? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** Search reaches the name only — both clients' rule. */
    val visible: List<ExternalUser>
        get() = users
            .filter { query.isBlank() || it.fullName.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.fullName.lowercase() }
}

/**
 * The form's draft. [bucket] and [otherType] hold the type UI apart from the
 * wire: `Others` stores the typed text as the type itself.
 */
data class EditingUser(
    val original: ExternalUser? = null,
    val draft: ExternalUser = ExternalUser(userType = CREW_TYPE),
    val bucket: ExternalUserBucket = ExternalUserBucket.Crew,
    val otherType: String = "",
    val errors: Map<String, String> = emptyMap(),
) {
    val isNew: Boolean get() = original == null

    /** The draft as it will ride the wire — the Others text becomes the type. */
    fun outgoing(): ExternalUser {
        val type = when (bucket) {
            ExternalUserBucket.Vendor -> com.zillit.desktop.feature.externalusers.domain.VENDOR_TYPE
            ExternalUserBucket.Others -> otherType.trim()
            else -> CREW_TYPE
        }
        return draft.copy(
            userType = type,
            // Web parity: leaving crew type clears the pair.
            departmentId = if (bucket == ExternalUserBucket.Crew) draft.departmentId else "",
            designationId = if (bucket == ExternalUserBucket.Crew) draft.designationId else "",
            otherInfo = draft.otherInfo.filter { it.label.isNotBlank() || it.value.isNotBlank() },
        )
    }
}

sealed interface ExternalUsersEvent {
    data object Refresh : ExternalUsersEvent
    data object LoadMore : ExternalUsersEvent
    data class Filter(val bucket: ExternalUserBucket) : ExternalUsersEvent
    data class Search(val query: String) : ExternalUsersEvent
    data object New : ExternalUsersEvent
    data class Edit(val user: ExternalUser) : ExternalUsersEvent
    data class ShowDetails(val user: ExternalUser) : ExternalUsersEvent
    data object CloseDetails : ExternalUsersEvent
    data class DraftChanged(val editing: EditingUser) : ExternalUsersEvent
    data object AddInfoRow : ExternalUsersEvent
    data class RemoveInfoRow(val index: Int) : ExternalUsersEvent
    data object Submit : ExternalUsersEvent
    data object CancelEdit : ExternalUsersEvent
    data class Delete(val user: ExternalUser) : ExternalUsersEvent
    data object ConfirmDelete : ExternalUsersEvent
    data object CancelDelete : ExternalUsersEvent
    data object DismissError : ExternalUsersEvent
}

sealed interface ExternalUsersEffect {
    data class Notice(val text: String) : ExternalUsersEffect
}

/**
 * External Users: one roster, a form, and nothing else — the reference
 * clients' contact directory, desktop-shaped.
 */
class ExternalUsersViewModel(
    private val repository: ExternalUsersRepository,
    private val resolveViewer: () -> ExternalUsersViewer,
    private val loadDepartments: suspend () -> List<DepartmentOption>,
    private val nowMillis: () -> Long,
) : ZillitViewModel<ExternalUsersUiState, ExternalUsersEvent, ExternalUsersEffect>(ExternalUsersUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        launch {
            val departments = loadDepartments()
            setState { copy(departments = departments) }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    override fun onEvent(event: ExternalUsersEvent) {
        when (event) {
            ExternalUsersEvent.Refresh -> refresh()
            ExternalUsersEvent.LoadMore -> loadMore()
            is ExternalUsersEvent.Filter -> {
                setState { copy(bucket = event.bucket, query = "") }
                refresh()
            }
            is ExternalUsersEvent.Search -> setState { copy(query = event.query) }
            ExternalUsersEvent.New -> guardPost { setState { copy(editing = EditingUser()) } }
            is ExternalUsersEvent.Edit -> guardEdit(event.user) {
                setState {
                    copy(
                        editing = EditingUser(
                            original = event.user,
                            draft = event.user,
                            bucket = ExternalUserBucket.of(event.user.userType),
                            otherType = event.user.userType
                                .takeIf { ExternalUserBucket.of(it) == ExternalUserBucket.Others }
                                .orEmpty(),
                        ),
                    )
                }
            }
            is ExternalUsersEvent.ShowDetails -> setState { copy(details = event.user) }
            ExternalUsersEvent.CloseDetails -> setState { copy(details = null) }
            is ExternalUsersEvent.DraftChanged -> setState { copy(editing = event.editing) }
            ExternalUsersEvent.AddInfoRow -> editDraft {
                copy(otherInfo = otherInfo + LabeledValue("", ""))
            }
            is ExternalUsersEvent.RemoveInfoRow -> editDraft {
                copy(otherInfo = otherInfo.filterIndexed { i, _ -> i != event.index })
            }
            ExternalUsersEvent.Submit -> submit()
            ExternalUsersEvent.CancelEdit -> setState { copy(editing = null) }
            is ExternalUsersEvent.Delete -> guardEdit(event.user) {
                setState { copy(confirmDelete = event.user) }
            }
            ExternalUsersEvent.ConfirmDelete -> delete()
            ExternalUsersEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            ExternalUsersEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun guardPost(block: () -> Unit) {
        if (currentState.viewer.canPost || currentState.viewer.isAdmin) block()
        else sendEffect(ExternalUsersEffect.Notice("You don't have posting rights."))
    }

    private fun guardEdit(user: ExternalUser, block: () -> Unit) {
        if (currentState.viewer.mayEdit(user)) block()
        else sendEffect(
            ExternalUsersEffect.Notice(
                "Only the person who added this contact, or an admin, can change it.",
            ),
        )
    }

    private fun editDraft(change: ExternalUser.() -> ExternalUser) {
        val editing = currentState.editing ?: return
        setState { copy(editing = editing.copy(draft = editing.draft.change())) }
    }

    private fun refresh() {
        setState { copy(isLoading = true, hasMore = true) }
        launchResult(
            block = { repository.list(currentState.bucket, nowMillis(), older = false) },
            onSuccess = { rows ->
                setState { copy(users = rows, isLoading = false, hasMore = rows.isNotEmpty()) }
            },
            onError = { error ->
                setState { copy(isLoading = false, error = error.userMessage) }
            },
        )
    }

    private fun loadMore() {
        val cursor = currentState.users.maxOfOrNull { it.updatedOnMillis } ?: return
        if (!currentState.hasMore || currentState.isLoading) return
        setState { copy(isLoading = true) }
        launchResult(
            block = { repository.list(currentState.bucket, cursor, older = true) },
            onSuccess = { rows ->
                setState {
                    copy(
                        users = (users + rows).distinctBy { it.id },
                        isLoading = false,
                        hasMore = rows.isNotEmpty(),
                    )
                }
            },
            onError = { error -> setState { copy(isLoading = false, error = error.userMessage) } },
        )
    }

    private fun submit() {
        val editing = currentState.editing ?: return
        val outgoing = editing.outgoing()
        val errors = outgoing.validationErrors()
        if (errors.isNotEmpty()) {
            setState { copy(editing = editing.copy(errors = errors)) }
            return
        }
        setState { copy(isSaving = true) }
        launchResult(
            block = {
                if (editing.isNew) repository.create(outgoing) else repository.update(outgoing)
            },
            onSuccess = {
                sendEffect(
                    ExternalUsersEffect.Notice(
                        if (editing.isNew) "User added successfully." else "User updated successfully.",
                    ),
                )
                setState {
                    copy(editing = null, isSaving = false, bucket = ExternalUserBucket.All, query = "")
                }
                refresh()
            },
            onError = { error ->
                setState {
                    copy(isSaving = false, editing = editing.copy(errors = mapOf("submit" to error.userMessage)))
                }
            },
        )
    }

    private fun delete() {
        val target = currentState.confirmDelete ?: return
        setState { copy(confirmDelete = null, isSaving = true) }
        launchResult(
            block = { repository.delete(target.id) },
            onSuccess = {
                sendEffect(ExternalUsersEffect.Notice("User deleted successfully."))
                setState { copy(isSaving = false) }
                refresh()
            },
            onError = { error -> setState { copy(isSaving = false, error = error.userMessage) } },
        )
    }
}

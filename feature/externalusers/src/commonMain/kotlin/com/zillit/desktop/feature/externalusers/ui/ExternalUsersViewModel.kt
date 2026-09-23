package com.zillit.desktop.feature.externalusers.ui

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.externalusers.data.EXTERNAL_USERS_SYNC_EVENTS
import com.zillit.desktop.feature.externalusers.domain.CREW_TYPE
import com.zillit.desktop.feature.externalusers.domain.Creator
import com.zillit.desktop.feature.externalusers.domain.DialCode
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
    /** The form's country-code picker options — the world's, not the production's. */
    val dialCodes: List<DialCode> = emptyList(),
    /** The crew by id, for each card's "Created By" line. */
    val crew: Map<String, Creator> = emptyMap(),
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

    /** Who added [user], when they are still on the crew; null reads as the web's bare fallback. */
    fun creatorOf(user: ExternalUser): Creator? = crew[user.createdBy]
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
            // The web's `normalize` on the email field: stray spaces never reach the wire.
            email = draft.email.trim(),
            fullName = draft.fullName.trim(),
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

    /** An email address on a card or the details view was clicked — the web's `EmailOpener`. */
    data class WriteTo(val address: String) : ExternalUsersEvent
}

sealed interface ExternalUsersEffect {
    data class Notice(val text: String) : ExternalUsersEffect

    /** Raise the mail composer addressed to [address]; the host decides how. */
    data class ComposeEmail(val address: String) : ExternalUsersEffect
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
    /** The ISD preset, or the bundled copy — the web's `getCountryDetails`. Empty in tests. */
    private val loadDialCodes: suspend () -> List<DialCode> = { emptyList() },
    /** The production's crew, so "Created By" can name a person rather than an id. */
    private val loadCrew: suspend () -> List<Creator> = { emptyList() },
    /**
     * The socket, so a guest added or removed by another coordinator lands
     * without a refresh. Null in tests and on a build with no socket.
     */
    private val events: SocketEventBus? = null,
    /**
     * The open production, sampled on [start]. Defaults to unknown, which is
     * treated as "assume it changed" — safe without wiring, precise with it.
     */
    private val projectId: () -> String? = { null },
/** Carries a refused press to the app frame, which offers to ask an admin. */
private val rights: RightsRequestBus? = null,
) : ZillitViewModel<ExternalUsersUiState, ExternalUsersEvent, ExternalUsersEffect>(ExternalUsersUiState()) {

    /** The production the rows on screen were fetched under. */
    private var loadedProjectId: String? = null

    /**
     * Bumped whenever the roster is wiped; a list answer whose stamp no
     * longer matches is dropped, so a fetch still in flight from the last
     * production can never paint its rows into this one.
     */
    private var rosterGeneration = 0

    /**
     * Runs on every window open. The reference clients rebuild their page per
     * visit (the web's `Externaluser.jsx:158-162` refetches on mount;
     * Android's `ExternalUserListingPage` is a fresh Activity each time), but
     * this view model outlives production switches — so it refetches every
     * time, and when the open production is not the one the rows were fetched
     * under it wipes them first: another production's contacts must never
     * render here, not even for the beat the refetch takes.
     */
    /** Set up once: [start] runs on every visit to the page. */
    private var listening = false

    /**
     * Somebody else changed the directory.
     *
     * Reloads rather than patching: the list is searched and filtered, and a
     * removal that left a row behind would still offer access to someone who
     * no longer has it.
     */
    private fun listenForChanges() {
        val bus = events ?: return
        if (listening) return
        listening = true

        launch { bus.onAny(EXTERNAL_USERS_SYNC_EVENTS).collect { refresh() } }
    }

    fun start() {
        listenForChanges()
        val project = projectId()
        if (project == null || project != loadedProjectId) forgetRoster()
        loadedProjectId = project
        setState { copy(viewer = resolveViewer()) }
        refresh()
        launch {
            val departments = loadDepartments()
            setState { copy(departments = departments) }
        }
        launch {
            val crew = loadCrew().associateBy { it.userId }
            setState { copy(crew = crew) }
        }
        if (currentState.dialCodes.isEmpty()) {
            launch {
                val codes = loadDialCodes()
                setState { copy(dialCodes = codes) }
            }
        }
    }

    /**
     * The production changed: the rows, rights, departments, and any
     * half-typed form all belong to the previous one. Wiped at once — the
     * next [start] refetches under the new production's headers.
     */
    fun onProjectChanged() {
        loadedProjectId = null
        forgetRoster()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `resolveViewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = resolveViewer()
        setState { copy(viewer = resolved) }
    }

    private fun forgetRoster() {
        rosterGeneration++
        // The dial codes are the world's, not the production's: they stay.
        setState { ExternalUsersUiState(dialCodes = dialCodes) }
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
            is ExternalUsersEvent.WriteTo -> {
                if (event.address.isNotBlank()) sendEffect(ExternalUsersEffect.ComposeEmail(event.address))
            }
        }
    }

    private fun guardPost(block: () -> Unit) {
        if (currentState.viewer.canPost || currentState.viewer.isAdmin) block()
        else askForRights(RightsKind.Post)
    }
    /**
     * Refuses, and offers the one thing that changes the answer.
     *
     * The control that got here is on screen for everyone now — hiding it is
     * what sent people to support instead of to an admin. Null when the host
     * wired no bus (tests, previews), and then this is just the refusal.
     */
    private fun askForRights(kind: RightsKind) {
        sendEffect(
            ExternalUsersEffect.Notice(
                if (rights == null) {
                    str(S.desktop_no_rights_on_module, kind.verb, str(S.external_invitees))
                } else {
                    str(S.desktop_no_rights_on_module_asking_admin, kind.verb, str(S.external_invitees))
                },
            ),
        )
        rights?.ask(MODULE_LABEL, kind)
    }


    private fun guardEdit(user: ExternalUser, block: () -> Unit) {
        if (currentState.viewer.mayEdit(user)) block()
        else sendEffect(
            ExternalUsersEffect.Notice(
                str(S.desktop_eu_only_creator_or_admin_can_change),
            ),
        )
    }

    private fun editDraft(change: ExternalUser.() -> ExternalUser) {
        val editing = currentState.editing ?: return
        setState { copy(editing = editing.copy(draft = editing.draft.change())) }
    }

    private fun refresh() {
        val stamp = rosterGeneration
        setState { copy(isLoading = true, hasMore = true) }
        launchResult(
            block = { repository.list(currentState.bucket, nowMillis(), older = false) },
            onSuccess = { rows ->
                if (stamp != rosterGeneration) return@launchResult
                setState { copy(users = rows, isLoading = false, hasMore = rows.isNotEmpty()) }
            },
            onError = { error ->
                if (stamp != rosterGeneration) return@launchResult
                setState { copy(isLoading = false, error = error.localised()) }
            },
        )
    }

    private fun loadMore() {
        val stamp = rosterGeneration
        val cursor = currentState.users.maxOfOrNull { it.updatedOnMillis } ?: return
        if (!currentState.hasMore || currentState.isLoading) return
        setState { copy(isLoading = true) }
        launchResult(
            block = { repository.list(currentState.bucket, cursor, older = true) },
            onSuccess = { rows ->
                if (stamp != rosterGeneration) return@launchResult
                setState {
                    copy(
                        users = (users + rows).distinctBy { it.id },
                        isLoading = false,
                        hasMore = rows.isNotEmpty(),
                    )
                }
            },
            onError = { error ->
                if (stamp != rosterGeneration) return@launchResult
                setState { copy(isLoading = false, error = error.localised()) }
            },
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
                        if (editing.isNew) str(S.desktop_eu_user_added) else str(S.desktop_eu_user_updated),
                    ),
                )
                setState {
                    copy(editing = null, isSaving = false, bucket = ExternalUserBucket.All, query = "")
                }
                refresh()
            },
            onError = { error ->
                setState {
                    copy(isSaving = false, editing = editing.copy(errors = mapOf("submit" to error.localised())))
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
                sendEffect(ExternalUsersEffect.Notice(str(S.desktop_eu_user_deleted)))
                setState { copy(isSaving = false) }
                refresh()
            },
            onError = { error -> setState { copy(isSaving = false, error = error.localised()) } },
        )
    }
}

private const val MODULE_LABEL = "External Users"

package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.crewlist.domain.CrewDocumentRequest
import com.zillit.desktop.feature.crewlist.domain.CrewListHost
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewMemberRules
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.PhoneRules
import com.zillit.desktop.feature.crewlist.domain.search
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate

/**
 * The Crew List — `CrewListCustom.jsx` on the web: the roster and its search,
 * document-only member edits, the Generate PDF chooser with View / Publish /
 * Publish to Doc Distribution, the letterhead designer, and the two admin
 * editors. The PDF, the designer and the admin editors each live in their own
 * controller; this class owns the sheet.
 *
 * Every write re-checks its right in a handler, not only on screen — the button
 * may be showing for someone without it (the rights-request flip), and an
 * event can reach a handler without any button at all.
 */
@Suppress("LongParameterList") // Each seam is one thing the host owns; see CrewListHost.
class CrewListViewModel(
    private val repository: CrewListRepository,
    private val resolveViewer: () -> CrewListViewer,
    host: CrewListHost = CrewListHost.None,
    /** Translates a label key for search — injected so the domain stays pure. */
    private val translate: (String) -> String = { it },
    /** The production's wording for a key, or the web's English. */
    private val text: (key: String, fallback: String) -> String = { _, fallback -> fallback },
    private val selfUserId: () -> String = { "" },
    /** The open production, sampled on [start]; a switch forgets the last one's edits. */
    private val projectId: () -> String? = { null },
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<CrewListUiState, CrewListEvent, CrewListEffect>(CrewListUiState()) {

    private val store = object : CrewStore {
        override val state: CrewListUiState get() = currentState
        override fun update(reducer: CrewListUiState.() -> CrewListUiState) = setState(reducer)
        override fun launch(block: suspend CoroutineScope.() -> Unit): Job = this@CrewListViewModel.launch(block)
        override fun toast(text: String, tone: CrewListEffect.Tone) = sendEffect(CrewListEffect.Toast(text, tone))
        override fun copy(key: String, fallback: String): String = words(key, fallback)
        override fun refuse(message: String, module: String) = this@CrewListViewModel.refuse(message, module)
    }

    private val document = CrewDocumentController(store, repository, host, resolveViewer)
    private val designer = CrewDesignController(store, repository)
    private val admin = CrewAdminController(store, repository, host, onCompanySaved = designer::companyChanged) {
        refresh()
    }
    /** Where the phone pickers' dial codes come from. */
    private val dialCodeSource: CrewListHost = host

    /**
     * Whether the app frame has a rights prompt on screen. The Customise
     * canvas is a heavyweight browser that would paint straight over it, so it
     * steps aside while this is true.
     */
    val framePrompting: StateFlow<Boolean> = rights?.showing ?: MutableStateFlow(false)

    private var listening = false
    private var loadedProjectId: String? = null
    private var rosterGeneration = 0

    fun start() {
        val project = projectId()
        if (project != null && loadedProjectId != null && project != loadedProjectId) forgetProduction()
        loadedProjectId = project ?: loadedProjectId
        // Read outside the reducer: inside it, `selfUserId` is the state's own field.
        val viewer = resolveViewer()
        val self = selfUserId()
        setState { copy(viewer = viewer, selfUserId = self) }
        refresh()
        listenOnce()
        if (currentState.dialCodes.isEmpty()) {
            launch {
                val codes = dialCodeSource.dialCodes()
                setState { copy(dialCodes = codes) }
            }
        }
    }

    /**
     * Reloads the roster when the socket says a department was reordered
     * elsewhere. Guarded so a second start (the window reopening) does not
     * stack collectors; `conflate()` folds a burst into one reload.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch { repository.refreshes.conflate().collect { refresh() } }
    }

    /** The roster, the edits and the letterhead all belong to the production they were made on. */
    private fun forgetProduction() {
        rosterGeneration++
        document.forget()
        // The dial codes are the world's, not the production's: they stay.
        setState { CrewListUiState(dialCodes = dialCodes) }
    }

    /** The visible roster: the web's name-or-designation search. */
    fun visibleUnits(): List<CrewUnit> = currentState.units.search(currentState.query, translate)

    override fun onEvent(event: CrewListEvent) {
        when (event) {
            is CrewListEvent.Sheet -> onSheet(event)
            is CrewListEvent.Document -> document.onEvent(event)
            is CrewListEvent.Design -> designer.onEvent(event)
            is CrewListEvent.Admin -> admin.onEvent(event)
        }
    }

    private fun onSheet(event: CrewListEvent.Sheet) {
        when (event) {
            CrewListEvent.Sheet.Refresh -> refresh()
            is CrewListEvent.Sheet.Search -> setState { copy(query = event.query) }
            CrewListEvent.Sheet.StartEditing -> startEditing()
            CrewListEvent.Sheet.DoneEditing -> doneEditing()
            is CrewListEvent.Sheet.EditMember -> editMember(event.member, event.edit)
            is CrewListEvent.Sheet.OpenProfile -> setState { copy(profile = event.member) }
            CrewListEvent.Sheet.CloseProfile -> setState { copy(profile = null) }
            CrewListEvent.Sheet.AddExternalUser -> addExternalUser()
            is CrewListEvent.Sheet.ExternalUserFinished -> {
                setState { copy(addingExternalUser = false) }
                event.notice?.let { notice ->
                    sendEffect(CrewListEffect.Toast(notice, CrewListEffect.Tone.Success))
                    refresh()
                }
            }
            CrewListEvent.Sheet.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh() {
        val generation = rosterGeneration
        setState { copy(isLoading = true) }
        launchResult(
            block = { repository.roster() },
            onSuccess = { rows ->
                if (generation == rosterGeneration) {
                    setState { copy(units = rows, isLoading = false, hasLoaded = true) }
                }
            },
            onError = { error ->
                if (generation == rosterGeneration) {
                    setState { copy(isLoading = false, hasLoaded = true, error = error.readable()) }
                }
            },
        )
    }

    private fun startEditing() {
        val viewer = resolveViewer().also { setState { copy(viewer = it) } }
        if (!viewer.canPost) {
            refuse(rightsLine(viewer.toolName), viewer.toolName)
            return
        }
        setState { copy(editing = true) }
    }

    /**
     * Leaving edit mode hides the inline errors, which still block the PDF — so
     * it is refused while one stands, with the same words.
     */
    private fun doneEditing() {
        if (currentState.problems.isNotEmpty()) {
            val message = words(
                "crew_list_fix_errors_before_done",
                "Please fix the highlighted phone / country code errors first.",
            )
            sendEffect(CrewListEffect.Toast(message, CrewListEffect.Tone.Error))
            return
        }
        setState { copy(editing = false) }
    }

    private fun editMember(member: CrewMember, edit: MemberOverride) {
        val state = currentState
        if (!state.editing || !state.viewer.canPost) return
        val merged = CrewMemberRules.merge(member, state.overrides[member.userId], edit)
        val overrides = if (merged == null) {
            state.overrides - member.userId
        } else {
            state.overrides + (member.userId to merged)
        }
        // Only the phone pair is validated — the web checks nothing else as it is typed.
        val problems = if (edit.phone != null || edit.countryCode != null) {
            val cells = CrewMemberRules.cells(member, merged)
            when (val problem = PhoneRules.validate(cells.phone, cells.countryCode, text)) {
                null -> state.problems - member.userId
                else -> state.problems + (member.userId to problem)
            }
        } else {
            state.problems
        }
        setState { copy(overrides = overrides, problems = problems, customise = customise?.copy(previewDirty = true)) }
    }

    private fun addExternalUser() {
        val viewer = resolveViewer().also { setState { copy(viewer = it) } }
        if (!viewer.canAddExternalUser) {
            refuse(rightsLine(EXTERNAL_USERS), EXTERNAL_USERS)
            return
        }
        setState { copy(addingExternalUser = true) }
    }

    private fun words(key: String, fallback: String): String =
        text(key, fallback).replace("{tool_name}", currentState.viewer.toolName)

    private fun rightsLine(module: String): String =
        "You do not have ${RightsKind.Post.verb} rights on $module" +
            if (rights == null) "." else " — asking an administrator."

    /** Says why, then offers the one thing that changes the answer. */
    private fun refuse(message: String, module: String) {
        sendEffect(CrewListEffect.Toast(message, CrewListEffect.Tone.Error))
        rights?.ask(module, RightsKind.Post)
    }

    private companion object {
        const val EXTERNAL_USERS = "External Users"
    }
}

/** What every render is made from right now — preview, PDF and publish alike. */
internal fun CrewListUiState.documentRequest(hideExternalLabel: Boolean? = null, stacked: Boolean = false) =
    CrewDocumentRequest(
        layout = layout.current,
        overrides = overrides,
        hideInternalLines = hideInternalLines,
        hideExternalLabel = hideExternalLabel,
        stacked = stacked,
    )

/** What the controllers may touch: the state, a coroutine, a toast, the words, a refusal. */
internal interface CrewStore {
    val state: CrewListUiState
    fun update(reducer: CrewListUiState.() -> CrewListUiState)
    fun launch(block: suspend CoroutineScope.() -> Unit): Job
    fun toast(text: String, tone: CrewListEffect.Tone = CrewListEffect.Tone.Info)
    fun copy(key: String, fallback: String): String
    fun refuse(message: String, module: String)
}

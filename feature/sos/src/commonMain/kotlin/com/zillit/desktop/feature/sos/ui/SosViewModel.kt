package com.zillit.desktop.feature.sos.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.sos.data.SosEndpoints
import com.zillit.desktop.feature.sos.data.SOS_SYNC_EVENTS
import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosCrewMember
import com.zillit.desktop.feature.sos.domain.SosFix
import com.zillit.desktop.feature.sos.domain.SosRepository
import com.zillit.desktop.feature.sos.domain.SosViewer

/**
 * The SOS page: the alert feed, the alarm, and the receiver list.
 *
 * A port of Android's `Sos` fragment plus `SosContactsActivity`
 * (`bottomNav/sos/view/Sos.kt`, `SosContactsActivity.kt`) and the web's
 * `SOSMain`/`SOS` pair. What differs from both, deliberately:
 *
 *  - **Sending asks first.** The phones guard the alarm with a long tooltip
 *    (`SOSMain.jsx:596-604`); a mis-clicked desktop button would raise a false
 *    alarm across a whole production, so the click opens a confirmation.
 *  - **Paging is a button, not a scroll.** The web's `IntersectionObserver`
 *    (ZL-17771, `SOSMain.jsx:184-204`) has no equivalent worth the complexity
 *    in a window the user can resize to any height; "Show older" is explicit.
 *  - **No crew call of its own.** The member picker's list is handed in by the
 *    host ([crew]), where the web dispatches `getDeviceProjectUsers`
 *    (`SOS.jsx:147`) — the desktop already holds that list.
 */
class SosViewModel(
    private val repository: SosRepository,
    private val nowMillis: () -> Long,
    /**
     * Who is looking, resolved at [start] rather than captured here: view
     * models are built once per graph, before any production is open, so a
     * viewer read in the constructor would be the empty one forever.
     */
    private val viewer: () -> SosViewer = { SosViewer() },
    /** The production's crew, for the member picker. Resolved at [start] too. */
    private val crew: () -> List<SosCrewMember> = { emptyList() },
    /**
     * Where the sender is, asked for at the moment the alarm is confirmed.
     *
     * Both phones and the browser refuse to send without a fix; a desktop may
     * have no location service at all, so the default answers null and the
     * alarm still goes out — without coordinates rather than not at all.
     */
    private val locationFix: suspend () -> SosFix? = { null },
    /**
     * The socket, so an alarm raised on set appears without a refresh.
     *
     * Null in tests and on a build with no socket; the screen then behaves as
     * it did before — correct, just not live.
     */
    private val events: SocketEventBus? = null,
    /** The page size the backend answers; a shorter page is the last one. */
    private val pageLimit: Int = SosEndpoints.PAGE_LIMIT,
) : ZillitViewModel<SosUiState, SosEvent, SosEffect>(SosUiState()) {

    fun start() {
        setState { copy(viewer = viewer(), contacts = contacts.copy(crew = crew())) }
        if (!currentState.loaded && !currentState.loading) refresh()
        if (!currentState.contacts.loading && currentState.contacts.rows.isEmpty()) loadContacts()
        listenForAlerts()
    }

    /** Set up once: [start] runs on every visit to the screen. */
    private var listening = false

    /**
     * An alarm raised by somebody else.
     *
     * Reloads rather than inserting the payload's row: the list is paged and
     * newest-first, and the fetch already merges correctly. An SOS is the one
     * thing on this wire where being a second late matters, so there is no
     * debounce and no echo suppression — the sender's own alarm reloading
     * their list is harmless, and suppressing it could hide a second alarm.
     */
    private fun listenForAlerts() {
        val bus = events ?: return
        if (listening) return
        listening = true

        launch {
            bus.onAny(SOS_SYNC_EVENTS).collect { refresh() }
        }
    }

    override fun onEvent(event: SosEvent) {
        when (event) {
            SosEvent.Refresh -> refresh()
            SosEvent.LoadOlder -> loadOlder()
            SosEvent.AskSendAlert -> setState { copy(confirm = SosConfirm.SendAlert) }
            is SosEvent.AskDeleteAlert -> setState { copy(confirm = SosConfirm.DeleteAlert(event.alertId)) }
            SosEvent.AskDeleteAllAlerts -> setState { copy(confirm = SosConfirm.DeleteAllAlerts) }
            is SosEvent.AskDeleteContact -> setState { copy(confirm = SosConfirm.DeleteContact(event.contactId)) }
            SosEvent.ConfirmAction -> confirmAction()
            SosEvent.CancelConfirm -> setState { copy(confirm = null) }
            is SosEvent.OpenMap -> openMap(event.alertId)
            is SosEvent.CallSender -> callSender(event.alertId, event.video)
            SosEvent.DismissError -> setState { copy(error = null) }
            // Everything the receiver card raises, handled next door so neither
            // half of this screen's event set outgrows one readable `when`.
            else -> onContactEvent(event)
        }
    }

    private fun onContactEvent(event: SosEvent) {
        when (event) {
            is SosEvent.SelectContactTab -> setState {
                copy(contacts = contacts.copy(tab = event.tab, formError = null))
            }
            is SosEvent.CrewSearchChanged -> setState { copy(contacts = contacts.copy(crewSearch = event.text)) }
            is SosEvent.CodeSearchChanged -> setState { copy(contacts = contacts.copy(codeSearch = event.text)) }
            is SosEvent.SubmitMember -> submitMember(event.userId)
            is SosEvent.ContactNameChanged -> setState {
                copy(contacts = contacts.edit { it.copy(contactName = event.text) })
            }
            is SosEvent.RelationChanged -> setState {
                copy(contacts = contacts.edit { it.copy(relation = event.relation) })
            }
            is SosEvent.CountryCodeChanged -> setState {
                copy(contacts = contacts.edit { it.copy(countryCode = event.dialCode) })
            }
            is SosEvent.PhoneChanged -> setState {
                copy(contacts = contacts.edit { it.copy(phoneNumber = event.text) })
            }
            SosEvent.SubmitOutsider -> submitOutsider()
            is SosEvent.EditContact -> editContact(event.contactId)
            SosEvent.CancelEdit -> setState { copy(contacts = contacts.cleared()) }
            else -> Unit
        }
    }

    // The feed ---------------------------------------------------------------

    /**
     * The newest page, replacing whatever is shown.
     *
     * The cursor is "now" and the direction is `previous` — Android's
     * empty-list branch (`SosVM.loadInitialOrRefresh`, `SosVM.kt:80-84`) and
     * the web's frozen `sosData` (`SOSMain.jsx:80-92`).
     */
    private fun refresh() {
        if (currentState.loading) return
        setState { copy(loading = true, error = null) }
        launchResult(
            block = { repository.alerts(nowMillis(), older = true, limit = pageLimit, newest = true) },
            onSuccess = { page ->
                setState {
                    copy(
                        loading = false,
                        loaded = true,
                        alerts = page.forDisplay(),
                        hasMore = page.size >= pageLimit,
                    )
                }
                markRead()
            },
            onError = { error -> setState { copy(loading = false, loaded = true, error = error.localised()) } },
        )
    }

    /**
     * The next page down, from the oldest row shown.
     *
     * The cursor is the oldest `updated` (falling back to `created`): the feed
     * sorts on `updated` (`SOSMain.jsx:99-101`) and the web pages from
     * `last.updated` (`SOSMain.jsx:189-196`). Android still cursors on
     * `created` (`SosVM.kt:105`); the two agree unless a row was re-touched.
     */
    private fun loadOlder() {
        val state = currentState
        if (state.loading || state.loadingMore || !state.hasMore) return
        val cursor = state.alerts.minOfOrNull { it.cursorMillis } ?: nowMillis()
        setState { copy(loadingMore = true) }
        launchResult(
            block = { repository.alerts(cursor, older = true, limit = pageLimit, newest = false) },
            onSuccess = { page ->
                setState {
                    copy(
                        loadingMore = false,
                        alerts = (alerts + page).forDisplay(),
                        hasMore = page.size >= pageLimit,
                    )
                }
                markRead()
            },
            onError = { error -> setState { copy(loadingMore = false, error = error.localised()) } },
        )
    }

    /**
     * Every page that answers marks the segment read, and only *after* the
     * page — Android fires it alongside each list request (`SosVM.kt:135-139`),
     * the web after the first one (`SOSMain.jsx:139-149`). The answer is not
     * waited on for anything; neither other client looks at it either.
     */
    private fun markRead() {
        launch { repository.markRead(nowMillis()) }
    }

    private fun openMap(alertId: String) {
        val url = currentState.alerts.firstOrNull { it.id == alertId }?.mapsUrl.orEmpty()
        if (url.isBlank()) {
            sendEffect(SosEffect.Notice("This alert carries no location."))
        } else {
            sendEffect(SosEffect.OpenLink(url))
        }
    }

    /**
     * Rings whoever raised an alert.
     *
     * Three refusals, each with its own message, because "nothing happened"
     * is the worst possible answer to pressing a call button on an emergency:
     * your own alert has nobody to ring, someone off the production cannot be
     * reached, and a crew row with no device id has no phone to ring at all.
     */
    private fun callSender(alertId: String, video: Boolean) {
        val alert = currentState.alerts.firstOrNull { it.id == alertId } ?: return
        if (alert.senderId.isBlank() || alert.senderId == currentState.viewer.userId) {
            sendEffect(SosEffect.Notice("This is your own alert."))
            return
        }
        val sender = crew().firstOrNull { it.userId == alert.senderId }
        when {
            sender == null || sender.hasLeft ->
                sendEffect(SosEffect.Notice("They are no longer on this project."))
            sender.deviceId.isBlank() ->
                sendEffect(SosEffect.Notice("They have no device to call."))
            else -> sendEffect(
                SosEffect.PlaceCall(
                    userId = sender.userId,
                    deviceId = sender.deviceId,
                    displayName = sender.fullName.ifBlank { alert.senderNameHint },
                    video = video,
                ),
            )
        }
    }

    // Confirmations ----------------------------------------------------------

    private fun confirmAction() {
        val confirm = currentState.confirm ?: return
        setState { copy(confirm = null, busy = true, contacts = contacts.copy(busy = true)) }
        launch { applyConfirmed(confirm, runConfirmed(confirm)) }
    }

    private suspend fun runConfirmed(confirm: SosConfirm): ZillitResult<Unit> = when (confirm) {
        SosConfirm.SendAlert -> repository.sendAlert(locationFix())
        is SosConfirm.DeleteAlert -> repository.deleteAlert(confirm.alertId, nowMillis())
        SosConfirm.DeleteAllAlerts -> repository.deleteAllAlerts(nowMillis())
        is SosConfirm.DeleteContact -> repository.deleteContact(confirm.contactId)
    }

    /**
     * What a confirmed act leaves behind.
     *
     * Deletes are applied locally on success rather than re-fetched, the way
     * Android drops the row itself (`Sos.kt:436-443`); a send re-reads the feed
     * so the sender sees their own alert, which is what the web's one-second
     * delayed refetch is for (`SOSMain.jsx:111-124`).
     */
    private fun applyConfirmed(confirm: SosConfirm, result: ZillitResult<Unit>) {
        if (result is ZillitResult.Failure) {
            setState { copy(busy = false, contacts = contacts.copy(busy = false), error = result.error.localised()) }
            return
        }
        setState {
            when (confirm) {
                SosConfirm.SendAlert -> copy(busy = false, contacts = contacts.copy(busy = false))
                is SosConfirm.DeleteAlert -> copy(
                    busy = false,
                    contacts = contacts.copy(busy = false),
                    alerts = alerts.filterNot { it.id == confirm.alertId },
                )
                SosConfirm.DeleteAllAlerts -> copy(
                    busy = false,
                    contacts = contacts.copy(busy = false),
                    alerts = emptyList(),
                    hasMore = false,
                )
                is SosConfirm.DeleteContact -> copy(
                    busy = false,
                    contacts = contacts.copy(busy = false).cleared(),
                )
            }
        }
        when (confirm) {
            SosConfirm.SendAlert -> {
                sendEffect(SosEffect.Notice("SOS sent to your receivers."))
                refresh()
            }
            is SosConfirm.DeleteAlert -> sendEffect(SosEffect.Notice("Alert deleted."))
            SosConfirm.DeleteAllAlerts -> sendEffect(SosEffect.Notice("All SOS alerts cleared."))
            is SosConfirm.DeleteContact -> {
                sendEffect(SosEffect.Notice("Receiver removed."))
                loadContacts()
            }
        }
    }

    // Receivers --------------------------------------------------------------

    /**
     * The receiver list, plus the two catalogues the outsider form needs.
     *
     * `entry_type` is the viewer's own — admins and members keep separate
     * lists (`SOS.jsx:118-126`). Relations and codes are fetched once; a
     * failure on either leaves the form without its dropdowns but does not
     * take the page down with it.
     */
    private fun loadContacts() {
        setState { copy(contacts = contacts.copy(loading = true)) }
        launchResult(
            block = { repository.contacts(currentState.viewer.entryType) },
            onSuccess = { rows -> setState { copy(contacts = contacts.copy(loading = false, rows = rows)) } },
            onError = { error ->
                setState { copy(contacts = contacts.copy(loading = false), error = error.localised()) }
            },
        )
        if (currentState.contacts.relations.isEmpty()) {
            launchResult(
                block = { repository.relations() },
                onSuccess = { rows -> setState { copy(contacts = contacts.copy(relations = rows)) } },
            )
        }
        if (currentState.contacts.isdCodes.isEmpty()) {
            launchResult(
                block = { repository.isdCodes() },
                onSuccess = { rows -> setState { copy(contacts = contacts.copy(isdCodes = rows)) } },
            )
        }
    }

    /**
     * Adds a crew member, or repoints the row being edited at a different one —
     * the web's single `handleFormSubmit` branch for tab 1 (`SOS.jsx:171-205`).
     */
    private fun submitMember(userId: String) {
        if (userId.isBlank() || currentState.contacts.busy) return
        val editing = currentState.contacts.editingId
        setState { copy(contacts = contacts.copy(busy = true, formError = null)) }
        launch {
            val result = if (editing.isNotEmpty()) {
                repository.updateInternal(editing, userId)
            } else {
                repository.createInternal(userId)
            }
            afterContactWrite(result, saved = "Receiver added.")
        }
    }

    /**
     * The outsider form. Validated exactly as the web validates it — all four
     * fields present, then the digits check (`SOS.jsx:207-239`) — before
     * anything leaves the machine.
     */
    private fun submitOutsider() {
        val form = currentState.contacts
        if (form.busy) return
        val problem = form.draft.problem()
        if (problem != null) {
            setState { copy(contacts = contacts.copy(formError = problem)) }
            return
        }
        val editing = form.editingId
        setState { copy(contacts = contacts.copy(busy = true, formError = null)) }
        launch {
            val result = if (editing.isNotEmpty()) {
                repository.updateExternal(editing, form.draft)
            } else {
                repository.createExternal(form.draft)
            }
            afterContactWrite(result, saved = "Receiver saved.")
        }
    }

    private fun afterContactWrite(result: ZillitResult<Unit>, saved: String) {
        when (result) {
            is ZillitResult.Success -> {
                setState { copy(contacts = contacts.copy(busy = false).cleared()) }
                sendEffect(SosEffect.Notice(saved))
                loadContacts()
            }
            is ZillitResult.Failure -> setState {
                copy(contacts = contacts.copy(busy = false, formError = result.error.localised()))
            }
        }
    }

    /** Fills the form from the row on screen, the way the web does (`SOS.jsx:386-409`). */
    private fun editContact(contactId: String) {
        val row = currentState.contacts.rows.firstOrNull { it.id == contactId } ?: return
        setState {
            copy(
                contacts = if (row.kind == SosContactKind.External) {
                    contacts.copy(
                        tab = SosContactTab.Outsider,
                        editingId = row.id,
                        formError = null,
                        codeSearch = "",
                        draft = contacts.draft.copy(
                            contactName = row.contactName,
                            relation = row.relation,
                            countryCode = row.countryCode,
                            phoneNumber = row.phoneNumber,
                        ),
                    )
                } else {
                    contacts.copy(
                        tab = SosContactTab.Member,
                        editingId = row.id,
                        formError = null,
                        crewSearch = "",
                    )
                },
            )
        }
    }

    private val SosAlert.cursorMillis: Long
        get() = if (updatedMillis > 0) updatedMillis else createdMillis
}

/**
 * The feed as it is shown: server-tombstoned rows dropped (Android's
 * `isSoftDeleted`, `Sos.kt:263-267`), one row per id after an append, newest
 * first on `updated` (`SOSMain.jsx:99-101`).
 */
internal fun List<SosAlert>.forDisplay(): List<SosAlert> =
    filterNot { it.deleted }
        .associateBy { it.id }
        .values
        .sortedByDescending { if (it.updatedMillis > 0) it.updatedMillis else it.createdMillis }

/** Edits the outsider draft and clears whatever the last attempt complained about. */
private fun SosContactsState.edit(
    change: (ExternalContactDraft) -> ExternalContactDraft,
): SosContactsState = copy(draft = change(draft), formError = null)

/** Back to an empty add-form, keeping the lists and catalogues in place. */
private fun SosContactsState.cleared(): SosContactsState = copy(
    editingId = "",
    formError = null,
    crewSearch = "",
    codeSearch = "",
    draft = draft.copy(contactName = "", relation = "", countryCode = "", phoneNumber = ""),
)

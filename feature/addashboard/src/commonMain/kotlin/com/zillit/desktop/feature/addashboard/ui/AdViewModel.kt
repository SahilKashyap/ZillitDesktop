package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdRepository
import com.zillit.desktop.feature.addashboard.domain.AdViewer

/**
 * The AD department's day.
 *
 * Opens on today because that is what an AD has the tool open for: who is on
 * the call, who has turned up, and getting the day sent before wrap.
 *
 * [now] is injected so the day the dashboard opens on is testable — the
 * alternative is a test that passes only until midnight UTC.
 */
class AdViewModel(
    private val repository: AdRepository,
    private val viewer: () -> AdViewer,
    private val now: () -> Long,
) : ZillitViewModel<AdUiState, AdEvent, AdEffect>(AdUiState(viewer = viewer())) {

    private var started = false

    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        // Every date on this service is UTC midnight; a local one files the
        // day against the wrong date for a unit shooting in another zone.
        setState { copy(viewer = identity, shootDate = AdDates.utcMidnight(now())) }
        if (!identity.isBlocked) refresh()
    }

    fun onProjectChanged() {
        started = false
        setState { AdUiState(viewer = viewer()) }
        start()
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
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action.
    override fun onEvent(event: AdEvent) {
        when (event) {
            is AdEvent.Open -> {
                setState { copy(destination = event.destination) }
                load(event.destination)
            }

            AdEvent.Refresh -> refresh()
            AdEvent.ClearNotice -> setState { copy(notice = null) }
            AdEvent.DismissError -> setState { copy(error = null) }

            is AdEvent.ChangeDay -> {
                setState { copy(shootDate = AdDates.utcMidnight(event.shootDate)) }
                loadDay()
            }

            is AdEvent.SetAttendance -> editEntry {
                repository.updateDayEntry(event.id, attendance = event.status)
            }

            is AdEvent.SetCallTime -> editEntry {
                repository.updateDayEntry(event.id, callTime = event.time)
            }

            is AdEvent.SetWrapTime -> editEntry {
                repository.updateDayEntry(event.id, wrapTime = event.time)
            }

            is AdEvent.RemoveFromDay -> editEntry { repository.removeFromDay(event.id) }

            AdEvent.AskSubmitDay -> setState { copy(confirmSubmit = true) }
            AdEvent.CancelSubmitDay -> setState { copy(confirmSubmit = false) }
            AdEvent.ConfirmSubmitDay -> submitDay()

            AdEvent.OpenAddToDay -> setState { copy(addToDay = AddToDayState()) }
            is AdEvent.AddSearch -> setState { copy(addToDay = addToDay?.copy(search = event.text)) }
            is AdEvent.ToggleArtiste -> setState {
                val open = addToDay ?: return@setState this
                val chosen = if (event.id in open.chosen) open.chosen - event.id else open.chosen + event.id
                copy(addToDay = open.copy(chosen = chosen))
            }

            is AdEvent.AddCallTime -> setState { copy(addToDay = addToDay?.copy(callTime = event.time)) }
            AdEvent.ConfirmAddToDay -> addToDay()
            AdEvent.CancelAddToDay -> setState { copy(addToDay = null) }

            is AdEvent.RegisterSearch -> setState { copy(registerSearch = event.text) }
            is AdEvent.FilterCategory -> setState { copy(categoryFilter = event.category) }
            is AdEvent.FilterStatus -> setState { copy(statusFilter = event.status) }

            is AdEvent.Verify -> mutate("Artiste verified") { repository.verify(event.id) }
            is AdEvent.StartBlock -> setState { copy(block = BlockState(artiste = event.artiste)) }
            is AdEvent.BlockReason -> setState { copy(block = block?.copy(reason = event.text)) }
            AdEvent.ConfirmBlock -> confirmBlock()
            AdEvent.CancelBlock -> setState { copy(block = null) }
            is AdEvent.Unblock -> mutate("Artiste unblocked") { repository.unblock(event.id) }
        }
    }

    // -- loading ---------------------------------------------------------------

    private fun refresh() {
        setState { copy(loading = true, error = null) }
        loadDay()
        loadRegister()
    }

    private fun load(destination: AdDestination) {
        when (destination) {
            AdDestination.Today -> loadDay()
            AdDestination.Register -> loadRegister()
            AdDestination.Days -> loadShootDays()
        }
    }

    private fun loadDay() {
        val date = currentState.shootDate
        launch {
            when (val day = repository.today(date)) {
                is ZillitResult.Success -> setState { copy(today = day.data, loading = false) }
                is ZillitResult.Failure -> fail(day.error)
            }
            when (val rows = repository.dayList(date)) {
                is ZillitResult.Success -> setState { copy(dayList = rows.data, loading = false) }
                is ZillitResult.Failure -> fail(rows.error)
            }
        }
    }

    private fun loadRegister() {
        launch {
            when (val rows = repository.artistes()) {
                is ZillitResult.Success -> setState { copy(artistes = rows.data, loading = false) }
                is ZillitResult.Failure -> fail(rows.error)
            }
        }
    }

    private fun loadShootDays() {
        setState { copy(loading = true) }
        launch {
            when (val rows = repository.shootDays()) {
                is ZillitResult.Success -> setState { copy(shootDays = rows.data, loading = false) }
                is ZillitResult.Failure -> fail(rows.error)
            }
        }
    }

    // -- the day ---------------------------------------------------------------

    /**
     * Every edit to a day row goes through one gate.
     *
     * Both halves matter: an AD without posting rights may not change
     * anything, and *nobody* may change a submitted day — the web derives
     * every editor's read-only flag from the same two facts.
     */
    private fun editEntry(call: suspend () -> ZillitResult<Unit>) {
        if (!currentState.canEditDay) {
            sendEffect(AdEffect.Failed(refusal()))
            return
        }
        launch {
            when (val result = call()) {
                is ZillitResult.Success -> loadDay()
                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    private fun addToDay() {
        val open = currentState.addToDay ?: return
        if (!open.ready) return
        if (!currentState.canEditDay) {
            sendEffect(AdEffect.Failed(refusal()))
            return
        }
        setState { copy(addToDay = addToDay?.copy(saving = true)) }
        launch {
            val result = repository.addToDay(
                artisteIds = open.chosen.toList(),
                shootDate = currentState.shootDate,
                callTime = open.callTime.takeIf { it.isNotBlank() },
            )
            when (result) {
                is ZillitResult.Success -> {
                    val added = open.chosen.size
                    setState {
                        copy(
                            addToDay = null,
                            notice = "Added $added artiste" + if (added == 1) "" else "s",
                        )
                    }
                    loadDay()
                }

                is ZillitResult.Failure -> {
                    setState { copy(addToDay = addToDay?.copy(saving = false)) }
                    report(result.error)
                }
            }
        }
    }

    /**
     * Sending the day locks it.
     *
     * Confirmed rather than done on a click: after this nobody can correct a
     * call time, and an AD who sent Tuesday by accident has to ask production
     * to reopen it.
     */
    private fun submitDay() {
        setState { copy(confirmSubmit = false) }
        if (!currentState.canEditDay) {
            sendEffect(AdEffect.Failed(refusal()))
            return
        }
        launch {
            when (val result = repository.submitDay(currentState.shootDate)) {
                is ZillitResult.Success -> {
                    setState { copy(notice = "Day submitted") }
                    loadDay()
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    // -- the register ----------------------------------------------------------

    private fun confirmBlock() {
        val open = currentState.block ?: return
        if (!currentState.viewer.canPost) {
            sendEffect(AdEffect.Failed("You do not have posting rights for the AD dashboard."))
            return
        }
        setState { copy(block = block?.copy(saving = true)) }
        launch {
            val result = repository.block(open.artiste.id, open.reason.takeIf { it.isNotBlank() })
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(block = null, notice = "${open.artiste.name} blocked") }
                    loadRegister()
                }

                is ZillitResult.Failure -> {
                    setState { copy(block = block?.copy(saving = false)) }
                    report(result.error)
                }
            }
        }
    }

    private fun mutate(success: String, call: suspend () -> ZillitResult<Unit>) {
        if (!currentState.viewer.canPost) {
            sendEffect(AdEffect.Failed("You do not have posting rights for the AD dashboard."))
            return
        }
        launch {
            when (val result = call()) {
                is ZillitResult.Success -> {
                    setState { copy(notice = success) }
                    loadRegister()
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    // -- failure ---------------------------------------------------------------

    /** Says which of the two gates refused, because they are fixed differently. */
    private fun refusal(): String = if (!currentState.viewer.canPost) {
        "You do not have posting rights for the AD dashboard."
    } else {
        "This day has been submitted and can no longer be changed."
    }

    private fun fail(error: ZillitError) {
        setState { copy(loading = false, error = error.localised()) }
    }

    private fun report(error: ZillitError) {
        sendEffect(AdEffect.Failed(error.localised()))
    }
}

package com.zillit.desktop.feature.saportal.ui

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.saportal.data.saRefreshes
import com.zillit.desktop.feature.saportal.domain.SaPortalRepository
import com.zillit.desktop.feature.saportal.domain.SaViewer
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The supporting artiste's own portal.
 *
 * One production, one artiste: the server resolves who from the session, so
 * nothing here takes an artiste id. The screen an artiste opens this for is
 * "is there anything I need to sign", which is why the overview loads first
 * and the outstanding vouchers lead it.
 */
@Suppress("TooManyFunctions") // One suspend loader or mutator per portal action.
class SaPortalViewModel(
    private val repository: SaPortalRepository,
    /**
     * Live changes from the AD side; null keeps the portal load-once.
     *
     * Ahead of [viewer] deliberately: `viewer` is the trailing lambda at every
     * call site, and a parameter added after it would silently capture that
     * lambda instead.
     */
    private val events: SocketEventBus? = null,
    /** The ledger's rows for this tool per unit, and the page read. */
    private val badges: TabBadgeSource = TabBadgeSource.None,
    private val viewer: () -> SaViewer,
) : ZillitViewModel<SaUiState, SaEvent, SaEffect>(SaUiState(viewer = viewer())) {

    private var started = false
    private var listening = false
    private var watchingBadges = false

    /** The page on screen is its read — the web's `emitSaTabRead`, the whole unit. */
    private fun readPage(destination: SaDestination) {
        val key = destination.badgeKey ?: return
        if ((currentState.unread[key] ?: 0) > 0) badges.read(key)
    }

    fun start() {
        if (started) return
        started = true
        if (!watchingBadges) {
            watchingBadges = true
            launch {
                badges.counts.collect { counts ->
                    setState { copy(unread = counts) }
                    readPage(currentState.destination)
                }
            }
        }
        val identity = viewer()
        setState { copy(viewer = identity) }
        if (!identity.isBlocked) refresh()

        // A reply, a settled day, a change to this artiste's record. Only the
        // page on screen reloads; every page reloads on open anyway, and the
        // overview is rebuilt from the vouchers a voucher frame reloads.
        // `listening` outlives `started`, which onProjectChanged resets — so a
        // production switch does not stack a second collector.
        val bus = events
        if (bus != null && !listening) {
            listening = true
            launch {
                saRefreshes(bus).collect { kind ->
                    if (currentState.destination.refresh == kind) load(currentState.destination)
                }
            }
        }
    }

    /** Re-reads rights and data when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { SaUiState(viewer = viewer()) }
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
    override fun onEvent(event: SaEvent) {
        when (event) {
            is SaEvent.Open -> {
                setState { copy(destination = event.destination) }
                load(event.destination)
                readPage(event.destination)
            }

            SaEvent.Refresh -> refresh()
            SaEvent.ClearNotice -> setState { copy(notice = null) }
            SaEvent.DismissError -> setState { copy(error = null) }

            is SaEvent.FilterVouchers -> {
                setState { copy(voucherFilter = event.status) }
                loadVouchers()
            }

            is SaEvent.OpenVoucher -> openVoucher(event.id)
            SaEvent.CloseVoucher -> setState { copy(openVoucher = null) }

            is SaEvent.StartSigning -> setState { copy(sign = SignState(voucher = event.voucher)) }
            is SaEvent.SignName -> setState { copy(sign = sign?.copy(typedName = event.text)) }
            is SaEvent.SignAccuracy -> setState { copy(sign = sign?.copy(consentAccuracy = event.on)) }
            is SaEvent.SignESign -> setState { copy(sign = sign?.copy(consentESign = event.on)) }
            SaEvent.ConfirmSign -> confirmSign()
            SaEvent.CancelSign -> setState { copy(sign = null) }

            is SaEvent.OpenQuery -> openQuery(event.id)
            SaEvent.CloseQuery -> setState { copy(openQuery = null, replyDraft = "") }
            is SaEvent.ReplyDraft -> setState { copy(replyDraft = event.text) }
            SaEvent.SendReply -> sendReply()
            is SaEvent.ResolveQuery -> resolveQuery(event.id)

            is SaEvent.StartQuery -> setState {
                copy(
                    queryDraft = QueryDraft(
                        voucherId = event.voucher.id,
                        voucherCode = event.voucher.code,
                    ),
                )
            }

            is SaEvent.QueryText -> setState { copy(queryDraft = queryDraft?.copy(text = event.text)) }
            SaEvent.SubmitQuery -> submitQuery()
            SaEvent.CancelQuery -> setState { copy(queryDraft = null) }
        }
    }

    // -- loading ---------------------------------------------------------------

    private fun refresh() {
        setState { copy(loading = true, error = null) }
        launch {
            when (val result = repository.summary()) {
                is ZillitResult.Success -> setState {
                    copy(loading = false, summary = result.data, notAnArtiste = false)
                }

                is ZillitResult.Failure -> settleFailure(result.error)
            }
            loadVouchers()
        }
    }

    private fun load(destination: SaDestination) {
        when (destination) {
            SaDestination.Dashboard -> refresh()
            SaDestination.Vouchers -> loadVouchers()
            SaDestination.Pay -> loadPay()
            SaDestination.Queries -> loadQueries()
            SaDestination.Profile -> loadProfile()
        }
    }

    private fun loadVouchers() {
        launch {
            when (val result = repository.vouchers(currentState.voucherFilter)) {
                is ZillitResult.Success -> setState { copy(vouchers = result.data, loading = false) }
                is ZillitResult.Failure -> settleFailure(result.error)
            }
        }
    }

    private fun loadPay() {
        setState { copy(loading = true) }
        launch {
            when (val result = repository.pay()) {
                is ZillitResult.Success -> setState { copy(loading = false, pay = result.data) }
                is ZillitResult.Failure -> settleFailure(result.error)
            }
        }
    }

    private fun loadQueries() {
        setState { copy(loading = true) }
        launch {
            when (val result = repository.queries()) {
                is ZillitResult.Success -> setState { copy(loading = false, queries = result.data) }
                is ZillitResult.Failure -> settleFailure(result.error)
            }
        }
    }

    private fun loadProfile() {
        setState { copy(loading = true) }
        launch {
            when (val result = repository.profile()) {
                is ZillitResult.Success -> setState { copy(loading = false, profile = result.data) }
                is ZillitResult.Failure -> settleFailure(result.error)
            }
        }
    }

    private fun openVoucher(id: String) {
        setState { copy(loadingVoucher = true) }
        launch {
            when (val result = repository.voucher(id)) {
                is ZillitResult.Success -> setState {
                    copy(loadingVoucher = false, openVoucher = result.data)
                }

                is ZillitResult.Failure -> {
                    setState { copy(loadingVoucher = false) }
                    report(result.error)
                }
            }
        }
    }

    // -- signing ---------------------------------------------------------------

    /**
     * Signs the open voucher.
     *
     * The gate is [SignState.ready] rather than the button alone: a signature
     * is a legal act, and the two consents plus the typed name are what the
     * server records as having been given.
     */
    private fun confirmSign() {
        val open = currentState.sign ?: return
        if (!open.ready) {
            sendEffect(SaEffect.Failed(str(S.desktop_sa_sign_validation)))
            return
        }
        setState { copy(sign = sign?.copy(saving = true)) }
        launch {
            val result = repository.sign(
                id = open.voucher.id,
                typedName = open.typedName,
                consentAccuracy = open.consentAccuracy,
                consentESign = open.consentESign,
            )
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(sign = null, notice = str(S.desktop_sa_signed_code, open.voucher.code)) }
                    loadVouchers()
                    // The open detail is stale the moment it is signed — its
                    // signature block and status both moved.
                    if (currentState.openVoucher?.voucher?.id == open.voucher.id) {
                        openVoucher(open.voucher.id)
                    }
                }

                is ZillitResult.Failure -> {
                    setState { copy(sign = sign?.copy(saving = false)) }
                    report(result.error)
                }
            }
        }
    }

    // -- queries ---------------------------------------------------------------

    private fun openQuery(id: String) {
        launch {
            when (val result = repository.query(id)) {
                is ZillitResult.Success -> setState { copy(openQuery = result.data, replyDraft = "") }
                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    private fun sendReply() {
        val open = currentState.openQuery ?: return
        val text = currentState.replyDraft.trim()
        if (text.isEmpty()) return
        setState { copy(replyDraft = "") }
        launch {
            when (val result = repository.replyToQuery(open.id, text)) {
                is ZillitResult.Success -> {
                    openQuery(open.id)
                    loadQueries()
                }

                is ZillitResult.Failure -> {
                    // The typing goes back in the box rather than being lost.
                    setState { copy(replyDraft = text) }
                    report(result.error)
                }
            }
        }
    }

    private fun resolveQuery(id: String) {
        launch {
            when (val result = repository.resolveQuery(id)) {
                is ZillitResult.Success -> {
                    setState { copy(notice = str(S.desktop_sa_query_resolved)) }
                    loadQueries()
                    if (currentState.openQuery?.id == id) openQuery(id)
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    private fun submitQuery() {
        val draft = currentState.queryDraft ?: return
        if (!draft.ready) return
        setState { copy(queryDraft = queryDraft?.copy(saving = true)) }
        launch {
            when (val result = repository.raiseQuery(draft.voucherId, draft.text)) {
                is ZillitResult.Success -> {
                    setState { copy(queryDraft = null, notice = str(S.ah_query_sent_toast)) }
                    loadQueries()
                }

                is ZillitResult.Failure -> {
                    setState { copy(queryDraft = queryDraft?.copy(saving = false)) }
                    report(result.error)
                }
            }
        }
    }

    // -- failure ---------------------------------------------------------------

    /**
     * "You are not an artiste here" is an answer, not a fault.
     *
     * Most crew on a production are not supporting artistes, and the server
     * says so with `artiste_not_found_for_user`. Showing that as a red error
     * would have every grip and sparks believing the tool was broken.
     */
    private fun settleFailure(error: ZillitError) {
        val notFound = error.serverMessageOrNull()?.contains(NOT_AN_ARTISTE, ignoreCase = true) == true
        setState {
            if (notFound) {
                copy(loading = false, notAnArtiste = true, error = null)
            } else {
                copy(loading = false, error = error.localised())
            }
        }
    }

    private fun report(error: ZillitError) {
        sendEffect(SaEffect.Failed(error.localised()))
    }

    private fun ZillitError.serverMessageOrNull(): String? =
        (this as? ZillitError.Http)?.serverMessage

    private companion object {
        const val NOT_AN_ARTISTE = "artiste_not_found_for_user"
    }
}

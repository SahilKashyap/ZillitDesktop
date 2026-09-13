package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealDates
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.NoticeRules
import kotlinx.coroutines.Job

/**
 * Notices — end-of-contract notices, single and bulk, and the tool's only
 * Deactivate (`DMNoticesPage.jsx`).
 */
internal class NoticesActions(private val vm: DealMemoViewModel) {

    private var loadJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per notice action.
    fun onEvent(event: NoticesEvent) {
        when (event) {
            is NoticesEvent.Search -> edit { copy(search = event.query) }
            is NoticesEvent.OpenSend -> openSend(event.deal)
            is NoticesEvent.EditSendDate -> editSend { draft ->
                val body = if (draft.edited) draft.body else letter(draft.deal, event.date)
                draft.copy(date = event.date, body = body)
            }
            is NoticesEvent.EditSendBody -> editSend { it.copy(body = event.body, edited = true) }
            NoticesEvent.ConfirmSend -> confirmSend()
            NoticesEvent.CancelSend -> edit { if (send?.sending == true) this else copy(send = null) }
            is NoticesEvent.OpenSendAll -> edit { copy(sendAll = event.group) }
            NoticesEvent.ConfirmSendAll -> confirmSendAll()
            NoticesEvent.CancelSendAll -> edit { if (sendingAll) this else copy(sendAll = null) }
            is NoticesEvent.OpenDeactivate -> openDeactivate(event.deal)
            is NoticesEvent.EditDeactivateDate -> edit { copy(deactivate = deactivate?.copy(date = event.date)) }
            NoticesEvent.ConfirmDeactivate -> confirmDeactivate()
            NoticesEvent.CancelDeactivate -> edit { if (deactivate?.busy == true) this else copy(deactivate = null) }
        }
    }

    /** Entering reads the tab's badge and loads the deals and the production's template. */
    fun enter() {
        vm.badges?.readTab(DealBadgeUnit.Notices)
        load(silent = vm.ui.notices.loaded)
        vm.work {
            val template = vm.repository.noticeTemplate().getOrNull()?.takeIf { it.isNotBlank() }
            edit { copy(template = template ?: NoticeRules.DEFAULT_TEMPLATE) }
        }
    }

    fun reload() = load(silent = true)

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true) }
        loadJob = vm.work {
            when (val result = vm.repository.deals()) {
                is ZillitResult.Success -> edit { copy(rows = result.data, loading = false, loaded = true) }
                is ZillitResult.Failure -> edit {
                    copy(rows = if (silent) rows else emptyList(), loading = false, loaded = true)
                }
            }
        }
    }

    /** The last pay day defaults to the contract's end (its UTC day), else today. */
    private fun openSend(deal: DealDoc) {
        if (!vm.ui.rights.canPost || deal.noticeSent) return
        val date = DealDates.utcIsoDate(deal.endDate ?: vm.clock())
        edit { copy(send = SendNoticeDraft(deal = deal, date = date, body = letter(deal, date))) }
    }

    private fun letter(deal: DealDoc, date: String): String =
        NoticeRules.fill(vm.ui.notices.template, NoticeRules.values(deal, NoticeRules.midnightUtc(date)))

    private fun confirmSend() {
        val draft = vm.ui.notices.send ?: return
        val lastPayDay = NoticeRules.noonUtc(draft.date)
        if (draft.sending || lastPayDay == null || draft.body.isBlank()) return
        editSend { it.copy(sending = true) }
        vm.work {
            when (val result = vm.repository.sendNotice(draft.deal.id, lastPayDay, draft.body)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "Notice sent successfully.")
                    edit { copy(send = null) }
                    reload()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    editSend { it.copy(sending = false) }
                }
            }
        }
    }

    /**
     * One POST per unsent deal, in turn, each with its own letter and its
     * contract end as the last pay day. A single summary toast each way.
     */
    private fun confirmSendAll() {
        val state = vm.ui.notices
        val kind = state.sendAll ?: return
        if (state.sendingAll) return
        val unsent = NoticeRules.groups(state.rows, state.search, vm.ui.labels)
            .firstOrNull { it.kind == kind }?.unsent.orEmpty()
        if (unsent.isEmpty()) {
            edit { copy(sendAll = null) }
            return
        }
        edit { copy(sendingAll = true) }
        vm.work {
            var sent = 0
            var failed = 0
            var lastMessage: String? = null
            unsent.forEach { deal ->
                val lastPayDay = NoticeRules.noonUtc(DealDates.utcIsoDate(deal.endDate ?: vm.clock())) ?: vm.clock()
                val body = NoticeRules.fill(vm.ui.notices.template, NoticeRules.values(deal, deal.endDate))
                when (val result = vm.repository.sendNotice(deal.id, lastPayDay, body)) {
                    is ZillitResult.Success -> {
                        sent += 1
                        lastMessage = result.data
                    }
                    is ZillitResult.Failure -> failed += 1
                }
            }
            edit { copy(sendingAll = false, sendAll = null) }
            if (sent > 0) vm.toastSuccess(lastMessage, "Notices sent successfully.")
            if (failed > 0) vm.toast(
                "$failed notice${if (failed == 1) "" else "s"} failed to send",
                DealToastTone.Error,
            )
            reload()
        }
    }

    /** Re-seeded on every open: the contract's end date, else today — never a stale pick. */
    private fun openDeactivate(deal: DealDoc) {
        if (!vm.ui.rights.canPost || !NoticeRules.canDeactivate(deal, vm.ui.notices.deactivatedHere)) return
        edit { copy(deactivate = DeactivateDraft(deal, DealDates.utcIsoDate(deal.endDate ?: vm.clock()))) }
    }

    private fun confirmDeactivate() {
        val draft = vm.ui.notices.deactivate ?: return
        val lastPayDate = NoticeRules.midnightUtc(draft.date)
        if (draft.busy || lastPayDate == null) return
        edit { copy(deactivate = draft.copy(busy = true)) }
        vm.work {
            when (val result = vm.repository.deactivate(draft.deal.id, lastPayDate)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_deactivation_scheduled")
                    edit { copy(deactivate = null, deactivatedHere = deactivatedHere + draft.deal.id) }
                    reload()
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error)
                    edit { copy(deactivate = deactivate?.copy(busy = false)) }
                }
            }
        }
    }

    private fun editSend(reducer: (SendNoticeDraft) -> SendNoticeDraft) = edit { copy(send = send?.let(reducer)) }

    private fun edit(reducer: NoticesState.() -> NoticesState) = vm.update { copy(notices = notices.reducer()) }
}

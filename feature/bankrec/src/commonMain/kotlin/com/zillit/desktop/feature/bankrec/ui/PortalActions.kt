package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.PortalExpiry
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
import kotlinx.coroutines.Job

/**
 * Read-only summaries of one period, for people outside Zillit.
 *
 * The link's token is in its URL but is not the credential: the recipient
 * proves who they are with a code emailed to the address on the link. What the
 * accountant chooses here is what that person may then see, and two of the
 * options name individuals — fraud alerts and transaction detail — which is
 * why neither is on by default.
 */
internal class PortalActions(
    private val vm: BankRecViewModel,
    private val portalUrl: (String) -> String,
) {

    private val state: PortalState get() = vm.ui.portal

    private var copiedJob: Job? = null

    private fun edit(reducer: PortalState.() -> PortalState) = vm.update { copy(portal = portal.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SelectPortalPeriod -> selectPeriod(event.periodId)
            BankRecEvent.ComposePortalLink -> edit {
                copy(draft = PortalLinkDraft(periodId = selectedPeriodId.orEmpty()))
            }

            is BankRecEvent.EditPortalLink -> edit { copy(draft = event.link.toDraft()) }
            is BankRecEvent.EditPortalDraft -> edit { copy(draft = event.draft) }
            BankRecEvent.DismissPortalDraft -> if (state.draft?.saving != true) edit { copy(draft = null) }
            BankRecEvent.SavePortalLink -> save()
            is BankRecEvent.CopyPortalLink -> copy(event.link)
            is BankRecEvent.RevokePortalLink -> revoke(event.link)
            else -> return false
        }
        return true
    }

    /** The tab opened: its links, and a fresh preview of its period. */
    fun open() {
        loadLinks()
        targetPeriod()?.let(::selectPeriod)
            ?: edit { copy(selectedPeriodId = null, preview = null, previewLoading = false) }
    }

    private fun targetPeriod(): String? {
        val periods = vm.ui.periods
        return state.selectedPeriodId?.takeIf { id -> periods.any { it.id == id } } ?: periods.firstOrNull()?.id
    }

    fun loadLinks() {
        edit { copy(loading = !loaded) }
        vm.runResult(vm.repo::portalLinks, { rows ->
            edit { copy(links = rows, loading = false, loaded = true) }
        }, { error ->
            edit { copy(loading = false, loaded = true) }
            vm.report(error)
        })
    }

    /**
     * Keeps the preview on a period that exists — the newest by default, as the
     * web defaults it — and loads it when the choice changes.
     */
    fun onPeriodsChanged() {
        if (vm.ui.tab != BankTab.GuarantorPortal && !state.loaded) return
        val target = targetPeriod()
        when {
            target == null -> edit { copy(selectedPeriodId = null, preview = null, previewLoading = false) }
            target != state.selectedPeriodId -> selectPeriod(target)
        }
    }

    private fun selectPeriod(periodId: String) {
        val period = vm.ui.period(periodId) ?: return
        edit { copy(selectedPeriodId = periodId, previewLoading = true) }
        vm.runResult({ vm.repo.portalPreview(periodId, period.bankAccountId) }, { preview ->
            if (state.selectedPeriodId == periodId) edit { copy(preview = preview, previewLoading = false) }
        }, { error ->
            if (state.selectedPeriodId == periodId) edit { copy(preview = null, previewLoading = false) }
            vm.report(error)
        })
    }

    private fun save() {
        val draft = state.draft ?: return
        if (draft.saving) return
        draft.problem(vm.ui.periods.map { it.id })?.let { return vm.refuse(it) }
        edit { copy(draft = draft.copy(saving = true)) }
        vm.runResult(
            { if (draft.isEdit) vm.repo.updatePortalLink(draft.editingId, draft) else vm.repo.createPortalLink(draft) },
            {
                edit { copy(draft = null) }
                vm.notify(
                    if (draft.isEdit) {
                        "Portal link updated."
                    } else {
                        "Link generated. ${draft.recipientName.trim()} will be emailed a code to open it."
                    },
                )
                loadLinks()
            },
            { error ->
                // The dialog as it now stands, not the one captured at submit.
                edit { copy(draft = this.draft?.copy(saving = false)) }
                vm.report(error)
            },
        )
    }

    private fun copy(link: PortalLink) {
        if (link.token.isBlank()) return vm.refuse("This link has no address to copy.")
        vm.emit(BankRecEffect.CopyToClipboard(portalUrl(link.token)))
        edit { copy(copiedToken = link.token) }
        copiedJob?.cancel()
        copiedJob = vm.after(COPIED_MILLIS) {
            if (state.copiedToken == link.token) edit { copy(copiedToken = null) }
        }
    }

    /** One click, as on the web: the link stops working at once, and the record of what was shared stays. */
    private fun revoke(link: PortalLink) {
        if (state.revokingId != null) return
        edit { copy(revokingId = link.id) }
        vm.runResult({ vm.repo.revokePortalLink(link.id) }, {
            edit {
                copy(
                    revokingId = null,
                    links = links.map { if (it.id == link.id) it.copy(status = PortalStatus.Revoked) else it },
                )
            }
            vm.notify("Link revoked.")
            loadLinks()
        }, { error ->
            edit { copy(revokingId = null) }
            vm.report(error)
        })
    }

    private companion object {
        const val COPIED_MILLIS = 2_000L
    }
}

/**
 * A link, as the form that edits or re-shares it.
 *
 * The expiry starts again at seven days rather than carrying the old one: a
 * re-share of an expired link with its old expiry would be expired on arrival.
 */
private fun PortalLink.toDraft() = PortalLinkDraft(
    editingId = id,
    recipientName = recipientName,
    recipientEmail = recipientEmail,
    orgType = orgType,
    bankAccountId = bankAccountId,
    periodId = periodId,
    permissions = permissions.toSet(),
    expiry = PortalExpiry.SevenDays,
    notifyOnView = notifyOnView,
)

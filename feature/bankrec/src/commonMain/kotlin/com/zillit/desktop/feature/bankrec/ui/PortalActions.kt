package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalStatus

/**
 * Read-only links to one period, for people outside Zillit.
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

    private fun edit(reducer: PortalState.() -> PortalState) =
        vm.update { copy(portal = portal.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            BankRecEvent.ComposePortalLink -> edit {
                copy(draft = PortalLinkDraft(periodId = vm.ui.currentPeriod?.id.orEmpty()))
            }

            is BankRecEvent.EditPortalLink -> edit { copy(draft = event.link.toDraft()) }
            is BankRecEvent.EditPortalDraft -> edit { copy(draft = event.draft) }
            BankRecEvent.DismissPortalDraft -> edit { copy(draft = null) }
            BankRecEvent.SavePortalLink -> save()
            is BankRecEvent.CopyPortalLink -> copy(event.link)
            is BankRecEvent.AskRevokeLink -> edit { copy(revoking = event.link) }
            BankRecEvent.DismissRevokeLink -> edit { copy(revoking = null) }
            BankRecEvent.ConfirmRevokeLink -> revoke()
            else -> return false
        }
        return true
    }

    fun load(force: Boolean) {
        if (!force && state.links.isNotEmpty()) return
        edit { copy(loading = true) }
        vm.runResult(vm.repo::portalLinks, { rows ->
            edit { copy(links = rows, loading = false) }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
    }

    private fun save() {
        val draft = state.draft ?: return
        draft.problem?.let { return vm.refuse(it) }
        edit { copy(draft = draft.copy(saving = true)) }
        vm.runResult(
            {
                if (draft.isEdit) {
                    vm.repo.updatePortalLink(draft.editingId, draft)
                } else {
                    vm.repo.createPortalLink(draft)
                }
            },
            {
                edit { copy(draft = null) }
                vm.notify(
                    if (draft.isEdit) {
                        "Link updated."
                    } else {
                        "Link created. ${draft.recipientName} will be emailed a code to open it."
                    },
                )
                load(force = true)
            },
            { error ->
                edit { copy(draft = draft.copy(saving = false)) }
                vm.report(error)
            },
        )
    }

    private fun copy(link: PortalLink) {
        if (link.token.isBlank()) return vm.refuse("This link has no address to copy.")
        edit { copy(copiedToken = link.token) }
        vm.emit(BankRecEffect.CopyToClipboard(portalUrl(link.token)))
    }

    private fun revoke() {
        val link = state.revoking ?: return
        edit { copy(revoking = null) }
        vm.runResult({ vm.repo.revokePortalLink(link.id) }, {
            edit {
                copy(links = links.map { if (it.id == link.id) it.copy(status = PortalStatus.Revoked) else it })
            }
            vm.notify("Link revoked. It stops working immediately.")
        }, vm::report)
    }
}

/** A link, as the form that would re-share it. */
private fun PortalLink.toDraft() = PortalLinkDraft(
    editingId = id,
    recipientName = recipientName,
    recipientEmail = recipientEmail,
    orgType = orgType,
    bankAccountId = bankAccountId,
    periodId = periodId,
    permissions = permissions,
    notifyOnView = notifyOnView,
)

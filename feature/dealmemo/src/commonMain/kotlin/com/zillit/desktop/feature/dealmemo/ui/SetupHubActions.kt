package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate

/** Deal Memo Setup's hub: which group is open, the search, a delete in flight, and the inline create. */
data class SetupHubState(
    val query: String = "",
    /** The setup the delete confirm is asking about. */
    val confirmDelete: DealTemplate? = null,
    val deletingId: String? = null,
    /** The group whose empty tab shows a new setup inline, until it holds a named one. */
    val inlineFor: SetupGroup? = null,
)

/** What the Setup Hub can be asked to do. */
sealed interface SetupHubEvent : DealMemoEvent {
    data class PickGroup(val group: SetupGroup) : SetupHubEvent

    data class Search(val query: String) : SetupHubEvent

    data object NewSetup : SetupHubEvent

    data class Edit(val template: DealTemplate) : SetupHubEvent

    /** "Create Deal Memo": a deal page drawn from the setup's saved payload. */
    data class Use(val template: DealTemplate) : SetupHubEvent

    data class AskDelete(val template: DealTemplate) : SetupHubEvent

    data object CancelDelete : SetupHubEvent

    data object ConfirmDelete : SetupHubEvent

    data object Back : SetupHubEvent
}

/**
 * `DMSetupHubPage.jsx`: a group's setups as cards — edit, use, delete — or,
 * while the group has none, a new setup of that group right in the tab.
 */
internal class SetupHubActions(private val vm: DealMemoViewModel) {

    fun onEvent(event: SetupHubEvent) {
        when (event) {
            is SetupHubEvent.PickGroup -> vm.navigate(DealMemoRoute.SetupHub(event.group))
            is SetupHubEvent.Search -> update { copy(query = event.query) }
            SetupHubEvent.NewSetup -> vm.navigate(DealMemoRoute.SetupHub(group(), DealMemoRoute.NEW))
            is SetupHubEvent.Edit -> vm.navigate(DealMemoRoute.SetupHub(groupOf(event.template), event.template.id))
            is SetupHubEvent.Use -> use(event.template)
            is SetupHubEvent.AskDelete -> update {
                if (deletingId != null) this else copy(confirmDelete = event.template)
            }
            SetupHubEvent.CancelDelete -> update { if (deletingId != null) this else copy(confirmDelete = null) }
            SetupHubEvent.ConfirmDelete -> delete()
            SetupHubEvent.Back -> vm.navigate(DealMemoRoute.Tab(DealTab.Deals))
        }
    }

    fun enter() {
        update { copy(query = "") }
        reconcile()
    }

    /**
     * The inline-create latch: a group with no setups shows a new one inline;
     * once latched it stays until the group holds a setup that isn't an
     * autosave draft, so the embed's own draft never evicts the form.
     */
    fun reconcile() {
        val page = vm.ui.page as? DealMemoRoute.SetupHub ?: return
        if (page.setupId != null) return
        val rows = vm.ui.templates.rows ?: return
        val group = page.group ?: SetupGroup.Union
        val groupRows = rows.filter { it.nonUnion == (group == SetupGroup.NonUnion) }
        val named = groupRows.filterNot { it.name.startsWith(TemplateStore.DRAFT_PREFIX) }
        val current = vm.ui.hub.inlineFor
        val next = when {
            groupRows.isEmpty() -> group
            current == group -> if (named.isEmpty()) current else null
            else -> current
        }
        if (next != current) update { copy(inlineFor = next) }
        if (next == group) {
            vm.builder.embed(group)
        } else if (vm.ui.builder?.mode?.embedded == true) {
            vm.builder.leave()
        }
    }

    private fun use(template: DealTemplate) {
        if (template.form.isNullOrEmpty()) {
            vm.toast("This setup has nothing saved to use.", DealToastTone.Error)
            return
        }
        vm.navigate(
            DealMemoRoute.QuickDeal(templateId = template.id, exitTo = DealMemoRoute.SetupHub(groupOf(template))),
        )
    }

    private fun delete() {
        val target = vm.ui.hub.confirmDelete ?: return
        if (vm.ui.hub.deletingId != null) return
        update { copy(deletingId = target.id) }
        vm.work {
            when (val result = vm.repository.deleteTemplate(target.id)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "template_deleted")
                    vm.templateStore.remove(target.id)
                }
                is ZillitResult.Failure -> vm.toastError(result.error, "template_delete_failed")
            }
            update { copy(deletingId = null, confirmDelete = null) }
            reconcile()
        }
    }

    private fun group(): SetupGroup = (vm.ui.page as? DealMemoRoute.SetupHub)?.group ?: SetupGroup.Union

    private fun groupOf(template: DealTemplate) = if (template.nonUnion) SetupGroup.NonUnion else SetupGroup.Union

    private fun update(reducer: SetupHubState.() -> SetupHubState) = vm.update { copy(hub = hub.reducer()) }
}

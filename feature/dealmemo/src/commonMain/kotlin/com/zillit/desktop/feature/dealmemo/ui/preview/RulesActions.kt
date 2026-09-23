package com.zillit.desktop.feature.dealmemo.ui.preview

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRules
import com.zillit.desktop.feature.dealmemo.domain.rules.NonUnionPayBreakdown
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMessages
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import kotlinx.coroutines.Job

/**
 * "Update Non-Union Pay breakdown" (`UpdateDealRulesModal.jsx`): the rules
 * grid seeded from the deal, committed as an in-place amendment — the status
 * and signatures stay, and the crew member is asked to acknowledge it.
 */
internal class RulesActions(private val vm: DealMemoViewModel, private val page: DealPreviewActions) {

    private var importJob: Job? = null

    fun open() {
        val deal = vm.ui.preview?.deal ?: return
        val rows = BulkRules.rows(BulkRules.fromDeal(deal), vm.newId).ifEmpty { listOf(BulkRules.blank(vm.newId())) }
        page.updatePreview {
            copy(rules = RulesEditorState(rows = rows, importSource = RuleImportSource(IMPORT_LABEL, loading = true)))
        }
        vm.ensureCoa()
        loadImportSource()
    }

    /** Production's non-union rules, add-only; no department filter. */
    private fun loadImportSource() {
        importJob?.cancel()
        importJob = vm.work {
            val settings = vm.repository.projectSettings().getOrNull()
            val lists = NonUnionPayBreakdown.lists(NonUnionPayBreakdown.section(settings), vm.newId)
            val rows = BulkRules.rows(lists, vm.newId)
            update { copy(importSource = RuleImportSource(IMPORT_LABEL, rows, loading = false)) }
        }
    }

    fun onEvent(event: RulesEvent) {
        val editor = vm.ui.preview?.rules ?: return
        when (event) {
            is RulesEvent.Patch -> update { copy(rows = rows.map { if (it.uid == event.uid) event.row else it }) }
            is RulesEvent.Remove -> update { copy(rows = rows.filterNot { it.uid == event.uid }) }
            is RulesEvent.Add -> update { copy(rows = rows + List(event.count) { BulkRules.blank(vm.newId()) }) }
            RulesEvent.Import -> import(editor)
            RulesEvent.ImportAgreement -> Unit
            RulesEvent.Save -> save()
            RulesEvent.Close -> if (!editor.saving) page.updatePreview { copy(rules = null) }
        }
    }

    /** New rows only — a rule already on the deal may have been tuned for it. Untouched blanks make room. */
    private fun import(editor: RulesEditorState) {
        val source = editor.importSource ?: return
        if (source.loading) return
        val importable = BulkRules.importable(source.rows, editor.rows)
        if (importable.isEmpty()) return
        update {
            copy(
                rows = rows.filterNot { it.pristine } + importable,
                importNote = importable.size to (source.rows.size - importable.size),
            )
        }
    }

    @Suppress("ReturnCount") // Each guard is one of the web's bail-outs.
    private fun save() {
        val preview = vm.ui.preview ?: return
        val deal = preview.deal ?: return
        val editor = preview.rules ?: return
        update { copy(tried = true) }
        if (!editor.allReady || editor.saving || !page.allows { EditAction.Rules in editControl.actions }) return
        val payload = BulkRules.toDeal(BulkRules.lists(editor.rows), deal)
        if (payload.isEmpty()) {
            vm.toast(DealMessages.text(null, "deal_no_rule_fields"), DealToastTone.Error)
            return
        }
        update { copy(saving = true) }
        vm.work {
            when (val result = vm.repository.updateDealRules(deal.id, payload)) {
                is ZillitResult.Success -> {
                    vm.toastSuccess(result.data, "deal_rules_updated")
                    page.refreshNow()
                    page.updatePreview { copy(rules = null) }
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_update_deal_rules")
                    update { copy(saving = false) }
                }
            }
        }
    }

    private fun update(reducer: RulesEditorState.() -> RulesEditorState) =
        page.updatePreview { copy(rules = rules?.reducer()) }

    private companion object {
        val IMPORT_LABEL: String get() = str(S.desktop_dm_productions_non_union_pay_rules)
    }
}

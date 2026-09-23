package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.data.formTemplateRefreshes
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation

/**
 * Forms Configuration: which fields a module's form shows, in what order, and
 * which are required — the web's `FormConfigModule`.
 *
 * Its own class for the reason the other collaborators are — the console's
 * view model is already long — and because this one is a document editor
 * rather than a record editor. Every change to the form is a pure
 * transformation on [FormTemplate]; nothing here talks to the server except
 * load, save and reset, and "Set Approver Level" hands its chain to
 * [ApprovalActions] once it has been read.
 */
@Suppress("TooManyFunctions") // One handler per user action, as on the view model itself.
internal class FormConfigActions(private val vm: AccountHubViewModel) {

    /**
     * Forms Configuration, or false when the event is not one of its own.
     *
     * A boolean rather than a fall-through `else -> Unit`: this dispatcher sits
     * behind others, and one that swallowed everything it did not recognise
     * would silently eat their events.
     */
    // One branch per action; every one of them delegates.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.OpenFormConfig -> openFor(event.module)
            is AccountHubEvent.OpenFormModule -> askSwitch(event.module)
            is AccountHubEvent.SearchFormModules -> edit { copy(moduleSearch = event.term) }
            AccountHubEvent.ReloadFormTemplate -> load(state.module)
            is AccountHubEvent.EditForm -> setEditing(event.editing)
            AccountHubEvent.AskDiscardFormChanges -> edit { copy(discard = DiscardIntent.Revert) }
            AccountHubEvent.DismissDiscardFormChanges -> edit { copy(discard = null) }
            AccountHubEvent.ConfirmDiscardFormChanges -> confirmDiscard()
            is AccountHubEvent.ToggleRearrange -> toggleRearrange(event.on)
            is AccountHubEvent.PickRearrangeSection -> edit { copy(rearrangeSection = event.key) }
            is AccountHubEvent.MoveFormSection -> changeTemplate { moveSection(event.fromKey, event.toKey) }
            is AccountHubEvent.MoveFormField ->
                changeTemplate { moveField(event.sectionKey, event.fromId, event.toId) }
            is AccountHubEvent.ComposeFormSection -> composeSection(event.afterKey)
            is AccountHubEvent.EditFormSectionName ->
                edit { copy(composer = composer?.copy(name = event.name)) }
            AccountHubEvent.DismissFormSection -> edit { copy(composer = null) }
            AccountHubEvent.AddFormSection -> addSection()
            is AccountHubEvent.RenameFormSection -> startRename(event.key, event.label)
            is AccountHubEvent.EditFormSectionRename -> edit { copy(rename = rename?.copy(name = event.name)) }
            AccountHubEvent.DismissFormSectionRename -> edit { copy(rename = null) }
            AccountHubEvent.SaveFormSectionRename -> saveRename()
            is AccountHubEvent.AskRemoveFormSection -> askRemoveSection(event.section)
            AccountHubEvent.DismissRemoveFormSection -> edit { copy(removingSection = null) }
            AccountHubEvent.ConfirmRemoveFormSection -> confirmRemoveSection()
            is AccountHubEvent.FocusFormField -> focusField(event.sectionKey, event.fieldId)
            AccountHubEvent.DismissFormField -> edit { copy(focus = null, draft = NewFieldDraft()) }
            is AccountHubEvent.EditNewFormField -> edit { copy(draft = event.draft) }
            is AccountHubEvent.ToggleSystemFields -> edit { copy(systemFieldsOpen = event.open) }
            AccountHubEvent.AddFormField -> addField()
            is AccountHubEvent.RemoveFormField -> removeField(event.sectionKey, event.fieldId)
            is AccountHubEvent.RestoreFormField ->
                changeTemplate { restoreField(event.sectionKey, event.fieldId) }
            is AccountHubEvent.MoveFormFieldToSection ->
                moveFieldToSection(event.sectionKey, event.fieldId, event.toKey)
            is AccountHubEvent.SetFormFieldName ->
                changeField(event.sectionKey, event.fieldId) { it.copy(name = event.name) }
            is AccountHubEvent.SetFormFieldType -> setFieldType(event.sectionKey, event.fieldId, event.type)
            is AccountHubEvent.SetFormFieldRequired ->
                changeField(event.sectionKey, event.fieldId) { it.copy(required = event.required) }
            is AccountHubEvent.SetFormFieldSelection ->
                setFieldSelection(event.sectionKey, event.fieldId, event.selectionType)
            AccountHubEvent.SaveFormTemplate -> save()
            AccountHubEvent.AskResetFormTemplate -> askReset()
            AccountHubEvent.DismissResetFormTemplate -> edit { copy(confirmingReset = false) }
            AccountHubEvent.ConfirmResetFormTemplate -> confirmReset()
            is AccountHubEvent.OpenApproverScope -> openScope(event.open)
            is AccountHubEvent.PickApproverScope -> edit {
                copy(scopeModal = ScopeModalState(mode = event.scope, departmentId = event.departmentId))
            }
            AccountHubEvent.ContinueApproverScope -> continueScope()
            else -> return false
        }
        return true
    }

    private val state: FormConfigState get() = vm.setupState.formConfig

    private fun edit(reducer: FormConfigState.() -> FormConfigState) =
        vm.update { copy(formConfig = formConfig.reducer()) }

    /** Rewrites the template, for a person who may edit it. */
    private fun changeTemplate(transform: FormTemplate.() -> FormTemplate) {
        if (!vm.mayEdit()) return
        edit { copy(template = template.transform()) }
    }

    private fun changeField(sectionKey: String, fieldId: String, change: (FormField) -> FormField) =
        changeTemplate { editField(sectionKey, fieldId, change) }

    private fun field(sectionKey: String, fieldId: String): FormField? =
        state.template.section(sectionKey)?.fields?.firstOrNull { it.id == fieldId }

    // -- loading ------------------------------------------------------------

    /**
     * Watches for another accountant editing the same form.
     *
     * Its own collector rather than a line in the console's: the frame names a
     * module rather than an area, and whether it matters is this editor's
     * question, not the shell's.
     */
    fun listen(bus: SocketEventBus) {
        vm.launchWork {
            formTemplateRefreshes(bus).collect { module ->
                if (vm.setupState.area == HubArea.FormConfig) refresh(module)
            }
        }
    }

    /** Opening the page keeps whatever is unsaved on it, and otherwise reads the module afresh. */
    fun open() {
        if (state.dirty) return
        load(state.module, silent = state.saved.sections.isNotEmpty())
    }

    /**
     * Opens this page on [module], from a screen that is not it.
     *
     * The sidebar's own gate is checked here too: a person who cannot reach
     * the page from the sidebar does not reach it through a shortcut on
     * another one.
     */
    fun openFor(module: FormModule) {
        if (HubArea.FormConfig !in HubNavigation.areasFor(vm.setupState.viewer)) return
        vm.update { copy(area = HubArea.FormConfig) }
        if (module != state.module) askSwitch(module) else open()
    }

    /**
     * Another module's form. Unsaved edits on this one are asked about first:
     * the web silently drops them, and an hour of rearranging is not a thing a
     * click on the rail should be able to lose.
     */
    private fun askSwitch(module: FormModule) {
        if (module == state.module) return
        if (state.dirty) {
            edit { copy(discard = DiscardIntent.Switch(module)) }
        } else {
            switchTo(module)
        }
    }

    private fun switchTo(module: FormModule) {
        edit { FormConfigState(module = module, moduleSearch = moduleSearch) }
        load(module)
    }

    private fun confirmDiscard() {
        when (val intent = state.discard) {
            DiscardIntent.Revert -> edit {
                copy(template = saved, discard = null, focus = null, rename = null, draft = NewFieldDraft())
            }
            is DiscardIntent.Switch -> switchTo(intent.module)
            null -> Unit
        }
    }

    /**
     * Re-reads without disturbing the editor.
     *
     * Driven by the socket when another accountant saves or resets the same
     * module. An unsaved local edit wins: overwriting someone mid-change to
     * show them a change they did not make is worse than a stale preview, and
     * the page says when it is holding unsaved work.
     */
    fun refresh(module: FormModule) {
        if (module != state.module || state.dirty || state.busy) return
        load(module, silent = true)
    }

    /**
     * Reads [module]'s template. Only the module still open when the answer
     * lands is written, so a quick switch cannot paint one form under the
     * other's name.
     */
    private fun load(module: FormModule, silent: Boolean = false) {
        if (!silent) edit { copy(loading = true, loadFailed = false) }
        vm.runResult({ vm.repo.formTemplate(module) }, { loaded ->
            edit {
                if (this.module != module || dirty) {
                    copy(loading = false)
                } else {
                    copy(template = loaded, saved = loaded, loading = false, loadFailed = false)
                }
            }
        }, { error ->
            edit {
                if (this.module != module) this else copy(loading = false, loadFailed = saved.sections.isEmpty())
            }
            if (!silent) vm.report(error)
        })
    }

    // -- mode ---------------------------------------------------------------

    /**
     * Edit mode, or back to the preview.
     *
     * Back keeps the edits, as the web's does; the preview then says they are
     * unsaved and offers to save or discard them. What closes is only what
     * belongs to the editor: an open panel, a rename in progress.
     */
    private fun setEditing(editing: Boolean) {
        if (editing && !vm.setupState.viewer.canEdit) return
        edit {
            copy(
                editing = editing,
                focus = null,
                draft = NewFieldDraft(),
                rearrange = false,
                rearrangeSection = null,
                rename = null,
                systemFieldsOpen = false,
            )
        }
    }

    /** Rearrange and the property panel share the right-hand side; opening one closes the other, as on the web. */
    private fun toggleRearrange(on: Boolean) = edit {
        copy(rearrange = on, rearrangeSection = null, focus = if (on) null else focus)
    }

    // -- sections -----------------------------------------------------------

    private fun composeSection(afterKey: String?) {
        if (!vm.mayEdit()) return
        edit { copy(composer = SectionComposer(afterKey = afterKey)) }
    }

    private fun addSection() {
        if (!vm.mayEdit()) return
        val composer = state.composer ?: return
        if (!composer.isReady) return
        edit {
            copy(
                template = template.addSection(composer.name, composer.afterKey, vm.nowMillis()),
                composer = null,
            )
        }
    }

    /** Only a section this production added has a name to change; the module's own keep theirs. */
    private fun startRename(key: String, label: String) {
        if (!vm.mayEdit()) return
        val section = state.template.section(key) ?: return
        if (section.systemDefault) return
        edit { copy(rename = SectionRename(key, label)) }
    }

    /** A blank name leaves the section as it was, which is what the web's blur does. */
    private fun saveRename() {
        val rename = state.rename ?: return
        if (!vm.mayEdit()) return
        edit {
            copy(
                template = if (rename.name.isBlank()) template else template.renameSection(rename.key, rename.name),
                rename = null,
            )
        }
    }

    /** The module's own sections are part of its schema and are not offered for removal — nor removed here. */
    private fun askRemoveSection(section: FormSection) {
        if (!vm.mayEdit() || section.systemDefault) return
        edit { copy(removingSection = section) }
    }

    private fun confirmRemoveSection() {
        val section = state.removingSection ?: return
        if (!vm.mayEdit() || section.systemDefault) return
        edit {
            copy(
                template = template.removeSection(section.key),
                removingSection = null,
                focus = focus?.takeIf { it.sectionKey != section.key },
                rearrangeSection = rearrangeSection?.takeIf { it != section.key },
                rename = rename?.takeIf { it.key != section.key },
            )
        }
    }

    // -- fields -------------------------------------------------------------

    /**
     * The property panel on a field, or the add-a-field panel.
     *
     * Clicking the field already open closes it; any click here closes
     * Rearrange, which shares the panel's side of the page.
     */
    private fun focusField(sectionKey: String, fieldId: String?) {
        if (!vm.mayEdit()) return
        val next = FieldFocus(sectionKey, fieldId)
        edit {
            val same = fieldId != null && focus == next
            copy(
                focus = if (same) null else next,
                draft = NewFieldDraft(),
                systemFieldsOpen = false,
                rearrange = false,
                rearrangeSection = null,
            )
        }
    }

    private fun addField() {
        if (!vm.mayEdit()) return
        val focus = state.focus?.takeIf { it.isNew } ?: return
        val draft = state.draft
        if (!draft.isReady) return
        edit {
            copy(
                template = template.addField(
                    sectionKey = focus.sectionKey,
                    name = draft.name,
                    type = draft.type,
                    required = draft.required,
                    // Only a select field reads from anywhere; carrying a
                    // source on a text field would have the form try to
                    // populate a box that has no options.
                    selectionType = draft.selectionType?.takeIf { draft.type == FormFieldType.Select.wire },
                ),
                focus = null,
                draft = NewFieldDraft(),
                systemFieldsOpen = false,
            )
        }
    }

    /**
     * Takes a field off the form.
     *
     * A custom field is deleted; a system field is hidden. A system field is
     * part of the module's schema and the server would put it back, so
     * "remove" means "take it off this form" and the add-a-field panel offers
     * it again.
     */
    private fun removeField(sectionKey: String, fieldId: String) {
        if (!vm.mayEdit()) return
        val field = field(sectionKey, fieldId) ?: return
        edit {
            copy(
                template = if (field.systemDefault) {
                    template.hideField(sectionKey, fieldId)
                } else {
                    template.removeField(sectionKey, fieldId)
                },
                focus = null,
            )
        }
    }

    /**
     * A custom field to another section.
     *
     * Only a custom field: the module's forms find their system fields in the
     * section the module put them in — a Purchase Order reads its vendor from
     * `po_details` — and one moved elsewhere would read as taken off the form.
     */
    private fun moveFieldToSection(sectionKey: String, fieldId: String, toKey: String) {
        if (!vm.mayEdit()) return
        val field = field(sectionKey, fieldId) ?: return
        if (field.systemDefault || state.template.section(toKey) == null) return
        edit {
            val moved = template.moveFieldToSection(sectionKey, fieldId, toKey)
            val landed = moved.section(toKey)?.ordered?.lastOrNull()
            copy(template = moved, focus = landed?.let { FieldFocus(toKey, it.id) })
        }
    }

    /**
     * A system field's type is the schema's, not the production's.
     *
     * Changing it would have the form send a date where the module expects a
     * number. The web disables the control; this refuses the change as well,
     * because a guard only on the screen is not a guard.
     */
    private fun setFieldType(sectionKey: String, fieldId: String, type: String) {
        val field = field(sectionKey, fieldId) ?: return
        if (field.systemDefault) return
        changeField(sectionKey, fieldId) { row ->
            row.copy(
                type = type,
                // A field that is no longer a select has nothing to read from.
                selectionType = row.selectionType?.takeIf { type == FormFieldType.Select.wire },
            )
        }
    }

    /** Where a custom select reads its options from; the module's own fields keep theirs. */
    private fun setFieldSelection(sectionKey: String, fieldId: String, selectionType: String?) {
        val field = field(sectionKey, fieldId) ?: return
        if (field.systemDefault || field.type != FormFieldType.Select.wire) return
        changeField(sectionKey, fieldId) { it.copy(selectionType = selectionType) }
    }

    // -- saving -------------------------------------------------------------

    /**
     * Sends the whole template. The editor stays open, as the web's does, so a
     * change after a save is one more click rather than a trip back in.
     */
    private fun save() {
        if (!vm.mayEdit() || state.busy) return
        val module = state.module
        val template = state.template
        edit { copy(saving = true) }
        vm.runResult({ vm.repo.saveFormTemplate(module, template) }, {
            edit { if (this.module == module) copy(saved = template, saving = false) else copy(saving = false) }
            vm.update { copy(notice = str(S.desktop_hub_form_template_saved_successfully)) }
        }, { error ->
            edit { copy(saving = false) }
            fail(error, str(S.desktop_hub_failed_to_save_form_template))
        })
    }

    private fun askReset() {
        if (!vm.mayEdit() || state.busy) return
        edit { copy(confirmingReset = true) }
    }

    /**
     * Back to the system defaults.
     *
     * Confirmed, where the web resets on the click: it throws away every
     * custom field and every reorder this production has made, for everybody
     * at once, without a save and without an undo.
     */
    private fun confirmReset() {
        if (!vm.mayEdit() || state.busy) return
        val module = state.module
        edit { copy(resetting = true, confirmingReset = false) }
        vm.runResult({ vm.repo.resetFormTemplate(module) }, { defaults ->
            edit {
                if (this.module != module) {
                    copy(resetting = false)
                } else {
                    copy(
                        template = defaults,
                        saved = defaults,
                        resetting = false,
                        focus = null,
                        rename = null,
                        rearrangeSection = null,
                    )
                }
            }
            vm.update { copy(notice = str(S.desktop_hub_template_reset_to_defaults)) }
        }, { error ->
            edit { copy(resetting = false) }
            fail(error, str(S.desktop_hub_failed_to_reset_template))
        })
    }

    /** The server's own reason when it gave one; the web's words when it did not. */
    private fun fail(error: ZillitError, fallback: String) {
        val silent = error is ZillitError.Http && error.serverMessage.isNullOrBlank()
        val message = if (silent) fallback else error.localised()
        vm.sendSideEffect(AccountHubEffect.Failed(message))
    }

    // -- set approver level ----------------------------------------------------

    /** Opens the scope question — or closes it, and stops waiting on a chain being read for it. */
    private fun openScope(open: Boolean) {
        if (open && !vm.setupState.viewer.canActAsAccountant) return
        edit { copy(scopeModal = if (open) ScopeModalState() else null, approverLoad = null) }
    }

    /**
     * "Set Approver Level": the scope chosen, read the module's chains and
     * open the Approvers page's builder on the one for that scope — a
     * department without its own starting from the production's, as the web
     * does. Saving goes through that builder's rules, so a chain is written
     * the same way from either page.
     */
    private fun continueScope() {
        val scope = state.scopeModal ?: return
        val mode = scope.mode ?: return
        if (!scope.canContinue || !vm.mayActAsAccountant()) return
        val module = approvalModule(state.module)
        val load = ApproverLoad(mode, scope.departmentId.takeIf { mode == ApprovalScope.Department })
        edit { copy(scopeModal = null, approverLoad = load) }
        vm.runResult({ vm.repo.approvalConfigs(module) }, { rows ->
            if (state.approverLoad != load) return@runResult
            edit { copy(approverLoad = null) }
            vm.approvals.openFromForms(module, rows, load.scope, load.departmentId)
        }, { error ->
            if (state.approverLoad != load) return@runResult
            edit { copy(approverLoad = null) }
            vm.report(error)
        })
    }

    /** The approval module a form module's approvers are saved under. */
    private fun approvalModule(module: FormModule): ApprovalModule = when (module) {
        FormModule.PurchaseOrders -> ApprovalModule.PurchaseOrders
        FormModule.CashExpenses -> ApprovalModule.CashExpenses
    }
}

/** What an approver load in progress reads as, for the builder's placeholder chrome. */
internal fun ApproverLoad.scopeLabel(state: AccountHubUiState): String =
    if (scope == ApprovalScope.All) str(S.all_departments) else state.departmentName(departmentId).ifBlank {
        str(S.department)
    }

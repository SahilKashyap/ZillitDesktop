package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.accounthub.data.formTemplateRefreshes
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation

/**
 * Forms Configuration: which fields a module's form shows, and in what order.
 *
 * Its own class for the reason the other collaborators are — the console's
 * view model is already long — and because this one is a document editor
 * rather than a record editor. Every mutation is a pure transformation on
 * [FormTemplate]; nothing here talks to the server except load, save and
 * reset.
 */
@Suppress("TooManyFunctions") // One handler per user action, as on the view model itself.
internal class FormConfigActions(private val vm: AccountHubViewModel) {

    private companion object {
        /** The terms section's clause list, kept in its extras. */
        const val TERMS_VALUES = "values"
    }

    /**
     * Forms Configuration, or false when the event is not one of its own.
     *
     * A boolean rather than a fall-through `else -> Unit`: this dispatcher sits
     * between two others, and one that swallowed everything it did not
     * recognise would silently eat the chart's and the vendors' events.
     */
    // One branch per action; every one of them delegates.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.OpenFormConfig -> openFor(event.module)
            is AccountHubEvent.OpenFormModule -> openModule(event.module)
            is AccountHubEvent.EditForm -> setEditing(event.editing)
            is AccountHubEvent.ToggleFormSection -> toggleSection(event.key)
            is AccountHubEvent.NudgeFormSection -> nudgeSection(event.key, event.delta)
            is AccountHubEvent.ComposeFormSection -> composeSection(event.afterKey)
            is AccountHubEvent.EditFormSectionName -> editSectionName(event.name)
            AccountHubEvent.DismissFormSection -> dismissSection()
            AccountHubEvent.AddFormSection -> addSection()
            is AccountHubEvent.RenameFormSection -> startRename(event.key, event.label)
            is AccountHubEvent.EditFormSectionRename -> editRename(event.name)
            AccountHubEvent.DismissFormSectionRename -> dismissRename()
            AccountHubEvent.SaveFormSectionRename -> saveRename()
            is AccountHubEvent.AskRemoveFormSection -> askRemoveSection(event.section)
            AccountHubEvent.DismissRemoveFormSection -> dismissRemoveSection()
            AccountHubEvent.ConfirmRemoveFormSection -> confirmRemoveSection()
            is AccountHubEvent.FocusFormField -> focusField(event.sectionKey, event.fieldId)
            AccountHubEvent.DismissFormField -> dismissField()
            is AccountHubEvent.EditNewFormField -> editDraft(event.draft)
            AccountHubEvent.AddFormField -> addField()
            is AccountHubEvent.RemoveFormField -> removeField(event.sectionKey, event.fieldId)
            is AccountHubEvent.RestoreFormField ->
                restoreField(event.sectionKey, event.fieldId)
            is AccountHubEvent.NudgeFormField ->
                nudgeField(event.sectionKey, event.fieldId, event.delta)
            is AccountHubEvent.MoveFormFieldToSection ->
                moveFieldToSection(event.sectionKey, event.fieldId, event.toKey)
            is AccountHubEvent.SetFormFieldName ->
                setFieldName(event.sectionKey, event.fieldId, event.name)
            is AccountHubEvent.SetFormFieldType ->
                setFieldType(event.sectionKey, event.fieldId, event.type)
            is AccountHubEvent.SetFormFieldRequired ->
                setFieldRequired(event.sectionKey, event.fieldId, event.required)
            is AccountHubEvent.SetFormFieldSelection ->
                setFieldSelection(event.sectionKey, event.fieldId, event.selectionType)
            AccountHubEvent.SaveFormTemplate -> save()
            AccountHubEvent.AskResetFormTemplate -> askReset()
            AccountHubEvent.DismissResetFormTemplate -> dismissReset()
            AccountHubEvent.ConfirmResetFormTemplate -> confirmReset()
            is AccountHubEvent.SearchFormModules -> edit { copy(moduleSearch = event.term) }
            is AccountHubEvent.ToggleRearrange -> edit { copy(rearrange = event.on, rearrangeSection = null) }
            is AccountHubEvent.PickRearrangeSection -> edit { copy(rearrangeSection = event.key) }
            is AccountHubEvent.MoveFormSection -> moveSection(event.fromKey, event.toKey)
            is AccountHubEvent.ToggleTermsEditor -> edit { copy(termsEditing = event.open) }
            is AccountHubEvent.SetTerm -> editTerms {
                terms -> terms.mapIndexed { i, t -> if (i == event.index) event.text else t }
            }
            AccountHubEvent.AddTerm -> editTerms { it + "" }
            is AccountHubEvent.RemoveTerm -> editTerms { it.filterIndexed { i, _ -> i != event.index } }
            is AccountHubEvent.OpenApproverScope ->
                edit { copy(scopeModal = if (event.open) ScopeModalState() else null) }
            is AccountHubEvent.PickApproverScope ->
                edit { copy(scopeModal = ScopeModalState(mode = event.scope, departmentId = event.departmentId)) }
            AccountHubEvent.ContinueApproverScope -> continueScope()
            is AccountHubEvent.UpdateFormApprovers ->
                edit { copy(approverBuilder = approverBuilder?.copy(config = event.config)) }
            AccountHubEvent.SaveFormApprovers -> saveApprovers()
            AccountHubEvent.DismissFormApprovers -> edit { copy(approverBuilder = null) }
            else -> return false
        }
        return true
    }

    // -- rearranging -----------------------------------------------------------

    private fun moveSection(fromKey: String, toKey: String) {
        if (!vm.mayEdit()) return
        template { moveSection(fromKey, toKey) }
    }

    // -- terms of engagement ---------------------------------------------------

    /**
     * The terms section's clauses live in its `values` array — a key this
     * client does not model on [FormSection], so it is edited through the
     * extras that round-trip every save.
     */
    private fun editTerms(change: (List<String>) -> List<String>) {
        if (!vm.mayEdit()) return
        val section = state.termsSection ?: return
        val current = (section.extras[TERMS_VALUES] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val next = change(current)
        val updated = section.copy(
            extras = section.extras + (TERMS_VALUES to JsonArray(next.map { JsonPrimitive(it) })),
        )
        template { copy(sections = sections.map { if (it.key == section.key) updated else it }) }
    }

    // -- set approver level ----------------------------------------------------

    /**
     * "Set Approver Level": the scope chosen, load the module's chain for it
     * and open the builder — the same tier builder the Approvers page uses,
     * saved through the same route. A department without its own chain
     * starts on one empty level, as there.
     */
    private fun continueScope() {
        val scope = state.scopeModal ?: return
        if (!scope.canContinue || !vm.mayActAsAccountant()) return
        val module = approvalModule(state.module)
        val approvalScope = scope.mode ?: return
        edit { copy(scopeModal = null, approverSaving = true) }
        vm.runResult({ vm.repo.approvalConfigs(module) }, { rows ->
            val existing = rows.firstOrNull {
                it.scope == approvalScope &&
                    (approvalScope == ApprovalScope.All || it.departmentId == scope.departmentId)
            }
            val target = existing ?: ApprovalConfig(
                module = module,
                scope = approvalScope,
                departmentId = scope.departmentId,
                departmentName = vm.setupState.departmentName(scope.departmentId),
                tiers = listOf(ApprovalTier(order = 1, rules = listOf(ApprovalRule(type = "default")))),
            )
            val seeded = target.copy(
                tiers = target.tiers.ifEmpty { listOf(ApprovalTier(1)) }.map { tier ->
                    if (tier.rules.isEmpty()) tier.copy(rules = listOf(ApprovalRule(type = "default"))) else tier
                },
            )
            edit { copy(approverBuilder = ApprovalBuilder(seeded, seeded), approverSaving = false) }
        }, { error ->
            edit { copy(approverSaving = false) }
            vm.report(error)
        })
    }

    @Suppress("ReturnCount") // One guard per rule; merging them loses which failed.

    private fun saveApprovers() {
        val builder = state.approverBuilder ?: return
        if (!vm.mayActAsAccountant()) return
        val config = builder.config
        if (config.tiers.isEmpty()) return vm.sendSideEffect(AccountHubEffect.Failed("Please add at least one level."))
        val badAmount = config.tiers.flatMap { it.rules }.any {
            it.type == "amount" && (it.amountThreshold ?: 0.0) <= 0.0
        }
        if (badAmount) {
            return vm.sendSideEffect(
                AccountHubEffect.Failed("Enter an amount greater than 0 for each \"Amount greater than\" rule."),
            )
        }
        val compacted = ApprovalSequence.compacted(config.tiers)
        if (compacted.isEmpty()) return vm.sendSideEffect(AccountHubEffect.Failed("Add at least one approver."))
        edit { copy(approverSaving = true) }
        vm.runResult({ vm.repo.saveApprovalConfig(config.copy(tiers = compacted)) }, {
            edit {
                copy(
                    approverBuilder = null,
                    approverSaving = false,
                    message = CloseResult(true, "Approval levels saved successfully."),
                )
            }
            vm.update { copy(notice = "Approval levels saved successfully.") }
        }, { error ->
            edit { copy(approverSaving = false) }
            vm.report(error)
        })
    }

    /** The approval module a form module's approvers are saved under. */
    private fun approvalModule(module: FormModule): ApprovalModule = when (module) {
        FormModule.PurchaseOrders -> ApprovalModule.PurchaseOrders
        FormModule.CashExpenses -> ApprovalModule.CashExpenses
    }

    private val state: FormConfigState get() = vm.setupState.formConfig

    private fun edit(reducer: FormConfigState.() -> FormConfigState) =
        vm.update { copy(formConfig = formConfig.reducer()) }

    /** Rewrites the template, leaving everything else where it is. */
    private fun template(transform: FormTemplate.() -> FormTemplate) =
        edit { copy(template = template.transform()) }

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

    fun open() {
        if (state.saved.sections.isNotEmpty() && !state.dirty) return
        load(state.module)
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
        openModule(module)
    }

    fun openModule(module: FormModule) {
        if (module == state.module && state.saved.sections.isNotEmpty()) return
        edit { FormConfigState(module = module) }
        load(module)
    }

    /**
     * Re-reads without disturbing the editor.
     *
     * Driven by the socket when another accountant saves or resets the same
     * module. An unsaved local edit wins: overwriting someone mid-sentence to
     * show them a change they did not make is worse than a stale preview, and
     * the page says when it is holding unsaved work.
     */
    fun refresh(module: FormModule) {
        if (module != state.module || state.dirty) return
        load(module, silent = true)
    }

    private fun load(module: FormModule, silent: Boolean = false) {
        if (!silent) edit { copy(loading = true) }
        vm.runResult({ vm.repo.formTemplate(module) }, { loaded ->
            edit { copy(template = loaded, saved = loaded, loading = false) }
        }, { error ->
            edit { copy(loading = false) }
            vm.report(error)
        })
    }

    // -- mode ---------------------------------------------------------------

    /** Leaving edit mode throws unsaved changes away, which is what Cancel means. */
    fun setEditing(editing: Boolean) = edit {
        if (editing) {
            copy(editing = true)
        } else {
            copy(
                editing = false,
                template = saved,
                focus = null,
                draft = NewFieldDraft(),
                rearrange = false,
                termsEditing = false,
            )
        }
    }

    fun toggleSection(key: String) = edit {
        copy(collapsed = if (key in collapsed) collapsed - key else collapsed + key)
    }

    // -- sections -----------------------------------------------------------

    fun nudgeSection(key: String, delta: Int) {
        if (!vm.mayEdit()) return
        template { nudgeSection(key, delta) }
    }

    fun composeSection(afterKey: String?) =
        edit { copy(addingSectionAfter = afterKey, addingSectionName = "") }

    fun editSectionName(name: String) = edit { copy(addingSectionName = name) }

    fun dismissSection() = edit { copy(addingSectionAfter = null, addingSectionName = "") }

    fun addSection() {
        if (!vm.mayEdit()) return
        val name = state.addingSectionName.trim()
        if (name.isEmpty()) return
        val after = state.addingSectionAfter
        edit {
            copy(
                template = template.addSection(name, after, vm.nowMillis()),
                addingSectionAfter = null,
                addingSectionName = "",
            )
        }
    }

    fun startRename(key: String, label: String) =
        edit { copy(renamingSection = key, renamingSectionName = label) }

    fun editRename(name: String) = edit { copy(renamingSectionName = name) }

    fun dismissRename() = edit { copy(renamingSection = null, renamingSectionName = "") }

    fun saveRename() {
        if (!vm.mayEdit()) return
        val key = state.renamingSection ?: return
        val name = state.renamingSectionName
        edit {
            copy(
                template = template.renameSection(key, name),
                renamingSection = null,
                renamingSectionName = "",
            )
        }
    }

    fun askRemoveSection(section: FormSection) =
        edit { copy(removingSection = section) }

    fun dismissRemoveSection() = edit { copy(removingSection = null) }

    fun confirmRemoveSection() {
        if (!vm.mayEdit()) return
        val section = state.removingSection ?: return
        edit {
            copy(
                template = template.removeSection(section.key),
                removingSection = null,
                focus = focus?.takeIf { it.sectionKey != section.key },
            )
        }
    }

    // -- fields -------------------------------------------------------------

    fun focusField(sectionKey: String, fieldId: String?) =
        edit { copy(focus = FieldFocus(sectionKey, fieldId), draft = NewFieldDraft()) }

    fun dismissField() = edit { copy(focus = null, draft = NewFieldDraft()) }

    fun editDraft(draft: NewFieldDraft) = edit { copy(draft = draft) }

    fun addField() {
        if (!vm.mayEdit()) return
        val focus = state.focus ?: return
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
                    selectionType = draft.selectionType
                        ?.takeIf { draft.type == FormFieldType.Select.wire },
                ),
                focus = null,
                draft = NewFieldDraft(),
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
    fun removeField(sectionKey: String, fieldId: String) {
        if (!vm.mayEdit()) return
        val field = state.template.section(sectionKey)?.fields?.firstOrNull { it.id == fieldId } ?: return
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

    fun restoreField(sectionKey: String, fieldId: String) {
        if (!vm.mayEdit()) return
        template { restoreField(sectionKey, fieldId) }
    }

    fun nudgeField(sectionKey: String, fieldId: String, delta: Int) {
        if (!vm.mayEdit()) return
        template { nudgeField(sectionKey, fieldId, delta) }
    }

    fun moveFieldToSection(sectionKey: String, fieldId: String, toKey: String) {
        if (!vm.mayEdit()) return
        edit {
            copy(
                template = template.moveFieldToSection(sectionKey, fieldId, toKey),
                focus = null,
            )
        }
    }

    fun setFieldName(sectionKey: String, fieldId: String, name: String) =
        editField(sectionKey, fieldId) { it.copy(name = name) }

    /**
     * A system field's type is the schema's, not the production's.
     *
     * Changing it would have the form send a date where the module expects a
     * number. The web disables the control; this refuses the change as well,
     * because a guard only on the screen is not a guard.
     */
    fun setFieldType(sectionKey: String, fieldId: String, type: String) {
        val field = state.template.section(sectionKey)?.fields?.firstOrNull { it.id == fieldId } ?: return
        if (field.systemDefault) return
        editField(sectionKey, fieldId) { row ->
            row.copy(
                type = type,
                // A field that is no longer a select has nothing to read from.
                selectionType = row.selectionType
                    ?.takeIf { type == FormFieldType.Select.wire },
            )
        }
    }

    fun setFieldRequired(sectionKey: String, fieldId: String, required: Boolean) =
        editField(sectionKey, fieldId) { it.copy(required = required) }

    fun setFieldSelection(sectionKey: String, fieldId: String, selectionType: String?) =
        editField(sectionKey, fieldId) { it.copy(selectionType = selectionType) }

    private fun editField(
        sectionKey: String,
        fieldId: String,
        change: (FormField) -> FormField,
    ) {
        if (!vm.mayEdit()) return
        template { editField(sectionKey, fieldId, change) }
    }

    // -- saving -------------------------------------------------------------

    fun save() {
        if (!vm.mayEdit()) return
        val module = state.module
        val template = state.template
        edit { copy(saving = true) }
        vm.runResult({ vm.repo.saveFormTemplate(module, template) }, {
            edit {
                copy(
                    saved = template,
                    saving = false,
                    editing = false,
                    focus = null,
                    rearrange = false,
                    termsEditing = false,
                    message = CloseResult(true, "Form template saved successfully."),
                )
            }
            vm.update { copy(notice = "Form template saved successfully.") }
        }, { error ->
            edit { copy(saving = false) }
            vm.report(error)
        })
    }

    fun askReset() = edit { copy(confirmingReset = true) }

    fun dismissReset() = edit { copy(confirmingReset = false) }

    /**
     * Back to the system defaults.
     *
     * Confirmed because it throws away every custom field and every reorder
     * this production has made, for everybody, and there is no undo.
     */
    fun confirmReset() {
        if (!vm.mayEdit()) return
        val module = state.module
        edit { copy(saving = true, confirmingReset = false) }
        vm.runResult({ vm.repo.resetFormTemplate(module) }, { defaults ->
            edit {
                copy(
                    template = defaults,
                    saved = defaults,
                    saving = false,
                    focus = null,
                    message = CloseResult(true, "Template reset to defaults."),
                )
            }
            vm.update { copy(notice = "Template reset to defaults.") }
        }, { error ->
            edit { copy(saving = false) }
            vm.report(error)
        })
    }
}

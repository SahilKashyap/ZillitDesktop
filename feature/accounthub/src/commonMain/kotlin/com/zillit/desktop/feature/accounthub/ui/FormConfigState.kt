package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope

/**
 * Forms Configuration's state — the web's `FormConfigModule`.
 *
 * Its own file rather than a block in the console's state, because the
 * editor is a document editor with a dozen transient pieces (a section being
 * named, one being renamed in place, a discard being confirmed) that nothing
 * else on the console reads.
 */

/** A field being inspected — or, with no [fieldId], the add-a-field panel for [sectionKey]. */
data class FieldFocus(val sectionKey: String, val fieldId: String? = null) {
    val isNew: Boolean get() = fieldId == null
}

/** What the add-a-field panel is holding before it is added. */
data class NewFieldDraft(
    val name: String = "",
    val type: String = FormFieldType.Text.wire,
    val required: Boolean = false,
    val selectionType: String? = null,
) {
    val isReady: Boolean get() = name.isNotBlank()
}

/** The "Set Approver Level" scope choice — everyone, or one department. */
data class ScopeModalState(val mode: ApprovalScope? = null, val departmentId: String? = null) {
    val canContinue: Boolean get() =
        mode == ApprovalScope.All || (mode == ApprovalScope.Department && departmentId != null)
}

/**
 * A section being added — after [afterKey], or at the very top when it is null.
 *
 * Its own object, not a nullable key: "insert at the top" and "not adding
 * anything" were both a null key once, so the rail above the first section
 * opened nothing.
 */
data class SectionComposer(val afterKey: String? = null, val name: String = "") {
    val isReady: Boolean get() = name.isNotBlank()
}

/** A custom section's name being edited in place, as the web's double-click does. */
data class SectionRename(val key: String, val name: String)

/** What confirming a discard throws unsaved edits away for. */
sealed interface DiscardIntent {
    /** Back to what the server holds, staying on this module. */
    data object Revert : DiscardIntent

    /** Off to another module's form, whose template replaces these edits. */
    data class Switch(val module: FormModule) : DiscardIntent
}

/**
 * The approval levels being read for the chosen scope.
 *
 * The builder opens on them once they land; until then the page shows the
 * builder's chrome with a loading line, as the web does, rather than a level
 * the server may be about to contradict.
 */
data class ApproverLoad(val scope: ApprovalScope, val departmentId: String? = null)

/** Which page opened a chain builder, and so which page shows it. */
enum class BuilderOrigin {
    /** The Approvers page's own Edit and Configure. */
    Approvers,

    /** Forms Configuration's "Set Approver Level". */
    Forms,
}

/**
 * The per-module form editor.
 *
 * [saved] is what the server last answered with, kept beside [template] so the
 * page can say whether anything is unsaved. Leaving edit mode keeps the edits,
 * as the web does — the preview then shows them with a banner offering to save
 * or discard — because a form template is a document other people's screens
 * read from, and losing an hour's rearranging to a Back arrow is worse than
 * being asked.
 */
data class FormConfigState(
    val module: FormModule = FormModule.PurchaseOrders,
    val template: FormTemplate = FormTemplate(),
    val saved: FormTemplate = FormTemplate(),
    val loading: Boolean = false,
    /**
     * The last read failed. Shown with a retry instead of the empty-template
     * message, which would claim the module has no form when all that is known
     * is that it could not be read.
     */
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val resetting: Boolean = false,
    /** Edit mode, as against the read-only preview the page opens on. */
    val editing: Boolean = false,
    val focus: FieldFocus? = null,
    val draft: NewFieldDraft = NewFieldDraft(),
    /** The add-a-field panel's "System Fields" list is open. */
    val systemFieldsOpen: Boolean = false,
    val composer: SectionComposer? = null,
    val rename: SectionRename? = null,
    val removingSection: FormSection? = null,
    val confirmingReset: Boolean = false,
    val discard: DiscardIntent? = null,
    val moduleSearch: String = "",
    /** The Rearrange panel, and the section whose fields it lists. */
    val rearrange: Boolean = false,
    val rearrangeSection: String? = null,
    val scopeModal: ScopeModalState? = null,
    val approverLoad: ApproverLoad? = null,
) {
    val dirty: Boolean get() = template != saved

    /** A save or a reset is on its way; neither may start while the other runs. */
    val busy: Boolean get() = saving || resetting

    val sectionCount: Int get() = template.configurable.size

    val fieldCount: Int get() = template.configurable.sumOf { it.fields.size }

    val customCount: Int get() = template.customFieldCount

    /** The field the inspector is showing, or null when it is adding one. */
    val focusedField: FormField?
        get() = focus?.fieldId?.let { id ->
            template.section(focus.sectionKey)?.fields?.firstOrNull { it.id == id }
        }

    val focusedSection: FormSection?
        get() = focus?.let { template.section(it.sectionKey) }
}

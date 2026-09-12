package com.zillit.desktop.core.forms

import kotlinx.serialization.Serializable

/**
 * What a module's form should show, read from its template.
 *
 * The template is edited in the Account Hub and read here, by the screen whose
 * form it describes. Three things it decides: which system fields are on the
 * form at all, which of them a person must fill in, and what extra fields this
 * production has added.
 *
 * A screen that does not render a field the template names is not a bug — the
 * two clients offer different amounts of the same form — so nothing here
 * assumes the caller can satisfy every rule. See [requiredMissing].
 */
class FormLayout(private val template: FormTemplate) {

    /** Whether the template was read at all. False means "show everything". */
    val isLoaded: Boolean get() = template.sections.isNotEmpty()

    fun section(key: String): FormSection? = template.section(key)

    /** The fields on the form in a section, in order. Hidden ones are gone. */
    fun visible(sectionKey: String): List<FormField> = section(sectionKey)?.visible.orEmpty()

    /** What this production added to a section. */
    fun custom(sectionKey: String): List<FormField> = visible(sectionKey).filter { it.isCustom }

    /**
     * Whether a system field is on the form.
     *
     * True when the template has not been read: a screen must not blank its own
     * fields because a fetch failed.
     */
    fun shows(sectionKey: String, label: String): Boolean =
        !isLoaded || visible(sectionKey).any { it.label == label }

    /**
     * What this production calls a field, or [fallback] where it has not
     * renamed it.
     *
     * Productions do rename these — "Nominal Code" becomes "Account", "Vendor"
     * becomes "Supplier" — and a screen that hard-codes the wording shows one
     * name on the form and another in Forms Configuration.
     */
    fun label(sectionKey: String, field: String, fallback: String): String =
        visible(sectionKey).firstOrNull { it.label == field }?.name?.takeIf { it.isNotBlank() } ?: fallback

    fun isRequired(sectionKey: String, label: String): Boolean =
        visible(sectionKey).firstOrNull { it.label == label }?.required == true

    /**
     * The custom fields a person has left blank that the template requires.
     *
     * [values] is keyed by the field's form key, which is what the screen
     * stores its answers under.
     */
    fun missingCustom(sectionKey: String, values: Map<String, String>): List<FormField> =
        custom(sectionKey).filter { it.required && values[it.label].orEmpty().isBlank() }

    /**
     * The required system fields this screen cannot answer.
     *
     * A template can mark a field required that the screen does not render —
     * the two clients offer different amounts of the same form. Those are
     * reported rather than enforced: blocking somebody on a control that is not
     * on their screen leaves them with nothing to do about it.
     */
    fun requiredMissing(sectionKey: String, rendered: Set<String>): List<FormField> =
        visible(sectionKey).filter { it.required && !it.isCustom && it.label !in rendered }
}

/**
 * The custom answers, in the shape the services store them.
 *
 * Serialisable because an order raised offline waits in the outbox as JSON,
 * and its extra fields have to survive the wait.
 *
 * Grouped by the section's *label* rather than its key, because that is what a
 * reader sees on the printed order.
 */
@Serializable
data class CustomFieldGroup(val section: String, val fields: List<CustomFieldValue>)

/**
 * One answer, with enough of the field to describe it.
 *
 * The two services want different amounts of this. Purchase orders store the
 * display name and the value; petty cash stores the key, the type and the
 * select's source alongside them, so its saved answers can be rendered back
 * without the template. Carrying the lot here lets each body write its own
 * subset rather than each module keeping a parallel model.
 */
@Serializable
data class CustomFieldValue(
    val name: String,
    val value: String,
    /** The form's own key for the field. */
    val label: String = "",
    val type: String = "",
    val selectionType: String? = null,
)

/**
 * The answers for one section, dropping the ones nobody filled in.
 *
 * An empty answer is left out rather than sent as an empty string: a field
 * somebody skipped and a field somebody cleared read the same on the wire, and
 * storing blanks fills a printed order with empty rows.
 */
fun FormLayout.customValues(sectionKey: String, values: Map<String, String>): CustomFieldGroup? {
    val section = section(sectionKey) ?: return null
    val answered = custom(sectionKey).mapNotNull { field ->
        values[field.label]?.takeIf { it.isNotBlank() }?.let {
            CustomFieldValue(
                name = field.name,
                value = it,
                label = field.label,
                type = field.type,
                selectionType = field.selectionType,
            )
        }
    }
    return answered.takeIf { it.isNotEmpty() }?.let { CustomFieldGroup(section.label, it) }
}

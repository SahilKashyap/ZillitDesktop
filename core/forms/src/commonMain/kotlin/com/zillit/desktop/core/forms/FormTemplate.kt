package com.zillit.desktop.core.forms

import kotlinx.serialization.json.JsonElement

/**
 * Which module's form is being configured.
 *
 * Two, because two is what the web allows
 * (`FormConfigModule.ALLOWED_MODULE_IDS`). The backend takes any module id, so
 * the list is a deliberate gate rather than a limit — a module whose form
 * nothing renders yet would offer configuration that changes nothing.
 */
enum class FormModule(val wire: String, val label: String) {
    PurchaseOrders("purchase_orders", "Purchase Orders"),
    CashExpenses("cash_expenses", "Petty Cash Expenses"),
    ;

    companion object {
        fun from(wire: String?): FormModule? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The types offered when adding a field.
 *
 * A field's type is kept as its raw string rather than as one of these,
 * because the server's own defaults use types this list does not offer —
 * Petty Cash has `textarea` system fields. Narrowing to the enum on read would
 * rewrite those to `text` on the next save, which is a form quietly losing a
 * multi-line box nobody touched.
 */
enum class FormFieldType(val wire: String, val label: String) {
    Text("text", "Text"),
    Number("number", "Number"),
    Url("url", "URL"),
    Email("email", "Email"),
    Phone("phone", "Phone"),
    Select("select", "Select"),
    Date("date", "Date"),
    ;

    companion object {
        fun from(wire: String?): FormFieldType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where a select field draws its options from. */
enum class SelectionType(val wire: String, val label: String) {
    Vendor("vendor", "Vendor"),
    Department("department", "Department"),
    User("user", "User"),
    AccountCode("account_code", "Account Code"),
    Tax("vat", "Tax"),
    Currency("currency", "Currency"),
    Country("country", "Country"),
    ExpenditureType("exp_type", "Expenditure Type"),
    Tags("line_item_tag", "Tags"),
    ;

    companion object {
        fun from(wire: String?): SelectionType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One field on a module's form.
 *
 * [extras] carries every key the server sent that this client does not model.
 * Saving replaces the whole template, so a field rebuilt from only the fields
 * named here would drop anything the backend added since — silently, and for
 * every field on the form at once.
 */
data class FormField(
    /** The stable key the form stores this field's value under. */
    val label: String = "",
    /** What the person sees. */
    val name: String = "",
    val type: String = FormFieldType.Text.wire,
    val order: Int = 0,
    val required: Boolean = false,
    /** Part of the module's schema. Cannot be deleted, only taken off the form. */
    val systemDefault: Boolean = false,
    /** Off the form. A system field's only way out; see [FormTemplate.hideField]. */
    val hidden: Boolean = false,
    val selectionType: String? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    /**
     * What addresses this field within its section.
     *
     * The label, which is the form's own key for it. Positions are not used:
     * every surface here filters something out — hidden fields, the terms
     * section — and an index taken from a filtered list addresses a different
     * row than the one on screen. That is the bug the web's own comments
     * describe twice.
     */
    val id: String get() = label.ifBlank { "$name#$order" }

    val knownType: FormFieldType? get() = FormFieldType.from(type)

    val typeLabel: String get() = knownType?.label ?: type.ifBlank { "Text" }

    val selection: SelectionType? get() = SelectionType.from(selectionType)

    val isCustom: Boolean get() = !systemDefault
}

/** A titled group of fields. */
data class FormSection(
    val key: String = "",
    val label: String = "",
    val order: Int = 0,
    val systemDefault: Boolean = false,
    val fields: List<FormField> = emptyList(),
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    /** In display order, hidden ones included — the config lists both. */
    val ordered: List<FormField> get() = fields.sortedBy { it.order }

    val visible: List<FormField> get() = ordered.filterNot { it.hidden }

    val removed: List<FormField> get() = ordered.filter { it.hidden }
}

/**
 * A module's whole form: its sections, their fields, and the order of both.
 *
 * Every operation returns a new template with orders renumbered from one, so a
 * saved template's `order` always matches its position. The web relies on that
 * too, and it is what lets a field be addressed by key rather than by index.
 */
data class FormTemplate(val sections: List<FormSection> = emptyList()) {

    val ordered: List<FormSection> get() = sections.sortedBy { it.order }

    /**
     * The sections a person may configure.
     *
     * `terms_of_engagement` is superseded by the Terms and Conditions document
     * on PO settings and is filtered here rather than dropped, so a save
     * round-trips it untouched instead of deleting a production's clauses.
     * Filtered in one place because the web filtered it in two and forgot the
     * third.
     */
    val configurable: List<FormSection> get() = ordered.filterNot { it.key == TERMS_SECTION }

    val fieldCount: Int get() = sections.sumOf { it.fields.size }

    val customFieldCount: Int get() = sections.sumOf { section -> section.fields.count { it.isCustom } }

    fun section(key: String): FormSection? = sections.firstOrNull { it.key == key }

    // -- sections -----------------------------------------------------------

    /** Moves [fromKey] to where [toKey] sits. Keyed, never positional. */
    fun moveSection(fromKey: String, toKey: String): FormTemplate {
        if (fromKey == toKey) return this
        val rows = ordered.toMutableList()
        val from = rows.indexOfFirst { it.key == fromKey }
        val to = rows.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0) return this
        rows.add(to, rows.removeAt(from))
        return copy(sections = rows).renumberSections()
    }

    /** One step up or down, by [delta] of -1 or 1. */
    fun nudgeSection(key: String, delta: Int): FormTemplate {
        val rows = ordered
        val from = rows.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in rows.indices) return this
        val moved = rows.toMutableList()
        moved[from] = rows[to]
        moved[to] = rows[from]
        return copy(sections = moved).renumberSections()
    }

    /** Adds a section after [afterKey]; null puts it at the top. */
    fun addSection(name: String, afterKey: String?, nowMillis: Long): FormTemplate {
        val label = name.trim()
        if (label.isEmpty()) return this
        val rows = ordered.toMutableList()
        val at = if (afterKey == null) 0 else rows.indexOfFirst { it.key == afterKey } + 1
        rows.add(
            at.coerceIn(0, rows.size),
            FormSection(key = "custom_$nowMillis", label = label, systemDefault = false),
        )
        return copy(sections = rows).renumberSections()
    }

    fun removeSection(key: String): FormTemplate =
        copy(sections = ordered.filterNot { it.key == key }).renumberSections()

    fun renameSection(key: String, label: String): FormTemplate {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return this
        return mapSection(key) { it.copy(label = trimmed) }
    }

    // -- fields -------------------------------------------------------------

    /**
     * Adds a custom field, with a key nothing else in the section uses.
     *
     * The key is derived from the name and suffixed until it is free: two
     * fields sharing one would have the form store both under the same key,
     * and the second would overwrite the first on every submission.
     */
    fun addField(
        sectionKey: String,
        name: String,
        type: String,
        required: Boolean = false,
        selectionType: String? = null,
    ): FormTemplate {
        val display = name.trim()
        if (display.isEmpty()) return this
        return mapSection(sectionKey) { section ->
            val taken = section.fields.map { it.label }.filter { it.isNotBlank() }.toSet()
            section.copy(
                fields = section.ordered + FormField(
                    label = uniqueLabel(fieldLabelFor(display), taken),
                    name = display,
                    type = type,
                    order = section.fields.size + 1,
                    required = required,
                    systemDefault = false,
                    selectionType = selectionType,
                ),
            )
        }.renumberFields(sectionKey)
    }

    /** Deletes a custom field. A system field cannot be deleted — see [hideField]. */
    fun removeField(sectionKey: String, fieldId: String): FormTemplate =
        mapSection(sectionKey) { section ->
            section.copy(fields = section.ordered.filterNot { it.id == fieldId })
        }.renumberFields(sectionKey)

    /**
     * Takes a system field off the form.
     *
     * Not deleted: it is part of the module's schema, and the server would
     * put it back. Required is cleared alongside, because a field that is not
     * on the form cannot be mandatory and leaving it set would resurrect a
     * required field the moment somebody restored it.
     */
    fun hideField(sectionKey: String, fieldId: String): FormTemplate =
        mapField(sectionKey, fieldId) { it.copy(hidden = true, required = false) }

    fun restoreField(sectionKey: String, fieldId: String): FormTemplate =
        mapField(sectionKey, fieldId) { it.copy(hidden = false) }

    fun editField(sectionKey: String, fieldId: String, edit: (FormField) -> FormField): FormTemplate =
        mapField(sectionKey, fieldId, edit)

    /** One step up or down within its own section. */
    fun nudgeField(sectionKey: String, fieldId: String, delta: Int): FormTemplate {
        val rows = section(sectionKey)?.ordered ?: return this
        val from = rows.indexOfFirst { it.id == fieldId }
        val to = from + delta
        if (from < 0 || to !in rows.indices) return this
        val moved = rows.toMutableList()
        moved[from] = rows[to]
        moved[to] = rows[from]
        return mapSection(sectionKey) { it.copy(fields = moved) }.renumberFields(sectionKey)
    }

    /**
     * Moves [fromId] to where [toId] sits within their section — the
     * rearrange panel's drop.
     *
     * Keyed for the reason sections are: the panel lists only the fields on
     * the form, so a position taken from it names a different row whenever a
     * field above is off the form.
     */
    fun moveField(sectionKey: String, fromId: String, toId: String): FormTemplate {
        if (fromId == toId) return this
        val rows = section(sectionKey)?.ordered?.toMutableList() ?: return this
        val from = rows.indexOfFirst { it.id == fromId }
        val to = rows.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return this
        rows.add(to, rows.removeAt(from))
        return mapSection(sectionKey) { it.copy(fields = rows) }.renumberFields(sectionKey)
    }

    /** Moves a field to the end of another section. */
    fun moveFieldToSection(fromKey: String, fieldId: String, toKey: String): FormTemplate {
        if (fromKey == toKey) return this
        val field = section(fromKey)?.fields?.firstOrNull { it.id == fieldId } ?: return this
        val destination = section(toKey) ?: return this
        // Re-keyed if the destination already stores a field under this key,
        // for the same reason a new field is: one key, one value.
        val taken = destination.fields.map { it.label }.filter { it.isNotBlank() }.toSet()
        val landed = field.copy(label = uniqueLabel(field.label, taken))
        return mapSection(fromKey) { it.copy(fields = it.ordered.filterNot { row -> row.id == fieldId }) }
            .mapSection(toKey) { it.copy(fields = it.ordered + landed) }
            .renumberFields(fromKey)
            .renumberFields(toKey)
    }

    // -- helpers ------------------------------------------------------------

    private fun mapSection(key: String, edit: (FormSection) -> FormSection): FormTemplate =
        copy(sections = sections.map { if (it.key == key) edit(it) else it })

    private fun mapField(
        sectionKey: String,
        fieldId: String,
        edit: (FormField) -> FormField,
    ): FormTemplate = mapSection(sectionKey) { section ->
        section.copy(fields = section.fields.map { if (it.id == fieldId) edit(it) else it })
    }

    private fun renumberSections(): FormTemplate =
        copy(sections = sections.mapIndexed { index, section -> section.copy(order = index + 1) })

    private fun renumberFields(sectionKey: String): FormTemplate = mapSection(sectionKey) { section ->
        section.copy(fields = section.fields.mapIndexed { index, field -> field.copy(order = index + 1) })
    }

    companion object {
        /**
         * Not configurable: the Terms and Conditions document on PO settings
         * replaced it. Kept in the template so a save does not delete it.
         */
        const val TERMS_SECTION = "terms_of_engagement"
    }
}

/** `"Budget Code"` becomes `budget_code`. The form's key for the field. */
fun fieldLabelFor(name: String): String = name.trim().lowercase()
    .map { if (it.isLetterOrDigit()) it else '_' }
    .joinToString("")
    .split('_')
    .filter { it.isNotEmpty() }
    .joinToString("_")
    .ifEmpty { "custom_field" }

/** `budget_code`, then `budget_code_2`, until nothing else holds it. */
private fun uniqueLabel(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var suffix = 2
    while ("${base}_$suffix" in taken) suffix++
    return "${base}_$suffix"
}

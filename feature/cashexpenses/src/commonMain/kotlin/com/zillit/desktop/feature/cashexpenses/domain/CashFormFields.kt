package com.zillit.desktop.feature.cashexpenses.domain

import com.zillit.desktop.core.forms.FormField

/**
 * The form template's own names for the fields on a float request, and the
 * web's rules for them (`PCFloatRequestPage.jsx:113-175`, `344-388`).
 *
 * The names are the server's system-field keys — `FIELD_TO_PAYLOAD` maps each
 * one to the payload key it becomes.
 */
object CashFormFields {

    /** The section a float request is built from. */
    const val FLOAT_REQUEST = "float_request"

    const val USER = "user_id"
    const val AMOUNT = "requested_amount"
    const val PURPOSE = "purpose"
    const val DURATION = "duration"
    const val DURATION_TYPE = "duration_type"
    const val DEPARTMENT = "department_id"
    const val COLLECT_DATE = "collect_date"
    const val EPISODE = "episode"
    const val COLLECTION_METHOD = "collection_method"
    const val START_DATE = "start_date"
    const val COLLECT_TIME = "collect_time"

    /** `duration_type`'s two values. */
    const val RUN_OF_SHOW = "run_of_show"
    const val DAYS = "days"

    /** The textarea type the template uses for a long answer. */
    const val TEXTAREA = "textarea"

    /** Every field this screen has a control for — all the web's float request renders. */
    val RENDERED: Set<String> = setOf(
        USER, AMOUNT, PURPOSE, DURATION, DURATION_TYPE, DEPARTMENT, COLLECT_DATE, EPISODE, COLLECTION_METHOD,
    )

    /**
     * Product change: the request no longer collects a start date or a
     * collection time; they are dropped from the template (`REMOVED_FLOAT_FIELDS`).
     */
    val REMOVED: Set<String> = setOf(START_DATE, COLLECT_TIME)

    /** Read-only on the form: filled from the profile (`READONLY_FIELDS`). */
    val READ_ONLY: Set<String> = setOf(USER, DEPARTMENT)

    /** The static selects' options, value → label (`SELECT_OPTIONS`), in the web's order. */
    val OPTIONS: Map<String, List<Pair<String, String>>> = mapOf(
        EPISODE to listOf(
            "ep_3" to "Ep.3",
            "ep_4" to "Ep.4",
            "ep_4_5" to "Ep.4 & Ep.5",
            "multiple" to "Multiple / General",
        ),
        COLLECTION_METHOD to listOf(
            "production_office" to "Collect from production office",
            "arrange_accountant" to "Arrange with accountant",
        ),
        DURATION_TYPE to listOf(
            RUN_OF_SHOW to "Run of Show",
            DAYS to "Days",
        ),
    )

    /** The label of a static select's stored value, or the value. */
    fun optionLabel(field: String, value: String): String =
        OPTIONS[field]?.firstOrNull { it.first == value }?.second ?: value

    /**
     * The float request's fields as the page renders them: visible, in order,
     * the removed two dropped, and every one required except the department —
     * product rule: every field on a petty-cash float request is mandatory,
     * but the department is read-only and derived, and forcing it would
     * dead-end an accountant raising a float for someone with none.
     *
     * An unread or empty template renders [DEFAULT_FIELDS], none of them
     * required beyond the amount — the web renders nothing and requires
     * nothing then; this keeps the request possible.
     */
    fun requestFields(visible: List<FormField>): List<FormField> {
        if (visible.isEmpty()) return DEFAULT_FIELDS
        return visible
            .filterNot { it.label in REMOVED }
            .map { if (it.label == DEPARTMENT) it else it.copy(required = true) }
    }

    /** Where a select starts before anyone touches it — the first option, except How long. */
    fun defaultSelect(field: FormField): String? =
        if (field.label in READ_ONLY || field.label == DURATION_TYPE || !field.systemDefault) {
            null
        } else {
            OPTIONS[field.label]?.firstOrNull()?.first
        }

    /** The system fields, as a template with nothing configured would carry them. */
    val DEFAULT_FIELDS: List<FormField> = listOf(
        FormField(label = USER, name = "Requested By", order = 1, systemDefault = true, type = "select"),
        FormField(label = DEPARTMENT, name = "Department", order = 2, systemDefault = true, type = "select"),
        FormField(label = AMOUNT, name = "Requested Amount", order = 3, systemDefault = true, type = "number"),
        FormField(
            label = DURATION_TYPE, name = "How long", order = 4, systemDefault = true, type = "select",
            selectionType = DURATION_TYPE,
        ),
        FormField(label = DURATION, name = "Days", order = 5, systemDefault = true, type = "number"),
        FormField(
            label = COLLECTION_METHOD, name = "Collection Method", order = 6, systemDefault = true,
            type = "select", selectionType = COLLECTION_METHOD,
        ),
        FormField(label = COLLECT_DATE, name = "Collect Date", order = 7, systemDefault = true, type = "date"),
        FormField(label = PURPOSE, name = "Purpose", order = 8, systemDefault = true, type = TEXTAREA),
    )
}

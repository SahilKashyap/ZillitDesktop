package com.zillit.desktop.feature.cashexpenses.domain

/**
 * The form template's own names for the fields on a float request.
 *
 * Taken from the web's `PCFloatRequestPage.FIELD_TO_PAYLOAD`, which maps each
 * one to the payload key it becomes — they are the server's system-field keys,
 * not a guess.
 *
 * This screen renders five of them. The rest belong to the web's fuller form;
 * see [RENDERED].
 */
object CashFormFields {

    /** The section a float request is built from. */
    const val FLOAT_REQUEST = "float_request"

    const val AMOUNT = "requested_amount"
    const val PURPOSE = "purpose"
    const val DURATION = "duration"
    const val DURATION_TYPE = "duration_type"
    const val DEPARTMENT = "department_id"

    /**
     * What this screen actually offers a control for.
     *
     * The collection date and time, the start date, the episode, the
     * collection method and the on-behalf-of picker belong to the web's fuller
     * form. A template requiring one of those is reported rather than
     * enforced: refusing the request would leave the person with nothing on
     * screen to put right.
     */
    val RENDERED: Set<String> = setOf(AMOUNT, PURPOSE, DURATION, DURATION_TYPE, DEPARTMENT)
}

package com.zillit.desktop.feature.costumesetsync.domain

import com.zillit.desktop.core.strings.S

/**
 * Field checks for the module's forms — the web's `syncOnset/lib/validate.js`.
 *
 * Apart from the forms so the rules have a test of their own and so every form
 * states them the same way. Each answers a STRING KEY when the value is wrong
 * and null when it is fine, so a form reads `val err = phoneError(phone)` and
 * shows `str(err)`.
 *
 * They all treat an empty value as fine: whether a field is required is a
 * separate question, and these forms do let you save a vendor with no phone.
 */
object Validate {

    /** Digits only — what was typed, minus spaces, dashes and brackets. */
    private fun digits(value: String): String = value.filter { it.isDigit() }

    /** The same 5–20 the app's shared phone input enforces. */
    const val PHONE_MIN: Int = 5
    const val PHONE_MAX: Int = 20

    /** The backend refuses a country code longer than this. */
    const val MAX_CODE_LENGTH: Int = 10

    /**
     * ZL-21945. Length only: the country code is a field of its own now
     * (ZL-21942), and national formats vary too much to pattern-match without
     * refusing real numbers.
     */
    fun phoneError(value: String?): String? {
        val n = digits(value.orEmpty())
        if (n.isEmpty()) return null
        return if (n.length < PHONE_MIN || n.length > PHONE_MAX) S.desktop_csync_err_phone_length else null
    }

    /**
     * ZL-21945. Deliberately loose — something@something.tld with no
     * whitespace. A stricter pattern refuses addresses that are genuinely
     * deliverable, and the server is the real check.
     */
    fun emailError(value: String?): String? {
        val v = value.orEmpty().trim()
        if (v.isEmpty()) return null
        return if (EMAIL.matches(v)) null else S.desktop_csync_err_email_format
    }

    /**
     * ZL-21937. A cost may be blank or zero; it may not be negative. Anything
     * non-numeric is caught too, since these fields are free text.
     */
    fun costError(value: String?): String? {
        val raw = value.orEmpty().trim()
        if (raw.isEmpty()) return null
        val n = raw.toDoubleOrNull() ?: return S.docusign_field_number_desc
        return if (n < 0) S.desktop_csync_err_negative else null
    }

    /**
     * ZL-21938. The later date may EQUAL the earlier one — a same-day rental
     * is a real thing — but it may not come before it. Both are `YYYY-MM-DD`,
     * so a plain string compare is the whole rule: no date library, no zone.
     */
    fun dateOrderError(from: String?, to: String?): String? {
        if (from.isNullOrBlank() || to.isNullOrBlank()) return null
        return if (to < from) S.desktop_csync_err_return_before_pickup else null
    }

    /** True when nothing in the list is an error, for a Save button's `enabled`. */
    fun noErrors(vararg errors: String?): Boolean = errors.all { it == null }

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")
}

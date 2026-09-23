package com.zillit.desktop.feature.crewlist.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
/**
 * The two admin-only surfaces the crew list reaches from its own screen, as
 * the web mounts them there: the department listing order (Settings →
 * Admin Settings → Listing Order for Crew List) and the company details that
 * print as the document's letterhead.
 */
data class OrderedDepartment(
    val id: String,
    /** Translated for display; the order itself is by id. */
    val name: String,
)

/**
 * The department order being edited — the web's `ChangePriorityList`: drag a
 * department, or type its new position; Reset puts the list in id order
 * (creation order, as ObjectIds are time-ordered); Save sends the whole order.
 */
data class DepartmentOrder(
    val loaded: List<OrderedDepartment> = emptyList(),
    val current: List<OrderedDepartment> = loaded,
) {
    /** The web compares ids and names position by position. */
    val isChanged: Boolean get() = current != loaded

    fun move(from: Int, to: Int): DepartmentOrder {
        if (from !in current.indices || to !in current.indices || from == to) return this
        val rows = current.toMutableList()
        rows.add(to, rows.removeAt(from))
        return copy(current = rows)
    }

    /**
     * The typed-priority path: 1-based, refused outside `1..size` — the
     * caller shows "Priority must be between 1 and N".
     */
    fun moveToPosition(index: Int, position: Int): DepartmentOrder? =
        if (position in 1..current.size && index in current.indices) move(index, position - 1) else null

    fun reset(): DepartmentOrder = copy(current = current.sortedBy { it.id })

    fun discard(): DepartmentOrder = copy(current = loaded)
}

/** One person in a department's listing order. */
data class OrderedPerson(
    val userId: String,
    val name: String,
    /** A label key — translate before display and search. */
    val designation: String = "",
)

/**
 * The order of the people inside one department — the web's `DragUser`,
 * opened by clicking a department in the listing order: drag, or type the
 * position; Save sends every id in order.
 */
data class PeopleOrder(
    val department: OrderedDepartment,
    val loaded: List<OrderedPerson> = emptyList(),
    val current: List<OrderedPerson> = loaded,
) {
    val isChanged: Boolean get() = current.map { it.userId } != loaded.map { it.userId }

    fun move(from: Int, to: Int): PeopleOrder {
        if (from !in current.indices || to !in current.indices || from == to) return this
        val rows = current.toMutableList()
        rows.add(to, rows.removeAt(from))
        return copy(current = rows)
    }

    fun moveToPosition(index: Int, position: Int): PeopleOrder? =
        if (position in 1..current.size && index in current.indices) move(index, position - 1) else null

    /** Saved: what is on screen becomes what was loaded. */
    fun saved(): PeopleOrder = copy(loaded = current)

    companion object {
        /** Who the web leaves out of the list: people removed, still pending, or gone. */
        val HIDDEN_STATUSES = setOf("removed", "pending", "left")
    }
}

/** A label/value line the admin adds under the company details. */
data class CompanyCustomField(
    val label: String = "",
    val value: String = "",
    val fieldType: String = TEXT,
) {
    companion object {
        const val TEXT = "text"
    }
}

/** The stored logo — a raw S3 key, like every other file here. */
data class CompanyLogo(
    val media: String,
    val thumbnail: String = "",
    val bucket: String = "",
    val region: String = "",
    val caption: String = "",
)

/**
 * The production's company details (web `CompanyDetails.jsx`, ZL-19721): one
 * partial `PATCH project` carries every field; the logo is uploaded to storage
 * by the client first, and removed through its own `DELETE project/company-logo`.
 */
data class CompanyDetails(
    val name: String = "",
    val number: String = "",
    val email: String = "",
    val countryCode: String = "",
    val phone: String = "",
    val address: String = "",
    val registeredAddress: String = "",
    val customFields: List<CompanyCustomField> = emptyList(),
    val logo: CompanyLogo? = null,
) {
    /** Two letters for the logo tile when there is no image — "CO" when unnamed. */
    val initials: String
        get() = name.split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "CO" }

    val phoneLine: String get() = listOf(countryCode, phone).filter { it.isNotBlank() }.joinToString(" ")

    /** Custom rows with nothing in them are dropped, labels trimmed — the web's save normalisation. */
    fun normalisedFields(): List<CompanyCustomField> = customFields
        .filter { it.label.isNotBlank() || it.value.isNotBlank() }
        .map { it.copy(label = it.label.trim(), fieldType = it.fieldType.ifBlank { CompanyCustomField.TEXT }) }
}

/** Which input a company-details problem belongs to. */
enum class CompanyField { Email, Phone, CustomLabel }

data class CompanyProblem(val field: CompanyField, val message: String, val index: Int = -1)

object CompanyRules {
    const val MIN_PHONE = 5
    const val MAX_PHONE = 20

    /**
     * The web's checks, in its order: the email's shape, every custom row needs
     * a label (the form's `required` rule — an empty row must be removed, not
     * left), then the phone pair. Empty list means savable.
     */
    fun validate(details: CompanyDetails, text: (key: String, fallback: String) -> String): List<CompanyProblem> =
        buildList {
            if (details.email.isNotBlank() && !details.email.trim().looksLikeEmail()) {
                add(CompanyProblem(CompanyField.Email, text("InvalidEmail", str(S.desktop_please_enter_a_valid_email))))
            }
            details.customFields.forEachIndexed { index, field ->
                if (field.label.isBlank()) {
                    val message = text("LabelRequired", str(S.desktop_label_is_required))
                    add(CompanyProblem(CompanyField.CustomLabel, message, index))
                }
            }
            val phone = details.phone.trim()
            val code = details.countryCode.trim()
            when {
                phone.isNotEmpty() && code.isEmpty() -> add(
                    CompanyProblem(
                        CompanyField.Phone,
                        text("please_select_your_country_code", str(S.desktop_cl_select_country_code_for_phone)),
                    ),
                )
                code.isNotEmpty() && phone.isEmpty() -> add(
                    CompanyProblem(
                        CompanyField.Phone,
                        text("please_enter_phone_number", str(S.desktop_cl_enter_phone_for_country_code)),
                    ),
                )
                phone.isNotEmpty() && phone.length !in MIN_PHONE..MAX_PHONE -> add(
                    CompanyProblem(
                        CompanyField.Phone,
                        text("phone_number_character_error", str(S.desktop_cl_phone_between_5_and_20)),
                    ),
                )
            }
        }

    private fun String.looksLikeEmail(): Boolean {
        val at = indexOf('@')
        return at > 0 && at == lastIndexOf('@') && substring(at + 1).let { domain ->
            domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
        } && none { it.isWhitespace() }
    }
}

/** A dial code the phone pickers offer: `India (+91)`, stored as `+91`. */
data class DialCode(
    val name: String,
    val dialCode: String,
    val isoCode: String = "",
) {
    val label: String get() = "$name ($dialCode)"
}

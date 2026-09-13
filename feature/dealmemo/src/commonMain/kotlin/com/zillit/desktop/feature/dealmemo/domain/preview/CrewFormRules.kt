package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A step of "Complete your details" — each titled exactly as its memo section. */
enum class CrewStep(val label: String, val sub: String) {
    Crew("Crew Details", "Your personal and contact details"),
    Emergency("Emergency Details", "Who we contact in an emergency"),
    Representative("Representative Details", "Agent or representative, if you have one"),
    Bank("Bank Details", "Where your wages are paid"),
    LoanOut("Loan Out Company", "The company you're engaged through"),
}

/** A field whose text fails its format — named in the footer, shown under the field. */
data class FormatError(val field: CrewField, val message: String)

/** The crew form's validated fields, in the footer's order. */
enum class CrewField(val label: String) {
    Email("Email"),
    Mobile("Mobile"),
    EmergencyEmail("Emergency contact email"),
    EmergencyPhone("Emergency contact number"),
    RepresentativeEmail("Agency email"),
    RepresentativePhone("Agency phone"),
    LoanOutEmail("Loan Out Company email"),
    LoanOutPhone("Loan Out Company phone"),
    PayeRef("P45 previous employer PAYE ref"),
}

/**
 * The crew form's rules (`CrewDetailsEditorPanel.jsx`, `utils/phone.js`,
 * `utils/email.js`): the steps, the format checks that block Save and
 * Continue, and the input sanitisers. Empty is never invalid.
 */
object CrewFormRules {

    const val EMAIL_ERROR = "Enter a valid email address"
    const val PHONE_ERROR = "Enter a valid phone number (5–15 digits)"
    const val PAYE_ERROR = "Use the format 123/AB456."
    const val ACCOUNTS_OFFICE_ERROR = "Use the format 123PA00012345."

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$")
    private val PAYE = Regex("^\\d{3}/[A-Za-z0-9]{1,10}$")
    private val ACCOUNTS_OFFICE = Regex("^\\d{3}P[A-Za-z]\\d{8}$")
    private const val PHONE_MIN = 5
    private const val PHONE_MAX = 15

    fun validEmail(value: String?): Boolean = value.isNullOrBlank() || EMAIL.matches(value.trim())

    fun validPhone(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return value.count { it.isDigit() } in PHONE_MIN..PHONE_MAX
    }

    fun validPayeRef(value: String?): Boolean = value.isNullOrBlank() || PAYE.matches(value.trim())

    /** An employer's Accounts Office reference: three digits, `P`, a letter, eight digits. */
    fun validAccountsOfficeRef(value: String?): Boolean = value.isNullOrBlank() || ACCOUNTS_OFFICE.matches(value.trim())

    /** `sanitizePhoneInput`: digits only, and one leading `+` where the field allows it. */
    fun sanitizePhone(value: String, allowPlus: Boolean): String {
        val digits = value.filter { it.isDigit() }
        return if (allowPlus && value.trimStart().startsWith("+")) "+$digits" else digits
    }

    /** Sort code state: digits, at most six. */
    fun stripSortCode(value: String): String = value.filter { it.isDigit() }.take(SORT_CODE_DIGITS)

    /** The steps for this deal — Loan Out only for a loan-out engagement, read from the draft first. */
    fun steps(deal: DealDoc, draft: CrewDraft?): List<CrewStep> {
        val status = draft?.let { DocRead.text(it.crewDetails, "emp_status") } ?: DocRead.text(deal.crew, "emp_status")
        return CrewStep.entries.filter { it != CrewStep.LoanOut || CrewStatus.isLoanOut(status) }
    }

    /** Every format error in the draft, whichever step it sits on. */
    fun formatErrors(deal: DealDoc, draft: CrewDraft): List<FormatError> {
        val cd = draft.crewDetails
        val emergency = DocRead.obj(cd, "emergency_details")
        val representative = DocRead.obj(cd, "representative_details")
        val loanOut = DocRead.obj(cd, "loan_out_company")
        val checks = listOf(
            Triple(CrewField.Email, text(cd, "email"), EMAIL_ERROR),
            Triple(CrewField.Mobile, text(cd, "mobile"), PHONE_ERROR),
            Triple(CrewField.EmergencyEmail, text(emergency, "email"), EMAIL_ERROR),
            Triple(CrewField.EmergencyPhone, text(cd, "emergency_contact_number"), PHONE_ERROR),
            Triple(CrewField.RepresentativeEmail, text(representative, "email"), EMAIL_ERROR),
            Triple(CrewField.RepresentativePhone, text(representative, "phone_number"), PHONE_ERROR),
            Triple(CrewField.LoanOutEmail, text(loanOut, "email"), EMAIL_ERROR),
            Triple(CrewField.LoanOutPhone, text(loanOut, "phone_number"), PHONE_ERROR),
        )
        val errors = checks.filterNot { (_, value, message) ->
            if (message == EMAIL_ERROR) validEmail(value) else validPhone(value)
        }.map { (field, _, message) -> FormatError(field, message) }
        val uk = DocRead.obj(cd, UkPayroll.KEY)
        val empStatus = DocRead.text(cd, "emp_status") ?: DocRead.text(deal.crew, "emp_status")
        val payeError = UkPayroll.appliesTo(deal.territoryCode, empStatus) &&
            UkPayroll.route(uk) == UkPayroll.ROUTE_P45 && !validPayeRef(text(uk, "p45_previous_paye_ref"))
        return errors + listOfNotNull(FormatError(CrewField.PayeRef, PAYE_ERROR).takeIf { payeError })
    }

    /**
     * The footer's amber note: a field the sender marked, that the crew can
     * fill and keep, is still empty. Nested sections and the passport never count.
     */
    fun markedGateableMissing(deal: DealDoc, draft: CrewDraft): Boolean =
        CrewRequirements.markedPaths(deal)
            .filter { '.' !in it && it != "passport_attachment" }
            .any { CrewRequirements.isMissing(it, CrewRequirements.valueAt(draft, it)) }

    /** Paths a red asterisk marks on the form: marks, the floor, and unsatisfied pairs. */
    fun requiredPaths(deal: DealDoc, draft: CrewDraft): Set<String> =
        CrewRequirements.requiredPaths(CrewRequirements.markedPaths(deal), draft).toSet()

    private fun text(json: JsonObject?, key: String): String? =
        (json?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private const val SORT_CODE_DIGITS = 6
}

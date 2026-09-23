package com.zillit.desktop.feature.crewlist.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
/**
 * One member's pending changes for the crew list only — never saved to their
 * profile. Null leaves a field as the roster has it; an empty string blanks
 * that cell in the document. Sent as a `member_overrides` entry with every
 * preview, generate and publish (web `buildMemberOverrides`).
 */
data class MemberOverride(
    val phone: String? = null,
    val countryCode: String? = null,
    val email: String? = null,
) {
    val isEmpty: Boolean get() = phone == null && countryCode == null && email == null
}

/**
 * What a member's phone and email cells show — the source record with any
 * override laid over it. Read mode shows the override too: editing is a mode
 * you leave, and a cell snapping back to the roster value after Done would
 * read as a discarded edit.
 */
data class MemberCells(
    val countryCode: String,
    /** The local number only; the dial code is its own field. */
    val phone: String,
    /** PROFILE — the member's own address, the only editable one. */
    val profileEmail: String,
    /** PROJECT — the auto-generated Zillit mailbox, never editable. */
    val projectEmail: String,
) {
    /** `+449876543`, or empty when there is no number. */
    val phoneDisplay: String get() = if (phone.isNotEmpty()) countryCode + phone else ""
}

object CrewMemberRules {

    /** The roster's own values, before any edit. */
    fun source(member: CrewMember): MemberCells = MemberCells(
        countryCode = member.countryCode,
        phone = member.phone,
        profileEmail = member.profileEmail,
        projectEmail = member.projectEmail,
    )

    fun cells(member: CrewMember, override: MemberOverride?): MemberCells {
        val source = source(member)
        return source.copy(
            countryCode = override?.countryCode ?: source.countryCode,
            phone = override?.phone ?: source.phone,
            profileEmail = override?.email ?: source.profileEmail,
        )
    }

    /**
     * Applies an edit and drops every field that now matches the roster, so an
     * edit typed and then undone by hand leaves no override behind (and no
     * "your edits will be applied" hint for nothing). Null when nothing differs.
     */
    fun merge(member: CrewMember, existing: MemberOverride?, edit: MemberOverride): MemberOverride? {
        val source = source(member)
        val merged = MemberOverride(
            phone = edit.phone ?: existing?.phone,
            countryCode = edit.countryCode ?: existing?.countryCode,
            email = edit.email ?: existing?.email,
        )
        return MemberOverride(
            phone = merged.phone?.takeIf { it != source.phone },
            countryCode = merged.countryCode?.takeIf { it != source.countryCode },
            email = merged.email?.takeIf { it != source.profileEmail },
        ).takeUnless { it.isEmpty }
    }

    /** Digits only — what the web's key filter and blur clean-up leave. */
    fun digitsOnly(raw: String): String = raw.filter { it in '0'..'9' }
}

/** Which of the two phone inputs a validation message belongs under. */
enum class PhoneField { CountryCode, Number }

data class PhoneProblem(val field: PhoneField, val message: String)

/**
 * The web's phone rules (ZL-20023): a number needs a dial code and a dial
 * code needs a number; clearing both blanks the cell with no complaint; the
 * number is 5 to 25 digits. The messages are the web's own translation keys.
 */
object PhoneRules {
    const val MIN_DIGITS = 5
    const val MAX_DIGITS = 25

    fun validate(phone: String, countryCode: String, text: (key: String, fallback: String) -> String): PhoneProblem? {
        val number = phone.trim()
        val code = countryCode.trim()
        return when {
            number.isEmpty() && code.isEmpty() -> null
            code.isEmpty() -> PhoneProblem(
                PhoneField.CountryCode,
                text("please_select_your_country_code", str(S.desktop_please_select_your_country_code)),
            )
            number.isEmpty() -> PhoneProblem(
                PhoneField.Number,
                text("required_phone_number", str(S.desktop_cl_phone_required)),
            )
            number.length < MIN_DIGITS -> PhoneProblem(
                PhoneField.Number,
                text("min_length_phone_number", str(S.desktop_cl_phone_min_length)),
            )
            number.length > MAX_DIGITS -> PhoneProblem(
                PhoneField.Number,
                text("max_length_phone_number", str(S.desktop_cl_phone_max_length)),
            )
            else -> null
        }
    }
}

/**
 * The web's roster search: a member stays when their name or translated
 * designation contains the query, case-insensitively; departments and units
 * left empty go. Nobody is excluded for status or for being the viewer.
 */
fun List<CrewUnit>.search(query: String, translate: (String) -> String): List<CrewUnit> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return mapNotNull { unit ->
        val departments = unit.departments.mapNotNull { department ->
            val members = department.members.filter { member ->
                member.fullName.contains(needle, ignoreCase = true) ||
                    translate(member.designationName).contains(needle, ignoreCase = true)
            }
            department.copy(members = members).takeIf { members.isNotEmpty() }
        }
        unit.copy(departments = departments).takeIf { departments.isNotEmpty() }
    }
}

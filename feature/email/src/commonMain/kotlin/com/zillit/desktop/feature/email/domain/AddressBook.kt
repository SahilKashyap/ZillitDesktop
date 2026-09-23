package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A contact in the user's own address book, in full.
 *
 * [EmailContact] is the *suggestion* — address, name, one line under it — and
 * is what the composer wants. This is the whole record the contacts screen
 * edits: Android's `Contact` (`bottomNav/new_email/domain/model/Contact.kt:3-33`)
 * and `ContactDto` (`EmailDto.kt:112-138`), same fields.
 *
 * Only [address] is required. The rest is whatever the person filling the form
 * chose to say — Android's editor validates just the address and the
 * phone/country-code pairing (`EditContactActivity.kt:358-375`).
 */
data class SavedContact(
    /** Blank until the server has assigned one. */
    val id: String = "",
    val address: String,
    val firstName: String = "",
    val lastName: String = "",
    /**
     * The name as saved. Android sends `first last` (falling back to the
     * address) whatever was typed here (`ContactRepositoryImpl.kt:95-97`), so
     * this is authoritative only on the way *in*.
     */
    val contactName: String = "",
    val company: String = "",
    val phone: String = "",
    val countryCode: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val notes: String = "",
) {
    /** Android `Contact.displayName` (`Contact.kt:19-22`). */
    val displayName: String
        get() = contactName.ifBlank { "$firstName $lastName".trim().ifBlank { address } }

    /** `+91 98765…`, or blank — Android `Contact.formattedPhone`. */
    val formattedPhone: String
        get() = if (phone.isNotBlank()) "$countryCode $phone".trim() else ""

    /** Street through country, comma-joined, empties dropped — Android `Contact.formattedAddress`. */
    val formattedAddress: String
        get() = listOf(street, city, state, zipCode, country).filter { it.isNotBlank() }.joinToString(", ")

    /** What the composer would insert for this contact. */
    fun asEmailContact(): EmailContact = EmailContact(
        address = address,
        name = displayName.takeUnless { it == address }.orEmpty(),
        source = ContactSource.Saved,
        subtitle = company,
    )

    /** Never prints the address or phone — an address book is personal data. */
    override fun toString(): String = "SavedContact(id=$id, named=${displayName != address})"
}

/**
 * Why a contact cannot be saved as typed.
 *
 * Android's three checks (`EditContactActivity.kt:358-375`): the address must
 * contain an `@`; a phone needs a country code, and a country code needs a
 * phone. Everything else is optional.
 */
enum class ContactFormError {
    InvalidAddress,
    PhoneWithoutCountryCode,
    CountryCodeWithoutPhone,
    ;

    val message: String
        get() = when (this) {
            InvalidAddress -> str(S.desktop_please_enter_a_valid_email)
            PhoneWithoutCountryCode -> str(S.desktop_please_choose_country_code)
            CountryCodeWithoutPhone -> str(S.desktop_please_enter_valid_phone)
        }
}

fun SavedContact.validate(): ContactFormError? = when {
    !address.trim().contains('@') -> ContactFormError.InvalidAddress
    phone.isNotBlank() && countryCode.isBlank() -> ContactFormError.PhoneWithoutCountryCode
    countryCode.isNotBlank() && phone.isBlank() -> ContactFormError.CountryCodeWithoutPhone
    else -> null
}

/**
 * The address book: `email-contact` on the mail service.
 *
 * Distinct from [ContactRepository], which the composer uses for suggestions
 * and which only reads. This one edits, and hands back the full record.
 */
interface AddressBookRepository {

    /**
     * Named apart from [ContactRepository.contacts] so one class can be both —
     * the live implementation is, and Kotlin will not let two overrides share
     * a name and differ only in return type.
     */
    suspend fun savedContacts(): ZillitResult<List<SavedContact>>

    suspend fun save(contact: SavedContact): ZillitResult<Unit>

    suspend fun update(id: String, contact: SavedContact): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>
}

/**
 * Contacts matching [query] — on name, address, company or phone.
 *
 * Case-insensitive `contains`, as the phone's search does. Blank shows all.
 */
fun List<SavedContact>.matching(query: String): List<SavedContact> {
    val term = query.trim().lowercase()
    if (term.isEmpty()) return this
    return filter { contact ->
        contact.displayName.lowercase().contains(term) ||
            contact.address.lowercase().contains(term) ||
            contact.company.lowercase().contains(term) ||
            contact.formattedPhone.contains(term)
    }
}

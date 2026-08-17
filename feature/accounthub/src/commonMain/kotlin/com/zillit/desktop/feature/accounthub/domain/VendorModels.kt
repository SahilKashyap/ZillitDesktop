package com.zillit.desktop.feature.accounthub.domain

/**
 * A vendor's postal address.
 *
 * ## It is an object on the wire, in both directions
 *
 * Sending `"address": ""` is refused outright: `"address" must be of type
 * object` (dev, 2026-08-12). The service validates the shape, so a flattened
 * string cannot be written back even though several read paths hand one out.
 */
data class VendorAddress(
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(line1, line2, city, state, postalCode, country).all { it.isBlank() }

    /** One line, for a table cell. Blank parts are dropped rather than double-comma'd. */
    val oneLine: String
        get() = listOf(line1, line2, city, state, postalCode, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
}

/**
 * A vendor's phone, as a dialling code and a number.
 *
 * Null rather than an empty pair when unset: `{ country_code, number: "" }` is
 * truthy, and every guard downstream then renders a bare dial code — the web
 * documents that exact trap on its own payload builder.
 */
data class VendorPhone(val countryCode: String = "", val number: String = "") {
    val isEmpty: Boolean get() = number.isBlank()

    val display: String get() = listOf(countryCode, number).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * A supplier the production buys from.
 *
 * ## `verified` is derived, not sent
 *
 * The service sends a `status` string and no boolean. Every surface that reads a
 * `verified` field off the raw row therefore sees nothing and renders
 * "Non-verified" for the entire register — the exact drift the web's
 * `normalizeVendor` exists to stop. It is derived once, here, at the boundary.
 *
 * ## The tax field is `vat_number`
 *
 * Not `tax_number`, which reads as the obvious name and is what this module
 * sent until the server rejected the payload. Both the read and the write use
 * `vat_number`.
 */
data class Vendor(
    val id: String,
    val name: String = "",
    val email: String = "",
    val contactPerson: String = "",
    val phone: VendorPhone? = null,
    val address: VendorAddress = VendorAddress(),
    val departmentId: String? = null,
    val vatNumber: String = "",
    val currencyCode: String = "",
    val verified: Boolean = false,
    val bankAccountId: String? = null,
) {
    /** What to show when the register is scanned — the name, or the email if unnamed. */
    val display: String get() = name.ifBlank { email }.ifBlank { "Unnamed vendor" }

    companion object {
        const val VERIFIED_STATUS = "VERIFIED"
    }
}

/** A change to a vendor, newest first, from its audit trail. */
data class VendorChange(
    val id: String,
    val at: Long? = null,
    val byName: String = "",
    val summary: String = "",
)

/** A vendor being created or edited. */
data class NewVendor(
    val name: String = "",
    val email: String = "",
    val contactPerson: String = "",
    val phoneCountryCode: String = DEFAULT_DIAL_CODE,
    val phoneNumber: String = "",
    val address: VendorAddress = VendorAddress(country = DEFAULT_COUNTRY),
    val vatNumber: String = "",
    val departmentId: String? = null,
    val currencyCode: String = "",
) {
    /** Null when no number was entered — see [VendorPhone]. */
    fun phone(): VendorPhone? =
        phoneNumber.trim().takeIf { it.isNotEmpty() }?.let { VendorPhone(phoneCountryCode, it) }

    companion object {
        /** What the web's picker opens on. */
        const val DEFAULT_DIAL_CODE = "+44"

        /**
         * Pre-filled because the service refuses an empty country and this is
         * the one address field a user is most likely to skip.
         */
        const val DEFAULT_COUNTRY = "United Kingdom"

        fun from(vendor: Vendor) = NewVendor(
            name = vendor.name,
            email = vendor.email,
            contactPerson = vendor.contactPerson,
            phoneCountryCode = vendor.phone?.countryCode?.ifBlank { DEFAULT_DIAL_CODE }
                ?: DEFAULT_DIAL_CODE,
            phoneNumber = vendor.phone?.number.orEmpty(),
            address = vendor.address,
            vatNumber = vendor.vatNumber,
            departmentId = vendor.departmentId,
            currencyCode = vendor.currencyCode,
        )
    }
}

/**
 * What the vendor form refuses to send, or null when it is ready.
 *
 * ## Every one of these is the server's rule, not a house style
 *
 * The service rejects a vendor missing any of them, one field per round trip —
 * measured on dev 2026-08-12, where a name-only vendor came back first with
 * `"address" must be of type object`, then `"address.postal_code" is not
 * allowed to be empty`. Checking here turns three failed submissions into none.
 *
 * Phone is the one genuinely optional field: email is the guaranteed contact
 * channel, and international vendors are often reached no other way.
 *
 * The email rule is stricter than "contains an @" because the server's is — it
 * refused `hire@ziltest.example` outright.
 */
fun NewVendor.validationError(): String? = when {
    name.isBlank() -> "Give the vendor a name."
    contactPerson.isBlank() -> "Name a contact person."
    email.isBlank() -> "Give the vendor an email address."
    !email.isPlausibleEmail() -> "That email address is not valid."
    address.line1.isBlank() -> "Give the vendor a street address."
    address.city.isBlank() -> "Give the vendor a city."
    address.postalCode.isBlank() -> "Give the vendor a postcode."
    address.country.isBlank() -> "Give the vendor a country."
    phoneNumber.isNotBlank() && phoneNumber.trim().length < MIN_PHONE_DIGITS ->
        "A phone number needs at least $MIN_PHONE_DIGITS digits."
    else -> null
}

/**
 * Local-part, `@`, domain, dot, and a two-letter-or-longer suffix.
 *
 * Deliberately not a full RFC 5322 parser — that accepts things this service
 * does not. This mirrors what the server's validator will take.
 */
private fun String.isPlausibleEmail(): Boolean {
    val trimmed = trim()
    if (trimmed.count { it == '@' } != 1) return false
    val (local, domain) = trimmed.split('@')
    if (local.isEmpty() || domain.isEmpty()) return false
    val labels = domain.split('.')
    return labels.size >= 2 &&
        labels.all { it.isNotEmpty() } &&
        labels.last().length >= MIN_TLD_LENGTH
}

private const val MIN_TLD_LENGTH = 2

/** Shorter than this is a typo, not a number. Matches the web's own rule. */
private const val MIN_PHONE_DIGITS = 5

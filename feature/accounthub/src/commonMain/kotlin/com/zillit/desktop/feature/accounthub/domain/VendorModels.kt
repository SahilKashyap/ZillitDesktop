package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
    /** The raw status string — `VERIFIED`, `PENDING`. */
    val status: String = "",
    // -- audit --
    val addedBy: String? = null,
    val verifiedBy: String? = null,
    val verifiedAtMillis: Long? = null,
    val updatedBy: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    // -- bank details, flat on the row as the web reads and writes them --
    val bankName: String = "",
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val ibanCode: String = "",
    val swiftCode: String = "",
    val additionalInfo: List<BankDetail> = emptyList(),
    val bankId: String? = null,
    // -- classification --
    val vendorType: String = "",
    val companyType: String = "",
    /** A payment term — `net_30`. See [VendorTerms]. */
    val terms: String = "",
    /** The chart code new lines default to. */
    val defaultCode: String = "",
    val compliance: String = "",
) {
    /** What to show when the register is scanned — the name, or the email if unnamed. */
    val display: String get() = name.ifBlank { email }.ifBlank { str(S.desktop_unnamed_vendor) }

    val hasBankDetails: Boolean
        get() = listOf(bankName, accountHolderName, accountNumber, sortCode, ibanCode, swiftCode)
            .any { it.isNotBlank() } || additionalInfo.any { it.isTitled }

    val hasClassification: Boolean
        get() = listOf(vendorType, companyType, terms, defaultCode, compliance).any { it.isNotBlank() }

    companion object {
        const val VERIFIED_STATUS = "VERIFIED"
    }
}

/**
 * A vendor's bank details, from wherever they actually live.
 *
 * ## Two homes, one reading
 *
 * A vendor's bank block is **written** flat on the vendor row — `bank_name`,
 * `iban_code`, `additional_info` — and the service keeps a linked record in
 * `/account-hub/bank-accounts` pointed at by `bank_id`. It is **read** from that
 * record, whose names differ: `name`, `iban_number`, `additional_details`. The
 * flat columns on the row are the legacy copy and are empty on a vendor saved
 * through the current flow.
 *
 * Reading only the row therefore showed no bank details for such a vendor, and
 * worse, seeded an empty bank block into the edit form — so saving an unrelated
 * change sent every bank field back as null. The web reads the record first and
 * falls back to the row, which is what [resolve] does.
 */
data class VendorBank(
    val bankName: String = "",
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val ibanCode: String = "",
    val swiftCode: String = "",
    val additionalInfo: List<BankDetail> = emptyList(),
) {
    val isEmpty: Boolean
        get() = listOf(bankName, accountHolderName, accountNumber, sortCode, ibanCode, swiftCode)
            .all { it.isBlank() } && additionalInfo.none { it.isTitled }

    companion object {
        /**
         * The linked record when there is one, the row's legacy copy otherwise.
         *
         * A record that came back empty does not override a populated row — a
         * half-migrated vendor with details in both places should never read as
         * having none.
         */
        fun resolve(vendor: Vendor, record: BankAccount?): VendorBank {
            val fromRecord = record?.let {
                VendorBank(
                    bankName = it.name,
                    accountHolderName = it.accountHolderName,
                    accountNumber = it.accountNumber,
                    sortCode = it.sortCode,
                    ibanCode = it.ibanNumber,
                    swiftCode = it.swiftCode,
                    additionalInfo = it.additionalDetails,
                )
            }
            if (fromRecord != null && !fromRecord.isEmpty) return fromRecord
            return VendorBank(
                bankName = vendor.bankName,
                accountHolderName = vendor.accountHolderName,
                accountNumber = vendor.accountNumber,
                sortCode = vendor.sortCode,
                ibanCode = vendor.ibanCode,
                swiftCode = vendor.swiftCode,
                additionalInfo = vendor.additionalInfo,
            )
        }
    }
}

/** Seeds a draft's bank block from wherever the vendor's details live. See [VendorBank]. */
fun NewVendor.withBank(bank: VendorBank): NewVendor = copy(
    bankName = bank.bankName,
    accountHolderName = bank.accountHolderName,
    accountNumber = bank.accountNumber,
    sortCode = bank.sortCode,
    ibanCode = bank.ibanCode,
    swiftCode = bank.swiftCode,
    additionalInfo = bank.additionalInfo,
)

/** The payment terms a vendor can be on — the web's `PAYMENT_TERMS_OPTIONS`. */
enum class VendorTerms(val wire: String, private val labelKey: String) {
    Net7("net_7", S.drive_expiry_7d),
    Net14("net_14", S.desktop_14_days),
    Net30("net_30", S.drive_expiry_30d),
    Net60("net_60", S.desktop_60_days),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** `net_30` → "30 days"; an unknown value passes through; blank is a dash. */
        fun labelFor(wire: String, fallback: String = "—"): String {
            if (wire.isBlank()) return fallback
            return entries.firstOrNull { it.wire == wire }?.label ?: wire
        }
    }
}

/** The web's vendor tabs, with the one department users get. */
enum class VendorTab(val slug: String, private val labelKey: String) {
    All("all", S.desktop_all_vendors),
    Verified("verified", S.ah_verified),
    Unverified("unverified", S.ah_non_verified),
    Mine("mine", S.ah_added_by_me),
    ;

    val label: String get() = str(labelKey)
}

/** A change to a vendor, newest first, from its audit trail. */
data class VendorChange(
    val id: String,
    val at: Long? = null,
    val byName: String = "",
    /** The actor's user id, for the roster to name when the row carries no name. */
    val byId: String = "",
    val summary: String = "",
    val note: String = "",
)

/** A vendor being created or edited. */
data class NewVendor(
    val name: String = "",
    val email: String = "",
    val contactPerson: String = "",
    /**
     * Empty until someone picks one — never a silent +44.
     *
     * The web defaulted this to Great Britain and saved `+44` onto vendors that
     * never chose it (ZL-20520); its fix starts empty and leans on the
     * picker's placeholder. Same here.
     */
    val phoneCountryCode: String = "",
    val phoneNumber: String = "",
    /**
     * Empty until someone picks one — never a silent United Kingdom.
     *
     * Country is required, and pre-filling it made that rule unreachable: every
     * vendor whose form nobody scrolled down was saved as UK. The web removed
     * the default for exactly that reason, and the "Country is required" line
     * is now what catches a skipped field.
     */
    val address: VendorAddress = VendorAddress(),
    val vatNumber: String = "",
    val departmentId: String? = null,
    val currencyCode: String = "",
    val bankName: String = "",
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val sortCode: String = "",
    val ibanCode: String = "",
    val swiftCode: String = "",
    val additionalInfo: List<BankDetail> = emptyList(),
    val companyType: String = "",
    val terms: String = "",
    val defaultCode: String = "",
    /** Carried through unchanged; the form never edits either. */
    val vendorType: String = "",
    val compliance: String = "",
) {
    /** Null when no number was entered — see [VendorPhone]. */
    fun phone(): VendorPhone? =
        phoneNumber.trim().takeIf { it.isNotEmpty() }?.let { VendorPhone(phoneCountryCode, it) }

    /** Whether any bank field was touched — the web gates its bank rules on this. */
    val hasBankInput: Boolean
        get() = listOf(bankName, accountHolderName, accountNumber, sortCode, ibanCode, swiftCode)
            .any { it.isNotBlank() } || additionalInfo.any { it.isTitled }

    companion object {
        fun from(vendor: Vendor) = NewVendor(
            name = vendor.name,
            email = vendor.email,
            contactPerson = vendor.contactPerson,
            // The vendor's own code, or none — an edit must not quietly add a
            // +44 to a phone that was saved without one.
            phoneCountryCode = vendor.phone?.countryCode.orEmpty(),
            phoneNumber = vendor.phone?.number.orEmpty(),
            address = vendor.address,
            vatNumber = vendor.vatNumber,
            departmentId = vendor.departmentId,
            currencyCode = vendor.currencyCode,
            bankName = vendor.bankName,
            accountHolderName = vendor.accountHolderName,
            accountNumber = vendor.accountNumber,
            sortCode = vendor.sortCode,
            ibanCode = vendor.ibanCode,
            swiftCode = vendor.swiftCode,
            additionalInfo = vendor.additionalInfo,
            companyType = vendor.companyType,
            terms = vendor.terms,
            defaultCode = vendor.defaultCode,
            vendorType = vendor.vendorType,
            compliance = vendor.compliance,
        )
    }
}

/**
 * The web form's per-field errors, in its own words (`VendorForm.validate`).
 *
 * Keyed by field so each input can show its own line; [NewVendor.validationError]
 * is the first of them, for the one-line refusal.
 */
@Suppress("CyclomaticComplexMethod") // One line per rule; the list IS the contract.
fun NewVendor.fieldErrors(): Map<String, String> = buildMap {
    when {
        name.isBlank() -> put("name", str(S.desktop_hub_vendor_name_is_required))
        name.length > MAX_NAME -> put("name", str(S.desktop_max_200_characters))
    }
    when {
        contactPerson.isBlank() -> put("contactPerson", str(S.ah_err_contact_person_required))
        contactPerson.length > MAX_NAME -> put("contactPerson", str(S.desktop_max_200_characters))
    }
    when {
        email.isBlank() -> put("email", str(S.ah_err_email_required))
        !email.isPlausibleEmail() -> put("email", str(S.dd_invalid_email))
    }
    if (phoneNumber.isNotBlank()) {
        when {
            phoneNumber.trim().length < MIN_PHONE_DIGITS -> put("phoneNumber", str(S.ah_err_phone_min))
            phoneNumber.length > MAX_PHONE -> put("phoneNumber", str(S.desktop_max_20_characters))
        }
    }
    when {
        address.line1.isBlank() -> put("line1", str(S.ah_err_line1_required))
        address.line1.length > MAX_LINE1 -> put("line1", str(S.desktop_max_200_characters))
    }
    when {
        address.city.isBlank() -> put("city", str(S.ah_err_city_required))
        address.city.length > MAX_CITY -> put("city", str(S.desktop_max_100_characters))
    }
    when {
        address.postalCode.isBlank() -> put("postalCode", str(S.ah_err_postal_required))
        address.postalCode.length > MAX_POSTCODE -> put("postalCode", str(S.desktop_max_20_characters))
    }
    if (address.country.isBlank()) put("country", str(S.ah_err_country_required))
    if (hasBankInput) {
        if (bankName.isBlank()) put("bankName", str(S.ah_err_bank_name_required))
        if (accountHolderName.isBlank()) put("accountHolderName", str(S.desktop_hub_account_holder_is_required))
        if (accountNumber.isBlank() && ibanCode.isBlank()) {
            put("accountNumber", str(S.desktop_hub_enter_an_account_number_or_an_iban))
            put("ibanCode", str(S.desktop_hub_enter_an_account_number_or_an_iban))
        }
        BankAccounts.firstInvalidDetail(additionalInfo)?.let {
            val type = it.fieldType.label.lowercase()
            put("additionalInfo", str(S.desktop_hub_x_is_not_a_valid_y_field, it.title, type))
        }
    }
}

private const val MAX_NAME = 200
private const val MAX_PHONE = 20
private const val MAX_LINE1 = 200
private const val MAX_CITY = 100
private const val MAX_POSTCODE = 20

/** Digits only, as the web's `sanitizePhoneInput`. */
fun sanitisePhone(raw: String): String = raw.filter { it.isDigit() }

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
fun NewVendor.validationError(): String? = fieldErrors().values.firstOrNull()

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

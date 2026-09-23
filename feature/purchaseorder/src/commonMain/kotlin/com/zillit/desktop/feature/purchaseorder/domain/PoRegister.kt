package com.zillit.desktop.feature.purchaseorder.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.Serializable

/**
 * A saved purchase order shape — the "Templates" tab.
 *
 * A template is a whole order minus its number and its status: the vendor, the
 * coding, the lines. Productions raise the same order every week (a camera
 * package, a stage hire) and re-typing it is where the coding drifts.
 */
@Serializable
data class PoTemplate(
    val id: String,
    val name: String,
    val vendorId: String?,
    val vendorName: String,
    val departmentId: String?,
    val nominalCode: String?,
    val description: String,
    val currency: String?,
    val notes: String?,
    val lines: List<PoLine> = emptyList(),
    val createdAt: Long? = null,
    val createdBy: String? = null,
) {
    /** What the template commits, for the list's Amount column. */
    val total: Double get() = PoTotals.of(lines).gross
}

/**
 * One entry of the production's delivery address book.
 *
 * Project-level, saved off orders: the form picks from it rather than retyping
 * a stage address, and the server de-dupes on save. The label is the server's
 * own one-line rendering; [address] holds the parts the form edits.
 */
@Serializable
data class PoDeliveryAddress(
    val id: String,
    val label: String,
    val address: PoAddress,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val updatedBy: String? = null,
    val updatedAt: Long? = null,
) {
    /**
     * Whether [viewer] may edit this row.
     *
     * The server enforces the same rule and answers 403 otherwise: an
     * accountant may edit any, everyone else only the rows they created. Shown
     * rather than discovered — the web disables the pencil with
     * "Only the creator or an accountant can edit this".
     */
    fun editableBy(viewer: PoViewer): Boolean =
        viewer.isAccountant || viewer.hasFullAccess || (createdBy != null && createdBy == viewer.userId)
}

/** The parts of a delivery address, as the form and the wire both hold them. */
@Serializable
data class PoAddress(
    val name: String = "",
    val email: String = "",
    val phoneCode: String = "",
    val phone: String = "",
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(name, email, phone, line1, line2, city, state, postalCode, country).all { it.isBlank() }

    /** One line, for a list cell. */
    val oneLine: String
        get() = listOf(line1, line2, city, state, postalCode, country).filter { it.isNotBlank() }.joinToString(", ")

    /**
     * The first reason this address cannot be saved, or null.
     *
     * The web validates in this order and the order is the point: a row with
     * neither a name nor a street is not an address at all, so that check comes
     * before the shape of the email or the length of the phone number.
     */
    fun validationError(): String? = when {
        name.isBlank() && line1.isBlank() -> str(S.desktop_po_address_needs_name_or_line)
        email.isNotBlank() && !email.looksLikeEmail() -> str(S.desktop_enter_valid_email)
        phone.isNotBlank() && phone.count { it.isDigit() } < MIN_PHONE_DIGITS ->
            str(S.ah_err_phone_min)

        else -> null
    }

    private companion object {
        const val MIN_PHONE_DIGITS = 5
    }
}

private fun String.looksLikeEmail(): Boolean {
    val at = indexOf('@')
    if (at <= 0 || at != lastIndexOf('@')) return false
    val domain = substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') && !contains(' ')
}

/**
 * A company the order can be raised under — the production's trading entities.
 *
 * Read from the account hub's project settings rather than this service, so the
 * host hands them in; the same list backs the form's Company selector and the
 * detail's Company tile.
 */
data class PoCompany(val id: String, val name: String)

/** One of the production's tax treatments, for a line's Tax column. */
data class PoTaxType(
    val id: String,
    val name: String,
    /** Percent, as the editor shows it — 20.0, not 0.2. */
    val rate: Double?,
    /** Whether the tax on a line of this type can be reclaimed. */
    val recoverable: Boolean = false,
)

/** Everything the host lends the purchase-order tool from the account hub's settings. */
interface PoProjectSettings {
    suspend fun companies(): List<PoCompany> = emptyList()
    suspend fun taxTypes(): List<PoTaxType> = emptyList()

    /** The production's departments, for the form and the department filter. */
    suspend fun departments(): List<PoDepartment> = emptyList()

    /** Currencies the production trades in, most-used first; the default leads. */
    suspend fun currencies(): List<String> = emptyList()
}

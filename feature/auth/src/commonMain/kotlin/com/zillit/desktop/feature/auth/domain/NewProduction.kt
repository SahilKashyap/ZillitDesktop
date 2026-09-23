package com.zillit.desktop.feature.auth.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A production type offered at creation, with its sub-types.
 *
 * `GET preset/project-types`. [id] is the `_id` the create call sends as
 * `project_type_id`; [label] is the display name, which is localised — so
 * anything that branches on the type must branch on [id].
 */
data class ProductionType(
    val id: String,
    val label: String,
    val subTypes: List<String> = emptyList(),
) {
    /** The "other" type is the only one that lets the user type a new sub-type. */
    val allowsCustomSubType: Boolean get() = id.equals(OTHER, ignoreCase = true)

    companion object {
        const val OTHER = "other"

        /** The sentinel the web appends to `other`'s sub-type list. */
        const val ADD_NEW_SUB_TYPE = "add_new_project_sub_type"
    }
}

/** `GET preset/languages`. */
data class ProductionLanguage(
    val code: String,
    val name: String,
)

/** Which field an error belongs to, so the form can put it under the right input. */
enum class ProductionField {
    FirstName,
    LastName,
    ProductionName,
    Email,
    Type,
    SubType,
    CustomSubType,
    Language,
    Phone,
    Terms,
}

/**
 * What the user has entered so far.
 *
 * A single immutable draft rather than ten separate state fields: validation
 * needs to see the whole thing at once (sub-type depends on type, phone is a
 * both-or-neither pair), and passing ten arguments around invites two of them
 * to get out of step.
 */
data class NewProductionDraft(
    val firstName: String = "",
    val lastName: String = "",
    val productionName: String = "",
    val email: String = "",
    val typeId: String? = null,
    val subType: String? = null,
    val customSubType: String = "",
    val languageCode: String? = null,
    val countryCode: String = "",
    val phone: String = "",
    /** Not sent; the backend has no field for it. Gated client-side, as on web. */
    val agreedToTerms: Boolean = false,
    /** Hidden on the web too — an enterprise deployment detail most users never see. */
    val storageCode: String = "",
) {
    /** True once the custom sub-type input should be shown. */
    fun needsCustomSubType(type: ProductionType?): Boolean =
        type?.allowsCustomSubType == true && subType == ProductionType.ADD_NEW_SUB_TYPE
}

/**
 * Validates a draft, returning one message per bad field.
 *
 * A pure function returning a map rather than a boolean, so the form can put
 * each message under its own input instead of showing one banner for whichever
 * problem was found first. The web spreads these rules across ten `Form.Item`
 * validators plus a ref-based phone check, none of which is reachable from a
 * test.
 *
 * [selectedType] is passed in rather than looked up here: this module has no
 * business fetching presets, and the caller already has them.
 */
fun NewProductionDraft.validate(selectedType: ProductionType?): Map<ProductionField, String> =
    rulesFor(selectedType)
        .filterNot { (_, rule) -> rule.satisfied() }
        .mapValues { (_, rule) -> rule.message }

/** One rule: the message to show, and whether the draft currently satisfies it. */
private class Rule(val message: String, val satisfied: () -> Boolean)

/**
 * Every rule, in field order.
 *
 * A table rather than a chain of `if`s: the rules are a flat list with no
 * interdependence beyond the arguments each closure reads, and expressed this
 * way adding one is a line rather than another branch in a method that already
 * has fifteen.
 */
private fun NewProductionDraft.rulesFor(
    selectedType: ProductionType?,
): Map<ProductionField, Rule> = buildMap {
    put(ProductionField.FirstName, Rule(str(S.desktop_enter_a_first_name)) { firstName.isNotBlank() })
    put(ProductionField.LastName, Rule(str(S.desktop_enter_a_last_name)) { lastName.isNotBlank() })
    put(ProductionField.ProductionName, Rule(str(S.desktop_enter_a_project_name)) { productionName.isNotBlank() })

    put(
        ProductionField.Email,
        if (email.isBlank()) {
            Rule(str(S.desktop_enter_an_email_address)) { false }
        } else {
            Rule(str(S.docusign_role_email_invalid)) { email.looksLikeEmail() }
        },
    )

    put(ProductionField.Type, Rule(str(S.desktop_choose_a_project_type)) { !typeId.isNullOrBlank() })

    // Only demanded when the chosen type actually offers sub-types — requiring
    // one unconditionally would block types that have none.
    if (selectedType != null && selectedType.subTypes.isNotEmpty()) {
        put(ProductionField.SubType, Rule(str(S.desktop_choose_a_sub_type_rule)) { !subType.isNullOrBlank() })
    }

    if (needsCustomSubType(selectedType)) {
        put(ProductionField.CustomSubType, Rule(str(S.desktop_name_the_new_sub_type)) { customSubType.isNotBlank() })
    }

    put(ProductionField.Language, Rule(str(S.desktop_choose_a_language_rule)) { !languageCode.isNullOrBlank() })

    // Both halves or neither. One half alone is what the backend rejects, and
    // it is the mistake a user makes by tabbing past the country code.
    put(
        ProductionField.Phone,
        Rule(str(S.desktop_enter_country_code_and_number_or_neither)) {
            countryCode.isBlank() == phone.isBlank()
        },
    )

    put(ProductionField.Terms, Rule(str(S.desktop_accept_the_terms_to_continue)) { agreedToTerms })
}

/**
 * The sub-type actually sent.
 *
 * When the user picked "add new" the typed value replaces the sentinel — the
 * server must never receive `add_new_project_sub_type` as a real sub-type.
 */
fun NewProductionDraft.resolvedSubType(selectedType: ProductionType?): String? =
    if (needsCustomSubType(selectedType)) customSubType.trim() else subType

/**
 * Deliberately permissive: one `@`, something either side, a dot in the domain.
 *
 * Matches the web's `/^[^\s@]+@[^\s@]+\.[^\s@]+$/`. A stricter rule rejects
 * valid addresses, and the address is verified by a one-time code anyway — the
 * real check is whether the mail arrives.
 */
private fun String.looksLikeEmail(): Boolean {
    val at = indexOf('@')
    if (at <= 0 || at != lastIndexOf('@') || at == length - 1) return false
    val local = substring(0, at)
    val domain = substring(at + 1)
    return local.none(Char::isWhitespace) &&
        domain.none(Char::isWhitespace) &&
        domain.contains('.') &&
        !domain.startsWith('.') &&
        !domain.endsWith('.')
}

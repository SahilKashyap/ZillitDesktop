package com.zillit.desktop.feature.dealmemo.domain.authoring

import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormRules
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A company added from a setup page (`CompanyFormModal.jsx`,
 * `makeCompanyDraft`): the whole row shape, and the form's gate.
 */
object CompanyDraft {

    /** The server refuses a UK reference longer than this rather than cutting it. */
    const val UK_REF_MAX = 20

    /** Every key present — the companies PATCH replaces the list, and a key a row leaves out is cleared. */
    fun blank(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", "")
        put("legal_name", "")
        put("country", "")
        put("country_code", "")
        put("bank_ids", JsonArray(emptyList()))
        put("tax_credits", JsonArray(emptyList()))
        put(
            "uk",
            buildJsonObject {
                put("paye_ref", "")
                put("accounts_office_ref", "")
            },
        )
    }

    /** A name and a country; a GB company's references optional, but well formed when given. */
    fun ready(draft: JsonObject): Boolean {
        if (text(draft, "name").isBlank() || text(draft, "country").isBlank()) return false
        if (!isGb(draft)) return true
        val uk = draft["uk"] as? JsonObject
        return CrewFormRules.validPayeRef(text(uk, "paye_ref")) &&
            CrewFormRules.validAccountsOfficeRef(text(uk, "accounts_office_ref"))
    }

    fun isGb(draft: JsonObject): Boolean = text(draft, "country_code").uppercase() == "GB"

    /** `companyLabel`: `Name — Country`, or the name alone. */
    fun label(name: String, country: String): String = if (country.isNotEmpty()) "$name — $country" else name

    private fun text(row: JsonObject?, key: String): String =
        row?.get(key)?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
}

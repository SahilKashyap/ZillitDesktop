package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One marked field's state, for the checklist. */
data class RequiredField(val key: String, val label: String, val done: Boolean)

/** The checklist's two sections of marked fields. */
data class RequiredFieldStatus(val personal: List<RequiredField>, val bank: List<RequiredField>)

/**
 * What a crew member must fill before their deal can go for approval — the
 * web's single requiredness model (`CrewDetailsEditorPanel.jsx:249-425, 717-931`).
 *
 * The sender marks fields (`crew_details.mandatory_fields`, wizard keys); a
 * floor is always required on top (ZL-21059); and two either/or pairs ask for
 * one of their halves unless the sender marked a side.
 */
object CrewRequirements {

    /** Wizard key → path; `bank.*` reads the draft's bank, everything else its crew details. */
    val KEY_MAP: Map<String, String> = linkedMapOf(
        "preferredName" to "preferred_name",
        "screenCreditDesignation" to "screen_credit_designation",
        "dob" to "dob",
        "niNumber" to "insurance_no",
        "taxCode" to "tax_code",
        "rightToWork" to "right_to_work",
        "homeAddress" to "home_address",
        "emergencyContact" to "emergency_contact",
        "emergencyContactName" to "emergency_contact_name",
        "emergencyContactNumber" to "emergency_contact_number",
        "email" to "email",
        "mobile" to "mobile",
        "gender" to "gender",
        "emergencyEmail" to "emergency_details.email",
        "emergencyAddress" to "emergency_details.address",
        "representativeName" to "representative_details.name",
        "representativePhone" to "representative_details.phone_number",
        "representativeEmail" to "representative_details.email",
        "representativeAddress" to "representative_details.address",
        "bankAccountHolderName" to "bank.account_holder_name",
        "bankName" to "bank.name",
        "bankAccountNumber" to "bank.account_number",
        "bankSortCode" to "bank.sort_code",
        "bankIbanNumber" to "bank.iban_number",
        "bankSwiftCode" to "bank.swift_code",
        "passportAttachment" to "passport_attachment",
    )

    private val FIELD_LABEL_KEYS: Map<String, String> = mapOf(
        "preferredName" to S.dm_step2_preferred_name,
        "screenCreditDesignation" to S.dm_step2_screen_credit_designation,
        "dob" to S.dm_req_dob,
        "niNumber" to S.dm_req_ni,
        "taxCode" to S.dm_crew_tax_code,
        "rightToWork" to S.dm_req_rtw,
        "homeAddress" to S.dm_req_home_address,
        "emergencyContact" to S.dm_req_emergency,
        "emergencyContactName" to S.desktop_dm_emergency_contact_name,
        "emergencyContactNumber" to S.desktop_dm_emergency_contact_number,
        "email" to S.email,
        "mobile" to S.dm_req_mobile,
        "gender" to S.gender,
        "emergencyEmail" to S.desktop_dm_emergency_email,
        "emergencyAddress" to S.desktop_dm_emergency_address,
        "representativeName" to S.dm_step2_agency_name,
        "representativePhone" to S.desktop_dm_agency_phone,
        "representativeEmail" to S.desktop_dm_agency_email,
        "representativeAddress" to S.desktop_dm_agency_address,
        "bankAccountHolderName" to S.dm_req_account_holder,
        "bankName" to S.bank_name_label,
        "bankAccountNumber" to S.account_number,
        "bankSortCode" to S.ah_lbl_sort_code,
        "bankIbanNumber" to S.dm_step2_bank_iban,
        "bankSwiftCode" to S.dm_step2_bank_swift,
        "passportAttachment" to S.dm_step2_passport,
    )

    val FIELD_LABELS: Map<String, String>
        get() = FIELD_LABEL_KEYS.mapValues { (_, key) -> str(key) }

    private val LABEL_BY_PATH: Map<String, String>
        get() = KEY_MAP.entries.associate { (key, path) -> path to (FIELD_LABEL_KEYS[key]?.let(::str) ?: key) } +
            ("full_legal_name" to str(S.dm_req_full_legal_name))

    val ALWAYS_REQUIRED = listOf("full_legal_name", "bank.name", "bank.account_holder_name", "emergency_contact_name")

    val EITHER_OR = listOf(
        listOf("bank.account_number", "bank.iban_number"),
        listOf("emergency_contact_number", "emergency_details.email"),
    )

    private val ADDRESS_PATHS = setOf(
        "home_address",
        "emergency_details.address",
        "representative_details.address",
        "loan_out_company.address",
    )

    /** The sender's marks as paths, in their order, unknown keys dropped. */
    fun markedPaths(deal: DealDoc): List<String> =
        DocRead.array(deal.crew?.get("mandatory_fields"))
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .mapNotNull(KEY_MAP::get)
            .distinct()

    /** The value at a KEY_MAP path in a draft. */
    fun valueAt(draft: CrewDraft, path: String): JsonElement? {
        val segments = path.split('.')
        val head: JsonElement? = if (segments.first() == "bank") draft.bank else draft.crewDetails[segments.first()]
        return segments.drop(1).fold(head) { node, segment -> (node as? JsonObject)?.get(segment) }
    }

    /** A required path still unsatisfied: an address needs line 1, city and postcode; anything else a value. */
    fun isMissing(path: String, value: JsonElement?): Boolean =
        if (path in ADDRESS_PATHS) DealAddress.of(value).isIncomplete else isValueBlank(value)

    /** Marks ∪ floor ∪ both halves of every pair the sender left alone and nobody has filled. */
    fun requiredPaths(marked: List<String>, draft: CrewDraft): List<String> {
        val paths = LinkedHashSet(marked + ALWAYS_REQUIRED)
        EITHER_OR.forEach { pair ->
            val senderWasExplicit = pair.any { it in marked }
            val satisfied = pair.any { !isMissing(it, valueAt(draft, it)) }
            if (!senderWasExplicit && !satisfied) paths += pair
        }
        return paths.toList()
    }

    /**
     * `missingRequiredLabels`: every required path still empty, named — a pair
     * the sender left alone reads as one line, "Account number or IBAN".
     */
    fun missingLabels(deal: DealDoc, draft: CrewDraft?): List<String> {
        val current = draft ?: CrewDraft.seed(deal)
        val marked = markedPaths(deal)
        val missing = requiredPaths(marked, current).filter { isMissing(it, valueAt(current, it)) }
        val paired = mutableSetOf<String>()
        val out = mutableListOf<String>()
        missing.forEach { path ->
            if (path in paired) return@forEach
            val pair = EITHER_OR.firstOrNull { pair ->
                path in pair && pair.none { it in marked } && pair.all { it in missing }
            }
            if (pair != null) {
                paired += pair
                val labels = LABEL_BY_PATH
                out += str(S.desktop_dm_or_join, labels[pair[0]] ?: pair[0], labels[pair[1]] ?: pair[1])
            } else {
                out += LABEL_BY_PATH[path] ?: path
            }
        }
        return out
    }

    /** `requiredFieldStatus`: the marked fields only, bank ones in their own section. */
    fun fieldStatus(deal: DealDoc, draft: CrewDraft?): RequiredFieldStatus {
        val current = draft ?: CrewDraft.seed(deal)
        val keys = DocRead.array(deal.crew?.get("mandatory_fields"))
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        val rows = keys.mapNotNull { key ->
            val path = KEY_MAP[key] ?: return@mapNotNull null
            path to RequiredField(key, FIELD_LABELS[key] ?: key, !isMissing(path, valueAt(current, path)))
        }
        return RequiredFieldStatus(
            personal = rows.filterNot { it.first.startsWith("bank.") }.map { it.second },
            bank = rows.filter { it.first.startsWith("bank.") }.map { it.second },
        )
    }

    /** `sectionComplete`: the marked rows decide when there are any; otherwise the legacy core rule. */
    fun sectionComplete(rows: List<RequiredField>, coreDone: Boolean): Boolean =
        if (rows.isNotEmpty()) rows.all { it.done } else coreDone

    /**
     * The legacy "Personal details" rule: legal name (or crew name), email,
     * mobile, date of birth, NI, right to work and home address, plus an
     * emergency name and number — or the old single emergency contact.
     */
    fun personalCoreDone(deal: DealDoc): Boolean {
        val cd = deal.crew
        val name = cd?.get("full_legal_name")?.takeUnless { it is JsonNull } ?: cd?.get("crew_name")
        val core = listOf(name) + listOf("email", "mobile", "dob", "insurance_no", "right_to_work", "home_address")
            .map { cd?.get(it) }
        val emergency = (!isValueBlank(cd?.get("emergency_contact_name")) &&
            !isValueBlank(cd?.get("emergency_contact_number"))) || !isValueBlank(cd?.get("emergency_contact"))
        return core.all { !isValueBlank(it) } && emergency
    }
}

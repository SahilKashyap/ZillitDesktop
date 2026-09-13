package com.zillit.desktop.feature.crewlist.data

import com.zillit.desktop.feature.crewlist.domain.CompanyCustomField
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CrewDepartment
import com.zillit.desktop.feature.crewlist.domain.CrewDocumentRequest
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewPicture
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.HeaderSection
import com.zillit.desktop.feature.crewlist.domain.OrderedPerson
import com.zillit.desktop.feature.crewlist.domain.PeopleOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The crew list's wire, as `unitApi.js` and `CrewListCustom.jsx` speak it.
 * The client hands these DTOs the UNWRAPPED `data`. Every leaf is loose — a
 * JsonElement where the server has sent both a string and a number, a
 * nullable everywhere — because one odd row must not lose the whole roster.
 */

@Serializable
internal data class UnitDto(
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("departments") val departments: List<DepartmentDto>? = null,
) {
    fun toModel(): CrewUnit {
        val unit = unitName.orEmpty()
        return CrewUnit(
            unitName = unit,
            departments = departments.orEmpty().map { it.toModel(unit) },
        )
    }
}

@Serializable
internal data class DepartmentDto(
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("users") val users: List<MemberDto>? = null,
) {
    fun toModel(unit: String): CrewDepartment {
        val department = departmentName.orEmpty()
        return CrewDepartment(
            departmentName = department,
            members = users.orEmpty().mapNotNull { it.toModel(unit, department) },
        )
    }
}

@Serializable
internal data class MemberDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("phone") val phone: JsonElement? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("primary_email") val primaryEmail: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("is_external_user") val isExternal: JsonElement? = null,
    @SerialName("joining_date") val joiningDate: JsonElement? = null,
    @SerialName("profile_picture") val profilePicture: JsonElement? = null,
) {
    fun toModel(unit: String, department: String): CrewMember? {
        val id = userId?.takeIf { it.isNotBlank() } ?: return null
        return CrewMember(
            userId = id,
            fullName = fullName.orEmpty(),
            designationName = designationName.orEmpty(),
            departmentName = departmentName ?: department,
            unitName = unitName ?: unit,
            phone = phone.text(),
            countryCode = countryCode.orEmpty(),
            primaryEmail = primaryEmail.orEmpty(),
            email = email.orEmpty(),
            isExternal = isExternal.flag(),
            joiningDate = joiningDate.text(),
            picture = (profilePicture as? JsonObject)?.toPicture(),
        )
    }
}

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement?.flag(): Boolean = when (val value = (this as? JsonPrimitive)?.contentOrNull) {
    null -> false
    else -> value.equals("true", ignoreCase = true) || value == "1"
}

private fun JsonObject.toPicture(): CrewPicture? {
    val media = this["media"].text()
    if (media.isBlank()) return null
    return CrewPicture(
        media = media,
        thumbnail = this["thumbnail"].text(),
        bucket = this["bucket"].text(),
        region = this["region"].text(),
    )
}

/** The generate answer's `data`, kept whole for the Info post. */
internal fun JsonObject.toPdf(): CrewListPdf = CrewListPdf(
    media = this["media"].text(),
    bucket = this["bucket"].text(),
    region = this["region"].text(),
    // The web's derivation: `original_name || name || 'Crew List.pdf'`.
    name = this["original_name"].text().ifBlank { this["name"].text() }.ifBlank { CrewListPdf.DEFAULT_NAME },
    contentSubtype = this["content_subtype"].text().ifBlank { "pdf" },
    thumbnail = this["thumbnail"].text(),
    fileSize = this["file_size"].text(),
    attachment = this,
)

/**
 * The body every render shares — `buildBody` in `CrewListCustom.jsx`:
 * `header_layout`, `member_overrides`, `hide_internal_lines` and
 * `show_zillit_email: true` (the PDF prints each member's project mailbox),
 * plus `hide_external_label` for the PDF itself.
 */
internal fun CrewDocumentRequest.toBody(): JsonObject = buildJsonObject {
    putJsonObject("header_layout") {
        put("logo", layout.logo.wire)
        put("logoSize", layout.logoSize)
        putJsonObject("offsets") {
            HeaderSection.entries.forEach { section ->
                val offset = layout.offsetOf(section)
                putJsonObject(section.wire) {
                    put("x", offset.x)
                    put("y", offset.y)
                }
            }
        }
        put("order", orderJson(if (stacked) HeaderLayout.PREVIEW_STACK_ORDER else layout.order))
    }
    putJsonArray("member_overrides") {
        overrides.forEach { (userId, override) ->
            add(
                buildJsonObject {
                    put("user_id", userId)
                    override.phone?.let { put("phone", it) }
                    override.email?.let { put("email", it) }
                    override.countryCode?.let { put("country_code", it) }
                },
            )
        }
    }
    put("hide_internal_lines", hideInternalLines)
    put("show_zillit_email", true)
    hideExternalLabel?.let { put("hide_external_label", it) }
}

/** A one-section row travels as a bare string, a pair as an array — the grouped contract. */
internal fun orderJson(order: List<List<HeaderSection>>): JsonArray = buildJsonArray {
    order.forEach { row ->
        if (row.size == 1) {
            add(JsonPrimitive(row.single().wire))
        } else {
            add(buildJsonArray { row.forEach { add(JsonPrimitive(it.wire)) } })
        }
    }
}

/** An order as it arrives from the page: bare strings and arrays, unknown ids dropped. */
internal fun JsonElement.toOrder(): List<List<HeaderSection>> = (this as? JsonArray).orEmpty().mapNotNull { row ->
    val sections = when (row) {
        is JsonArray -> row.mapNotNull { HeaderSection.of(it.text()) }
        is JsonPrimitive -> listOfNotNull(HeaderSection.of(row.contentOrNull))
        else -> emptyList()
    }
    sections.takeIf { it.isNotEmpty() }
}

/**
 * A row of `project/users?reorder=true` as the order editor lists it — or null
 * for someone the web leaves out (removed, pending, left) or a row with no id.
 */
internal fun JsonObject.toOrderedPerson(): OrderedPerson? {
    val id = this["user_id"].text().ifBlank { this["_id"].text() }.takeIf { it.isNotBlank() } ?: return null
    if (this["status"].text().lowercase() in PeopleOrder.HIDDEN_STATUSES) return null
    return OrderedPerson(userId = id, name = this["full_name"].text(), designation = this["designation_name"].text())
}

// --- Company details (`project/:id` read, `PATCH project` write) ------------------

internal fun JsonObject.toCompanyDetails(): CompanyDetails = CompanyDetails(
    name = this["company_name"].text(),
    number = this["company_number"].text(),
    email = this["company_email"].text(),
    countryCode = this["company_country_code"].text(),
    phone = this["company_phone"].text(),
    address = this["company_address"].text(),
    registeredAddress = this["company_registered_address"].text(),
    customFields = (this["customFields"] as? JsonArray).orEmpty().mapNotNull { row ->
        (row as? JsonObject)?.let {
            CompanyCustomField(
                label = it["label"].text(),
                value = it["value"].text(),
                fieldType = it["fieldType"].text().ifBlank { CompanyCustomField.TEXT },
            )
        }
    },
    logo = (this["company_logo"] as? JsonObject)?.let { logo ->
        logo["media"].text().takeIf { it.isNotBlank() }?.let { media ->
            CompanyLogo(
                media = media,
                thumbnail = logo["thumbnail"].text(),
                bucket = logo["bucket"].text(),
                region = logo["region"].text(),
                caption = logo["caption"].text(),
            )
        }
    },
)

/**
 * Every field, `''` clearing one — the web sends the whole form. The logo only
 * rides when a new one was uploaded; removal is its own DELETE.
 */
internal fun CompanyDetails.toPatchBody(newLogo: CompanyLogo?): JsonObject = buildJsonObject {
    put("company_name", name)
    put("company_address", address)
    put("company_country_code", countryCode)
    put("company_phone", phone)
    put("company_email", email)
    put("company_number", number)
    put("company_registered_address", registeredAddress)
    putJsonArray("customFields") {
        normalisedFields().forEach { field ->
            add(
                buildJsonObject {
                    put("label", field.label)
                    put("value", field.value)
                    put("fieldType", field.fieldType)
                },
            )
        }
    }
    newLogo?.let { logo ->
        putJsonObject("company_logo") {
            put("caption", name.ifBlank { "logo" })
            put("media", logo.media)
            // Omitted rather than null: the body hash refuses a JSON null, and
            // the web's `undefined` never reaches the wire either.
            logo.thumbnail.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
            put("bucket", logo.bucket)
            put("region", logo.region)
        }
    }
}

package com.zillit.desktop.feature.settings.admin.data

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CompanyField
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The wire rows behind the administration pages.
 *
 * ## Everything is nullable
 *
 * Not laziness. These routes span three services and several generations of the
 * API: `departments` answers `system_defined` on some productions and omits it
 * on others, the crew list names a person with `name` or with
 * `first_name`/`last_name` depending on how they joined, and units answer
 * `unit_name` on one service and `name` on the other. A strict reader turns any
 * of those into an empty page, and an empty administration page is unreadable
 * as "the call failed" — it reads as "this project has no departments".
 *
 * A row that cannot be identified at all is dropped rather than defaulted: an
 * id is what every action on these pages is keyed on, and a row with no id is a
 * row whose delete button would go to the wrong place.
 */

// -- departments ----------------------------------------------------------

@Serializable
internal data class DepartmentDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("department_name") val name: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    /** A string, and sometimes `"_all_"`. Kept verbatim. */
    @SerialName("priority") val priority: String? = null,
    @SerialName("designations") val designations: List<DesignationDto> = emptyList(),
) {
    fun toDomain(): Department? {
        val resolved = id?.takeIf { it.isNotBlank() }
            ?: departmentId?.takeIf { it.isNotBlank() }
            ?: return null
        return Department(
            id = resolved,
            name = name.orEmpty().ifBlank { resolved },
            systemDefined = systemDefined == true,
            priority = priority?.takeIf { it.isNotBlank() },
            jobTitles = designations.mapNotNull { it.toDomain() },
        )
    }
}

@Serializable
internal data class DesignationDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("designation_name") val name: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
) {
    fun toDomain(): JobTitle? {
        val resolved = id?.takeIf { it.isNotBlank() }
            ?: designationId?.takeIf { it.isNotBlank() }
            ?: return null
        return JobTitle(
            id = resolved,
            name = name.orEmpty().ifBlank { resolved },
            systemDefined = systemDefined == true,
        )
    }
}

// -- crew ------------------------------------------------------------------

@Serializable
internal data class CrewDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("_id") val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("department") val department: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation") val designation: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
    @SerialName("admin_access") val adminAccess: Boolean? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("keep_name_private") val keepNamePrivate: Boolean? = null,
) {
    fun toDomain(): CrewMember? {
        val resolved = userId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return null
        val named = fullName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

        return CrewMember(
            userId = resolved,
            // Never blank: a nameless row still has an admin switch on it, and
            // a switch beside an empty line cannot be thrown with confidence.
            fullName = named.ifBlank { email.orEmpty() }.ifBlank { str(S.desktop_someone_with_no_name) },
            email = email?.takeIf { it.isNotBlank() },
            phone = phone?.takeIf { it.isNotBlank() },
            department = (department ?: departmentName)?.takeIf { it.isNotBlank() },
            designation = (designation ?: designationName)?.takeIf { it.isNotBlank() },
            // Two spellings live on this route depending on which service
            // answered; either one being true is the same fact.
            isAdmin = isAdmin == true || adminAccess == true,
            deviceId = deviceId?.takeIf { it.isNotBlank() },
            status = CrewStatus.from(status),
            keepNamePrivate = keepNamePrivate == true,
        )
    }
}

@Serializable
internal data class PreApprovedDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("join_unit_name") val unitName: String? = null,
) {
    fun toDomain(): PreApprovedCrew? {
        val resolved = id?.takeIf { it.isNotBlank() } ?: return null
        val named = fullName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        return PreApprovedCrew(
            id = resolved,
            fullName = named.ifBlank { email.orEmpty() }.ifBlank { str(S.desktop_someone_with_no_name) },
            email = email?.takeIf { it.isNotBlank() },
            phone = listOfNotNull(countryCode?.takeIf { it.isNotBlank() }, phone?.takeIf { it.isNotBlank() })
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" "),
            departmentName = departmentName?.takeIf { it.isNotBlank() },
            designationName = designationName?.takeIf { it.isNotBlank() },
            unitName = unitName?.takeIf { it.isNotBlank() },
        )
    }
}

// -- tools -------------------------------------------------------------------

@Serializable
internal data class ToolDto(
    @SerialName("identifier") val identifier: String? = null,
    /**
     * The tool's name, called `unit_name` on this route.
     *
     * Nothing to do with production units. The tools list predates the units
     * service and kept the word.
     */
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    /** The server's own "you may not switch this off" flag. */
    @SerialName("disabled") val locked: Boolean? = null,
    @SerialName("group_identifier") val groupIdentifier: String? = null,
) {
    fun toDomain(): ProductionTool? {
        val resolved = identifier?.takeIf { it.isNotBlank() } ?: return null
        return ProductionTool(
            identifier = resolved,
            name = (unitName ?: toolName).orEmpty().ifBlank { resolved },
            enabled = enabled == true,
            groupIdentifier = groupIdentifier.orEmpty(),
            // The two tools an admin needs to undo a mistake made here. Both
            // phone clients hard-code the same pair rather than trusting the
            // flag, because the flag is absent on older productions.
            isLocked = locked == true || resolved in ALWAYS_ON,
        )
    }
}

/**
 * The two tools an admin needs in order to undo a mistake made on this page.
 *
 * A file-level value rather than a companion on [ToolDto]: a `private companion
 * object` hides the serializer kotlinx generates alongside it, and the failure
 * is a compile error about a companion nobody wrote.
 */
private val ALWAYS_ON = setOf("permission_grid_tool", "info_tool")

@Serializable
internal data class ToolGroupDto(
    @SerialName("tool_group_id") val id: String? = null,
    @SerialName("group_identifier") val identifier: String? = null,
    @SerialName("group_name") val name: String? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    @SerialName("order") val order: Int? = null,
) {
    fun toDomain(): ToolGroup? {
        val resolved = id?.takeIf { it.isNotBlank() } ?: return null
        return ToolGroup(
            id = resolved,
            identifier = identifier.orEmpty(),
            name = name.orEmpty().ifBlank { identifier.orEmpty() },
            systemDefined = systemDefined == true,
            order = order ?: 0,
        )
    }
}

// -- the production record ------------------------------------------------------

/**
 * The parts of `GET project/{id}` this feature reads.
 *
 * Deliberately partial: the record carries storage configuration, enterprise
 * ids and a folder tree that this page has no business decoding, and every one
 * of those is a shape that has broken a strict reader before.
 */
@Serializable
internal data class ProductionRecordDto(
    @SerialName("project_name") val name: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("company_address") val companyAddress: String? = null,
    @SerialName("company_registered_address") val registeredAddress: String? = null,
    @SerialName("company_country_code") val countryCode: String? = null,
    @SerialName("company_phone") val phone: String? = null,
    @SerialName("company_email") val email: String? = null,
    @SerialName("company_number") val companyNumber: String? = null,
    /**
     * The extra crew-list header lines, under **camelCase** among snake_case
     * siblings.
     *
     * Not a transcription slip: the web writes and reads `customFields` with
     * `fieldType` inside, while iOS uses the snake_case spelling. Both are read
     * because both are live, and [allCustomFields] merges them; the write side
     * sends the web's spelling, which is the one the backend is known to
     * accept today.
     */
    @SerialName("customFields") val customFields: List<CompanyFieldDto> = emptyList(),
    @SerialName("custom_fields") val customFieldsSnake: List<CompanyFieldDto> = emptyList(),
    /** An attachment object, read leniently — see [imageUrl]. */
    @SerialName("company_logo") val companyLogo: JsonElement? = null,
    @SerialName("watermark") val watermark: JsonElement? = null,
    @SerialName("mark_deleted") val markDeleted: Boolean? = null,
    @SerialName("delete_in_hours") val deleteInHours: JsonPrimitive? = null,
) {
    fun toCompanyDetails() = CompanyDetails(
        name = companyName.orEmpty(),
        address = companyAddress.orEmpty(),
        registeredAddress = registeredAddress.orEmpty(),
        countryCode = countryCode.orEmpty(),
        phone = phone.orEmpty(),
        email = email.orEmpty(),
        companyNumber = companyNumber.orEmpty(),
        customFields = allCustomFields.mapNotNull { it.toDomain() },
        logoUrl = companyLogo.imageUrl(),
    )

    /** Whichever spelling this production's server answered with. */
    private val allCustomFields: List<CompanyFieldDto>
        get() = customFields.ifEmpty { customFieldsSnake }

    /** Hours until deletion, when one is scheduled. */
    val scheduledHours: Int?
        get() = deleteInHours?.let { it.intOrNull ?: it.content.toIntOrNull() }?.takeIf { it > 0 }
}

@Serializable
internal data class CompanyFieldDto(
    @SerialName("label") val label: String? = null,
    @SerialName("value") val value: String? = null,
) {
    /** A field with no label has nothing to print beside its value. */
    fun toDomain(): CompanyField? =
        label?.takeIf { it.isNotBlank() }?.let { CompanyField(it, value.orEmpty()) }
}

// -- SOS ----------------------------------------------------------------------------

@Serializable
internal data class SosContactDto(
    @SerialName("_id") val id: String? = null,
    /** `internal` for a crew member, `external` for an outsider. */
    @SerialName("type") val type: String? = null,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("relation") val relation: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    /** Present for crew recipients; the server resolves them from the crew list. */
    @SerialName("user") val user: SosUserDto? = null,
) {
    fun toDomain(): SosRecipient? {
        val resolved = id?.takeIf { it.isNotBlank() } ?: return null
        val kind = SosEntryType.from(type)
        val named = when (kind) {
            SosEntryType.Outsider -> contactName
            SosEntryType.Crew -> user?.fullName
        }
        return SosRecipient(
            id = resolved,
            name = named.orEmpty().ifBlank { str(S.desktop_someone_with_no_name) },
            entryType = kind,
            userId = user?.userId?.takeIf { it.isNotBlank() },
            phone = phoneNumber?.takeIf { it.isNotBlank() } ?: user?.phone?.takeIf { it.isNotBlank() },
            countryCode = countryCode?.takeIf { it.isNotBlank() },
            relationship = relation?.takeIf { it.isNotBlank() },
            designation = user?.designationName?.takeIf { it.isNotBlank() },
        )
    }
}

@Serializable
internal data class SosUserDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    @SerialName("phone") val phone: String? = null,
)

// -- units -----------------------------------------------------------------------------

@Serializable
internal data class AdminUnitDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("visibility") val visibility: Boolean? = null,
    @SerialName("system_defined") val systemDefined: Boolean? = null,
    /**
     * `"false"` marks a unit the production did not create.
     *
     * A string, not a boolean, and it is the *string* `"false"` that means
     * locked — iOS reads it exactly this way. Comparing it as a boolean is how
     * a protected unit grows a delete button.
     */
    @SerialName("priority") val priority: String? = null,
) {
    fun toDomain(kind: UnitKind): AdminUnit? {
        val resolved = id?.takeIf { it.isNotBlank() } ?: unitId?.takeIf { it.isNotBlank() } ?: return null
        return AdminUnit(
            id = resolved,
            name = (unitName ?: name).orEmpty().ifBlank { resolved },
            kind = kind,
            // Absent means on. A unit that arrives without the flag is one the
            // production has never switched off.
            enabled = visibility ?: enabled ?: true,
            locked = systemDefined == true || priority == "false",
        )
    }
}

// -- rights grid -------------------------------------------------------------------------

/**
 * One person's rights over one tool.
 *
 * From `user/access/{userId}`, which answers a flat list — the shape both phone
 * clients read. Each row says which half of the page it belongs to through
 * [home] and [tool], and a row that claims neither is dropped: it would render
 * under no heading.
 *
 * The `*_updatable` flags are the server saying this particular right is not
 * this admin's to change. Read so the page can disable the box rather than send
 * a write the server will discard.
 */
@Serializable
internal data class ToolAccessDto(
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("identifier") val identifier: String? = null,
    /** The tool's name. Nothing to do with production units — see [ToolDto]. */
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    @SerialName("viewing_updatable") val viewingUpdatable: Boolean? = null,
    @SerialName("posting_updatable") val postingUpdatable: Boolean? = null,
    @SerialName("download_updatable") val downloadUpdatable: Boolean? = null,
    /** On the dashboard. */
    @SerialName("home") val home: Boolean? = null,
    /** On the Film Tools grid. */
    @SerialName("tool") val tool: Boolean? = null,
) {
    /**
     * A row per section it appears in.
     *
     * A tool can be both — the dashboard and the grid are separate rights over
     * the same thing, written to separate routes — so one wire row can become
     * two rows on the page.
     */
    fun toDomain(): List<ToolRights> {
        // The write is keyed on `unit_id`; without one the row has switches
        // that could never be saved.
        val unit = unitId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val name = unitName?.takeIf { it.isNotBlank() } ?: identifier ?: return emptyList()

        return listOfNotNull(
            RightsSection.Home.takeIf { home == true },
            RightsSection.Tools.takeIf { tool == true },
        ).map { section ->
            ToolRights(
                toolIdentifier = identifier?.takeIf { it.isNotBlank() } ?: unit,
                toolName = name,
                unitId = unit,
                section = section,
                canView = viewAccess == true,
                canPost = postingAccess == true,
                canDownload = downloadAccess == true,
                // Only an explicit `false` locks a right. Treating absence as
                // locked would grey out the whole page on any production whose
                // server predates the flag.
                viewLocked = viewingUpdatable == false,
                postLocked = postingUpdatable == false,
                downloadLocked = downloadUpdatable == false,
            )
        }
    }
}

/**
 * An attachment's URL, from a field that is sometimes a string and sometimes an
 * object.
 *
 * The same tolerance the session's avatar reader needs, for the same reason: a
 * logo is decoration, and it must never be why a company-details form fails to
 * open.
 */
internal fun JsonElement?.imageUrl(): String? = when (this) {
    null -> null
    is JsonPrimitive -> if (isString) content.takeIf { it.isNotBlank() } else null
    is JsonObject -> IMAGE_KEYS.firstNotNullOfOrNull { key -> text(key) }
    else -> null
}

/** In preference order — the full image, since these are shown at size. */
private val IMAGE_KEYS = listOf("media", "thumbnail", "url", "path", "file_name")

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

package com.zillit.desktop.feature.settings.admin.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.NewPreApproval
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsChange
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The administration pages, against the endpoints the other clients use.
 *
 * ## `{ status: 0 }` on an HTTP 200
 *
 * Almost every rejection an admin will actually hit — a duplicate department
 * name, a tool group that still has tools in it, a department some budget is
 * posted against — arrives as **HTTP 200** with `{ status: 0, message }`. The
 * shared [ApiClient] decides success on the HTTP code, so without [checked]
 * these read as successes and the page reports nothing while the server did
 * nothing. Every mutation here goes through it.
 *
 * The `message` is a translation key (`department_is_in_use`), and it is
 * localised before it reaches the reader rather than shown raw.
 *
 * ## Booleans that are not booleans
 *
 * This surface is inconsistent and the inconsistency is load-bearing:
 * `keep_name_private` is the **string** `"true"` on the pre-approval route and
 * a real boolean on the join route, and `delete_in_hours` is a **string**.
 * Each is sent the way the route in question takes it, with a note at the site
 * — "fixing" one is a silent 406.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see AdminRepository.
class AdminRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /**
     * The open production.
     *
     * A lambda rather than a value: the graph outlives a production switch, and
     * three of these routes carry the id in their body. Null between
     * productions, and the calls that need it fail with a message rather than
     * sending the previous production's id.
     */
    private val projectId: () -> String?,
) : AdminRepository {

    private val endpoints = AdminEndpoints(config)

    // -- departments and job titles ------------------------------------------

    override suspend fun departments(): ZillitResult<List<Department>> =
        get(endpoints.departments, ListSerializer(DepartmentDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun createDepartment(name: String): ZillitResult<Unit> =
        withProject { project ->
            post(
                endpoints.departments,
                buildJsonObject {
                    put("project_id", project)
                    put("department_name", name.trim())
                },
            )
        }

    override suspend fun deleteDepartment(departmentId: String): ZillitResult<Unit> =
        delete(endpoints.department(departmentId))

    /**
     * The whole order, under `newOrder`.
     *
     * Position is derived from index, so this is not a move — sending one
     * department's new place leaves every other one where it was.
     */
    override suspend fun reorderDepartments(departmentIds: List<String>): ZillitResult<Unit> =
        put(
            endpoints.reorderDepartments,
            buildJsonObject { put("newOrder", departmentIds.toJsonArray()) },
        )

    override suspend fun createJobTitle(departmentId: String, name: String): ZillitResult<Unit> =
        post(
            endpoints.jobTitles(departmentId),
            // The department is in the path, not the body.
            buildJsonObject { put("designation_name", name.trim()) },
        )

    override suspend fun deleteJobTitle(departmentId: String, jobTitleId: String): ZillitResult<Unit> =
        delete(endpoints.jobTitle(departmentId, jobTitleId))

    // -- crew -------------------------------------------------------------------

    override suspend fun crew(): ZillitResult<List<CrewMember>> =
        get(endpoints.crew, ListSerializer(CrewDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun setAdminAccess(userId: String, isAdmin: Boolean): ZillitResult<Unit> =
        post(
            endpoints.adminAccess,
            buildJsonObject {
                put("user_id", userId)
                put("admin_access", isAdmin)
            },
        )

    override suspend fun setCrewStatus(
        userId: String,
        deviceId: String,
        status: CrewStatus,
    ): ZillitResult<Unit> = post(
        endpoints.crewStatus,
        buildJsonObject {
            put("user_id", userId)
            put("device_id", deviceId)
            put("status", status.wire)
        },
    )

    // -- pre-approved crew ----------------------------------------------------------

    override suspend fun preApproved(): ZillitResult<List<PreApprovedCrew>> =
        get(endpoints.preApprovedList, ListSerializer(PreApprovedDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun addPreApproved(request: NewPreApproval): ZillitResult<Unit> =
        withProject { project ->
            post(
                endpoints.preApproved,
                buildJsonObject {
                    put("project_id", project)
                    put("first_name", request.firstName.trim())
                    put("last_name", request.lastName.trim())
                    put("department_id", request.departmentId)
                    put("designation_id", request.designationId)
                    request.unitId?.takeIf { it.isNotBlank() }?.let { put("join_unit_id", it) }
                    // A string on this route, a boolean on the join route. Both
                    // clients send it this way here; a real boolean is a 406.
                    put("keep_name_private", request.keepNamePrivate.toString())

                    // Omitted rather than sent empty: an empty address is not
                    // the same as no address, and the server stores what it is
                    // given.
                    request.email?.takeIf { it.isNotBlank() }?.let { put("email", it.trim()) }
                    val code = request.countryCode?.takeIf { it.isNotBlank() }
                    val phone = request.phone?.takeIf { it.isNotBlank() }
                    if (code != null && phone != null) {
                        put("country_code", code)
                        put("phone", phone.trim())
                    }
                },
            )
        }

    // -- tools ------------------------------------------------------------------------

    override suspend fun tools(includeAlwaysOn: Boolean): ZillitResult<List<ProductionTool>> =
        get(
            if (includeAlwaysOn) endpoints.allTools else endpoints.adminTools,
            ListSerializer(ToolDto.serializer()),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun setToolsEnabled(tools: List<ProductionTool>): ZillitResult<Unit> =
        put(
            endpoints.enableTools,
            buildJsonObject {
                put(
                    "tools",
                    buildJsonArray {
                        tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("identifier", tool.identifier)
                                    put("enabled", tool.enabled)
                                },
                            )
                        }
                    },
                )
            },
        )

    override suspend fun toolGroups(): ZillitResult<List<ToolGroup>> =
        get(endpoints.toolGroups, ListSerializer(ToolGroupDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() }.sortedBy { it.order } }

    override suspend fun createToolGroup(name: String): ZillitResult<Unit> =
        post(endpoints.toolGroups, buildJsonObject { put("group_name", name.trim()) })

    override suspend fun renameToolGroup(toolGroupId: String, name: String): ZillitResult<Unit> =
        put(endpoints.toolGroupById(toolGroupId), buildJsonObject { put("group_name", name.trim()) })

    override suspend fun deleteToolGroup(toolGroupId: String): ZillitResult<Unit> =
        delete(endpoints.toolGroupById(toolGroupId))

    /**
     * A blank [groupIdentifier] is a real target, not a missing one.
     *
     * It means "out of every group", and the tool then shows on the grid
     * ungrouped. Sending it must not be optimised away.
     */
    override suspend fun moveTool(identifier: String, groupIdentifier: String): ZillitResult<Unit> =
        put(
            endpoints.toolGroup,
            buildJsonObject {
                put("identifier", identifier)
                put("group_identifier", groupIdentifier)
            },
        )

    // -- the production itself ------------------------------------------------------------

    override suspend fun renameProduction(name: String): ZillitResult<Unit> =
        patch(endpoints.project, buildJsonObject { put("project_name", name.trim()) })

    override suspend fun companyDetails(): ZillitResult<CompanyDetails> =
        production().map { it.toCompanyDetails() }

    /**
     * Sends every field, including the blank ones.
     *
     * An absent key means "leave it alone" and an empty string means "clear
     * it". A form that omitted its empty fields could never clear one — the
     * old value would survive every save.
     *
     * The logo is not here: it is written at upload time and cleared through
     * [clearCompanyLogo], because `company_logo: ""` is refused with a 406.
     */
    override suspend fun saveCompanyDetails(details: CompanyDetails): ZillitResult<Unit> =
        patch(
            endpoints.project,
            buildJsonObject {
                put("company_name", details.name.trim())
                put("company_address", details.address.trim())
                put("company_registered_address", details.registeredAddress.trim())
                put("company_country_code", details.countryCode.trim())
                put("company_phone", details.phone.trim())
                put("company_email", details.email.trim())
                put("company_number", details.companyNumber.trim())
                put(
                    // camelCase, alone among its snake_case siblings. See
                    // ProductionRecordDto.
                    "customFields",
                    buildJsonArray {
                        details.customFields
                            // A row with no label has nothing to print beside
                            // its value, and the server keeps it forever.
                            .filter { it.label.isNotBlank() }
                            .forEach { field ->
                                add(
                                    buildJsonObject {
                                        put("label", field.label.trim())
                                        put("value", field.value.trim())
                                        put("fieldType", "text")
                                    },
                                )
                            }
                    },
                )
            },
        )

    override suspend fun clearCompanyLogo(): ZillitResult<Unit> = delete(endpoints.companyLogo)

    override suspend fun watermarkUrl(): ZillitResult<String?> =
        productionRecord().map { it["watermark"].imageUrl() }

    override suspend fun clearWatermark(): ZillitResult<Unit> = delete(endpoints.watermark)

    // -- SOS ----------------------------------------------------------------------------------

    override suspend fun sosRecipients(): ZillitResult<List<SosRecipient>> =
        get(endpoints.sosContacts(ADMIN_ENTRY_TYPE), ListSerializer(SosContactDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * One route, two genuinely different bodies.
     *
     * A crew recipient is `{ user_id }` and the server fills in the name and
     * number from the crew list; an outsider carries all four fields because
     * there is nowhere else to read them from.
     */
    override suspend fun addSosRecipient(request: NewSosRecipient): ZillitResult<Unit> =
        post(
            endpoints.createSosContact,
            when (request.entryType) {
                SosEntryType.Crew -> buildJsonObject { put("user_id", request.userId.orEmpty()) }
                SosEntryType.Outsider -> buildJsonObject {
                    put("contact_name", request.name.trim())
                    put("relation", request.relationship.trim())
                    put("country_code", request.countryCode.trim())
                    put("phone_number", request.phone.trim())
                }
            },
        )

    /** The id goes in the path *and* the body; the web sends both. */
    override suspend fun removeSosRecipient(recipientId: String): ZillitResult<Unit> =
        delete(
            endpoints.sosContact(recipientId),
            buildJsonObject { put("sosId", recipientId) },
        )

    // -- units --------------------------------------------------------------------------------

    override suspend fun units(kind: UnitKind): ZillitResult<List<AdminUnit>> {
        val url = when (kind) {
            UnitKind.Home -> endpoints.shootingUnitsAdmin
            UnitKind.Remote -> endpoints.remoteUnits
            // The read is `join/unit`; only the writes carry `/remote`.
            UnitKind.Shooting -> endpoints.joinedUnitsList
        }
        return get(url, ListSerializer(AdminUnitDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain(kind) } }
    }

    override suspend fun createUnit(kind: UnitKind, name: String): ZillitResult<Unit> {
        val url = when (kind) {
            UnitKind.Home -> endpoints.shootingUnits
            UnitKind.Remote -> endpoints.remoteUnits
            UnitKind.Shooting -> endpoints.joinedUnits
        }
        return post(
            url,
            buildJsonObject {
                put("unit_name", name.trim())
                // The remote route takes its membership up front. Empty is
                // accepted — the web's own validation for these is commented
                // out — and crew are added afterwards from the unit's page.
                if (kind == UnitKind.Remote) {
                    put("admins", buildJsonArray { })
                    put("users", buildJsonArray { })
                }
            },
        )
    }

    override suspend fun renameUnit(kind: UnitKind, unitId: String, name: String): ZillitResult<Unit> {
        val url = when (kind) {
            UnitKind.Home -> endpoints.shootingUnit(unitId)
            UnitKind.Shooting -> endpoints.joinedUnit(unitId)
            // No update route exists. The page withholds the action, so this
            // is unreachable rather than merely unimplemented.
            UnitKind.Remote -> return unsupported(str(S.desktop_remote_units_cannot_be_renamed))
        }
        return put(url, buildJsonObject { put("unit_name", name.trim()) })
    }

    override suspend fun deleteUnit(kind: UnitKind, unitId: String): ZillitResult<Unit> {
        val url = when (kind) {
            UnitKind.Home -> endpoints.shootingUnit(unitId)
            UnitKind.Shooting -> endpoints.joinedUnit(unitId)
            UnitKind.Remote -> return unsupported(str(S.desktop_remote_units_cannot_be_deleted))
        }
        return delete(url)
    }

    override suspend fun setUnitEnabled(unitId: String, enabled: Boolean): ZillitResult<Unit> =
        put(
            endpoints.shootingUnitVisibility(unitId),
            buildJsonObject { put("visibility", enabled) },
        )

    // -- rights ---------------------------------------------------------------------------

    /**
     * One tool can answer twice.
     *
     * A tool that appears on both the dashboard and the Film Tools grid carries
     * separate rights in each, and arrives as one wire row with both flags set
     * — so the flat list is longer than the response.
     */
    override suspend fun rights(userId: String): ZillitResult<List<ToolRights>> =
        get(endpoints.userAccess(userId), ListSerializer(ToolAccessDto.serializer()))
            .map { rows -> rows.flatMap { it.toDomain() } }

    override suspend fun changeRights(change: RightsChange): ZillitResult<Unit> =
        post(
            // The section is in the path, and the two halves of the page write
            // to genuinely different routes.
            endpoints.writeAccess(change.section.wire),
            buildJsonObject {
                put("user_id", change.userId)
                put("unit_id", change.unitId)
                put("access_type", change.access.wire)
                put("enable", change.enable)
            },
        )

    // -- deletion ---------------------------------------------------------------------------------

    /** A **string** on this route, unlike every other number this client sends. */
    override suspend fun scheduleDeletion(hours: Int): ZillitResult<Unit> =
        delete(endpoints.efface, buildJsonObject { put("delete_in_hours", hours.toString()) })

    override suspend fun cancelDeletion(): ZillitResult<Unit> = patch(endpoints.efface, body = null)

    // -- plumbing -----------------------------------------------------------------------------------

    /** The production record, for the two pages that read fields off it. */
    private suspend fun production(): ZillitResult<ProductionRecordDto> =
        withProject { project ->
            get(endpoints.projectById(project), ProductionRecordDto.serializer())
        }

    /**
     * The same record, untyped.
     *
     * The watermark is an attachment object whose shape varies, and lifting one
     * field out of raw JSON beats declaring a DTO that can fail to decode over
     * a field this page does not read.
     */
    private suspend fun productionRecord(): ZillitResult<JsonObject> =
        withProject { project ->
            get(endpoints.projectById(project), JsonElement.serializer())
                .map { it as? JsonObject ?: JsonObject(emptyMap()) }
        }

    private suspend fun <T> get(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun post(url: String, body: JsonObject?) = mutate(HttpVerb.Post, url, body)

    private suspend fun put(url: String, body: JsonObject?) = mutate(HttpVerb.Put, url, body)

    private suspend fun patch(url: String, body: JsonObject?) = mutate(HttpVerb.Patch, url, body)

    /**
     * A DELETE that may carry a body.
     *
     * Two routes here need one — scheduling a deletion takes its delay in the
     * body, and removing an SOS recipient repeats the id there. Neither works
     * with the value in the path alone.
     */
    private suspend fun delete(url: String, body: JsonObject? = null) =
        mutate(HttpVerb.Delete, url, body)

    private suspend fun mutate(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = verb,
        url = url,
        module = RequestModule.ProjectUser,
        body = body,
    ).flatMap { it.checked() }

    /**
     * Turns this API's soft rejection into a real failure.
     *
     * `{ status: 0, message }` on an HTTP 200 is how these routes say no, and
     * it is the *common* case for an admin: a duplicate department name, a
     * group that still has tools in it, a department a budget is posted
     * against. Without this the page would report a save that never happened.
     */
    private fun ApiEnvelope.checked(): ZillitResult<Unit> =
        if (status == REJECTED) {
            ZillitResult.Failure(
                // The message is a translation key. Resolved at the point of
                // display, where the label map is — see AdminViewModel.
                ZillitError.Validation(message?.takeIf { it.isNotBlank() } ?: str(S.desktop_could_not_be_saved)),
            )
        } else {
            ZillitResult.Success(Unit)
        }

    /** Runs [block] against the open production, or fails saying there is none. */
    private suspend fun <T> withProject(
        block: suspend (String) -> ZillitResult<T>,
    ): ZillitResult<T> {
        val project = projectId()?.takeIf { it.isNotBlank() }
            ?: return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_no_project_is_open)))
        return block(project)
    }

    private fun unsupported(reason: String): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Validation(reason))

    private fun List<String>.toJsonArray(): JsonArray =
        buildJsonArray { forEach { add(JsonPrimitive(it)) } }

    private companion object {
        /** `{ status: 0 }` on an HTTP 200 — this API's soft "no". */
        const val REJECTED = 0

        /** Whose SOS list to read: the production's, not the caller's own. */
        const val ADMIN_ENTRY_TYPE = "admin"
    }
}

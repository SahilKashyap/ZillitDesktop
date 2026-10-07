package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewProfileChange
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.NewPreApproval
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.DownloadRequest
import com.zillit.desktop.feature.settings.admin.domain.DownloadStatus
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.RightsWrite
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind



/** Records what was called, and answers whatever the test set up. */
internal class Recorder : AdminRepository {
    val calls = mutableListOf<String>()

    var departmentsAnswer: ZillitResult<List<Department>> = ZillitResult.Success(
        listOf(
            Department("d1", "Art", jobTitles = listOf(JobTitle("r1", "Standby Art"))),
            Department("d2", "Camera"),
            Department("d3", "Transportation"),
        ),
    )
    var crewAnswer: ZillitResult<List<CrewMember>> = ZillitResult.Success(
        listOf(
            CrewMember("u1", "Ada Lovelace", deviceId = "dev-1"),
            CrewMember("u2", "Grace Hopper", deviceId = "dev-2", isAdmin = true),
        ),
    )
    /** What every mutation answers. Set to a failure to test the sad path. */
    var mutationAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit)

    private fun record(name: String): ZillitResult<Unit> {
        calls += name
        return mutationAnswer
    }

    override suspend fun departments(): ZillitResult<List<Department>> {
        calls += "departments"
        return departmentsAnswer
    }

    override suspend fun createDepartment(name: String) = record("createDepartment:$name")
    override suspend fun deleteDepartment(departmentId: String) = record("deleteDepartment:$departmentId")
    override suspend fun reorderDepartments(departmentIds: List<String>) =
        record("reorder:${departmentIds.joinToString(",")}")

    override suspend fun createJobTitle(departmentId: String, name: String) =
        record("createJobTitle:$departmentId:$name")

    override suspend fun deleteJobTitle(departmentId: String, jobTitleId: String) =
        record("deleteJobTitle:$departmentId:$jobTitleId")

    override suspend fun crew(): ZillitResult<List<CrewMember>> {
        calls += "crew"
        return crewAnswer
    }

    override suspend fun setAdminAccess(userId: String, isAdmin: Boolean) =
        record("setAdminAccess:$userId:$isAdmin")

    override suspend fun setCrewStatus(userId: String, deviceId: String?, status: CrewStatus) =
        record("setCrewStatus:$userId:${status.wire}")

    override suspend fun updateCrewProfile(change: CrewProfileChange) =
        record(
            "updateCrewProfile:${change.userId}:${change.departmentId}:${change.designationId}:" +
                "${change.joinUnitId}:${change.keepNamePrivate}",
        )

    var rightsAnswer: List<ToolRights> = emptyList()

    override suspend fun rights(userId: String): ZillitResult<List<ToolRights>> {
        calls += "rights:$userId"
        return ZillitResult.Success(rightsAnswer)
    }

    override suspend fun writeRight(userId: String, section: RightsSection, write: RightsWrite) =
        record("writeRight:$userId:${section.wire}:${write.unitId}:${write.access.wire}:${write.enable}")

    override suspend fun chatAllowList(userId: String): ZillitResult<List<String>> {
        calls += "chatAllowList:$userId"
        return ZillitResult.Success(listOf("u2"))
    }

    override suspend fun setChatAllowList(userId: String, allowed: List<String>) =
        record("setChatAllowList:$userId:${allowed.sorted().joinToString(",")}")

    override suspend fun preApproved(): ZillitResult<List<PreApprovedCrew>> {
        calls += "preApproved"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun addPreApproved(request: NewPreApproval) =
        record("addPreApproved:${request.firstName}")

    override suspend fun tools(includeAlwaysOn: Boolean): ZillitResult<List<ProductionTool>> {
        calls += "tools:$includeAlwaysOn"
        return ZillitResult.Success(toolsAnswer)
    }

    var toolsAnswer: List<ProductionTool> = listOf(ProductionTool("casting_tool", "Casting", enabled = true))

    /** What the server holds as the current File Cabinet request; null is `{}`. */
    var cabinetRequest: DownloadRequest? = null

    /** What a new request answers with. */
    var requestAnswer: ZillitResult<DownloadRequest?> =
        ZillitResult.Success(DownloadRequest("req-1", DownloadStatus.Pending))

    override suspend fun downloadRequest(): ZillitResult<DownloadRequest?> {
        calls += "downloadRequest"
        return ZillitResult.Success(cabinetRequest)
    }

    override suspend fun requestDownload(identifiers: List<String>): ZillitResult<DownloadRequest?> {
        calls += "requestDownload:${identifiers.joinToString(",")}"
        (requestAnswer as? ZillitResult.Success)?.let { cabinetRequest = it.data }
        return requestAnswer
    }

    override suspend fun cancelDownload(requestId: String): ZillitResult<Unit> {
        cabinetRequest = null
        return record("cancelDownload:$requestId")
    }

    override suspend fun downloadUrl(requestId: String): ZillitResult<String> {
        calls += "downloadUrl:$requestId"
        return ZillitResult.Success("https://files.example/zip")
    }

    override suspend fun setToolsEnabled(tools: List<ProductionTool>) =
        record("setToolsEnabled:${tools.count { it.enabled }}")

    override suspend fun toolGroups(): ZillitResult<List<ToolGroup>> {
        calls += "toolGroups"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun createToolGroup(name: String) = record("createToolGroup:$name")
    override suspend fun renameToolGroup(toolGroupId: String, name: String) =
        record("renameToolGroup:$toolGroupId:$name")

    override suspend fun deleteToolGroup(toolGroupId: String) = record("deleteToolGroup:$toolGroupId")
    override suspend fun moveTool(identifier: String, groupIdentifier: String) =
        record("moveTool:$identifier:$groupIdentifier")

    override suspend fun renameProduction(name: String) = record("renameProduction:$name")

    override suspend fun companyDetails(): ZillitResult<CompanyDetails> {
        calls += "companyDetails"
        return ZillitResult.Success(CompanyDetails(name = "Zillit Films"))
    }

    override suspend fun saveCompanyDetails(details: CompanyDetails) =
        record("saveCompanyDetails:${details.name}")

    override suspend fun clearCompanyLogo() = record("clearCompanyLogo")

    override suspend fun watermarkUrl(): ZillitResult<String?> {
        calls += "watermarkUrl"
        return ZillitResult.Success(null)
    }

    override suspend fun clearWatermark() = record("clearWatermark")

    override suspend fun sosRecipients(): ZillitResult<List<SosRecipient>> {
        calls += "sosRecipients"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun addSosRecipient(request: NewSosRecipient) = record("addSosRecipient")
    override suspend fun removeSosRecipient(recipientId: String) = record("removeSos:$recipientId")

    var unitsAnswer: List<AdminUnit> = emptyList()

    override suspend fun units(kind: UnitKind): ZillitResult<List<AdminUnit>> {
        calls += "units:$kind"
        return ZillitResult.Success(unitsAnswer)
    }

    override suspend fun createUnit(kind: UnitKind, name: String) = record("createUnit:$kind:$name")
    override suspend fun renameUnit(kind: UnitKind, unitId: String, name: String) =
        record("renameUnit:$kind:$unitId:$name")

    override suspend fun deleteUnit(kind: UnitKind, unitId: String) = record("deleteUnit:$kind:$unitId")
    override suspend fun setUnitEnabled(unitId: String, enabled: Boolean) =
        record("setUnitEnabled:$unitId:$enabled")

    override suspend fun scheduleDeletion(hours: Int) = record("scheduleDeletion:$hours")
    override suspend fun cancelDeletion() = record("cancelDeletion")
}

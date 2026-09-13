package com.zillit.desktop.feature.crewlist

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CompanyLogo
import com.zillit.desktop.feature.crewlist.domain.CrewDepartment
import com.zillit.desktop.feature.crewlist.domain.CrewDocumentRequest
import com.zillit.desktop.feature.crewlist.domain.CrewListHost
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewPdfPage
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.OrderedPerson
import com.zillit.desktop.feature.crewlist.domain.PickedLogo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal val Aisha = CrewMember(
    userId = "u1",
    fullName = "Aisha Khan",
    designationName = "first_ac_label",
    unitName = "main_unit_label",
    countryCode = "+44",
    phone = "5550001",
    primaryEmail = "aisha@crew.example",
    email = "aisha@zillit.org",
    joiningDate = "12 Sep 2026",
)

internal val Ravi = CrewMember(userId = "u2", fullName = "Ravi Mehta", email = "ravi@vendor.example", isExternal = true)

internal val Roster = listOf(
    CrewUnit(
        unitName = "main_unit_label",
        departments = listOf(CrewDepartment("camera_label", listOf(Aisha, Ravi))),
    ),
)

internal val GeneratedPdf = CrewListPdf(media = "crew/Crew List 1.pdf", bucket = "b", region = "r", name = "Crew List")

/** Records every call; answers with whatever each field holds. */
internal class FakeCrewRepository(
    override val refreshes: Flow<Unit> = emptyFlow(),
) : CrewListRepository {
    val calls = mutableListOf<String>()
    val requests = mutableListOf<CrewDocumentRequest>()
    var roster: ZillitResult<List<CrewUnit>> = ZillitResult.Success(Roster)
    var generated: ZillitResult<CrewListPdf> = ZillitResult.Success(GeneratedPdf)
    var html: ZillitResult<String> = ZillitResult.Success("<html><body><div class=\"doc\"></div></body></html>")
    var published: ZillitResult<Unit> = ZillitResult.Success(Unit)
    var company: ZillitResult<CompanyDetails> = ZillitResult.Success(CompanyDetails(name = "Take One"))
    var savedCompany: Triple<CompanyDetails, CompanyLogo?, Boolean>? = null

    override suspend fun roster(): ZillitResult<List<CrewUnit>> {
        calls += "roster"
        return roster
    }

    override suspend fun generate(request: CrewDocumentRequest): ZillitResult<CrewListPdf> {
        calls += "generate"
        requests += request
        return generated
    }

    override suspend fun previewHtml(request: CrewDocumentRequest): ZillitResult<String> {
        calls += if (request.stacked) "design" else "preview"
        requests += request
        return html
    }

    override suspend fun publishToInfo(pdf: CrewListPdf, caption: String): ZillitResult<Unit> {
        calls += "publish:$caption"
        return published
    }

    override suspend fun companyDetails(): ZillitResult<CompanyDetails> {
        calls += "company"
        return company
    }

    override suspend fun saveCompanyDetails(
        details: CompanyDetails,
        newLogo: CompanyLogo?,
        removeLogo: Boolean,
    ): ZillitResult<Unit> {
        calls += "saveCompany"
        savedCompany = Triple(details, newLogo, removeLogo)
        return ZillitResult.Success(Unit)
    }

    var people: List<OrderedPerson> = listOf(
        OrderedPerson("p1", "Aisha Khan", "first_ac_label"),
        OrderedPerson("p2", "Lena Brooks", "second_ac_label"),
    )
    var reorderedPeople: List<String>? = null

    override suspend fun departmentPeople(departmentId: String): ZillitResult<List<OrderedPerson>> {
        calls += "people:$departmentId"
        return ZillitResult.Success(people)
    }

    override suspend fun reorderPeople(userIds: List<String>): ZillitResult<String> {
        reorderedPeople = userIds
        return ZillitResult.Success("users_reordered_successfully")
    }
}

internal class FakeCrewHost : CrewListHost {
    val calls = mutableListOf<String>()
    var distributed: ZillitResult<Unit> = ZillitResult.Success(Unit)
    var departments: List<OrderedDepartment> = listOf(
        OrderedDepartment("d1", "Camera"),
        OrderedDepartment("d2", "Sound"),
        OrderedDepartment("d3", "Art"),
    )
    var reordered: List<String>? = null

    override suspend fun fetchPdf(pdf: CrewListPdf): ZillitResult<ByteArray> {
        calls += "fetch"
        return ZillitResult.Success(byteArrayOf(1, 2, 3))
    }

    override suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<CrewPdfPage>> {
        calls += "render"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        calls += "save:$fileName"
        return ZillitResult.Success(Unit)
    }

    override suspend fun distribute(pdf: CrewListPdf): ZillitResult<Unit> {
        calls += "distribute:${pdf.fileName}"
        return distributed
    }

    override suspend fun departments(): ZillitResult<List<OrderedDepartment>> = ZillitResult.Success(departments)

    override suspend fun reorderDepartments(ids: List<String>): ZillitResult<Unit> {
        reordered = ids
        return ZillitResult.Success(Unit)
    }

    override suspend fun dialCodes(): List<DialCode> = listOf(DialCode("United Kingdom", "+44", "GB"))
    override suspend fun pickLogo(): PickedLogo? = null
    override suspend fun uploadLogo(file: PickedLogo): ZillitResult<CompanyLogo> =
        ZillitResult.Failure(ZillitError.Storage(userMessage = "no storage"))

    override suspend fun logoImage(logo: CompanyLogo): ImageBitmap? = null
    override suspend fun decode(bytes: ByteArray): ImageBitmap? = null
    override suspend fun face(userId: String): ImageBitmap? = null
}

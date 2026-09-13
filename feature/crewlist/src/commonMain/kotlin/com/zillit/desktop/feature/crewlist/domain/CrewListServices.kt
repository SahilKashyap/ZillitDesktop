package com.zillit.desktop.feature.crewlist.domain

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The crew list's own service calls — the units host, plus the production record for the letterhead. */
interface CrewListRepository {

    /**
     * A pulse per socket frame saying a department was reordered elsewhere
     * — the wire's `department:reordered`, whose web handler refetches the
     * roster. Empty by default: tests, and hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    /** `GET crewlist/list` — the grouped roster. */
    suspend fun roster(): ZillitResult<List<CrewUnit>>

    /** `POST crewlist` — the backend renders and stores the PDF, and answers its storage keys. */
    suspend fun generate(request: CrewDocumentRequest): ZillitResult<CrewListPdf>

    /** `POST crewlist/html` — the same render as HTML, for the Design and Preview canvases. */
    suspend fun previewHtml(request: CrewDocumentRequest): ZillitResult<String>

    /**
     * `POST info/chat` — the generated PDF as a pinned document post on the
     * Info board, captioned with the tool's name (web `publishCrewList`).
     */
    suspend fun publishToInfo(pdf: CrewListPdf, caption: String): ZillitResult<Unit>

    /** `GET project/:id` — the company fields the letterhead prints. */
    suspend fun companyDetails(): ZillitResult<CompanyDetails>

    /**
     * `PATCH project` with every field; [newLogo] rides only when one was just
     * uploaded, and [removeLogo] (with no replacement) is its own
     * `DELETE project/company-logo` first — `company_logo: ""` is refused.
     */
    suspend fun saveCompanyDetails(
        details: CompanyDetails,
        newLogo: CompanyLogo?,
        removeLogo: Boolean,
    ): ZillitResult<Unit>

    /** `GET project/users?reorder=true&departmentId=` — a department's people in their listing order. */
    suspend fun departmentPeople(departmentId: String): ZillitResult<List<OrderedPerson>>

    /** `PUT user/reorder-users {newOrder}` — answers the server's message key, for the toast. */
    suspend fun reorderPeople(userIds: List<String>): ZillitResult<String>
}

/** A page of the generated PDF, drawn for the in-app viewer. */
data class CrewPdfPage(val image: ImageBitmap, val widthPx: Int, val heightPx: Int)

/** A logo file the admin picked, not yet uploaded. */
class PickedLogo(val name: String, val bytes: ByteArray) {
    override fun toString(): String = "PickedLogo(${bytes.size} bytes)"
}

/**
 * What the crew list needs from the app around it — storage, the PDF engine,
 * the library, the admin service's departments, the dial codes. Each is the
 * host's to fetch; the module only says what it wants.
 */
interface CrewListHost {

    /** The stored PDF's bytes, through the production's signed reader. */
    suspend fun fetchPdf(pdf: CrewListPdf): ZillitResult<ByteArray>

    /** The PDF drawn page by page, at [widthPx] wide. */
    suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<CrewPdfPage>>

    /** Saves a copy to Downloads and opens it. */
    suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    /**
     * Files the generated PDF in the Document Distribution library under
     * "Crew List" (the cross-platform root, case-sensitive), dated today.
     */
    suspend fun distribute(pdf: CrewListPdf): ZillitResult<Unit>

    /** In the admin listing's current order, names translated. */
    suspend fun departments(): ZillitResult<List<OrderedDepartment>>

    /** `PUT departments/reorder-departments {newOrder}`. */
    suspend fun reorderDepartments(ids: List<String>): ZillitResult<Unit>

    /** Every country's dial code, for the phone pickers. */
    suspend fun dialCodes(): List<DialCode>

    /** Null when the admin cancelled the file dialog. */
    suspend fun pickLogo(): PickedLogo?

    suspend fun uploadLogo(file: PickedLogo): ZillitResult<CompanyLogo>

    /** The stored logo, for the company editor's preview. */
    suspend fun logoImage(logo: CompanyLogo): ImageBitmap?

    /** A picked file, for the preview before it is uploaded. */
    suspend fun decode(bytes: ByteArray): ImageBitmap?

    /** A crew member's face, by user id; null draws initials. */
    suspend fun face(userId: String): ImageBitmap?

    companion object {
        /** A host with nothing behind it — tests and previews. */
        val None: CrewListHost = object : CrewListHost {
            private fun <T> unavailable(): ZillitResult<T> =
                ZillitResult.Failure(ZillitError.Storage(userMessage = "Unavailable here."))

            override suspend fun fetchPdf(pdf: CrewListPdf): ZillitResult<ByteArray> = unavailable()
            override suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<CrewPdfPage>> =
                unavailable()
            override suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> = unavailable()
            override suspend fun distribute(pdf: CrewListPdf): ZillitResult<Unit> = unavailable()
            override suspend fun departments(): ZillitResult<List<OrderedDepartment>> = unavailable()
            override suspend fun reorderDepartments(ids: List<String>): ZillitResult<Unit> = unavailable()
            override suspend fun dialCodes(): List<DialCode> = emptyList()
            override suspend fun pickLogo(): PickedLogo? = null
            override suspend fun uploadLogo(file: PickedLogo): ZillitResult<CompanyLogo> = unavailable()
            override suspend fun logoImage(logo: CompanyLogo): ImageBitmap? = null
            override suspend fun decode(bytes: ByteArray): ImageBitmap? = null
            override suspend fun face(userId: String): ImageBitmap? = null
        }
    }
}

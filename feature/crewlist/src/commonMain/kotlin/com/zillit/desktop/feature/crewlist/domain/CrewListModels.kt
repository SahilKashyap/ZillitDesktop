package com.zillit.desktop.feature.crewlist.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The Crew List: the production's roster grouped Unit → Department → People,
 * and one act — generating it as a PDF the backend writes into project
 * storage. The desktop ships the phones' surface: a read-only roster and the
 * generate dialog; the web's in-browser header designer stays where it is.
 */
data class CrewUnit(
    /** Usually a label key — translate before display. */
    val unitName: String,
    val departments: List<CrewDepartment> = emptyList(),
)

data class CrewDepartment(
    /** Usually a label key — translate before display. */
    val departmentName: String,
    val members: List<CrewMember> = emptyList(),
)

data class CrewMember(
    val userId: String,
    val fullName: String,
    /** A label key — translate before display and search. */
    val designationName: String = "",
    val phone: String = "",
    val countryCode: String = "",
    /** The crew-list row's own email — not the profile store's. */
    val primaryEmail: String = "",
    /** True marks a contact who is not on Zillit. */
    val isExternal: Boolean = false,
)

/**
 * The generated PDF as the server answers it: a raw S3 key with its bucket
 * and region — never a URL. The client signs and fetches it itself.
 */
data class CrewListPdf(
    val media: String = "",
    val bucket: String = "",
    val region: String = "",
    val name: String = "",
)

/**
 * Rights from `generate_crew_list_tool`: `view_access` opens the tool and
 * (as on Android) allows generating; `posting_access` is the publish right,
 * kept on the viewer for the publish acts as they arrive.
 */
data class CrewListViewer(
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayGenerate: Boolean get() = isAdmin || canView

    companion object {
        const val TOOL_IDENTIFIER = "generate_crew_list_tool"

        fun from(permissions: ProjectPermissions): CrewListViewer {
            if (permissions.tools.isEmpty()) return CrewListViewer()
            return CrewListViewer(
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

interface CrewListRepository {
    /** `GET crewlist/list` on the units host — the grouped roster. */
    suspend fun roster(): ZillitResult<List<CrewUnit>>

    /**
     * `POST crewlist` — the backend renders and stores the PDF.
     * [hideExternalLabel] is the dialog's one question: whether the
     * "Not on Zillit" tag prints.
     */
    suspend fun generate(hideExternalLabel: Boolean): ZillitResult<CrewListPdf>
}

/** Fetches the stored PDF and hands it to the OS — Downloads, then open. */
fun interface CrewListTransfer {
    suspend fun open(pdf: CrewListPdf): ZillitResult<Unit>
}

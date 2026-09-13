package com.zillit.desktop.feature.crewlist.domain

import com.zillit.desktop.core.permissions.ProjectPermissions
import kotlinx.serialization.json.JsonObject

/**
 * The Crew List (`generate_crew_list_tool`, web `/film-tools/crewlist` →
 * `CrewListCustom.jsx`): the production's roster grouped Unit → Department →
 * People; per-member phone and email edits that apply to the document only; a
 * designed letterhead; and the generated PDF, viewed, published to Info or
 * filed in Document Distribution.
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
    val departmentName: String = "",
    /** The row's own unit — what the profile drawer's Unit line reads. */
    val unitName: String = "",
    /** The local number; the dial code is [countryCode]. */
    val phone: String = "",
    val countryCode: String = "",
    /** `primary_email` — the member's own address. */
    val primaryEmail: String = "",
    /**
     * `email` — for crew on Zillit, their project mailbox (sent only when they
     * consented; the backend blanks it otherwise). For external contacts, the
     * address typed into the Add External User form.
     */
    val email: String = "",
    /** True marks a contact who is not on Zillit. */
    val isExternal: Boolean = false,
    /** As the server phrases it; shown verbatim unless it is an epoch. */
    val joiningDate: String = "",
    val picture: CrewPicture? = null,
) {
    /**
     * PROFILE. External contacts only ever have the address they were added
     * with, which the form writes to `email` — so for them that IS the profile
     * address.
     */
    val profileEmail: String
        get() = if (isExternal) primaryEmail.ifBlank { email } else primaryEmail

    /**
     * PROJECT — the Zillit mailbox. Always empty for external contacts, even
     * when the API sends something: under this label it would claim a mailbox
     * they do not have.
     */
    val projectEmail: String
        get() = if (isExternal) "" else email
}

/** A stored profile picture — a raw S3 key, signed and fetched by the host. */
data class CrewPicture(
    val media: String,
    val thumbnail: String = "",
    val bucket: String = "",
    val region: String = "",
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
    val contentSubtype: String = "pdf",
    val thumbnail: String = "",
    val fileSize: String = "",
    /**
     * The answer's `data` object, untouched. Publishing to Info forwards it as
     * the post's attachment exactly as the web does, keys the desktop does not
     * model included.
     */
    val attachment: JsonObject = JsonObject(emptyMap()),
) {
    /** `Crew List.pdf` — with an extension, as the library refuses a bare name. */
    val fileName: String
        get() = name.ifBlank { DEFAULT_NAME }.let { if (it.contains('.')) it else "$it.pdf" }

    companion object {
        const val DEFAULT_NAME = "Crew List.pdf"
    }
}

/**
 * Everything the crew list can do, and who may. Four rights rows and two
 * facts about the person and the production feed it:
 *
 * - `generate_crew_list_tool` — view opens the tool; posting is editing the
 *   document and publishing it.
 * - `info_tool` posting — publishing lands on the Info board, so it is asked too.
 * - `document_distribution_tool` posting, or being an admin — filing the PDF in
 *   the library (the web's `useDistributeToDocDist`).
 * - `external_users_tool` view and posting — adding a contact from here.
 * - administrator — the department order and the company details.
 * - an `other` production is a Staff List, with no unit bands.
 */
data class CrewListViewer(
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canPostInfo: Boolean = false,
    val canDistribute: Boolean = false,
    val canAddExternalUser: Boolean = false,
    val isAdmin: Boolean = false,
    val isOtherProject: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayGenerate: Boolean get() = isAdmin || canView

    /** "Crew List", or "Staff List" on a non-production workspace. */
    val toolName: String get() = if (isOtherProject) STAFF_LIST else CREW_LIST

    companion object {
        const val TOOL_IDENTIFIER = "generate_crew_list_tool"
        const val INFO_TOOL = "info_tool"
        const val DOC_DISTRIBUTION_TOOL = "document_distribution_tool"
        const val EXTERNAL_USERS_TOOL = "external_users_tool"
        const val CREW_LIST = "Crew List"
        const val STAFF_LIST = "Staff List"

        fun from(permissions: ProjectPermissions, isOtherProject: Boolean = false): CrewListViewer {
            if (permissions.tools.isEmpty()) return CrewListViewer(isOtherProject = isOtherProject)
            return CrewListViewer(
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canPostInfo = permissions.canPost(INFO_TOOL),
                canDistribute = permissions.canPost(DOC_DISTRIBUTION_TOOL) || permissions.isAdmin,
                canAddExternalUser = permissions.canView(EXTERNAL_USERS_TOOL) &&
                    permissions.canPost(EXTERNAL_USERS_TOOL),
                isAdmin = permissions.isAdmin,
                isOtherProject = isOtherProject,
                ready = true,
            )
        }
    }
}

/** What every render of the document is made from — preview, PDF and publish alike. */
data class CrewDocumentRequest(
    val layout: HeaderLayout = HeaderLayout(),
    val overrides: Map<String, MemberOverride> = emptyMap(),
    val hideInternalLines: Boolean = false,
    /** Only the PDF asks; the previews always show the label. */
    val hideExternalLabel: Boolean? = null,
    /**
     * The Design canvas's render: all three header sections as separate
     * stacked blocks, so they can be regrouped in place.
     */
    val stacked: Boolean = false,
)

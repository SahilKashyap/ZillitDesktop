package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ComposeReport
import com.zillit.desktop.feature.productionreport.domain.EditorSelection
import com.zillit.desktop.feature.productionreport.domain.RenderKind
import com.zillit.desktop.feature.productionreport.domain.ReportComment
import com.zillit.desktop.feature.productionreport.domain.ReportReminder
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
import com.zillit.desktop.feature.productionreport.ui.EditorState
import com.zillit.desktop.feature.productionreport.ui.ReportList
import com.zillit.desktop.feature.productionreport.ui.ReportLists
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.Workspace

/** A production mid-shoot: every list populated, and an editor over the default layout. */
internal object ReportRenderFixtures {
    const val NOW = 1_789_300_000_000L
    const val TODAY = "2026-09-13"

    val members = listOf(
        SheetMember("u1", "Sahil Kashyap", "Production", "2nd AD", status = "accepted"),
        SheetMember("u2", "Maya Fernandes", "Production", "Producer", status = "accepted"),
        SheetMember("u3", "Oliver Grant", "Direction", "Director", status = "accepted"),
        SheetMember("u4", "Priya Nair", "Camera", "DOP", status = "accepted"),
        SheetMember("u5", "Tom Becker", "Camera", "1st AC", status = "accepted"),
        SheetMember("u6", "Lena Ortiz", "Sound", "Sound Mixer", status = "accepted"),
    )

    fun summary(
        id: String,
        name: String,
        status: ReportStatus,
        approvals: List<ApprovalRequest> = emptyList(),
        createdById: String = "u1",
        reminders: List<ReportReminder> = emptyList(),
    ) = ReportSummary(
        id = id,
        serialNo = id,
        name = name,
        status = status,
        createdBy = members.first { it.userId == createdById }.fullName,
        createdById = createdById,
        createdOn = NOW - 3_600_000L,
        updatedOn = NOW - 1_200_000L,
        publishedOn = if (status == ReportStatus.Published) NOW - 7_200_000 else null,
        approvals = approvals,
        reminders = reminders,
    )

    private val signatureRound = listOf(
        ApprovalRequest("a1", "u2", "Maya Fernandes", "Producer", status = "APPROVED", actedOn = NOW - 600_000),
        ApprovalRequest("a2", "u3", "Oliver Grant", "Director"),
    )

    /** Awaiting my signature, created by the producer. */
    val received = summary(
        id = "PR-008",
        name = "Day 8 — Pier night",
        status = ReportStatus.PendingApproval,
        approvals = listOf(ApprovalRequest("q8", "u1", "Sahil Kashyap", "2nd AD")),
        createdById = "u2",
        reminders = listOf(
            ReportReminder(
                id = "m1",
                approvalRequestId = "q8",
                assigneeId = "u1",
                // The sender's member id, resolved to a name and title on render.
                sentBy = "u2",
                message = "Please sign before call time",
                createdOn = NOW - 300_000,
            ),
        ),
    )

    val finalized = summary("PR-007", "Day 7 — Courtroom", ReportStatus.ApprovedForPublish, signatureRound)

    val comments = listOf(
        ReportComment("c1", "u2", "Maya Fernandes", "Producer", "Double-check the second unit wrap.", NOW - 5_000_000),
        ReportComment("c2", "u1", "Sahil Kashyap", "2nd AD", "Updated — wrapped at 19:40.", NOW - 4_000_000),
    )

    val templates = listOf(
        StockTemplate(
            "standard",
            "Create your own template",
            ComposeReport.defaultTemplate(TODAY),
            isCreateYourOwn = true,
        ),
        StockTemplate("feature", "Feature Film", ComposeReport.defaultTemplate(TODAY)),
    )

    val state = ReportUiState(
        viewer = ReportViewer(
            userId = "u1",
            displayName = "Sahil Kashyap",
            designation = "2nd AD",
            canPost = true,
            ready = true,
        ),
        members = members,
        metadata = SheetMetadata(currentShootDay = 12, totalDays = "40", finalApproverIds = listOf("u1", "u2", "u3")),
        workspace = Workspace.Manage,
        hasChat = false,
        canDistribute = true,
        lists = ReportLists(
            drafts = ReportList(
                rows = listOf(
                    summary("PR-014", "Day 14 — Studio 3", ReportStatus.Draft),
                    summary("PR-013", "Day 13 — Harbour exterior", ReportStatus.PendingInternalApproval),
                ),
                loaded = true,
            ),
            sent = ReportList(
                rows = listOf(summary("PR-011", "Day 11 — Warehouse", ReportStatus.PendingApproval, signatureRound)),
                loaded = true,
            ),
            received = ReportList(rows = listOf(received), loaded = true),
            finalized = ReportList(rows = listOf(finalized), loaded = true),
            published = ReportList(
                rows = listOf(summary("PR-010", "Day 10 — Rooftop", ReportStatus.Published, signatureRound)),
                loaded = true,
            ),
        ),
    )

    /** The default layout with today's crew, opened as a saved draft. */
    fun editor(
        selection: EditorSelection? = null,
        sidebar: Boolean = false,
        saveMenuOpen: Boolean = false,
    ): EditorState {
        val (document, day) = ComposeReport.newReport(
            template = ComposeReport.defaultTemplate(TODAY),
            metadata = state.metadata,
            members = members,
            todayYmd = TODAY,
        )
        return EditorState(
            reportId = "PR-014",
            status = ReportStatus.Draft,
            name = "Day 14 — Studio 3",
            document = document,
            currentShootDay = day,
            selection = selection,
            sidebarVisible = sidebar,
            paneOpen = selection != null,
            dayTypes = SheetMetadata.STANDARD_DAY_TYPES,
            saveMenuOpen = saveMenuOpen,
        )
    }

    /** Where the first crew section sits in [editor]'s document. */
    fun crewCell(): EditorSelection.Cell {
        val rows = editor().document.rows
        val row = rows.indexOfFirst { page -> page.cells.any { it.renderAs == RenderKind.Employee } }
        return EditorSelection.Cell(row, rows[row].cells.indexOfFirst { it.renderAs == RenderKind.Employee })
    }
}

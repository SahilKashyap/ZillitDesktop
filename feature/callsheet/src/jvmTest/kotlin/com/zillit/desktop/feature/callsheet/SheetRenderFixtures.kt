package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.ComposeSheet
import com.zillit.desktop.feature.callsheet.domain.EditorSelection
import com.zillit.desktop.feature.callsheet.domain.RenderKind
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetReminder
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.ui.EditorState
import com.zillit.desktop.feature.callsheet.ui.SheetList
import com.zillit.desktop.feature.callsheet.ui.SheetLists
import com.zillit.desktop.feature.callsheet.ui.SheetUiState

/** A production mid-shoot: every list populated, and an editor over the default layout. */
internal object SheetRenderFixtures {
    const val NOW = 1_789_300_000_000L

    val members = listOf(
        SheetMember("u1", "Sahil Kashyap", "Production", designation = "2nd AD", status = "accepted"),
        SheetMember("u2", "Maya Fernandes", "Production", designation = "Producer", status = "accepted"),
        SheetMember("u3", "Oliver Grant", "Direction", designation = "Director", status = "accepted"),
        SheetMember("u4", "Priya Nair", "Camera", designation = "DOP", status = "accepted"),
        SheetMember("u5", "Tom Becker", "Camera", designation = "1st AC", status = "accepted"),
        SheetMember("u6", "Lena Ortiz", "Sound", designation = "Sound Mixer", status = "accepted"),
    )

    fun summary(
        id: String,
        name: String,
        status: CallSheetStatus,
        approvals: List<ApprovalRequest> = emptyList(),
        createdById: String = "u1",
        reminders: List<SheetReminder> = emptyList(),
    ) = CallSheetSummary(
        id = id,
        serialNo = id,
        name = name,
        status = status,
        createdBy = members.first { it.userId == createdById }.fullName,
        createdById = createdById,
        createdOn = NOW - 3_600_000L,
        updatedOn = NOW - 1_200_000L,
        publishedOn = if (status == CallSheetStatus.Published) NOW - 7_200_000 else null,
        approvals = approvals,
        reminders = reminders,
    )

    private val signatureRound = listOf(
        ApprovalRequest("a1", "u2", "Maya Fernandes", "Producer", status = "APPROVED", actedOn = NOW - 600_000),
        ApprovalRequest("a2", "u3", "Oliver Grant", "Director"),
    )

    /** Awaiting my signature, created by the producer. */
    val received = summary(
        id = "CS-008",
        name = "Day 8 — Pier night",
        status = CallSheetStatus.PendingApproval,
        approvals = listOf(ApprovalRequest("q8", "u1", "Sahil Kashyap", "2nd AD")),
        createdById = "u2",
        reminders = listOf(
            SheetReminder(
                id = "m1",
                approvalRequestId = "q8",
                assigneeId = "u1",
                sentBy = "Maya Fernandes",
                message = "Please sign before call time",
                createdOn = NOW - 300_000,
            ),
        ),
    )

    val finalized = summary("CS-007", "Day 7 — Courtroom", CallSheetStatus.ApprovedForPublish, signatureRound)

    val comments = listOf(
        SheetComment("c1", "u2", "Maya Fernandes", "Producer", "Double-check the second unit wrap.", NOW - 5_000_000),
        SheetComment("c2", "u1", "Sahil Kashyap", "2nd AD", "Updated — wrapped at 19:40.", NOW - 4_000_000),
    )

    val templates = listOf(
        StockTemplate(
            "standard",
            "Create your own template",
            ComposeSheet.defaultTemplate(NOW),
            isCreateYourOwn = true,
        ),
        StockTemplate("comfort_and_joy", "Comfort and Joy", ComposeSheet.defaultTemplate(NOW)),
    )

    val state = SheetUiState(
        viewer = CallSheetViewer(
            userId = "u1",
            displayName = "Sahil Kashyap",
            designation = "2nd AD",
            canPost = true,
            canViewGrid = true,
            ready = true,
        ),
        members = members,
        metadata = SheetMetadata(currentShootDay = 12, totalDays = "40", finalApproverIds = listOf("u1", "u2", "u3")),
        metadataLoaded = true,
        tab = SheetTab.Drafts,
        canDistribute = true,
        lists = SheetLists(
            drafts = SheetList(
                rows = listOf(
                    summary("CS-014", "Day 14 — Studio 3", CallSheetStatus.Draft),
                    summary("CS-013", "Day 13 — Harbour exterior", CallSheetStatus.PendingInternalApproval),
                ),
                loaded = true,
            ),
            sent = SheetList(
                rows = listOf(summary("CS-011", "Day 11 — Warehouse", CallSheetStatus.PendingApproval, signatureRound)),
                loaded = true,
            ),
            received = SheetList(rows = listOf(received), loaded = true),
            finalized = SheetList(rows = listOf(finalized), loaded = true),
            published = SheetList(
                rows = listOf(summary("CS-010", "Day 10 — Rooftop", CallSheetStatus.Published, signatureRound)),
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
        val (document, day) = ComposeSheet.newSheet(
            template = ComposeSheet.defaultTemplate(NOW),
            metadata = state.metadata,
            members = members,
            company = CompanySeed(projectName = "Harbour Lights", companyName = "Pier Pictures"),
            todayMs = NOW,
        )
        return EditorState(
            sheetId = "CS-014",
            status = CallSheetStatus.Draft,
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

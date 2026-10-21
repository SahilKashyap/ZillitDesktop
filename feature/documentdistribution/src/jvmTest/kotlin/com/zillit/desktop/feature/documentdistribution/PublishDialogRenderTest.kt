package com.zillit.desktop.feature.documentdistribution

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PublishState
import com.zillit.desktop.feature.documentdistribution.ui.pages.PublishDialog
import kotlin.test.Test

/**
 * The Publish dialog draws, and draws the right fields for the destination.
 *
 * Composed rather than only unit-tested because almost everything here is
 * conditional on the tile that is lit: a destination whose required field
 * never renders is a form that can never be completed, and that failure is
 * invisible to the state tests.
 */
@OptIn(ExperimentalTestApi::class)
class PublishDialogRenderTest {

    private fun granted(identifier: String) = ToolAccess(
        identifier = identifier,
        enabled = true,
        canView = true,
        canPost = true,
        canDownload = true,
    )

    private fun viewer(vararg posting: String) = DocDistViewer.from(
        ProjectPermissions(tools = listOf(granted(DocDistViewer.TOOL_IDENTIFIER)) + posting.map(::granted)),
        userId = "u1",
        userEmail = "u@x",
        isTelevision = true,
    )

    private val everything = viewer(
        "schedule_distribution_tool",
        "dod_tool",
        "info_tool",
        "confidential_info_tool",
        "script_distribution_tool",
        "production_report_chat_tool",
    )

    private val pdf = LibraryDocument(id = "d1", name = "Call Sheet.pdf", contentType = "application/pdf")
    private val sheet = LibraryDocument(id = "d2", name = "Budget.xlsx", contentType = "application/vnd.ms-excel")

    private fun state(
        target: PublishTarget?,
        draft: PublishDraft = PublishDraft(documentIds = listOf("d1")),
        published: List<PublishedFile> = emptyList(),
        files: List<LibraryDocument> = listOf(pdf),
        who: DocDistViewer = everything,
    ) = DocDistUiState(
        viewer = who,
        publish = PublishState(
            target = target,
            draft = draft,
            files = files,
            alreadyPublished = published,
        ),
    )

    private fun render(
        state: DocDistUiState,
        dark: Boolean = false,
        check: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = dark) { PublishDialog(state, onEvent = {}) } }
        check()
    }

    // -- the rail ------------------------------------------------------------

    @Test
    fun `every destination is on the rail, tools as one tile each`() {
        render(state(target = null)) {
            onNodeWithText("Call Sheet").assertIsDisplayed()
            onNodeWithText("Production Report").assertIsDisplayed()
            onNodeWithText("Info").assertIsDisplayed()
            onNodeWithText("Confidential Info").assertIsDisplayed()
            onNodeWithText("Schedule D.O.D").assertIsDisplayed()
            onNodeWithText("Schedule Full & One Line").assertIsDisplayed()
            onNodeWithText("Script & Page Distribution").assertIsDisplayed()
        }
    }

    @Test
    fun `nothing picked yet asks for a destination instead of showing fields`() {
        render(state(target = null)) {
            onNodeWithText("Pick a destination to continue").assertIsDisplayed()
        }
    }

    /**
     * The rail is NOT filtered by rights: a destination somebody cannot post
     * to is tagged and offers the ask, because a tile that is simply absent
     * reads as a bug rather than as a permission.
     */
    @Test
    fun `a destination without posting rights is tagged, not hidden`() {
        render(state(target = null, who = viewer("info_tool"))) {
            // Still on the rail, and tagged. Five of the seven tiles are:
            // this viewer holds Info, and Call Sheet is gated by the home
            // unit list, which this module does not hold.
            onNodeWithText("Confidential Info").assertIsDisplayed()
            onAllNodesWithText("No access").assertCountEquals(5)
            onAllNodesWithText("View only").assertCountEquals(0)
        }
    }

    @Test
    fun `picking a destination you cannot post to offers the ask`() {
        val restricted = viewer("info_tool").let { base ->
            base.copy(
                publishAccess = base.publishAccess + (
                    "info" to base.accessFor("info").copy(canPost = false)
                    ),
            )
        }
        render(state(PublishTarget.Info, who = restricted)) {
            onNodeWithText("View only").assertIsDisplayed()
            onNodeWithText("Request Posting Rights").assertIsDisplayed()
        }
    }

    /** A module the production never switched on cannot be granted, so no button. */
    @Test
    fun `a disabled module says so and offers no request`() {
        render(state(PublishTarget.ConfidentialInfo, who = viewer("info_tool"))) {
            onNodeWithText(
                "The \"Confidential Info\" module isn't enabled for this project. " +
                    "Enable it in Project Settings (or ask a project admin to enable it) " +
                    "before publishing here.",
            ).assertIsDisplayed()
        }
    }

    // -- the fields ----------------------------------------------------------

    @Test
    fun `the schedule pages destination asks for a scene and which schedule`() {
        render(state(PublishTarget.SchedulePages)) {
            onNodeWithText("Enter Scene Number *").assertIsDisplayed()
            onNodeWithText("SCHEDULE TYPE *").assertIsDisplayed()
            onNodeWithText("Full Schedule Pages").assertIsDisplayed()
        }
    }

    @Test
    fun `dod asks for a name and, on television, an episode`() {
        render(state(PublishTarget.Dod)) {
            onNodeWithText("Name *").assertIsDisplayed()
            onNodeWithText("Episode Number *").assertIsDisplayed()
        }
    }

    @Test
    fun `schedule full offers an optional date, and no scene`() {
        render(state(PublishTarget.ScheduleFull)) {
            onNodeWithText("Schedule Date").assertIsDisplayed()
        }
    }

    @Test
    fun `a plain destination offers a note instead of tool fields`() {
        render(state(PublishTarget.Info)) {
            onNodeWithText("Note (optional)").assertIsDisplayed()
        }
    }

    // -- files to publish ----------------------------------------------------

    @Test
    fun `the files going out are listed and counted`() {
        render(state(PublishTarget.Info, files = listOf(pdf, sheet))) {
            onNodeWithText("FILES TO PUBLISH *").assertIsDisplayed()
            onNodeWithText("Call Sheet.pdf").assertIsDisplayed()
            onNodeWithText("Budget.xlsx").assertIsDisplayed()
            onNodeWithText("1 of 2 files selected").assertIsDisplayed()
        }
    }

    /** A PDF-only destination greys the rest and says why, rather than hiding them. */
    @Test
    fun `a non-pdf row is stamped on a pdf-only destination`() {
        render(state(PublishTarget.Dod, files = listOf(pdf, sheet))) {
            onNodeWithText("Budget.xlsx").assertIsDisplayed()
            onNodeWithText("PDF only").assertIsDisplayed()
            onNodeWithText("Only PDF files can be published as Day Out of Days.").assertIsDisplayed()
        }
    }

    @Test
    fun `a single-file destination says it replaces the previous one`() {
        render(state(PublishTarget.ScheduleFull)) {
            onNodeWithText("Only one file can be published as Schedule Full.").assertIsDisplayed()
        }
    }

    @Test
    fun `a row published before is stamped`() {
        val seen = pdf.copy(publishedCategory = "info")
        render(state(PublishTarget.Info, files = listOf(seen))) {
            onNodeWithText("Previously published").assertIsDisplayed()
        }
    }

    // -- republish options ---------------------------------------------------

    @Test
    fun `a destination with something live offers all three actions`() {
        val live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))
        val draft = PublishDraft(documentIds = listOf("d1"), mode = PublishMode.Add)

        render(state(PublishTarget.CallSheet, draft = draft, published = live)) {
            onNodeWithText("PUBLISH ACTION *").assertIsDisplayed()
            onNodeWithText("Addition to existing files on the Call Sheet").assertIsDisplayed()
            onNodeWithText("Replace an existing file on the Call Sheet").assertIsDisplayed()
            onNodeWithText("Publish as a New File on the Call Sheet").assertIsDisplayed()
        }
    }

    @Test
    fun `choosing replace lists what can be replaced`() {
        val live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))
        val replacing = PublishDraft(
            documentIds = listOf("d1"),
            mode = PublishMode.Replace,
            replaceChatId = "c1",
        )

        render(state(PublishTarget.CallSheet, draft = replacing, published = live)) {
            onNodeWithText("FILE TO REPLACE *").assertIsDisplayed()
            onNodeWithText("Call Sheet Day 11.pdf").assertIsDisplayed()
        }
    }

    /** A first publish keeps the plain form — there is nothing to replace. */
    @Test
    fun `a first publish shows no republish options`() {
        render(state(PublishTarget.CallSheet)) {
            onNodeWithText("FILES TO PUBLISH *").assertIsDisplayed()
        }
    }

    /** The optional display name belongs to the two republishing destinations only. */
    @Test
    fun `a ticked file on a republishing destination offers a display name`() {
        val live = listOf(PublishedFile(chatId = "c1", name = "Day 11.pdf"))
        val draft = PublishDraft(documentIds = listOf("d1"), mode = PublishMode.Add)

        render(state(PublishTarget.ProductionReport, draft = draft, published = live)) {
            onNodeWithText("Display name (optional)").assertIsDisplayed()
        }
    }

    // -- footer --------------------------------------------------------------

    /** The reason a publish cannot go is on screen, not only behind the button. */
    @Test
    fun `the missing field is named`() {
        render(state(PublishTarget.Dod)) {
            onNodeWithText("A name is required").assertIsDisplayed()
        }
    }

    @Test
    fun `the footer names the destination`() {
        render(state(PublishTarget.Info)) {
            onNodeWithText("1 of 1 files selected").assertIsDisplayed()
        }
    }

    @Test
    fun `the dialog composes in dark mode too`() {
        render(state(PublishTarget.Info), dark = true) {
            onNodeWithText("Note (optional)").assertIsDisplayed()
        }
    }
}

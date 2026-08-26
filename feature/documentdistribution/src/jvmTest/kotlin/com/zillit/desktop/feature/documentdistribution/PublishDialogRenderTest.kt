package com.zillit.desktop.feature.documentdistribution

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
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
 * Composed rather than only unit-tested because the fields are conditional:
 * a destination whose required field never renders is a form that can never
 * be completed, and that failure is invisible to the state tests.
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

    private val viewer = DocDistViewer.from(
        ProjectPermissions(
            tools = listOf(
                granted(DocDistViewer.TOOL_IDENTIFIER),
                granted("schedule_distribution_tool"),
                granted("dod_tool"),
                granted("info_tool"),
            ),
        ),
        userId = "u1",
        userEmail = "u@x",
        isTelevision = true,
    )

    private fun state(
        target: PublishTarget?,
        draft: PublishDraft = PublishDraft(documentIds = listOf("d1")),
        published: List<PublishedFile> = emptyList(),
    ) = DocDistUiState(
        viewer = viewer,
        publish = PublishState(target = target, draft = draft, alreadyPublished = published),
    )

    private fun render(
        state: DocDistUiState,
        dark: Boolean = false,
        check: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent { ZillitTheme(darkTheme = dark) { PublishDialog(state, onEvent = {}) } }
        check()
    }

    @Test
    fun `the destinations this viewer may use are offered, grouped`() {
        render(state(target = null)) {
            onNodeWithText("Info").assertIsDisplayed()
            onNodeWithText("Schedule Full & One Line").assertIsDisplayed()
            onNodeWithText("Day Out of Days").assertIsDisplayed()
        }
    }

    @Test
    fun `the schedule pages destination asks for a scene and which schedule`() {
        render(state(PublishTarget.SchedulePages)) {
            onNodeWithText("Scene number").assertIsDisplayed()
            // ZillitSectionLabel upper-cases its text.
            onNodeWithText("WHICH SCHEDULE IS THIS?").assertIsDisplayed()
            onNodeWithText("Full Schedule Pages").assertIsDisplayed()
        }
    }

    @Test
    fun `dod asks for a name and, on television, an episode`() {
        render(state(PublishTarget.Dod)) {
            onNodeWithText("Name").assertIsDisplayed()
            onNodeWithText("Episode").assertIsDisplayed()
        }
    }

    @Test
    fun `a plain destination offers a note instead of tool fields`() {
        render(state(PublishTarget.Info)) {
            onNodeWithText("Note (optional)").assertIsDisplayed()
        }
    }

    @Test
    fun `a destination with something live offers add or replace`() {
        val live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))

        render(state(PublishTarget.CallSheet, published = live)) {
            onNodeWithText("ALREADY PUBLISHED THERE").assertIsDisplayed()
            onNodeWithText("Add alongside").assertIsDisplayed()
            onNodeWithText("Replace").assertIsDisplayed()
        }
    }

    @Test
    fun `choosing replace lists what can be replaced`() {
        val live = listOf(PublishedFile(chatId = "c1", name = "Call Sheet Day 11.pdf"))
        val replacing = PublishDraft(documentIds = listOf("d1"), mode = PublishMode.Replace)

        render(state(PublishTarget.CallSheet, draft = replacing, published = live)) {
            onNodeWithText("Call Sheet Day 11.pdf").assertIsDisplayed()
        }
    }

    /** The reason a publish cannot go is on screen, not only behind the button. */
    @Test
    fun `the missing field is named`() {
        render(state(PublishTarget.Dod)) {
            onNodeWithText("A name is required").assertIsDisplayed()
        }
    }

    @Test
    fun `a single-file destination says so when several are chosen`() {
        val many = PublishDraft(documentIds = listOf("d1", "d2"))

        render(state(PublishTarget.ScheduleFull, draft = many)) {
            onNodeWithText(
                "Schedule Full takes one document at a time — a second would replace it.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun `the dialog composes in dark mode too`() {
        // The subtitle, not the title — "Publish" is both the heading and the
        // confirm button, so it matches two nodes.
        render(state(PublishTarget.Info), dark = true) {
            onNodeWithText("1 file into another tool").assertIsDisplayed()
        }
    }

    /** A viewer with no posting rights anywhere is told why, not shown an empty dialog. */
    @Test
    fun `a viewer with no destinations is told so`() {
        val none = DocDistUiState(
            viewer = DocDistViewer.from(
                ProjectPermissions(tools = listOf(granted(DocDistViewer.TOOL_IDENTIFIER))),
                userId = "u1",
                userEmail = "u@x",
            ).copy(publishable = emptySet()),
            publish = PublishState(draft = PublishDraft(documentIds = listOf("d1"))),
        )

        render(none) {
            onNodeWithText(
                "You do not have posting rights on any tool that accepts published documents.",
            ).assertIsDisplayed()
        }
    }
}

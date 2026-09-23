package com.zillit.desktop.feature.crewlist

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.crewlist.domain.CompanyDetails
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.DepartmentOrder
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.feature.crewlist.domain.OrderedDepartment
import com.zillit.desktop.feature.crewlist.domain.search
import com.zillit.desktop.feature.crewlist.ui.CanvasDocument
import com.zillit.desktop.feature.crewlist.ui.CompanyEditorState
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListScreen
import com.zillit.desktop.feature.crewlist.ui.CrewListSlots
import com.zillit.desktop.feature.crewlist.ui.CrewListUiState
import com.zillit.desktop.feature.crewlist.ui.CustomiseState
import com.zillit.desktop.feature.crewlist.ui.DepartmentOrderState
import com.zillit.desktop.feature.crewlist.ui.GenerateAction
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Composes the sheet and each surface laid over it, off the state alone. */
@OptIn(ExperimentalTestApi::class)
class CrewListScreenRenderTest {

    private val viewer =
        CrewListViewer(canView = true, canPost = true, canPostInfo = true, isAdmin = true, ready = true)
    private val base = CrewListUiState(viewer = viewer, units = Roster, hasLoaded = true, selfUserId = "me")

    @Test
    fun `the sheet groups unit - department - people with both addresses labelled`() = runComposeUiTest {
        val events = mutableListOf<CrewListEvent>()
        setContent {
            ZillitTheme {
                CrewListScreen(state = base, visibleUnits = { Roster }, onEvent = { events += it })
            }
        }
        onNodeWithText("Main Unit").assertExists()
        onNodeWithText("Camera").assertExists()
        onNodeWithText("NAME").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("+445550001").assertExists()
        onNodeWithText("aisha@crew.example").assertExists()
        onNodeWithText("aisha@zillit.org").assertExists()
        onNodeWithText("Not On Zillit").assertExists()
        // Ravi is external: his one address is PROFILE, his PROJECT an em dash.
        onNodeWithText("ravi@vendor.example").assertExists()
        onAllNodesWithText("PROFILE").assertCountEquals(2)
        onNodeWithText("You can rearrange the department listing", substring = true).assertExists()

        onNodeWithText("Generate PDF").performClick()
        onNodeWithText("Click here").performClick()
        onNodeWithText("Aisha Khan").performClick()
        assertTrue(CrewListEvent.Document.OpenChooser in events)
        assertTrue(CrewListEvent.Admin.OpenDepartments in events)
        assertTrue(events.any { it is CrewListEvent.Sheet.OpenProfile })
    }

    @Test
    fun `edit mode turns the phone and PROFILE into inputs and keeps PROJECT locked`() = runComposeUiTest {
        val editing = base.copy(editing = true, overrides = mapOf("u1" to MemberOverride(email = "new@x.y")))
        setContent {
            ZillitTheme { CrewListScreen(state = editing, visibleUnits = { Roster }, onEvent = {}) }
        }
        onNodeWithText("new@x.y").assertExists()
        onNodeWithText("aisha@zillit.org").assertExists()
        onNodeWithText("Done").assertExists()
        onNodeWithText("Your edits will be applied when you preview, generate or publish.").assertExists()
    }

    @Test
    fun `the chooser asks the label question and offers the web's four actions`() = runComposeUiTest {
        val events = mutableListOf<CrewListEvent>()
        setContent {
            ZillitTheme {
                CrewListScreen(
                    state = base.copy(chooserOpen = true),
                    visibleUnits = { Roster },
                    onEvent = { events += it },
                )
            }
        }
        onNodeWithText("(Not on Zillit)", substring = true).assertExists()
        onNodeWithText("View").assertExists()
        onNodeWithText("Publish to Doc Distribution").assertExists()
        onNodeWithText("Publish").performClick()
        assertTrue(CrewListEvent.Document.Run(GenerateAction.Publish) in events)
    }

    @Test
    fun `the profile drawer shows the actions, user details and contact details`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CrewListScreen(state = base.copy(profile = Aisha), visibleUnits = { Roster }, onEvent = {})
            }
        }
        onNodeWithText("USER DETAILS").assertExists()
        onNodeWithText("CONTACT DETAILS").assertExists()
        onNodeWithText("Joining Date").assertExists()
        onNodeWithText("12 Sep 2026").assertExists()
        onNodeWithText("Zillit Email").assertExists()
        onNodeWithText("Audio").assertExists()
        onNodeWithText("Video").assertExists()
        onNodeWithText("Chat").assertExists()
    }

    @Test
    fun `customise without a browser says so, beside the sidebar's controls`() = runComposeUiTest {
        val open = base.copy(customise = CustomiseState(designLoading = false, logoSizeDraft = 160))
        setContent {
            ZillitTheme { CrewListScreen(state = open, visibleUnits = { Roster }, onEvent = {}) }
        }
        onNodeWithText("Customise & Preview").assertExists()
        onNodeWithText("CUSTOMISE").assertExists()
        onNodeWithText("Logo size: 160px").assertExists()
        onNodeWithText("Show internal lines").assertExists()
        onNodeWithText("Edit Details").assertExists()
        onNodeWithText("The preview needs the embedded browser", substring = true).assertExists()
    }

    @Test
    fun `the canvas gets the document, and steps aside while something outside covers it`() = runComposeUiTest {
        val seen = mutableListOf<Pair<Int?, Boolean>>()
        val canvas: CrewCanvas = { document, obscured, _, placeholder, modifier ->
            seen += document?.key to obscured
            Box(modifier) { if (document != null) placeholder() }
        }
        val open = base.copy(
            customise = CustomiseState(
                designLoading = false,
                design = CanvasDocument(key = 7, html = "<html></html>"),
                logoSizeDraft = 160,
            ),
        )
        var covered by mutableStateOf(false)
        setContent {
            ZillitTheme {
                CrewListScreen(
                    state = open,
                    visibleUnits = { Roster },
                    onEvent = {},
                    slots = CrewListSlots(canvas = canvas),
                    canvasCovered = covered,
                )
            }
        }
        waitForIdle()
        assertEquals(7 to false, seen.last())

        // The frame's rights prompt, or the tool's toast, is up.
        covered = true
        waitForIdle()
        assertEquals(7 to true, seen.last())
    }

    @Test
    fun `the admin editors render their rows and fields`() = runComposeUiTest {
        val admin = base.copy(
            departments = DepartmentOrderState(
                order = DepartmentOrder(
                    listOf(OrderedDepartment("d1", "Camera Department"), OrderedDepartment("d2", "Sound")),
                ),
                loading = false,
            ),
            company = CompanyEditorState(
                details = CompanyDetails(name = "Take One Films", number = "0123"),
                loading = false,
            ),
        )
        setContent {
            ZillitTheme { CrewListScreen(state = admin, visibleUnits = { Roster }, onEvent = {}) }
        }
        onNodeWithText("Camera Department").assertExists()
        onNodeWithText("BRAND LOGO").assertExists()
        onNodeWithText("COMPANY INFORMATION").assertExists()
        onAllNodesWithText("Take One Films").assertCountEquals(2)
    }

    @Test
    fun `search prunes the sheet down to matching people`() = runComposeUiTest {
        val searched = base.copy(query = "ravi")
        setContent {
            ZillitTheme {
                CrewListScreen(state = searched, visibleUnits = { Roster.search("ravi") { it } }, onEvent = {})
            }
        }
        onNodeWithText("Ravi Mehta").assertExists()
        onAllNodesWithText("Aisha Khan").assertCountEquals(0)
    }
}

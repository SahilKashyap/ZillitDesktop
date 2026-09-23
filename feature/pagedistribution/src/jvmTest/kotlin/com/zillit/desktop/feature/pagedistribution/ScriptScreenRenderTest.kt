package com.zillit.desktop.feature.pagedistribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.OpenFolder
import com.zillit.desktop.feature.pagedistribution.ui.UploadEditor
import com.zillit.desktop.feature.pagedistribution.ui.script.sceneProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Composes the Script & Pages page in every state a reader meets: the full
 * script card with its More menu, the tab chips, the scene grid with its
 * badges and search strip, the search drawer, an open scene's pages, the
 * two upload forms, and the history. A crash in any of these is what only a
 * render test finds.
 */
@OptIn(ExperimentalTestApi::class)
class ScriptScreenRenderTest {

    private val viewer = DistributionViewer(
        userId = "u1",
        canView = true,
        canPost = true,
        canDownload = true,
        canPublish = true,
        isAdmin = true,
        isTelevision = true,
        ready = true,
    )

    private val script = DistDocument(
        id = "s1",
        createdMs = 1_786_950_000_000,
        createdBy = "u2",
        episode = "2",
        sceneNumber = "",
        pageNumber = "",
        colour = "",
        dateMs = 1_786_000_000_000,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "Shooting draft",
        originalName = "script_v3.pdf",
        deleted = false,
        replaced = false,
        attachment = StoredPdf("k", "script_v3.pdf", "b", "r", "2048576"),
    )

    private val page = script.copy(
        id = "p1",
        sceneNumber = "12",
        pageNumber = "4",
        colour = "#FFB6C1",
        dateMs = 0,
        userSelectedDateMs = 1_786_000_000_000,
        name = "",
        originalName = "scene12_pink.pdf",
        attachment = StoredPdf("k2", "scene12_pink.pdf", "b", "r", "10240"),
    )

    private val folders = listOf(
        DistFolder("f1", "12", 1_786_950_000_000, 0L, null, "", deleted = false),
        DistFolder("f2", "13A", 1_786_960_000_000, 0L, null, "", deleted = false),
    )

    private fun base() = DistributionUiState(
        tool = DistributionTool.ScriptDistribution,
        viewer = viewer,
        documents = listOf(script),
        folders = folders,
    )

    private fun pages() = base().copy(activeTabKey = "page")

    @Test
    fun `the full script card draws its facts and the tab chips in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        DistributionScreen(
                            state = base().copy(tabUnread = mapOf("full_script" to 1, "page" to 3)),
                            onEvent = {},
                            resolveUser = { "Aisha Khan (Writer)" },
                        )
                    }
                }
                onNodeWithText("Script & Pages Distribution").assertExists()
                onNodeWithText("4 unread").assertExists()
                onNodeWithText("Full Script").assertExists()
                onNodeWithText("Pages").assertExists()
                onNodeWithText("Aisha Khan (Writer)").assertExists()
                onNodeWithText("Episode:").assertExists()
                onNodeWithText("Script Date:").assertExists()
                onNodeWithText("Shooting draft").assertExists()
                onNodeWithText("script_v3.pdf  ·  2.0 MB").assertExists()
                onNodeWithText("Replace script").assertExists()
            }
        }
    }

    @Test
    fun `the full script More menu offers the web's actions in its order`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(state = base(), onEvent = { events += it }, resolveUser = { null })
                }
            }
            onNodeWithText("More +").performClick()
            listOf("View", "Publish to Doc Distribution", "Replace", "Download", "Download Count", "View Count")
                .forEach { onAllNodesWithText(it).onFirst().assertIsDisplayed() }
            onAllNodesWithText("Delete").assertCountEquals(0)
            onNodeWithText("Replace").performClick()
            assertTrue(events.any { it == DistributionEvent.PickPdf(replaces = script) }, "$events")
        }
    }

    @Test
    fun `the scene grid draws every folder with its badge and the search strip`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pages().copy(folderUnread = mapOf("12" to 2)),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("12").assertExists()
            onNodeWithText("13A").assertExists()
            onNodeWithText("2").assertExists()
            onNodeWithText("Search by scene number").assertExists()
            onNodeWithText("Search by episode").assertExists()
            onNodeWithText("Search by color").assertExists()
            onNodeWithText("Upload Page").assertExists()
            onNodeWithText("13A").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.OpenFolder("13A")), events)
        }
    }

    @Test
    fun `the search drawer groups the matches by scene and opens one`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pages().copy(
                            searchScene = "1",
                            searchResults = listOf(
                                page,
                                page.copy(id = "p2"),
                                page.copy(id = "p3", sceneNumber = "13A"),
                            ),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Search").assertExists()
            onNodeWithText("2 pages match").assertExists()
            onNodeWithText("1 page match").assertExists()
            onAllNodesWithText("View Details").assertCountEquals(2)
            onNodeWithText("2 pages match").performClick()
            assertTrue(events.contains(DistributionEvent.OpenFolder("12")), "$events")
            onNodeWithContentDescription("Close search").performClick()
            assertTrue(events.contains(DistributionEvent.ClearSearch), "$events")
        }
    }

    @Test
    fun `an open scene draws the page card and its More menu deletes but never replaces`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pages().copy(openFolder = OpenFolder(folders.first(), documents = listOf(page))),
                        onEvent = { events += it },
                        resolveUser = { "Aisha Khan (Writer)" },
                    )
                }
            }
            onNodeWithText("Scene No : 12  ·  1 page").assertExists()
            onNodeWithText("Scene Number:").assertExists()
            onNodeWithText("Page number:").assertExists()
            onNodeWithText("Page Date:").assertExists()
            onNodeWithText("Upload here").assertExists()
            onNodeWithText("More +").performClick()
            listOf("View", "Publish to Doc Distribution", "Delete", "Download", "Download Count", "View Count")
                .forEach { onAllNodesWithText(it).onFirst().assertIsDisplayed() }
            onAllNodesWithText("Replace").assertCountEquals(0)
            onNodeWithText("Delete").performClick()
            assertTrue(events.any { it == DistributionEvent.Delete(page) }, "$events")
        }
    }

    @Test
    fun `history hides the write actions and the chips but keeps view and download`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            mode = ListMode.History,
                            documents = listOf(script.copy(replaced = true)),
                            tabUnread = mapOf("page" to 3),
                        ),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onAllNodesWithText("Replace script").assertCountEquals(0)
            onAllNodesWithText("3").assertCountEquals(0)
            onNodeWithText("Replaced").assertExists()
            onNodeWithText("More +").performClick()
            onAllNodesWithText("View").onFirst().assertIsDisplayed()
            onAllNodesWithText("Download").onFirst().assertIsDisplayed()
            onAllNodesWithText("Replace").assertCountEquals(0)
            onAllNodesWithText("Publish to Doc Distribution").assertCountEquals(0)
            onAllNodesWithText("View Count").assertCountEquals(0)
        }
    }

    @Test
    fun `the page upload form asks for the scene and the episode and takes a colour`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pages().copy(upload = UploadEditor(fileName = "p.pdf", bytes = ByteArray(1500))),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Filed under its scene number").assertExists()
            onNodeWithText("File size: 1.5 KB").assertExists()
            onNodeWithText("Scene number *").assertExists()
            onNodeWithText("Starts with a number, up to 15 characters.").assertExists()
            onNodeWithText("Page Number (Optional)").assertExists()
            onNodeWithText("Page date (Optional)").assertExists()
            onNodeWithText("Episode Number *").assertExists()
            onNodeWithText("Select a color (Optional)").assertExists()
            onNodeWithText("WHITE").assertExists()
        }
    }

    @Test
    fun `the full script upload form has no name and no colour and replaces when a script is up`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            upload = UploadEditor(fileName = "s.pdf", bytes = ByteArray(1500), replaces = "s1"),
                        ),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Replace full script").assertExists()
            onNodeWithText("Replaces the current script").assertExists()
            onNodeWithText("Script date (Optional)").assertExists()
            onAllNodesWithText("Scene number *").assertCountEquals(0)
            onAllNodesWithText("Select a color (Optional)").assertCountEquals(0)
            onAllNodesWithText("Name (optional)").assertCountEquals(0)
        }
    }

    @Test
    fun `the scene rule is the web's`() {
        assertEquals("Scene number is required.", sceneProblem("  "))
        assertEquals("Scene Number should start with a number", sceneProblem("A1"))
        assertEquals("Scene Number not greater than 15 characters", sceneProblem("1234567890123456"))
        assertNull(sceneProblem("12A"))
    }
}

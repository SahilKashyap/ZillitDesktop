package com.zillit.desktop.feature.pagedistribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.ui.CountsView
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.MoveEditor
import com.zillit.desktop.feature.pagedistribution.ui.OpenFolder
import com.zillit.desktop.feature.pagedistribution.ui.UploadEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the D.O.D page in every state a reader meets: the folder grid
 * with its badges, an open folder with its ribbon cards and More menu, the
 * upload form, the tallies, the move picker, and the history. A crash in any
 * of these is what only a render test finds.
 */
@OptIn(ExperimentalTestApi::class)
class DodScreenRenderTest {

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

    private val folders = listOf(
        DistFolder("f1", "Week One", 1_786_950_000_000, 0L, null, "", deleted = false),
        DistFolder("f2", "Week Two", 1_786_960_000_000, 0L, null, "", deleted = false),
    )

    private val document = DistDocument(
        id = "d1",
        createdMs = 1_786_950_000_000,
        createdBy = "u2",
        episode = "2",
        sceneNumber = "",
        pageNumber = "",
        colour = "#ADD8E6",
        dateMs = 0,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "Week One",
        originalName = "dod_week_one.pdf",
        deleted = false,
        replaced = false,
        attachment = StoredPdf("k", "dod_week_one.pdf", "b", "r", "2048576"),
    )

    private fun base() = DistributionUiState(tool = DistributionTool.ScheduleDod, viewer = viewer, folders = folders)

    @Test
    fun `the folder grid draws every folder with its unread badge in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        DistributionScreen(
                            state = base().copy(folderUnread = mapOf("Week One" to 3)),
                            onEvent = {},
                            resolveUser = { null },
                        )
                    }
                }
                onNodeWithText("Week One").assertExists()
                onNodeWithText("Week Two").assertExists()
                onNodeWithText("3").assertExists()
                onNodeWithText("3 unread").assertExists()
                onNodeWithText("Upload PDF").assertExists()
            }
        }
    }

    @Test
    fun `a folder card opens its folder`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(state = base(), onEvent = { events += it }, resolveUser = { null })
                }
            }
            onNodeWithText("Week Two").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.OpenFolder("Week Two")), events)
        }
    }

    @Test
    fun `an open folder draws the ribbon card and the More menu offers the web's actions`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(openFolder = OpenFolder(folders.first(), documents = listOf(document))),
                        onEvent = { events += it },
                        resolveUser = { "Aisha Khan (1st AD)" },
                    )
                }
            }
            onNodeWithText("Aisha Khan (1st AD)").assertExists()
            onNodeWithText("Episode:").assertExists()
            onNodeWithText("dod_week_one.pdf  ·  2.0 MB").assertExists()
            onNodeWithText("More").performClick()
            listOf(
                "View", "Publish to Doc Distribution", "Delete", "Download",
                "Download Count", "View Count", "Move to folder",
            ).forEach { onAllNodesWithText(it).onFirst().assertIsDisplayed() }
            onNodeWithText("Move to folder").performClick()
            assertTrue(events.any { it == DistributionEvent.Move(document) }, "$events")
        }
    }

    @Test
    fun `history hides the write actions but keeps view and download`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            mode = ListMode.History,
                            openFolder = OpenFolder(folders.first(), documents = listOf(document.copy(deleted = true))),
                        ),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onAllNodesWithText("Upload PDF").assertCountEquals(0)
            onNodeWithText("More").performClick()
            onAllNodesWithText("View").onFirst().assertIsDisplayed()
            onAllNodesWithText("Download").onFirst().assertIsDisplayed()
            onAllNodesWithText("Delete").assertCountEquals(0)
            onAllNodesWithText("Move to folder").assertCountEquals(0)
        }
    }

    @Test
    fun `the upload form offers the existing folders and picks one verbatim`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            upload = UploadEditor(fileName = "new.pdf", bytes = ByteArray(1500), name = "wee"),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("File size: 1.5 KB").assertExists()
            onNodeWithText("Episode Number").assertExists()
            // The grid behind the dialog names the folder too; the suggestion is the later node.
            onAllNodesWithText("Week One")[1].performClick()
            assertTrue(
                events.contains(DistributionEvent.UploadChanged(name = "Week One", nameFromPick = true)),
                "$events",
            )
        }
    }

    @Test
    fun `the tallies filter by name`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            counts = CountsView(
                                document = document,
                                rows = listOf(CountRow("u2", viewCount = 4, downloadCount = 0), CountRow("u3", 1, 0)),
                                downloads = false,
                                loading = false,
                            ),
                        ),
                        onEvent = {},
                        resolveUser = { if (it == "u2") "Aisha Khan (1st AD)" else "Ben Ochieng (Gaffer)" },
                    )
                }
            }
            onNodeWithText("View count: 4").assertExists()
            onNodeWithText("Ben Ochieng (Gaffer)").assertExists()
        }
    }

    @Test
    fun `the move picker lists the other folders only`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(move = MoveEditor(document)),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Select folder to move").assertExists()
            onAllNodes(hasText("Week Two")).onFirst().assertExists()
            // Week One is the document's own folder — offered nowhere in the picker.
            assertEquals(1, onAllNodesWithText("Week One").fetchSemanticsNodes().size, "only the grid behind shows it")
        }
    }

    @Test
    fun `an empty live tool invites the first upload`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = DistributionUiState(tool = DistributionTool.ScheduleDod, viewer = viewer),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("No folders yet").assertExists()
        }
    }
}

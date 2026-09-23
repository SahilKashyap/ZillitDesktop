package com.zillit.desktop.feature.pagedistribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
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
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.ui.DistributionDates
import com.zillit.desktop.feature.pagedistribution.ui.DistributionEvent
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import com.zillit.desktop.feature.pagedistribution.ui.OpenFolder
import com.zillit.desktop.feature.pagedistribution.ui.UploadEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composes the Schedule Full & One Line page in every state a reader
 * meets: the schedule card with its More menu, the tab chips, the scene
 * folder grid with its badges, an open folder's page cards, the search
 * drawer, the two upload forms and their follow-up questions, and the
 * history. A crash in any of these is what only a render test finds.
 */
@OptIn(ExperimentalTestApi::class)
class ScheduleScreenRenderTest {

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

    private val schedule = DistDocument(
        id = "s1",
        createdMs = 1_786_950_000_000,
        createdBy = "u2",
        episode = "3",
        sceneNumber = "",
        pageNumber = "",
        colour = "",
        dateMs = 1_787_000_000_000,
        revisionDateMs = 0,
        userSelectedDateMs = 0,
        scheduleType = null,
        name = "",
        originalName = "shooting_schedule_v4.pdf",
        deleted = false,
        replaced = false,
        attachment = StoredPdf("k", "shooting_schedule_v4.pdf", "b", "r", "2048576"),
    )

    private val page = schedule.copy(
        id = "p1",
        sceneNumber = "12",
        pageNumber = "4B",
        colour = "#FFB6C1",
        userSelectedDateMs = 1_787_000_000_000,
        scheduleType = ScheduleType.OneLinePages,
        originalName = "scene_12.pdf",
        attachment = StoredPdf("k2", "scene_12.pdf", "b", "r", "10240"),
    )

    private val folders = listOf(
        DistFolder("f1", "12", 1_786_950_000_000, 0L, ScheduleType.OneLinePages, "#FFB6C1", deleted = false),
        DistFolder("f2", "7A", 1_786_960_000_000, 0L, ScheduleType.FullSchedulePages, "#FFFFFF", deleted = false),
    )

    private fun base() = DistributionUiState(
        tool = DistributionTool.ScheduleDistribution,
        viewer = viewer,
        documents = listOf(schedule),
        folders = folders,
        tabUnread = mapOf("full_script" to 1, "page" to 4),
    )

    private fun pagesTab() = base().copy(activeTabKey = "page", documents = emptyList())

    @Test
    fun `the schedule tab draws the ribbon card, the chips and the More menu in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        DistributionScreen(state = base(), onEvent = {}, resolveUser = { "Aisha Khan (1st AD)" })
                    }
                }
                onNodeWithText("Schedule Full & One Line").assertExists()
                onNodeWithText("5 unread").assertExists()
                onNodeWithText("Episode 3").assertExists()
                onNodeWithText("shooting_schedule_v4.pdf").assertExists()
                onNodeWithText("Aisha Khan (1st AD)").assertExists()
                onNodeWithText("Replace PDF").assertExists()
                onNodeWithText("More").performClick()
                listOf("View", "Publish to Doc Distribution", "Replace", "Download", "Download Count", "View Count")
                    .forEach { onNodeWithText(it).assertExists() }
                onAllNodesWithText("Delete").assertCountEquals(0)
            }
        }
    }

    @Test
    fun `the pages tab draws every scene folder with its kind and badge and opens one`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(folderUnread = mapOf("12" to 2)),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Search by scene number").assertExists()
            onNodeWithText("Search by episode").assertExists()
            onNodeWithText("12").assertExists()
            onNodeWithText("7A").assertExists()
            onNodeWithText("One Line Schedule Pages").assertExists()
            onNodeWithText("Full Schedule Pages").assertExists()
            onNodeWithText("2").assertExists()
            onNodeWithText("7A").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.OpenFolder("7A")), events)
        }
    }

    @Test
    fun `an open folder draws the page card with its facts and the page actions`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(
                            openFolder = OpenFolder(folders.first(), documents = listOf(page), exhausted = true),
                        ),
                        onEvent = { events += it },
                        resolveUser = { "Ben Ortiz (2nd AD)" },
                    )
                }
            }
            onNodeWithText("Pages · Scene 12").assertExists()
            onNodeWithText("4B").assertExists()
            onNodeWithText("Ben Ortiz (2nd AD)").assertExists()
            onNodeWithText("Uploaded on: ${DistributionDates.dateTime(page.createdMs)}").assertExists()
            onNodeWithText("More").performClick()
            listOf("View", "Publish to Doc Distribution", "Delete", "Download", "Download Count", "View Count")
                .forEach { onNodeWithText(it).assertExists() }
            onAllNodesWithText("Replace").assertCountEquals(0)
            onNodeWithText("Delete").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.Delete(page)), events)
        }
    }

    @Test
    fun `the search drawer groups the matches by scene and opens one`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            val other = page.copy(id = "p2", sceneNumber = "7A", scheduleType = ScheduleType.FullSchedulePages)
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(
                            searchScene = "1",
                            searchResults = listOf(page, page.copy(id = "p3"), other),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Search").assertExists()
            onNodeWithText("Scene number: 12").assertExists()
            onNodeWithText("2 pages match").assertExists()
            onNodeWithText("Scene number: 7A").assertExists()
            onNodeWithText("Scene number: 7A").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.OpenFolder("7A")), events)
        }
    }

    @Test
    fun `a new page asks which kind it is before it sends`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(
                            folders = emptyList(),
                            upload = UploadEditor(
                                fileName = "scene_12.pdf",
                                bytes = ByteArray(2048),
                                sceneNumber = "12",
                                episode = "3",
                                colour = PageColour.Pink,
                            ),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Upload pages").assertExists()
            onNodeWithText("Page colour (optional)").assertExists()
            onNodeWithText("PINK").assertExists()
            onNodeWithText("Upload").performClick()
            onNodeWithText("Please select an option").assertIsDisplayed()
            onNodeWithText("Ok").performClick()
            onNodeWithText("Please select an option before uploading.").assertExists()
            assertTrue(events.none { it is DistributionEvent.SubmitUpload })
            onNodeWithText("One Line Schedule Pages").performClick()
            assertEquals(
                DistributionEvent.UploadChanged(scheduleType = ScheduleType.OneLinePages),
                events.last(),
            )
        }
    }

    @Test
    fun `a new page refuses to leave the form without a scene number`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(
                            upload = UploadEditor(fileName = "scene.pdf", bytes = ByteArray(8), episode = "3"),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Upload").performClick()
            onNodeWithText("Scene number is required.").assertExists()
            onAllNodesWithText("Please select an option").assertCountEquals(0)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `a replaced page confirms its colour first`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(
                            upload = UploadEditor(
                                fileName = "scene_12_v2.pdf",
                                bytes = ByteArray(8),
                                replaces = "p1",
                                sceneNumber = "12",
                                colour = PageColour.Blue,
                            ),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Replace page").assertExists()
            onNodeWithText("Replace").performClick()
            onNodeWithText("Page colour is BLUE. Do you want to continue with BLUE?").assertIsDisplayed()
            onNodeWithText("Yes").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.SubmitUpload), events)
        }
    }

    @Test
    fun `a full schedule upload asks for the date and the episode and sends at once`() {
        runComposeUiTest {
            val events = mutableListOf<DistributionEvent>()
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            upload = UploadEditor(
                                fileName = "sched.pdf",
                                bytes = ByteArray(8),
                                replaces = "s1",
                                episode = "3",
                            ),
                        ),
                        onEvent = { events += it },
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Replace full schedule").assertExists()
            onNodeWithText("Schedule date (optional)").assertExists()
            onNodeWithText("Episode Number").assertExists()
            onAllNodesWithText("Page colour (optional)").assertCountEquals(0)
            onNodeWithText("Replace").performClick()
            assertEquals(listOf<DistributionEvent>(DistributionEvent.SubmitUpload), events)
        }
    }

    @Test
    fun `history shows the notice and keeps view and download only`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = base().copy(
                            mode = ListMode.History,
                            documents = listOf(schedule.copy(replaced = true)),
                        ),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("Schedule Full & One Line History").assertExists()
            onAllNodesWithText("Replaced").onFirst().assertExists()
            onAllNodesWithText("Upload PDF").assertCountEquals(0)
            onNodeWithText("More").performClick()
            onNodeWithText("View").assertExists()
            onNodeWithText("Download").assertExists()
            listOf("Replace", "Publish to Doc Distribution", "Download Count", "View Count").forEach {
                onAllNodesWithText(it).assertCountEquals(0)
            }
            val notice = onAllNodesWithText("Records of deleted messages", substring = true)
            assertTrue(notice.fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun `an empty live pages tab invites the first upload`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = pagesTab().copy(folders = emptyList()),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
            onNodeWithText("No Data found").assertExists()
            assertTrue(onAllNodesWithText("Upload PDF").fetchSemanticsNodes().size >= 2)
            onAllNodesWithText("Search by scene number").assertCountEquals(0)
        }
    }
}

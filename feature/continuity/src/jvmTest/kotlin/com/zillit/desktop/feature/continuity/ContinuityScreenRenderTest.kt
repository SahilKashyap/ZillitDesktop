package com.zillit.desktop.feature.continuity

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityCrewMember
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityUnread
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo
import com.zillit.desktop.feature.continuity.ui.ContinuityScreen
import com.zillit.desktop.feature.continuity.ui.ContinuityUiState
import com.zillit.desktop.feature.continuity.ui.DepartmentPick
import com.zillit.desktop.feature.continuity.ui.DetailEditor
import com.zillit.desktop.feature.continuity.ui.ForwardSheet
import com.zillit.desktop.feature.continuity.ui.OpenFolder
import com.zillit.desktop.feature.continuity.ui.SceneEditor
import kotlin.test.Test

/** Composes the real continuity board on each tab and every dialog it opens, in both themes. */
@OptIn(ExperimentalTestApi::class)
class ContinuityScreenRenderTest {

    private val viewer =
        ContinuityViewer(userId = "u1", departmentId = "d1", canPost = true, canDownload = true, ready = true)

    private val image = ContinuityAttachment("k/a.jpg", "k/a.jpg", "image", "jpg", "a.jpg", "b", "r", fileSize = "2048")
    private val video = ContinuityAttachment("k/v.mp4", "", "video", "mp4", "v.mp4", "b", "r")
    private val pdf = ContinuityAttachment("k/d.pdf", "", "document", "pdf", "notes.pdf", "b", "r")

    private fun scene(id: String, attachment: ContinuityAttachment?, dept: String = "d1") = ContinuityScene(
        id = id, uniqueId = id, sceneNumber = "12", episode = "3", notes = "Blue jacket, left pocket torn",
        actorName = "", talentInfo = listOf(TalentInfo("Prop", "Cup")), attachment = attachment,
        departmentId = dept, uploadedBy = "u1", visibleIntra = true, visibleAll = true,
        deletedIntra = false, deletedAll = false, createdMs = 1_000, updatedMs = 0,
    )

    private val cards = listOf(scene("a", image), scene("b", video, dept = "d2"), scene("c", pdf), scene("d", null))

    private fun compose(
        state: ContinuityUiState,
        dark: Boolean,
        check: androidx.compose.ui.test.ComposeUiTest.() -> Unit = {},
    ) = runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = dark) {
                    ContinuityScreen(
                        state = state,
                        onEvent = {},
                        loadImage = { _, _ -> null },
                        resolveUser = { "Aisha Khan" },
                        formatDate = { "26 Aug 2026" },
                    )
                }
            }
            check()
        }

    @Test
    fun `each tab composes in both themes with folders and their unread`() {
        ContinuityTab.entries.forEach { tab ->
            listOf(false, true).forEach { dark ->
                compose(
                    ContinuityUiState(
                        viewer = viewer,
                        tab = tab,
                        folders = listOf("12", "7", "70A"),
                        unread = ContinuityUnread(
                            tabs = mapOf(tab.readSegment to 2),
                            folders = mapOf(tab.readSegment to mapOf("12" to 2)),
                        ),
                    ),
                    dark,
                ) {
                    onNodeWithText("Continuity scene(s) from ${tab.label}").assertExists()
                    onNodeWithText("Scene No - 12").assertExists()
                    onNodeWithText("2 unread").assertExists()
                }
            }
        }
    }

    @Test
    fun `the empty board and the blocked viewer compose`() {
        compose(ContinuityUiState(viewer = viewer), dark = false) {
            onNodeWithText("No scenes available.").assertExists()
        }
        compose(ContinuityUiState(viewer = viewer.copy(canView = false)), dark = true) {
            onNodeWithText("You do not have access to the Continuity tool.").assertExists()
        }
    }

    @Test
    fun `the department pick composes with its rows`() {
        compose(
            ContinuityUiState(
                viewer = viewer,
                tab = ContinuityTab.AllDepartments,
                departmentNames = mapOf("d2" to "Camera Department"),
                pick = DepartmentPick(
                    sceneFolder = "12",
                    departments = listOf(ContinuityDepartment("d2", "camera_label"), ContinuityDepartment("d3", "Art")),
                    loading = false,
                ),
                unread = ContinuityUnread(departments = mapOf("12" to mapOf("d2" to 4))),
            ),
            dark = false,
        ) {
            onNodeWithText("Department list continuity").assertExists()
            onNodeWithText("Camera Department").assertExists()
        }
    }

    @Test
    fun `the cards dialog composes on both boards, plain and while ticking`() {
        listOf(ContinuityTab.MyDepartment, ContinuityTab.AllDepartments).forEach { tab ->
            listOf(false, true).forEach { selecting ->
                compose(
                    ContinuityUiState(
                        viewer = viewer,
                        tab = tab,
                        departmentNames = mapOf("d2" to "Camera Department"),
                        open = OpenFolder(
                            tab = tab,
                            sceneFolder = "12",
                            department = ContinuityDepartment("d2", "Camera")
                                .takeIf { tab == ContinuityTab.AllDepartments },
                            scenes = cards,
                            loading = false,
                            selecting = selecting,
                            selected = setOf("a"),
                        ),
                    ),
                    dark = selecting,
                ) {
                    onNodeWithText("Search by Scene notes / Scene Number").assertExists()
                    if (selecting) onNodeWithText("1 selected").assertExists()
                }
            }
        }
    }

    @Test
    fun `the forward ask and both drawer steps compose`() {
        val open = OpenFolder(
            ContinuityTab.MyDepartment, "12", scenes = cards, loading = false, selecting = true, selected = setOf("a"),
        )
        compose(ContinuityUiState(viewer = viewer, open = open, forwardIntent = true), dark = false) {
            onNodeWithText(
                "Please note, doing this will make your material visible to all. Do you still want to proceed?",
            ).assertExists()
        }
        val crew = listOf(ContinuityCrewMember("u2", "Aisha Khan", "Gaffer"), ContinuityCrewMember("u3", "Ben Ortiz"))
        compose(ContinuityUiState(viewer = viewer, open = open, crew = crew, forward = ForwardSheet()), dark = false) {
            onNodeWithText("Everyone on the production sees these cards under All Departments.").assertExists()
            onNodeWithText("Select Users").assertExists()
        }
        compose(
            ContinuityUiState(
                viewer = viewer,
                open = open,
                crew = crew,
                forward = ForwardSheet(
                    step = ForwardSheet.Step.Users,
                    selectedUsers = setOf("u2"),
                    error = "Could not reach Ben Ortiz",
                ),
            ),
            dark = true,
        ) {
            onNodeWithText("Aisha Khan").assertExists()
            onNodeWithText("Gaffer").assertExists()
            // A refused send is told inside the sheet, never as a page banner behind it.
            onNodeWithText("Could not reach Ben Ortiz").assertExists()
        }
    }

    @Test
    fun `the viewer composes for an image, a video, a pdf and a bare card`() {
        cards.forEachIndexed { i, scene ->
            compose(ContinuityUiState(viewer = viewer, viewing = scene), dark = i % 2 == 0) {
                onNodeWithText("View More").assertExists()
            }
        }
    }

    @Test
    fun `view more composes on both boards`() {
        compose(ContinuityUiState(viewer = viewer, details = cards.first()), dark = false) {
            onNodeWithText("Description").assertExists()
            onNodeWithText("More Info").assertExists()
        }
        compose(
            ContinuityUiState(
                viewer = viewer,
                tab = ContinuityTab.AllDepartments,
                departmentNames = mapOf("d2" to "Camera Department"),
                open = OpenFolder(ContinuityTab.AllDepartments, "12", scenes = cards, loading = false),
                details = cards[1],
            ),
            dark = true,
        ) {
            onNodeWithText("Department Name -").assertExists()
        }
    }

    @Test
    fun `the editor composes for a new upload, an edit, and the detail sub-dialog`() {
        val files = listOf(
            PickedContinuityFile("a.jpg", "image/jpeg", ByteArray(0)),
            PickedContinuityFile("v.mp4", "video/mp4", ByteArray(0)),
        )
        compose(
            ContinuityUiState(
                viewer = viewer.copy(isTelevision = true),
                editor = SceneEditor(files = files, error = "Fill the Scene Number"),
            ),
            dark = false,
        ) {
            onNodeWithText("Add Scene Details").assertExists()
            onNodeWithText("Add more details").assertExists()
            onNodeWithText("Episode No").assertExists()
            // The form rule it broke sits inside the dialog, where the scrim cannot dim it.
            onNodeWithText("Fill the Scene Number").assertExists()
        }
        compose(
            ContinuityUiState(
                viewer = viewer,
                editor = SceneEditor(
                    editingId = "a",
                    draft = SceneDraft("12", "", "notes", listOf(TalentInfo("Prop", "Cup"))),
                    saving = true,
                ),
            ),
            dark = true,
        ) {
            onNodeWithText("Edit Details").assertExists()
            onNodeWithText("Prop").assertExists()
        }
        compose(
            ContinuityUiState(
                viewer = viewer,
                editor = SceneEditor(files = files, detail = DetailEditor(label = "Prop")),
            ),
            dark = false,
        ) {
            onNodeWithText("Enter Description").assertExists()
        }
    }

    @Test
    fun `the delete ask composes`() {
        compose(
            ContinuityUiState(
                viewer = viewer,
                open = OpenFolder(ContinuityTab.MyDepartment, "12", scenes = cards, loading = false),
                confirmDelete = cards.first(),
            ),
            dark = false,
        ) {
            onNodeWithText("Are you sure you want to delete this Item ?").assertExists()
        }
    }
}

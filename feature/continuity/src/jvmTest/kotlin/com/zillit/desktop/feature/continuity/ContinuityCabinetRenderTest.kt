package com.zillit.desktop.feature.continuity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.cabinet.CabinetViewers
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.continuity.cabinet.ContinuityCabinetViewer
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The File Cabinet's Continuity viewer composed for real, over a fake service. */
@OptIn(ExperimentalTestApi::class)
class ContinuityCabinetRenderTest {

    private val image = ContinuityAttachment("k/a.jpg", "k/a.jpg", "image", "jpg", "a.jpg", "b", "r")

    private val card = ContinuityScene(
        id = "a", uniqueId = "a", sceneNumber = "12", episode = "", notes = "Blue jacket", actorName = "",
        talentInfo = emptyList(), attachment = image, departmentId = "d1", uploadedBy = "u1",
        visibleIntra = true, visibleAll = true, deletedIntra = false, deletedAll = false,
        createdMs = 100, updatedMs = 0,
    )

    private class FakeRepo(private val cards: List<ContinuityScene>) : ContinuityRepository {
        override suspend fun folders(tab: ContinuityTab) =
            ZillitResult.Success(cards.filter { it.shownOn(tab) }.map { it.sceneNumber }.distinct())

        override suspend fun scenes(tab: ContinuityTab, sceneFolder: String, departmentId: String?, beforeMs: Long) =
            ZillitResult.Success(cards.filter { it.sceneNumber == sceneFolder && it.cursorMs < beforeMs })

        override suspend fun departments(sceneFolder: String) =
            ZillitResult.Success(listOf(ContinuityDepartment("d1", "Art")))

        override suspend fun create(draft: SceneDraft, attachment: ContinuityAttachment, uniqueId: String) =
            ZillitResult.Success(Unit)

        override suspend fun update(id: String, draft: SceneDraft) = ZillitResult.Success(null)
        override suspend fun share(ids: List<String>, sceneFolder: String) = ZillitResult.Success(Unit)
        override suspend fun delete(tab: ContinuityTab, id: String) = ZillitResult.Success(Unit)
    }

    private object NoTransfer : ContinuityTransfer {
        override suspend fun upload(file: PickedContinuityFile) = error("a cabinet look never uploads")
        override suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean) =
            ZillitResult.Success(ByteArray(0))

        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private fun viewer(cards: List<ContinuityScene>) = ContinuityCabinetViewer(
        viewModel = ContinuityViewModel(
            repository = FakeRepo(cards),
            transfer = NoTransfer,
            resolveViewer = {
                ContinuityViewer(userId = "u1", departmentId = "d1", canPost = true, canDownload = true, ready = true)
            },
            departmentName = { null },
            newUniqueId = { "uid" },
            nowMillis = { 1_000L },
        ),
        loadImage = { _, _ -> null },
        resolveUser = { "Aisha Khan" },
        formatDate = { "26 Aug 2026" },
    )

    private fun render(
        viewer: ContinuityCabinetViewer,
        dark: Boolean,
        check: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize()) { viewer.Content(null, Modifier.fillMaxSize()) }
            }
        }
        waitUntil(timeoutMillis = LOAD_MS) {
            onAllNodesWithTextCount("Scene No - 12") > 0 || onAllNodesWithTextCount("No scenes available.") > 0
        }
        check()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `serves the continuity row, which does not expand`() {
        val v = viewer(emptyList())
        assertEquals("continuity", v.identifier)
        assertEquals(false, v.expands)
        assertNotNull(CabinetViewers(listOf(v))["continuity"])
    }

    @Test
    fun `the boards show their folders without the header, the search card or the paperclip`() {
        listOf(false, true).forEach { dark ->
            render(viewer(listOf(card)), dark) {
                onNodeWithText("Scene No - 12").assertExists()
                onNodeWithText("My Department").assertExists()
                assertEquals(0, onAllNodesWithTextCount("Upload"))
                assertEquals(0, onAllNodesWithTextCount("Continuity scene(s) from My Department"))
                assertEquals(0, onAllNodesWithTextCount("Refresh"))
            }
        }
    }

    @Test
    fun `an empty board says so and does not invite an upload`() {
        render(viewer(emptyList()), dark = false) {
            onNodeWithText("No scenes available.").assertExists()
            assertEquals(0, onAllNodesWithTextCount("Upload"))
        }
    }

    private companion object {
        const val LOAD_MS = 5_000L
    }
}

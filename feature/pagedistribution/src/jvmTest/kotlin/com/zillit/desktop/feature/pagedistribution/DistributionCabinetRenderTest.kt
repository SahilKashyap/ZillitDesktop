package com.zillit.desktop.feature.pagedistribution

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.pagedistribution.cabinet.cabinetReader
import com.zillit.desktop.feature.pagedistribution.cabinet.distributionCabinetViewers
import com.zillit.desktop.feature.pagedistribution.domain.CountRow
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTransfer
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.PdfPageImage
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The File Cabinet viewers composed for real.
 *
 * Two layers: the viewers end to end over a fake repository (what the
 * cabinet window shows once it has loaded), and every face on its own in
 * [ListMode.Cabinet] (the posting and history controls are simply not there).
 */
@OptIn(ExperimentalTestApi::class)
class DistributionCabinetRenderTest {

    private val doc = DistDocument(
        id = "d1", createdMs = 1_786_950_000_000, createdBy = "u2", episode = "", sceneNumber = "12",
        pageNumber = "3", colour = "#ADD8E6", dateMs = 1_786_000_000_000, revisionDateMs = 0,
        userSelectedDateMs = 0, scheduleType = null, name = "", originalName = "Archived.pdf",
        deleted = false, replaced = false, attachment = null,
    )
    private val folder = DistFolder(
        id = "f1", key = "Week One", createdMs = 1_786_950_000_000, revisionDateMs = 0,
        scheduleType = null, colour = "", deleted = false,
    )

    /** Everything answers at once; records the modes it was asked in. */
    private class FakeRepo(val doc: DistDocument, val folder: DistFolder) : DistributionRepository {
        val modes = mutableListOf<ListMode>()
        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(listOf(doc)).also { modes += mode }
        override suspend fun folders(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(listOf(folder.copy(key = if (tab.key == "dod") "Week One" else "12")))
                .also { modes += mode }
        override suspend fun folderDocuments(
            tab: DistributionTab, folderKey: String, beforeMs: Long, next: Boolean, mode: ListMode,
        ) = ZillitResult.Success(listOf(doc))
        override suspend fun search(
            tab: DistributionTab, sceneNumber: String?, episode: String?, colour: PageColour?, mode: ListMode,
        ) = ZillitResult.Success(emptyList<DistDocument>())
        override suspend fun document(tab: DistributionTab, id: String, action: ReadAction, mode: ListMode) =
            ZillitResult.Success(doc)
        override suspend fun upload(tab: DistributionTab, draft: UploadDraft, stored: StoredPdf, nowMs: Long) =
            ZillitResult.Success<DistDocument?>(null)
        override suspend fun delete(tab: DistributionTab, id: String) = ZillitResult.Success(Unit)
        override suspend fun move(tab: DistributionTab, id: String, folderName: String) = ZillitResult.Success(Unit)
        override suspend fun counts(tab: DistributionTab, id: String) = ZillitResult.Success(emptyList<CountRow>())
        override suspend fun publish(
            tool: DistributionTool, tab: DistributionTab, document: DistDocument, todayYmd: String,
        ) = ZillitResult.Success(Unit)
    }

    private object NoTransfer : DistributionTransfer {
        override suspend fun upload(storagePath: String, fileName: String, bytes: ByteArray) =
            ZillitResult.Success(StoredPdf("m", fileName, "b", "r", "1"))
        override suspend fun fetch(stored: StoredPdf) = ZillitResult.Success(ByteArray(0))
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<PdfPageImage>())
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    private val repo = FakeRepo(doc, folder)
    private val viewers = distributionCabinetViewers(
        repository = repo,
        transfer = NoTransfer,
        reader = { cabinetReader("u1", isTelevision = false) },
        nowMillis = { 1_786_950_000_000 },
        resolveUser = { "Aisha Khan (1st AD)" },
    ).associateBy { it.identifier }

    @Test
    fun `the three rows are served, two of them expanding into their tabs`() = runBlocking {
        assertEquals(setOf("script_distribution", "schedule_distribution", "dod"), viewers.keys)
        assertEquals(
            listOf("Full Script" to "full_script", "Pages" to "page"),
            viewers.getValue("script_distribution").entries().map { it.name to it.key },
        )
        assertEquals(
            listOf("full_script", "page", "oneline"),
            viewers.getValue("schedule_distribution").entries().map { it.key },
        )
        assertTrue(viewers.getValue("dod").let { !it.expands && it.entries().isEmpty() })
    }

    @Test
    fun `dod opens straight onto its folders with nothing to post`() = runComposeUiTest {
        setContent {
            ZillitTheme { viewers.getValue("dod").Content(null, Modifier) }
        }
        waitUntil(timeoutMillis = LOAD_MS) { onAllNodes("Week One") }
        onNodeWithText("Upload PDF").assertDoesNotExist()
        onNodeWithText("History").assertDoesNotExist()
        assertTrue(repo.modes.all { it == ListMode.Cabinet }, "every read is a cabinet read: ${repo.modes}")
    }

    @Test
    fun `script full script shows the archived script and no tab strip or paperclip`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                viewers.getValue("script_distribution").Content(CabinetEntry("full_script", "Full Script"), Modifier)
            }
        }
        waitUntil(timeoutMillis = LOAD_MS) { onAllNodes("Aisha Khan (1st AD)") }
        onNodeWithText("Upload Script").assertDoesNotExist()
        onNodeWithText("Pages").assertDoesNotExist()
        onNodeWithText("History").assertDoesNotExist()
    }

    @Test
    fun `script pages shows the scene folders without the search strip`() = runComposeUiTest {
        setContent {
            ZillitTheme { viewers.getValue("script_distribution").Content(CabinetEntry("page", "Pages"), Modifier) }
        }
        waitUntil(timeoutMillis = LOAD_MS) { onAllNodes("Scene No :") }
        onNodeWithText("Upload Page").assertDoesNotExist()
        onNodeWithText("Full Script").assertDoesNotExist()
    }

    @Test
    fun `schedule one line shows its document and the other two tabs are not drawn`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                viewers.getValue("schedule_distribution").Content(CabinetEntry("oneline", "One Line"), Modifier)
            }
        }
        waitUntil(timeoutMillis = LOAD_MS) { onAllNodes("Aisha Khan (1st AD)") }
        onNodeWithText("Upload PDF").assertDoesNotExist()
        onNodeWithText("Schedule Full").assertDoesNotExist()
    }

    @Test
    fun `every face composes in cabinet mode with its posting controls gone`() {
        listOf(
            DistributionTool.ScheduleDistribution,
            DistributionTool.ScriptDistribution,
            DistributionTool.ScheduleDod,
        ).forEach { tool ->
            tool.tabs.forEach { tab ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme {
                            DistributionScreen(
                                state = DistributionUiState(
                                    tool = tool,
                                    mode = ListMode.Cabinet,
                                    activeTabKey = tab.key,
                                    viewer = cabinetReader("u1", isTelevision = true),
                                ),
                                onEvent = {},
                                resolveUser = { null },
                            )
                        }
                    }
                    // D.O.D's empty folder grid has a title of its own.
                    if (tool != DistributionTool.ScheduleDod) onNodeWithText("No Data found").assertExists()
                    listOf("Upload PDF", "Upload Script", "Upload Page", "History", "Back to live", "Refresh")
                        .forEach { onNodeWithText(it).assertDoesNotExist() }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodes(text: String): Boolean =
        onAllNodesWithTextCount(text) > 0

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        const val LOAD_MS = 5_000L
    }
}

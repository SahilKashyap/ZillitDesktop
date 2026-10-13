package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.forward.ForwardField
import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forwarding into the film tools: what each destination asks for, and what
 * the answers become on the wire.
 *
 * The questions are the web's (`DocumentModal` as each tab configures it)
 * and the body is the tool's own upload, which is why the assertions below
 * are about the [UploadDraft] rather than about JSON: the wire itself is
 * already covered by the distribution module's own tests.
 */
class ForwardToolsTest {

    private val pdf = ForwardItem(
        text = "the sides for tomorrow",
        kind = ForwardKind.Document,
        file = ForwardFile(
            media = "chat/sides.pdf",
            name = "sides.pdf",
            subtype = "pdf",
            bucket = "b",
            region = "r",
            sizeBytes = 2048,
        ),
    )
    private val picture = ForwardItem(
        kind = ForwardKind.Image,
        file = ForwardFile(media = "chat/door.jpg", name = "door.jpg", subtype = "jpg", bucket = "b", region = "r"),
    )

    /** Records the one call a forward makes, draft and all. */
    private class UploadSpy : DistributionRepository {
        val uploads = mutableListOf<Triple<DistributionTab, UploadDraft, StoredPdf>>()

        override suspend fun upload(
            tab: DistributionTab,
            draft: UploadDraft,
            stored: StoredPdf,
            nowMs: Long,
        ): ZillitResult<DistDocument?> {
            uploads += Triple(tab, draft, stored)
            return ZillitResult.Success(null)
        }

        override suspend fun documents(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(emptyList<DistDocument>())

        override suspend fun folders(tab: DistributionTab, mode: ListMode) =
            ZillitResult.Success(emptyList<DistFolder>())
        override suspend fun folderDocuments(
            tab: DistributionTab,
            folderKey: String,
            beforeMs: Long,
            next: Boolean,
            mode: ListMode,
        ) = ZillitResult.Success(emptyList<DistDocument>())

        override suspend fun search(
            tab: DistributionTab,
            sceneNumber: String?,
            episode: String?,
            colour: PageColour?,
            mode: ListMode,
        ) = ZillitResult.Success(emptyList<DistDocument>())

        override suspend fun document(
            tab: DistributionTab,
            id: String,
            action: ReadAction,
            mode: ListMode,
        ) = ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("not used"))

        override suspend fun delete(tab: DistributionTab, id: String) = ZillitResult.Success(Unit)
        override suspend fun move(tab: DistributionTab, id: String, folderName: String) = ZillitResult.Success(Unit)
        override suspend fun counts(tab: DistributionTab, id: String) =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.pagedistribution.domain.CountRow>())

        override suspend fun publish(
            tool: DistributionTool,
            tab: DistributionTab,
            document: DistDocument,
            todayYmd: String,
        ) = ZillitResult.Success(Unit)
    }

    private fun tools(spy: DistributionRepository, television: Boolean = false) =
        distributionForwardTools(spy, isTelevision = { television }, nowMillis = { NOW })

    @Test
    fun `a script page asks for the scene, the page, a colour and a date`() = runTest {
        val tool = tools(UploadSpy()).first { it.id == "tool:script_distribution_tool:page" }

        val outcome = tool.ask(pdf)

        val form = (outcome as ForwardStepOutcome.Asks).form
        assertEquals(
            listOf(FIELD_SCENE, FIELD_PAGE, FIELD_COLOUR, FIELD_DATE),
            form.fields.map { it.key },
        )
        assertTrue(form.fields.first { it.key == FIELD_SCENE }.required, "the scene names the folder")
        // Colours carry their swatch so the picker can draw the dot.
        val colours = form.fields.filterIsInstance<ForwardField.Choice>().single { it.key == FIELD_COLOUR }
        assertEquals(PageColour.entries.size, colours.options.size)
        assertEquals(PageColour.White.hex, colours.options.first().swatch)
    }

    @Test
    fun `a full script asks for its name and date, and nothing about pages`() = runTest {
        val tool = tools(UploadSpy()).first { it.id == "tool:script_distribution_tool:full_script" }

        val form = (tool.ask(pdf) as ForwardStepOutcome.Asks).form

        assertEquals(listOf(FIELD_NAME, FIELD_DATE), form.fields.map { it.key })
    }

    @Test
    fun `a television production is asked for the episode as well`() = runTest {
        val tool = tools(UploadSpy(), television = true)
            .first { it.id == "tool:script_distribution_tool:full_script" }

        val form = (tool.ask(pdf) as ForwardStepOutcome.Asks).form

        assertEquals(listOf(FIELD_NAME, FIELD_DATE, FIELD_EPISODE), form.fields.map { it.key })
        assertTrue(form.fields.last().required, "the web throws episode_number_required without it")
    }

    @Test
    fun `the answers become the tool's own upload, with the file left where it is`() = runTest {
        val spy = UploadSpy()
        val tool = tools(spy).first { it.id == "tool:schedule_distribution_tool:page" }

        val answer = tool.send(
            pdf,
            mapOf(
                FIELD_SCENE to "12A",
                FIELD_PAGE to "3",
                FIELD_COLOUR to PageColour.Pink.name,
                FIELD_DATE to "2026-10-13",
                FIELD_SCHEDULE_TYPE to ScheduleType.OneLinePages.name,
            ),
        )

        assertTrue(answer is ZillitResult.Success)
        val (tab, draft, stored) = spy.uploads.single()
        assertEquals("page", tab.key)
        assertEquals("12A", draft.sceneNumber)
        assertEquals("3", draft.pageNumber)
        assertEquals(PageColour.Pink, draft.colour)
        assertEquals("2026-10-13", draft.dateYmd)
        assertEquals(ScheduleType.OneLinePages, draft.scheduleType)
        // Nothing is uploaded again: the forwarded file's own key travels.
        assertEquals("chat/sides.pdf", stored.media)
        assertEquals("b", stored.bucket)
        assertEquals("2048", stored.fileSize)
        assertTrue(draft.bytes.isEmpty(), "a forward has no bytes to send")
    }

    @Test
    fun `a schedule page offers its type, a script page does not`() = runTest {
        val schedule = tools(UploadSpy()).first { it.id == "tool:schedule_distribution_tool:page" }
        val script = tools(UploadSpy()).first { it.id == "tool:script_distribution_tool:page" }

        val scheduleForm = (schedule.ask(pdf) as ForwardStepOutcome.Asks).form
        val scriptForm = (script.ask(pdf) as ForwardStepOutcome.Asks).form

        assertTrue(scheduleForm.fields.any { it.key == FIELD_SCHEDULE_TYPE })
        assertTrue(scriptForm.fields.none { it.key == FIELD_SCHEDULE_TYPE })
    }

    @Test
    fun `D O D asks for the folder it files under`() = runTest {
        val tool = tools(UploadSpy()).first { it.id.startsWith("tool:dod_tool") }

        val form = (tool.ask(pdf) as ForwardStepOutcome.Asks).form

        assertEquals(FIELD_NAME, form.fields.first().key)
        assertTrue(form.fields.first().required)
        assertNull(tool.group, "D.O.D has one destination, so it stands on its own")
    }

    @Test
    fun `a picture is refused by the document tools, and never offered to them`() = runTest {
        val tool = tools(UploadSpy()).first { it.id == "tool:script_distribution_tool:page" }

        assertTrue(tool.ask(picture) is ForwardStepOutcome.Refused)
        assertTrue(!tool.takes(picture), "it is not even listed for a picture")
        assertTrue(tool.takes(pdf))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.workspace.forward.ForwardField
import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.castboard.domain.BoardCast
import com.zillit.desktop.feature.castboard.domain.BoardRecordDraft
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingStatus
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

    /**
     * The web splits `document` by subtype: a PDF is offered the
     * distributions and the budgets, anything else is offered neither — not
     * refused on click, absent from the list.
     */
    @Test
    fun `a document that is not a PDF is not offered the distributions or the budgets`() {
        val docx = ForwardItem(
            kind = ForwardKind.Document,
            file = ForwardFile(media = "chat/notes.docx", name = "notes.docx", subtype = "docx"),
        )
        val offered = tools(UploadSpy()) + budgetForwardTools(
            post = { ZillitResult.Success(Unit) },
            departments = { emptyList() },
            isTelevision = { false },
        )

        assertTrue(offered.none { it.takes(docx) })
        assertTrue(offered.all { it.takes(pdf) })
    }

    // Casting and wardrobe ---------------------------------------------------

    /** Records every record created, and answers with a fixed cast list. */
    private class CastSpy(private val casts: List<BoardCast> = emptyList()) {
        val created = mutableListOf<Pair<String, BoardRecordDraft>>()

        fun tools(board: BoardTool, television: Boolean = false, granted: Boolean = true) =
            castBoardForwardTools(
                board = board,
                unitId = { identifier -> "unit-$identifier".takeIf { granted } },
                create = { unitId, draft ->
                    created += unitId to draft
                    ZillitResult.Success(Unit)
                },
                casts = { casts },
                isTelevision = { television },
                newUniqueId = { "uid-1" },
            )
    }

    /** Both lists, all three shortlists, under the tool each belongs to. */
    @Test
    fun `each cast board offers its two lists at three stages`() {
        val tools = CastSpy().tools(BoardTool.Casting)

        assertEquals(
            listOf(
                "tool:casting_main_tool:selected",
                "tool:casting_main_tool:shortlisted",
                "tool:casting_main_tool:finalized",
                "tool:casting_background_tool:selected",
                "tool:casting_background_tool:shortlisted",
                "tool:casting_background_tool:finalized",
            ),
            tools.map { it.id },
        )
        // Rights are read per list, which is how this production grants them.
        assertEquals("casting_main_tool", tools.first().identifier)
        assertTrue(tools.all { it.group != null }, "a submenu, as the web has it")
    }

    /** `addToolsForForward` offers these four for a picture or a clip alone. */
    @Test
    fun `a cast board takes a picture and not a document`() {
        val tool = CastSpy().tools(BoardTool.Casting).first()

        assertTrue(tool.takes(picture))
        assertTrue(!tool.takes(pdf))
    }

    /** Nothing is required on Selects, and everything is from Shortlist on. */
    @Test
    fun `casting asks for the character and the candidate, and insists past Selects`() = runTest {
        val tools = CastSpy().tools(BoardTool.Casting)

        val selects = (tools[0].ask(picture) as ForwardStepOutcome.Asks).form
        val shortlist = (tools[1].ask(picture) as ForwardStepOutcome.Asks).form

        assertEquals(
            listOf(FIELD_CHARACTER, FIELD_CHARACTER_NUMBER, FIELD_TALENT, FIELD_GENDER, FIELD_DESCRIPTION),
            selects.fields.map { it.key },
        )
        assertTrue(selects.fields.none { it.required }, "a still with nothing known about it belongs in Selects")
        assertEquals(
            listOf(FIELD_CHARACTER, FIELD_TALENT, FIELD_GENDER),
            shortlist.fields.filter { it.required }.map { it.key },
        )
    }

    /** A costume files under a character that already exists on that list. */
    @Test
    fun `wardrobe picks the cast and the scene, from the list's own cast`() = runTest {
        val spy = CastSpy(listOf(BoardCast("k1", "Inspector Rao", "Ravi Menon")))
        val shortlist = spy.tools(BoardTool.Wardrobe)[1]

        val form = (shortlist.ask(picture) as ForwardStepOutcome.Asks).form

        assertEquals(listOf(FIELD_CAST, FIELD_SCENE, FIELD_DESCRIPTION), form.fields.map { it.key })
        val cast = form.fields.filterIsInstance<ForwardField.Choice>().single()
        assertEquals(listOf("k1"), cast.options.map { it.value })
        assertEquals("Inspector Rao · Ravi Menon", cast.options.single().label)
        assertTrue(form.fields.first { it.key == FIELD_SCENE }.required)
    }

    /** With no cast at all there is nothing to answer the one required question with. */
    @Test
    fun `wardrobe's shortlists refuse a list with no characters`() = runTest {
        val spy = CastSpy()

        assertTrue(spy.tools(BoardTool.Wardrobe)[2].ask(picture) is ForwardStepOutcome.Refused)
        // Selects does not insist, so a costume can still be parked there.
        assertTrue(spy.tools(BoardTool.Wardrobe)[0].ask(picture) is ForwardStepOutcome.Asks)
    }

    /** A television production answers for the episode here as well. */
    @Test
    fun `a television production is asked for the episode on a cast board`() = runTest {
        val tools = CastSpy().tools(BoardTool.Casting, television = true)

        val form = (tools[1].ask(picture) as ForwardStepOutcome.Asks).form

        assertTrue(form.fields.any { it.key == FIELD_EPISODE && it.required })
    }

    /** The answers become the record the board's own dialog would have created. */
    @Test
    fun `the answers become a record on that list, with the still on it`() = runTest {
        val spy = CastSpy()
        val tool = spy.tools(BoardTool.Casting).first { it.id == "tool:casting_main_tool:finalized" }

        val sent = tool.send(
            picture,
            mapOf(
                FIELD_CHARACTER to "Inspector Rao",
                FIELD_TALENT to "Ravi Menon",
                FIELD_GENDER to "male",
                FIELD_EPISODE to "3, 4",
                FIELD_DESCRIPTION to "the market scene",
            ),
        )

        assertTrue(sent is ZillitResult.Success)
        val (unitId, draft) = spy.created.single()
        assertEquals("unit-casting_main_tool", unitId, "the list's own unit id, from the rights")
        assertEquals(CastingStatus.Finalized, draft.status)
        assertEquals("Inspector Rao", draft.characterName)
        assertEquals(listOf("3", "4"), draft.episodes)
        assertEquals("chat/door.jpg", draft.attachment?.media)
        assertEquals("image", draft.attachment?.contentType)
    }

    /** A list this person was not granted has no unit id to post to. */
    @Test
    fun `a list that is not granted refuses rather than posting`() = runTest {
        val spy = CastSpy()
        val tool = spy.tools(BoardTool.Casting, granted = false).first()

        assertTrue(tool.ask(picture) is ForwardStepOutcome.Refused)
        assertTrue(tool.send(picture, emptyMap()) is ZillitResult.Failure)
        assertTrue(spy.created.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

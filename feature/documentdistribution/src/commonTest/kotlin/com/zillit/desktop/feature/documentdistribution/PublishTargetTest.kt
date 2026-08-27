package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each publish destination demands, and who may reach it.
 *
 * Every rule here mirrors a refusal on the receiving endpoint. Checking them
 * before sending is not politeness: the server answers with translation keys
 * (`publication_mode_not_supported`, `invalid_publication_category`) that mean
 * nothing to the person who filled the form in.
 */
class PublishTargetTest {

    private val oneDoc = PublishDraft(documentIds = listOf("d1"))

    @Test
    fun `a plain destination asks for nothing but a document`() {
        assertNull(PublishTarget.Info.problem(oneDoc, isTelevision = false))
        assertEquals(
            "Choose at least one document",
            PublishTarget.Info.problem(PublishDraft(), isTelevision = false),
        )
    }

    /**
     * The schedule and script destinations replace the project's record on
     * every upload, so a two-file publish would silently collapse to the last.
     * D.O.D accumulates and stays multi-select.
     */
    @Test
    fun `single-file destinations refuse a second document, dod does not`() {
        val two = PublishDraft(documentIds = listOf("d1", "d2"))

        assertEquals(
            "Schedule Full takes one document at a time",
            PublishTarget.ScheduleFull.problem(two, isTelevision = false),
        )
        assertNull(PublishTarget.Dod.problem(two.copy(name = "Week 1"), isTelevision = false))
    }

    @Test
    fun `the page destinations require a scene number`() {
        assertEquals(
            "A scene number is required",
            PublishTarget.ScriptPages.problem(oneDoc, isTelevision = false),
        )
        assertNull(
            PublishTarget.ScriptPages.problem(oneDoc.copy(sceneNumber = "12A"), isTelevision = false),
        )
    }

    @Test
    fun `schedule pages need both a scene and a schedule type`() {
        val withScene = oneDoc.copy(sceneNumber = "4")

        assertEquals(
            "Choose which schedule this is",
            PublishTarget.SchedulePages.problem(withScene, isTelevision = false),
        )
        assertNull(
            PublishTarget.SchedulePages.problem(
                withScene.copy(scheduleType = "full_schedule_pages"),
                isTelevision = false,
            ),
        )
    }

    @Test
    fun `dod requires a name`() {
        assertEquals("A name is required", PublishTarget.Dod.problem(oneDoc, isTelevision = false))
        assertNull(PublishTarget.Dod.problem(oneDoc.copy(name = "DOD v2"), isTelevision = false))
    }

    /** Only on television: a feature has no episodes to number. */
    @Test
    fun `the episode is demanded on television and not otherwise`() {
        val draft = oneDoc.copy(name = "DOD")

        assertNull(PublishTarget.Dod.problem(draft, isTelevision = false))
        assertEquals(
            "An episode is required on a television project",
            PublishTarget.Dod.problem(draft, isTelevision = true),
        )
        assertNull(PublishTarget.Dod.problem(draft.copy(episode = "101"), isTelevision = true))
    }

    @Test
    fun `the plain destinations never ask for an episode`() {
        assertNull(PublishTarget.Info.problem(oneDoc, isTelevision = true))
        assertNull(PublishTarget.CallSheet.problem(oneDoc, isTelevision = true))
    }

    @Test
    fun `a replace with nothing ticked is refused`() {
        val replacing = oneDoc.copy(mode = PublishMode.Replace)

        assertEquals(
            "Pick the published file to replace",
            PublishTarget.CallSheet.problem(replacing, isTelevision = false),
        )
        assertNull(
            PublishTarget.CallSheet.problem(
                replacing.copy(replaceChatIds = listOf("c1")),
                isTelevision = false,
            ),
        )
    }

    @Test
    fun `only call sheet and production report republish`() {
        val republishing = PublishTarget.all.filter { it.republishable }.map { it.category }

        assertEquals(listOf("call_sheet_unit", "production_report"), republishing)
    }

    /** The tool destinations have no note field, so a note must not be sent there. */
    @Test
    fun `a note rides with the plain destinations only`() {
        assertTrue(PublishTarget.Info.takesNote)
        assertTrue(PublishTarget.CallSheet.takesNote)
        assertFalse(PublishTarget.ScheduleFull.takesNote)
        assertFalse(PublishTarget.Dod.takesNote)
    }

    @Test
    fun `every category id is distinct and resolvable`() {
        val ids = PublishTarget.all.map { it.category }

        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(PublishTarget.of(it), "$it did not resolve") }
        assertNull(PublishTarget.of("made_up"))
    }

    /** PDF-only is every destination bar the two general notice boards. */
    @Test
    fun `the file-type rule matches the receiving endpoints`() {
        val open = PublishTarget.all.filterNot { it.pdfOnly }.map { it.category }

        assertEquals(listOf("info", "confidential_info"), open)
    }

    // -- who may publish where ------------------------------------------------

    private fun granted(identifier: String) = ToolAccess(
        identifier = identifier,
        enabled = true,
        canView = true,
        canPost = true,
        canDownload = true,
    )

    private fun permissions(vararg posting: String) = ProjectPermissions(
        tools = listOf(granted(DocDistViewer.TOOL_IDENTIFIER)) + posting.map(::granted),
    )

    @Test
    fun `a destination is offered only when its own tool admits posting`() {
        val viewer = DocDistViewer.from(permissions("info_tool"), "u1", "u@x", isTelevision = false)

        assertTrue("info" in viewer.publishable)
        assertFalse("production_report" in viewer.publishable)
        assertFalse("schedule_full" in viewer.publishable)
    }

    /**
     * The Call Sheet unit is gated by the home unit list, which this module
     * does not hold — so it is offered and the server has the final say.
     */
    @Test
    fun `the call sheet unit is always offered`() {
        val viewer = DocDistViewer.from(permissions(), "u1", "u@x", isTelevision = false)

        assertEquals(setOf("call_sheet_unit"), viewer.publishable)
    }

    @Test
    fun `one tool right opens every destination beneath it`() {
        val viewer = DocDistViewer.from(
            permissions("schedule_distribution_tool"),
            "u1",
            "u@x",
            isTelevision = false,
        )

        assertEquals(
            listOf("Schedule Full", "Pages", "Schedule One Line"),
            viewer.targets().filter { it.group == "Schedule Full & One Line" }.map { it.label },
        )
    }

    @Test
    fun `an unresolved viewer offers nothing rather than everything`() {
        val viewer = DocDistViewer.from(ProjectPermissions.Empty, "u1", "u@x")

        assertFalse(viewer.ready)
        assertTrue(viewer.publishable.isEmpty(), "nothing is offered until rights are known")
    }
    /**
     * The call sheet is gated by the home unit list, not the tool grid, so it
     * is offered to someone holding Document Distribution and nothing else.
     * Verified live 2026-08-27 on a production with 42 tools switched on,
     * where the dialog said "no posting rights on any tool" instead.
     */
    @Test
    fun `call sheet is offered to someone holding no destination tool`() {
        val viewer = DocDistViewer.from(
            permissions = ProjectPermissions(listOf(granted(DocDistViewer.TOOL_IDENTIFIER))),
            userId = "u1",
            userEmail = "u@x.com",
            isTelevision = false,
        )

        assertTrue(viewer.targets().isNotEmpty(), "no destination offered at all")
        assertEquals(listOf(PublishTarget.CallSheet), viewer.targets())
    }

}

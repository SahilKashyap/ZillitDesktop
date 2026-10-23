package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervision
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.domain.asRows
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The module's rights gate, its wire reader, and the menu it draws. */
class ScriptSupervisionTest {

    @Test
    fun `an unanswered rights list is not a denial, and a refused one is`() {
        // Empty means "not yet": denying here would show "no access" to
        // everybody while the tools call is still in flight.
        val waiting = ScriptSupervisionViewer.from(ProjectPermissions.Empty)
        assertFalse(waiting.resolved)
        assertFalse(waiting.canCall)
        assertFalse(waiting.isBlocked)

        val granted = ScriptSupervisionViewer.from(permissions(view = true, post = true))
        assertTrue(granted.canCall)
        assertTrue(granted.canPost)
        assertFalse(granted.isBlocked)

        val refused = ScriptSupervisionViewer.from(permissions(view = false))
        assertTrue(refused.resolved)
        assertFalse(refused.canCall)
        assertTrue(refused.isBlocked)
    }

    @Test
    fun `a tool row that is switched off cannot call, however the rights read`() {
        // The service answers 403 for a production without the tool, and a 403
        // anywhere in this app reads as "no longer a member".
        val off = ScriptSupervisionViewer.from(permissions(view = true, post = true, enabled = false))
        assertFalse(off.canCall)
        assertTrue(off.isBlocked)
    }

    @Test
    fun `the service's answers read whether they are an array or a page`() {
        // Scenes and takes answer `{data: [...], total}`; every continuity
        // folder answers a plain array.
        val paged = Json.parseToJsonElement("""{"data":[{"_id":"s1"}],"total":1}""")
        val bare = Json.parseToJsonElement("""[{"_id":"r1"},{"_id":"r2"}]""")
        assertEquals(listOf("s1"), paged.asRows().map { it.id })
        assertEquals(listOf("r1", "r2"), bare.asRows().map { it.id })
        // A refusal carries no data at all.
        assertEquals(emptyList(), Json.parseToJsonElement("{}").asRows().map { it.id })
    }

    @Test
    fun `a scene reads as its number and heading`() {
        val scene = rec("""{"scene_number":"14","scene_name":"INT. KITCHEN"}""")
        assertEquals("14 · INT. KITCHEN", ScriptSupervision.sceneLabel(scene))
        // A scene with no heading is still named by its number alone, and a
        // missing scene is blank rather than " · ".
        assertEquals("14", ScriptSupervision.sceneLabel(rec("""{"scene_number":"14"}""")))
        assertEquals("", ScriptSupervision.sceneLabel(null))
    }

    @Test
    fun `every page of the menu is reachable and named`() {
        // The route is the web's own segment, so a link, a log line and the
        // web's URL say the same word.
        assertEquals(SsPage.QuickLog, SsPage.of("quick-log"))
        assertEquals(SsPage.SoundReports, SsPage.of("sound-reports"))
        // An unknown route lands where the tool opens, as the web's catch-all does.
        assertEquals(SsPage.Start, SsPage.of("not-a-page"))
        assertEquals(SsPage.Start, SsPage.of(null))

        // Every page has a word for its row: a page whose label is missing
        // would draw its key in the menu.
        val missing = SsPage.entries.filterNot { SsText.containsKey(it.labelKey) }
        assertEquals(emptyList(), missing, "pages with no label: ${missing.map { it.labelKey }}")
    }

    @Test
    fun `a take's flags are seven booleans, not one status`() {
        // A take can be several of these at once — circled AND a pickup.
        val take = rec("""{"is_circled":true,"is_pickup":true,"is_ng":false}""")
        val on = ScriptSupervision.TAKE_FLAGS.filter { take.bool(it) }
        assertEquals(listOf("is_circled", "is_pickup"), on)
    }

    private fun rec(json: String) = Rec(Json.parseToJsonElement(json) as JsonObject)

    private fun permissions(view: Boolean = false, post: Boolean = false, enabled: Boolean = true) =
        ProjectPermissions(
            tools = listOf(
                ToolAccess(
                    identifier = ScriptSupervisionViewer.TOOL,
                    enabled = enabled,
                    canView = view,
                    canPost = post,
                ),
            ),
        )
}

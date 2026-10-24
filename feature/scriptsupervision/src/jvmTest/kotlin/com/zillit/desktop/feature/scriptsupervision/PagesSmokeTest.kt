package com.zillit.desktop.feature.scriptsupervision

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.ui.CastState
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalNav
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.ScenesState
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsNav
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.TramLinesPage
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every page of the tool, composed against a canned service.
 *
 * It cannot say a screen is right; it says none of them throws when it first
 * draws — which is the class of fault this app's tables keep producing and
 * which compiles perfectly: a `ZillitDataTable` measured against infinite
 * constraints inside a scrolling page, and a screen reading a field the
 * service left out. Both only show here.
 *
 * Driven off [SsPage] rather than a hand-written list, so a page added to the
 * menu without a screen fails this test rather than a reader's window.
 */
@OptIn(ExperimentalTestApi::class)
class PagesSmokeTest {

    /**
     * Composes [page], optionally at an inner position of it ([view]).
     *
     * The inner views are driven too, because the detail screens are where the
     * reads live — a character's section, a day's scenes, a look — and a page's
     * own list says nothing about whether those draw.
     */
    private fun draws(page: SsPage, view: SsView = SsView.Root) = runComposeUiTest {
        val fixture = SmokeFixture()
        val ctx = fixture.ctx
        val scenes = fixture.scenes
        val cast = fixture.cast
        val nav = fixture.nav(page, view)
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalSs provides ctx,
                    LocalScenes provides scenes,
                    LocalCast provides cast,
                    LocalNav provides nav,
                ) {
                    ZillitScrollColumn { Column { SsRoutes(page) { } } }
                }
            }
        }
        waitForIdle()
    }

    /**
     * The lined page itself, with coverage AND gaps.
     *
     * The menu sweep cannot reach it: the screen opens with no scene picked,
     * so `SsPage.LinedScript` only ever composes the empty state there, and
     * gaps arrive only when somebody presses "Check gaps". This composes the
     * page the way a reader sees it — 84 rows, four coverage lines, two kinds
     * of gap — which is where a row that cannot measure itself would show.
     */
    @Test
    fun `the lined page draws with coverage and gaps`() = runComposeUiTest {
        val fixture = SmokeFixture()
        val lines = runBlocking {
            ContinuityCrud(fixture.ctx.api, "lined-script").list(mapOf("scene_id" to "s1"))
        }.let { (it as ZillitResult.Success).data.rows }
        val gaps = runBlocking {
            fixture.ctx.api.get("/ai/coverage-gaps/p1/s1")
        }.let { (it as ZillitResult.Success).data.rows }
        // The fixture has to actually carry them, or this passes on nothing.
        assertEquals(4, lines.size, "the fixture's coverage lines")
        assertEquals(2, gaps.size, "the fixture's gaps")

        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalSs provides fixture.ctx,
                    LocalScenes provides fixture.scenes,
                    LocalCast provides fixture.cast,
                    LocalNav provides fixture.nav(SsPage.LinedScript),
                ) {
                    ZillitScrollColumn {
                        Column {
                            TramLinesPage(
                                scene = fixture.scenes.scene("s1"),
                                lines = lines,
                                gaps = gaps,
                                selectedId = lines[1].id,
                                onSelect = { },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        // The page drew the scene it was given, and the shot labels the
        // palette's colourblind separation depends on.
        onNodeWithText("INT. KITCHEN").assertExists()
        onAllNodesWithText("1A").onFirst().assertExists()
        onAllNodesWithText("3C").onFirst().assertExists()
    }

    @Test
    fun `every page of the menu draws`() {
        SsPage.entries.forEach { draws(it) }
    }

    @Test
    fun `every page of a character draws`() {
        draws(SsPage.Characters, SsView.Character("c1"))
        CharacterSection.entries.forEach { section ->
            draws(SsPage.Characters, SsView.Section("c1", section))
        }
        // A story day's scenes, a shooting day's scenes, and the day keys that
        // mean "nothing has claimed this": the two the groupers produce.
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ScriptDays, "Story Day 1"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ScriptDays, "unassigned"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ShootingDays, "2"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ShootingDays, "0"))
        draws(SsPage.Characters, SsView.CharacterScene("c1", "s1"))
    }

    @Test
    fun `one scene's breakdown draws`() {
        draws(SsPage.ScriptBreakdown, SsView.BreakdownScene("s1"))
    }

    @Test
    fun `a look, a wardrobe item and a prop draw`() {
        // On the board that owns it, and from the character that wears it.
        draws(SsPage.CostumeLooks, SsView.Look("l1", "c1", "costume"))
        draws(SsPage.HairLooks, SsView.Look("l1", "c1", "hair"))
        draws(SsPage.Characters, SsView.Look("l1", "c1", "makeup"))
        draws(SsPage.Characters, SsView.WardrobeItem("g1"))
        draws(SsPage.Characters, SsView.Prop("p1"))
    }

}

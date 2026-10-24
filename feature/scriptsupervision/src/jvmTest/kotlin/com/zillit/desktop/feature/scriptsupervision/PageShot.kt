package com.zillit.desktop.feature.scriptsupervision

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalNav
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.TramLinesPage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Renders a page to a PNG so it can be LOOKED AT.
 *
 * Not an assertion — it writes a file and passes. It exists because layout is
 * the one thing no test can check: a meter row whose label column is too
 * narrow, a tile grid that wraps badly, a tram line that breaks into dashes,
 * all compile and pass every gate while looking wrong. Point it at a page,
 * run it, open the PNG.
 *
 *     SS_SHOT=1 SS_SHOT_DIR=/some/dir ./gradlew \
 *       :feature:scriptsupervision:jvmTest --tests "*PageShot*"
 *
 * Off by default so it costs nothing in CI: set `SS_SHOT=1` to write files.
 */
class PageShot {

    @Test
    fun `render pages for eyeballing`() {
        if (System.getenv("SS_SHOT") != "1") return
        val out = File(System.getenv("SS_SHOT_DIR") ?: "build/shots").apply { mkdirs() }
        listOf(SsPage.Dashboard, SsPage.Export, SsPage.AuditLog, SsPage.LinedScript).forEach { page ->
            BOTH_THEMES.forEach { dark ->
                shoot(file(out, page.name, dark)) { SsRoutes(page) { } }
            }
        }
        // The tram lines on their own, with a scene PICKED and gaps SHOWING.
        // Neither reaches a render through the screen: the scene starts
        // unpicked and the gaps arrive only when somebody presses "Check
        // gaps", and both are the part worth looking at.
        BOTH_THEMES.forEach { dark ->
            val fixture = SmokeFixture()
            // Taller than a window: a lined page runs to the scene's measured
            // length (84 rows here) and the LEGEND is under it, so a
            // window-height frame cuts off the part that names the colours.
            shoot(file(out, "TramLines", dark), fixture, height = TALL) {
                TramLinesPage(
                    scene = fixture.scenes.scene("s1"),
                    lines = fixture.linedScript(),
                    gaps = fixture.coverageGaps(),
                    // One line drawn selected, so the wider bar is visible too.
                    selectedId = fixture.linedScript().getOrNull(1)?.id,
                    onSelect = { },
                )
            }
        }
    }

    private fun file(out: File, name: String, dark: Boolean) =
        File(out, "$name-${if (dark) "dark" else "light"}.png")

    /**
     * Renders [content] inside the shell's own frame and writes the PNG.
     *
     * ⚠ The frame is the SHELL's, copied rather than approximated: its canvas,
     * its padding and its `spacedBy(md)`. A harness that differs from the
     * shell reports layout problems the page does not have, which cost two
     * rounds of chasing them. The canvas matters as much as the spacing — the
     * dark theme's tone backgrounds are translucent (`0x33…`), so over
     * `ImageComposeScene`'s default white they composite to pale pink and a
     * dark page looks broken, while near-white text on white leaves titles
     * invisible.
     */
    private fun shoot(
        file: File,
        fixture: SmokeFixture = SmokeFixture(),
        height: Int = HEIGHT,
        content: @Composable () -> Unit,
    ) {
        val dark = file.name.endsWith("-dark.png")
        // The real window's size on this Mac, so the layout is the one a
        // reader actually gets rather than a narrower guess.
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                CompositionLocalProvider(
                    LocalSs provides fixture.ctx,
                    LocalScenes provides fixture.scenes,
                    LocalCast provides fixture.cast,
                    LocalNav provides fixture.nav(SsPage.LinedScript),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ZillitTheme.colors.canvas)
                            .padding(ZillitTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        content()
                    }
                }
            }
        }
        file.writeBytes(scene.render(0L).encodeToData()!!.bytes)
        scene.close()
    }

    /** The fixture's coverage lines, read through the real client. */
    private fun SmokeFixture.linedScript(): List<Rec> = runBlocking {
        ContinuityCrud(ctx.api, "lined-script").list(mapOf("scene_id" to "s1"))
    }.rowsOrEmpty()

    /** The fixture's gaps, read through the real client. */
    private fun SmokeFixture.coverageGaps(): List<Rec> = runBlocking {
        ctx.api.get("/ai/coverage-gaps/p1/s1")
    }.rowsOrEmpty()

    private fun ZillitResult<com.zillit.desktop.feature.scriptsupervision.data.Answer>.rowsOrEmpty(): List<Rec> =
        (this as? ZillitResult.Success)?.data?.rows.orEmpty()

    private companion object {
        val BOTH_THEMES = listOf(false, true)
        const val WIDTH = 2880
        const val HEIGHT = 2400
        const val TALL = 4000
    }
}

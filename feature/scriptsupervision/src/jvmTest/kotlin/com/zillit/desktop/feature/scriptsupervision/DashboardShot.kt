package com.zillit.desktop.feature.scriptsupervision

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalNav
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
import java.io.File
import kotlin.test.Test

/**
 * Renders a page to a PNG so it can be LOOKED AT.
 *
 * Not an assertion — it writes a file and passes. It exists because the
 * dashboard's layout is the one thing no test can check: a meter row whose
 * label column is too narrow, or a tile grid that wraps badly, compiles and
 * passes every gate while looking wrong. Point it at a page, run it, open
 * the PNG.
 *
 * Off by default so it costs nothing in CI: set `SS_SHOT=1` to write files.
 */
class DashboardShot {

    @Test
    fun `render pages for eyeballing`() {
        if (System.getenv("SS_SHOT") != "1") return
        val out = File(System.getenv("SS_SHOT_DIR") ?: "build/shots").apply { mkdirs() }
        listOf(SsPage.Dashboard, SsPage.Export, SsPage.AuditLog).forEach { page ->
            listOf(false, true).forEach { dark ->
                shoot(page, dark, File(out, "${page.name}-${if (dark) "dark" else "light"}.png"))
            }
        }
    }

    private fun shoot(page: SsPage, dark: Boolean, file: File) {
        val fixture = SmokeFixture()
        // The real window's width on this Mac, so the layout is the one a
        // reader actually gets rather than a narrower guess.
        val scene = ImageComposeScene(width = 2880, height = 2400, density = Density(2f)) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                CompositionLocalProvider(
                    LocalSs provides fixture.ctx,
                    LocalScenes provides fixture.scenes,
                    LocalCast provides fixture.cast,
                    LocalNav provides fixture.nav(page),
                ) {
                    // The SHELL's own canvas, column and spacing. A harness
                    // that differs from the shell reports layout problems the
                    // page does not have — this cost two rounds of chasing
                    // them. The CANVAS matters as much as the spacing: the
                    // dark theme's tone backgrounds are translucent (0x33…),
                    // so over the scene's default white they composited to
                    // pale pink and the dark page looked broken, while
                    // near-white text on white left the title invisible.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ZillitTheme.colors.canvas)
                            .padding(ZillitTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                    ) {
                        SsRoutes(page) { }
                    }
                }
            }
        }
        file.writeBytes(scene.render(0L).encodeToData()!!.bytes)
        scene.close()
    }
}

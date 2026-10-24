package com.zillit.desktop.feature.scriptsupervision

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalNav
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
import kotlin.test.Test

/**
 * The quick log's hand-over to a template.
 *
 * ⚠ This is the hole the template port was built to close, and it is the one
 * behaviour no other test covers: with a DEFAULT template the quick log is
 * not its own form at all, and a production that designed a form expects to
 * fill in that form. The shared fixture deliberately has no default (so the
 * quick log's own form stays covered), so this test supplies one.
 */
@OptIn(ExperimentalTestApi::class)
class TemplateHandoverTest {

    @Test
    fun `a default template replaces the quick log's own form`() = runComposeUiTest {
        val fixture = SmokeFixture(defaultTemplate = true)
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalSs provides fixture.ctx,
                    LocalScenes provides fixture.scenes,
                    LocalCast provides fixture.cast,
                    LocalNav provides fixture.nav(SsPage.QuickLog),
                ) {
                    ZillitScrollColumn { Column { SsRoutes(SsPage.QuickLog) { } } }
                }
            }
        }
        waitForIdle()
        // The TEMPLATE's name, which only the template log draws — the quick
        // log's own form shows the page's title instead.
        onNodeWithText("Unit A day log").assertExists()
    }
}

package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.nav

/**
 * One department's look board and the look pages under it — the web's
 * `costume-looks`, `hair-looks`, `makeup-looks` and `looks/:id` routes.
 *
 * The same dispatcher for all three departments, because the board is one
 * screen on the web too: the department is a parameter, not a different page.
 */
@Composable
internal fun LooksRoutes(department: String, onOpen: (SsPage) -> Unit) {
    when (val view = nav().current) {
        is SsView.Look -> LookDetailScreen(view.lookId, department)
        else -> LookBoardScreen(department, onOpen)
    }
}

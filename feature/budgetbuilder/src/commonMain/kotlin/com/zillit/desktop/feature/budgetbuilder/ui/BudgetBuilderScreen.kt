package com.zillit.desktop.feature.budgetbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Budget Builder's window.
 *
 * The budget application is a complete web app hosted rather than
 * reimplemented — the same decision the web client made, for the same reason —
 * and here it is the window's whole content, in the app's embedded Chromium.
 *
 * ## Full-bleed, and why
 *
 * [application] is given the window outright: no padding, no page header, no
 * strip of ours above it. The web arrived at the same place by steps —
 * zeroing the shell's content padding, then dropping its own header row once
 * the application grew a "← Film Tools" button of its own (`zillit:exit`,
 * which this tool answers by closing the window). Budget Builder carries its
 * own left nav and topbar; anything we draw around it is a second set of
 * chrome in front of the first number.
 *
 * What is left here is the three reasons the application is *not* what gets
 * drawn, each stated as the web states it.
 */
@Composable
fun BudgetBuilderScreen(
    state: BudgetBuilderUiState,
    /**
     * The embedded application, filling whatever it is given. Injected
     * because the browser is the host's to own — this module has no Chromium
     * and no loopback gateway.
     */
    application: @Composable () -> Unit,
) {
    if (state.showsApplication) {
        Box(Modifier.fillMaxSize()) { application() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_film_tools),
            title = str(S.desktop_bb_title),
            description = str(S.desktop_bb_description),
        )

        when {
            !state.configured -> ZillitNotice(
                // The web's wording for the same condition, kept identical so
                // support hears one sentence, not two.
                text = str(S.desktop_budget_builder_not_configured),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )

            state.viewer.isBlocked -> ZillitNotice(
                text = str(S.desktop_bb_no_access),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )

            else -> ZillitNotice(
                text = str(S.desktop_bb_offline),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
    }
}

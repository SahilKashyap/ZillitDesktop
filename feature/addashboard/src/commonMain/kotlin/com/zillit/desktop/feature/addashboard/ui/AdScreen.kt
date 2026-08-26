package com.zillit.desktop.feature.addashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.addashboard.ui.pages.AddToDayDialog
import com.zillit.desktop.feature.addashboard.ui.pages.BlockDialog
import com.zillit.desktop.feature.addashboard.ui.pages.RegisterPage
import com.zillit.desktop.feature.addashboard.ui.pages.ShootDaysPage
import com.zillit.desktop.feature.addashboard.ui.pages.SubmitDayDialog
import com.zillit.desktop.feature.addashboard.ui.pages.TodayPage

/**
 * The AD dashboard: the production's side of supporting artistes.
 *
 * The artiste's own view of the same days — their vouchers, their pay — is a
 * separate tool on a separate service (`feature:saportal`).
 */
@Composable
fun AdScreen(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    if (state.viewer.isBlocked) {
        ZillitEmptyState(
            title = "No access",
            message = "This production has not given you the AD dashboard.",
            icon = ZillitIcons.Shield,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ZillitPageHeader(
            title = "AD dashboard",
            eyebrow = "Supporting artistes",
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(AdEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    loading = state.loading,
                )
            },
        )
        AdBody(state, onEvent)
    }

    AddToDayDialog(state, onEvent)
    BlockDialog(state, onEvent)
    SubmitDayDialog(state, onEvent)

    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(AdEvent.ClearNotice) },
        tone = ZillitToastTone.Success,
    )
}

@Composable
private fun ColumnScope.AdBody(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitTabStrip(
        tabs = AdDestination.entries.map { ZillitTab(it.slug, it.label) },
        activeId = state.destination.slug,
        onSelect = { slug ->
            AdDestination.entries.firstOrNull { it.slug == slug }
                ?.let { onEvent(AdEvent.Open(it)) }
        },
    )

    // A viewer who may look but not change is told once, rather than
    // discovering it button by button.
    if (state.viewer.isReadOnly) {
        ZillitNotice(
            text = "You can see the roster and the day but not change them.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (state.loading && state.artistes.isEmpty() && state.dayList.isEmpty()) ZillitSpinner()
        when (state.destination) {
            AdDestination.Today -> TodayPage(state, onEvent)
            AdDestination.Register -> RegisterPage(state, onEvent)
            AdDestination.Days -> ShootDaysPage(state, onEvent)
        }
    }
}

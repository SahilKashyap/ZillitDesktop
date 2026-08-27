package com.zillit.desktop.feature.saportal.ui

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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.ui.pages.OverviewPage
import com.zillit.desktop.feature.saportal.ui.pages.PayPage
import com.zillit.desktop.feature.saportal.ui.pages.ProfilePage
import com.zillit.desktop.feature.saportal.ui.pages.QueriesPage
import com.zillit.desktop.feature.saportal.ui.pages.QueryDialog
import com.zillit.desktop.feature.saportal.ui.pages.SignDialog
import com.zillit.desktop.feature.saportal.ui.pages.VoucherDialog
import com.zillit.desktop.feature.saportal.ui.pages.VouchersPage

/**
 * The supporting artiste's portal.
 *
 * This is the artiste's own view of their work on one production — the days
 * they have done, what they are owed, and anything they need to sign. It is
 * *not* the AD's roster: that is the AD Dashboard, on a different service.
 */
@Composable
fun SaPortalScreen(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    if (state.viewer.isBlocked) {
        ZillitEmptyState(
            title = "No access",
            message = "This production has not given you the artiste portal.",
            icon = ZillitIcons.Shield,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ZillitPageHeader(
            title = "My work",
            eyebrow = "Supporting artiste",
            description = state.viewer.displayName.takeIf { it.isNotBlank() },
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(SaEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    loading = state.loading,
                )
            },
        )

        // Said before anything else: a person who is not an artiste on this
        // production is not looking at a broken screen, and telling them so
        // stops a support call.
        if (state.notAnArtiste) {
            ZillitEmptyState(
                title = "You are not booked as an artiste here",
                message = "This portal is for supporting artistes. If you think that is wrong, " +
                    "ask the AD department to add you to the production's artiste list.",
                icon = ZillitIcons.Users,
            )
            return@Column
        }

        PortalBody(state, onEvent)
    }

    VoucherDialog(state, onEvent)
    SignDialog(state, onEvent)
    QueryDialog(state, onEvent)

    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(SaEvent.ClearNotice) },
        tone = ZillitToastTone.Success,
    )
}

@Composable
private fun ColumnScope.PortalBody(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    ZillitTabStrip(
        tabs = SaDestination.entries.map { ZillitTab(it.slug, it.label) },
        activeId = state.destination.slug,
        onSelect = { slug ->
            SaDestination.entries.firstOrNull { it.slug == slug }
                ?.let { onEvent(SaEvent.Open(it)) }
        },
    )

    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        if (state.loading && state.summary == null && state.vouchers.isEmpty()) {
            ZillitSpinner()
        }
        when (state.destination) {
            SaDestination.Dashboard -> OverviewPage(state, onEvent)
            SaDestination.Vouchers -> VouchersPage(state, onEvent)
            SaDestination.Pay -> PayPage(state)
            SaDestination.Queries -> QueriesPage(state, onEvent)
            SaDestination.Profile -> ProfilePage(state)
        }
    }
}

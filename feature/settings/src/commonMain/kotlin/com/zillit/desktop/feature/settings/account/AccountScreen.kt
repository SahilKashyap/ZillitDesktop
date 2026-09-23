package com.zillit.desktop.feature.settings.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The account pages, under one frame.
 *
 * ## Why they share a chrome
 *
 * All four are reached the same way — a row on Settings — and left the same
 * way. Giving each its own header and back control would be four chances for
 * the chevron to sit somewhere different, on pages a reader moves between in
 * seconds.
 *
 * The body is a single column at reading width rather than the full window.
 * These are forms with two or three fields; stretched across a torn-off window
 * at 1600px the label and its input end up a hand's width apart.
 */
@Composable
fun AccountScreen(
    page: AccountPage,
    state: AccountUiState,
    onEvent: (AccountEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Header(page, onBack)

                when (page) {
                    AccountPage.EditProfile -> EditProfilePage(state, onEvent)
                    AccountPage.RecoveryEmail -> RecoveryEmailPage(state.recovery, onEvent)
                    AccountPage.LinkedDevices -> LinkedDevicesPage(state.devices, onEvent)
                    AccountPage.InviteCrew -> InviteCrewPage(state.seed, onEvent)
                }
            }
        }

        UnlinkDialog(state.devices, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(AccountEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

/**
 * Back, then what this page is.
 *
 * The chevron is always here, unlike on the administration listing: every one
 * of these pages is reached *from* Settings, so there is always something above
 * to return to.
 */
@Composable
private fun Header(page: AccountPage, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.desktop_back_to_settings),
            onClick = onBack,
        )
        ZillitPageHeader(
            eyebrow = str(S.settings),
            title = page.tabTitle,
            description = page.blurb,
        )
    }
}

/** One line under the title saying what the page is for, before it is read. */
private val AccountPage.blurb: String
    get() = when (this) {
        AccountPage.EditProfile ->
            str(S.desktop_your_profile_detail)

        AccountPage.RecoveryEmail ->
            str(S.desktop_recovery_email_blurb)

        AccountPage.LinkedDevices ->
            str(S.desktop_linked_devices_blurb)

        AccountPage.InviteCrew ->
            str(S.desktop_invite_crew_detail)
    }

private val CONTENT_MAX_WIDTH = 680.dp

package com.zillit.desktop.feature.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Sign-in and project selection.
 *
 * One centred card across every step rather than a screen per step: the flow is
 * three short forms, and on a 1440pt-wide window a full-bleed layout leaves the
 * user's eye travelling across empty space to find a single text field. The
 * Android app uses a separate Activity per step because that is what phones
 * want; desktop does not.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> kotlin.Unit = {},
    /** The language preference (blank = follow the system) and where a choice goes; see the shell. */
    language: String = "",
    onLanguageChange: (String) -> kotlin.Unit = {},
    createViewModel: CreateProductionViewModel? = null,
    joinViewModel: JoinProductionViewModel? = null,
    /**
     * The build's version, printed small on the sign-in page.
     *
     * Someone who cannot get past this page is exactly who support will ask
     * "which version?", and Settings is on the other side of signing in.
     */
    appVersion: String? = null,
) {
    val state by viewModel.state.collectAsState()

    // Two steps are full-bleed pages with their own frame rather than entries in
    // the shared card, matching the web: `/device/login` is the QR landing page,
    // `/projects` is a searchable list. Neither fits a 420pt column.
    (state.step as? AuthStep.QrLogin)?.let { qr ->
        QrLoginPage(qr, state, viewModel::onEvent, modifier, appVersion)
        return
    }
    if (state.step == AuthStep.ProjectSelection) {
        ProjectListScreen(
            state, viewModel::onEvent, themeMode, onThemeModeChange, modifier, createViewModel,
            joinViewModel, language, onLanguageChange,
        )
        return
    }

    Box(
        modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(CARD_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Branding()

            when (val step = state.step) {
                AuthStep.Email -> EmailStep(state, viewModel::onEvent)
                is AuthStep.Otp -> OtpStep(state, step.email, viewModel::onEvent)
                AuthStep.Recovery -> RecoveryStep(state, viewModel::onEvent)
                // Both handled above, full-bleed.
                is AuthStep.QrLogin -> Unit
                AuthStep.ProjectSelection -> Unit
                AuthStep.Complete -> Unit
            }

            state.error?.let { ErrorBanner(it) }
        }
    }
}

@Composable
private fun Branding() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(S.app_name),
            style = ZillitTheme.typography.displayLarge,
            color = ZillitTheme.colors.accent,
        )
        ZillitText(
            text = str(S.desktop_project_management),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun EmailStep(state: AuthUiState, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText(str(S.desktop_sign_in), style = ZillitTheme.typography.titleMedium)
    ZillitText(
        // Zillit has no password — say so, or the user waits for a field that
        // never appears.
        text = str(S.desktop_sign_in_no_password),
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.email,
        onValueChange = { onEvent(AuthEvent.EmailChanged(it)) },
        label = str(S.desktop_work_email),
        placeholder = "you@production.com",
        leadingIcon = ZillitIcons.Mail,
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Go,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitEmail) onEvent(AuthEvent.SubmitEmail) },
    )

    ZillitButton(
        text = str(S.desktop_send_code),
        onClick = { onEvent(AuthEvent.SubmitEmail) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitEmail,
        loading = state.isBusy,
    )

    ZillitButton(
        text = str(S.desktop_recover_a_device),
        onClick = { onEvent(AuthEvent.StartRecovery) },
        modifier = Modifier.fillMaxWidth(),
        variant = ButtonVariant.Tertiary,
        enabled = !state.isBusy,
    )
}

@Composable
private fun OtpStep(state: AuthUiState, email: String, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText(str(S.txt_email_code), style = ZillitTheme.typography.titleMedium)
    ZillitText(
        text = str(S.desktop_we_sent_a_code_to, email),
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.otp,
        onValueChange = { onEvent(AuthEvent.OtpChanged(it)) },
        label = str(S.desktop_verification_code),
        placeholder = "000000",
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Go,
        maxLength = OTP_MAX_LENGTH,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitOtp) onEvent(AuthEvent.SubmitOtp) },
    )

    ZillitButton(
        text = str(S.txt_verify),
        onClick = { onEvent(AuthEvent.SubmitOtp) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitOtp,
        loading = state.isBusy,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = str(S.back),
            onClick = { onEvent(AuthEvent.Back) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
        ZillitButton(
            text = str(S.desktop_resend),
            onClick = { onEvent(AuthEvent.ResendOtp) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
    }
}

@Composable
private fun RecoveryStep(state: AuthUiState, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText(str(S.desktop_recover_a_device), style = ZillitTheme.typography.titleMedium)
    ZillitText(
        text = str(S.desktop_recovery_code_hint),
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.recoveryCode,
        onValueChange = { onEvent(AuthEvent.RecoveryCodeChanged(it)) },
        label = str(S.recovery_code),
        imeAction = ImeAction.Go,
        enabled = !state.isBusy,
        onImeAction = { onEvent(AuthEvent.SubmitRecovery) },
    )

    ZillitButton(
        text = str(S.desktop_recover),
        onClick = { onEvent(AuthEvent.SubmitRecovery) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.recoveryCode.isNotBlank() && !state.isBusy,
        loading = state.isBusy,
    )
    ZillitButton(
        text = str(S.back),
        onClick = { onEvent(AuthEvent.Back) },
        modifier = Modifier.fillMaxWidth(),
        variant = ButtonVariant.Tertiary,
        enabled = !state.isBusy,
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.dangerSoft)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = message,
            modifier = Modifier.weight(1f),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.danger,
            textAlign = TextAlign.Start,
        )
    }
}

private val CARD_WIDTH = 420.dp
private const val OTP_MAX_LENGTH = 6

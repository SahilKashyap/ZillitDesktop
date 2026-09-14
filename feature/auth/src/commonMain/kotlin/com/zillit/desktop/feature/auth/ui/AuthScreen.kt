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
            joinViewModel,
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
            text = "Zillit",
            style = ZillitTheme.typography.displayLarge,
            color = ZillitTheme.colors.accent,
        )
        ZillitText(
            text = "Project management",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun EmailStep(state: AuthUiState, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText("Sign in", style = ZillitTheme.typography.titleMedium)
    ZillitText(
        // Zillit has no password — say so, or the user waits for a field that
        // never appears.
        text = "We'll email you a one-time code. There's no password to remember.",
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.email,
        onValueChange = { onEvent(AuthEvent.EmailChanged(it)) },
        label = "Work email",
        placeholder = "you@production.com",
        leadingIcon = ZillitIcons.Mail,
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Go,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitEmail) onEvent(AuthEvent.SubmitEmail) },
    )

    ZillitButton(
        text = "Send code",
        onClick = { onEvent(AuthEvent.SubmitEmail) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitEmail,
        loading = state.isBusy,
    )

    ZillitButton(
        text = "Recover a device",
        onClick = { onEvent(AuthEvent.StartRecovery) },
        modifier = Modifier.fillMaxWidth(),
        variant = ButtonVariant.Tertiary,
        enabled = !state.isBusy,
    )
}

@Composable
private fun OtpStep(state: AuthUiState, email: String, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText("Check your email", style = ZillitTheme.typography.titleMedium)
    ZillitText(
        text = "We sent a code to $email.",
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.otp,
        onValueChange = { onEvent(AuthEvent.OtpChanged(it)) },
        label = "Verification code",
        placeholder = "000000",
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Go,
        maxLength = OTP_MAX_LENGTH,
        enabled = !state.isBusy,
        onImeAction = { if (state.canSubmitOtp) onEvent(AuthEvent.SubmitOtp) },
    )

    ZillitButton(
        text = "Verify",
        onClick = { onEvent(AuthEvent.SubmitOtp) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canSubmitOtp,
        loading = state.isBusy,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = "Back",
            onClick = { onEvent(AuthEvent.Back) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
        ZillitButton(
            text = "Resend",
            onClick = { onEvent(AuthEvent.ResendOtp) },
            modifier = Modifier.weight(1f),
            variant = ButtonVariant.Tertiary,
            enabled = !state.isBusy,
        )
    }
}

@Composable
private fun RecoveryStep(state: AuthUiState, onEvent: (AuthEvent) -> kotlin.Unit) {
    ZillitText("Recover a device", style = ZillitTheme.typography.titleMedium)
    ZillitText(
        text = "Enter the recovery code from your other signed-in device.",
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )

    ZillitTextField(
        value = state.recoveryCode,
        onValueChange = { onEvent(AuthEvent.RecoveryCodeChanged(it)) },
        label = "Recovery code",
        imeAction = ImeAction.Go,
        enabled = !state.isBusy,
        onImeAction = { onEvent(AuthEvent.SubmitRecovery) },
    )

    ZillitButton(
        text = "Recover",
        onClick = { onEvent(AuthEvent.SubmitRecovery) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.recoveryCode.isNotBlank() && !state.isBusy,
        loading = state.isBusy,
    )
    ZillitButton(
        text = "Back",
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

package com.zillit.desktop.feature.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitQrCode
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The sign-in landing page — a port of the web client's `/device/login`.
 *
 * ## Why this one screen ignores the theme
 *
 * Everything else in the app reads light/dark from [ZillitTheme]. This page is
 * fixed dark-on-orange in both, because it is a brand splash shown *before* any
 * user preference exists to read: the theme preference is user-scoped, and there
 * is no user yet. Rendering a light card here would leave the desktop client
 * looking like a different product from the web one at the only moment the two
 * are placed side by side. The colours are still declared as semantic roles
 * (`signInCard`, `signInBackdrop`), so the "no raw colours outside the palette"
 * rule holds.
 *
 * The instruction copy is transcribed from `Login.jsx` verbatim, including its
 * step numbering — a user following along on their phone is reading the same
 * five steps whichever client they started from.
 */
@Composable
internal fun QrLoginPage(
    step: AuthStep.QrLogin,
    state: AuthUiState,
    onEvent: (AuthEvent) -> Unit,
    modifier: Modifier = Modifier,
    appVersion: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.signInBackdrop)
            .zillitVerticalScroll(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CARD_MAX_WIDTH)
                .padding(horizontal = ZillitTheme.spacing.xxl, vertical = CARD_TOP_INSET)
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.signInCard)
                .padding(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxl),
        ) {
            SignInHeader()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Instructions(
                    modifier = Modifier.weight(INSTRUCTIONS_WEIGHT),
                    onEvent = onEvent,
                )
                QrPanel(
                    step = step,
                    isBusy = state.isBusy,
                    onEvent = onEvent,
                    modifier = Modifier.weight(QR_WEIGHT),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ZillitTheme.colors.signInTextMuted.copy(alpha = DIVIDER_ALPHA)),
            )

            // The footer under the rule: which build this is, for the person
            // who cannot get past this page and is asked.
            appVersion?.takeIf { it.isNotBlank() }?.let { version ->
                ZillitText(
                    text = "Zillit Desktop $version",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.signInTextMuted,
                    modifier = Modifier.fillMaxWidth().testTag(VERSION_TAG),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SignInHeader() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A lettermark rather than a bitmap: the desktop build ships no image
        // resources yet, and a placeholder file would be worse than a shape
        // drawn from the brand colour.
        Box(
            modifier = Modifier
                .size(LOGO_SIZE)
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.signInStepBadge),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = "Z",
                style = ZillitTheme.typography.titleLarge,
                color = ZillitTheme.colors.signInText,
            )
        }
        ZillitText(
            text = "Zillit Desktop",
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.signInText,
        )
    }
}

@Composable
private fun Instructions(
    onEvent: (AuthEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
    ) {
        ZillitText(
            text = "USE ZILLIT APP ON YOUR COMPUTER",
            style = ZillitTheme.typography.titleLarge,
            color = ZillitTheme.colors.signInText,
        )

        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            SIGN_IN_STEPS.forEachIndexed { index, text ->
                NumberedStep(number = index + 1, text = text)
            }
        }

        // Opened through an effect rather than directly: only the app module
        // knows how to reach the host browser, and a feature module that could
        // launch arbitrary URLs is a wider surface than this needs.
        Row(
            modifier = Modifier.clickable { onEvent(AuthEvent.OpenCorporateSite) },
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "Visit Corporate Web Site",
                style = ZillitTheme.typography.button,
                color = ZillitTheme.colors.signInLink,
            )
            ZillitText(
                text = "→",
                style = ZillitTheme.typography.button,
                color = ZillitTheme.colors.signInLink,
            )
        }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Box(
            modifier = Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(ZillitTheme.colors.signInStepBadge),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = number.toString(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.signInText,
            )
        }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyLarge,
            color = ZillitTheme.colors.signInTextMuted,
        )
    }
}

/**
 * The QR, plus the reload control that replaces it once the poll window closes.
 *
 * The stale code stays on screen underneath the overlay rather than being
 * cleared, matching the web. A blank panel would read as a broken page; a dimmed
 * one reads as "this needs a nudge".
 */
@Composable
private fun QrPanel(
    step: AuthStep.QrLogin,
    isBusy: Boolean,
    onEvent: (AuthEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            // No code and nothing in flight means the fetch failed. Offering the
            // same reload control the stale path uses keeps that from being a
            // dead end — the error text alone would leave nothing to click.
            step.session == null && !isBusy ->
                ReloadOverlay(onClick = { onEvent(AuthEvent.RefreshQrCode) })

            step.session == null -> QrPlaceholder()

            else -> Box(contentAlignment = Alignment.Center) {
                ZillitQrCode(content = step.session.code, size = QR_SIZE)

                if (step.stale) {
                    ReloadOverlay(onClick = { onEvent(AuthEvent.RefreshQrCode) })
                }
            }
        }
    }
}

@Composable
private fun QrPlaceholder() {
    Box(
        modifier = Modifier
            .size(QR_SIZE)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.signInTextMuted.copy(alpha = PLACEHOLDER_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = "Preparing your sign-in code…",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.signInText,
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
        )
    }
}

@Composable
private fun ReloadOverlay(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(QR_SIZE)
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.scrim.copy(alpha = OVERLAY_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .size(RELOAD_BUTTON_SIZE)
                .clip(CircleShape)
                .background(ZillitTheme.colors.signInBackdrop)
                .clickable(onClick = onClick)
                .padding(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZillitIcon(
                icon = ZillitIcons.Reload,
                contentDescription = null,
                tint = ZillitTheme.colors.signInText,
                size = RELOAD_ICON_SIZE,
            )
            Spacer(Modifier.height(ZillitTheme.spacing.md))
            ZillitText(
                text = "CLICK TO RELOAD QR CODE",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.signInText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Transcribed from `Login.jsx`'s English fallbacks, verbatim.
 *
 * These are the strings a user reads on their phone while following along, so
 * they must not drift from the web wording. They stay hardcoded until the
 * localisation pipeline lands (plan §12) — the web fetches them from
 * `localLoginPageTranslation`, which desktop has no equivalent of yet.
 */
private val SIGN_IN_STEPS = listOf(
    "Open Zillit on your phone.",
    "If you are a member of a project then follow steps 4 & 5.",
    "If you are not a member of a project you need to ‘Start A Project’ or " +
        "‘Join A Project’ (with a code), then follow steps 4 & 5.",
    "Select ‘Settings’ when you are on ‘Home’ page and then tap on " +
        "‘Connect to Zillit from your laptop’.",
    "Point your phone to the screen and scan QR code by pressing ‘Link Device’.",
)

private val CARD_MAX_WIDTH = 1100.dp
private val CARD_PADDING = 56.dp
private val CARD_TOP_INSET = 72.dp
private val LOGO_SIZE = 40.dp
private val BADGE_SIZE = 24.dp
private val QR_SIZE = 260.dp
private val RELOAD_BUTTON_SIZE = 190.dp
private val RELOAD_ICON_SIZE = 56.dp

private const val INSTRUCTIONS_WEIGHT = 1.4f
private const val QR_WEIGHT = 1f
private const val OVERLAY_ALPHA = 0.95f
private const val PLACEHOLDER_ALPHA = 0.15f
private const val DIVIDER_ALPHA = 0.3f

/** The version footer, so a test can find it by something other than its text. */
internal const val VERSION_TAG = "sign-in-version"

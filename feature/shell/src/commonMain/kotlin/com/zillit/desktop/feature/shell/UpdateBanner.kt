package com.zillit.desktop.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The line under the top bar that says a newer build exists.
 *
 * ## Why a banner and not a dialog
 *
 * The optional case must not cost anybody a keystroke. A modal on launch is
 * read once, dismissed reflexively, and trains people to dismiss the next one
 * unread — which is exactly what the Android client's in-app update prompt runs
 * into (`baseUtils/BaseActivity.kt:215` guards on `isAppUpdateIsAskedOnce`
 * precisely because asking again is so annoying). A strip under the bar is
 * visible for as long as it is true and interrupts nothing.
 *
 * ## Why the mandatory case is still a banner
 *
 * A full-screen block would stop someone mid-edit with nowhere to save to, and
 * on Linux or a build without a published installer the only way out is a
 * browser link. The mandatory banner is therefore non-dismissible and says
 * plainly that the app must be updated; where the app can install itself,
 * "Update now" is one click and the restart is the user's to time.
 *
 * ## The in-app steps
 *
 * With [UpdateNotice.install] set the strip walks Update now → a progress bar
 * → Restart now. A failed download offers Try again; a file that failed its
 * checksum or signature does not (it would fail the same way) and falls back
 * to the download page.
 *
 * ## Colour is never the only signal
 *
 * The two cases differ in wording, in whether an X exists, and in tone — the
 * same rule `ZillitStatusPill` follows. Both tones are theme tokens
 * (`infoSoft`/`info`, `dangerSoft`/`danger`), so the strip is legible in light
 * and dark without a second implementation.
 */
@Composable
internal fun UpdateBanner(
    notice: UpdateNotice,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit = {},
    onRestart: () -> Unit = {},
    onCancel: () -> Unit = {},
    onOpenDownloaded: () -> Unit = {},
) {
    val accent = if (notice.mandatory) ZillitTheme.colors.danger else ZillitTheme.colors.info
    ZillitNotice(
        text = bannerText(notice),
        tone = if (notice.mandatory) StatusTone.Rejected else StatusTone.Progress,
        icon = ZillitIcons.Download,
        modifier = modifier
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm)
            .testTag(if (notice.mandatory) BLOCKING_TAG else DISMISSIBLE_TAG),
        action = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                InstallAction(notice, accent, onInstall, onRestart, onCancel, onOpenDownloaded)
                // The page link stays wherever the app cannot finish the job
                // itself: no installer, or an install that stopped.
                if (notice.install == null || notice.install is UpdateInstall.Failed) {
                    notice.downloadUrl?.let { url ->
                        // The lambda is the app's guarded launcher — https only.
                        // The URL came out of a remote console, so nothing here may
                        // hand it to the OS directly.
                        ZillitButton(
                            // Says where it goes. Once the app can download
                            // in place, the only button that leaves it must
                            // not look like the one that does not.
                            text = if (notice.manual) str(S.desktop_update_open_in_browser) else str(S.download),
                            onClick = { onDownload(url) },
                            size = ButtonSize.Small,
                            variant = if (notice.mandatory) ButtonVariant.Danger else ButtonVariant.Secondary,
                        )
                    }
                }
                // Absent when mandatory: there is no "later" for a build the
                // server will stop serving.
                // Nor while downloading: dismissing would hide a download
                // that is still running, with no way back to cancel it.
                if (!notice.mandatory && notice.install !is UpdateInstall.Downloading) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.desktop_dismiss_update),
                        onClick = onDismiss,
                        tint = accent,
                    )
                }
            }
        },
    )
}

/** The strip's sentence for where the update has got to. */
@Composable
private fun bannerText(notice: UpdateNotice): String {
    val version = notice.latestVersion
    return when (val install = notice.install) {
        is UpdateInstall.Downloading -> install.percent
            ?.let { str(S.desktop_update_downloading_percent, version, it) }
            ?: str(S.desktop_update_downloading, version)
        UpdateInstall.Preparing -> str(S.desktop_update_preparing, version)
        UpdateInstall.Ready -> str(S.desktop_update_ready, version)
        UpdateInstall.Downloaded -> str(S.desktop_update_downloaded, version)
        is UpdateInstall.Failed -> when {
            install.retryable -> str(S.desktop_update_failed_network, version)
            install.verification -> str(S.desktop_update_failed_verify, version)
            else -> str(S.desktop_update_failed_install, version)
        }
        UpdateInstall.Offer, null -> {
            val installed = notice.installedVersion?.takeIf { it.isNotBlank() }
                ?.let { " " + str(S.desktop_update_installed, it) }.orEmpty()
            val headline = if (notice.mandatory) S.desktop_update_required else S.desktop_update_available
            str(headline, version) + installed
        }
    }
}

/** Update now, the progress bar, Restart now or Try Again — whichever the step calls for. */
@Composable
private fun InstallAction(
    notice: UpdateNotice,
    accent: Color,
    onInstall: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onOpenDownloaded: () -> Unit,
) {
    val primary = if (notice.mandatory) ButtonVariant.Danger else ButtonVariant.Primary
    when (val install = notice.install) {
        UpdateInstall.Offer -> ZillitButton(
            // "Download" when the app fetches the file and hands it over;
            // "Update now" only when it can install it itself.
            text = if (notice.manual) str(S.download) else str(S.update_now),
            onClick = onInstall,
            size = ButtonSize.Small,
            variant = primary,
            modifier = Modifier.testTag(INSTALL_TAG),
        )
        is UpdateInstall.Downloading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            val percent = install.percent
            if (percent != null) {
                ZillitProgressBar(
                    fraction = percent / PERCENT,
                    // Read out as progress, not an unlabeled shape.
                    modifier = Modifier.width(PROGRESS_WIDTH).progressSemantics(percent / PERCENT)
                        .testTag(PROGRESS_TAG),
                    fillColor = accent,
                )
            } else {
                // No length from the server: a spinner says "working" where a
                // bar would sit empty and look stuck.
                ZillitSpinner(modifier = Modifier.testTag(PROGRESS_TAG), color = accent)
            }
            ZillitButton(
                text = str(S.cancel),
                onClick = onCancel,
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(CANCEL_TAG),
            )
        }
        UpdateInstall.Downloaded -> ZillitButton(
            text = str(S.desktop_update_open_installer),
            onClick = onOpenDownloaded,
            size = ButtonSize.Small,
            variant = primary,
            modifier = Modifier.testTag(OPEN_INSTALLER_TAG),
        )
        UpdateInstall.Ready -> ZillitButton(
            text = str(S.desktop_update_restart_now),
            onClick = onRestart,
            size = ButtonSize.Small,
            variant = primary,
            modifier = Modifier.testTag(RESTART_TAG),
        )
        is UpdateInstall.Failed -> if (install.retryable) {
            ZillitButton(
                text = str(S.try_again),
                onClick = onInstall,
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
            )
        }
        UpdateInstall.Preparing, null -> Unit
    }
}

private const val PERCENT = 100f
private val PROGRESS_WIDTH = 120.dp

/** The progress bar, or the spinner when the size is unknown. */
internal const val PROGRESS_TAG = "update-progress"

/** "Cancel", while downloading. */
internal const val CANCEL_TAG = "update-cancel"

/** "Show installer", once downloaded. */
internal const val OPEN_INSTALLER_TAG = "update-open-installer"

/** "Update now". */
internal const val INSTALL_TAG = "update-install"

/** "Restart now". */
internal const val RESTART_TAG = "update-restart"

/** The optional strip, which carries a dismiss control. */
internal const val DISMISSIBLE_TAG = "update-banner"

/** The mandatory strip, which does not. Separate tags so a test cannot confuse them. */
internal const val BLOCKING_TAG = "update-banner-blocking"

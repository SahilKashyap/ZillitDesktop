package com.zillit.desktop.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
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
 * This is step one of the update story: the check and the notice. It has no
 * downloader, so a full-screen block would leave someone staring at a wall with
 * a browser link on it and no way back to the work they had open. The mandatory
 * banner is therefore non-dismissible and says plainly that the app must be
 * updated to continue; hard-blocking belongs with the installer that can
 * actually resolve it.
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
) {
    val colors = ZillitTheme.colors
    val tone = if (notice.mandatory) StatusTone.Rejected else StatusTone.Progress
    val accent = if (notice.mandatory) colors.danger else colors.info

    val installed = notice.installedVersion?.takeIf { it.isNotBlank() }
        ?.let { " " + str(S.desktop_update_installed, it) }.orEmpty()
    ZillitNotice(
        text = if (notice.mandatory) {
            str(S.desktop_update_required, notice.latestVersion) + installed
        } else {
            str(S.desktop_update_available, notice.latestVersion) + installed
        },
        tone = tone,
        icon = ZillitIcons.Download,
        modifier = modifier
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm)
            .testTag(if (notice.mandatory) BLOCKING_TAG else DISMISSIBLE_TAG),
        action = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                notice.downloadUrl?.let { url ->
                    // The lambda is the app's guarded launcher — https only.
                    // The URL came out of a remote console, so nothing here may
                    // hand it to the OS directly.
                    ZillitButton(
                        text = str(S.download),
                        onClick = { onDownload(url) },
                        size = ButtonSize.Small,
                        variant = if (notice.mandatory) ButtonVariant.Danger else ButtonVariant.Secondary,
                    )
                }
                // Absent when mandatory: there is no "later" for a build the
                // server will stop serving.
                if (!notice.mandatory) {
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

/** The optional strip, which carries a dismiss control. */
internal const val DISMISSIBLE_TAG = "update-banner"

/** The mandatory strip, which does not. Separate tags so a test cannot confuse them. */
internal const val BLOCKING_TAG = "update-banner-blocking"

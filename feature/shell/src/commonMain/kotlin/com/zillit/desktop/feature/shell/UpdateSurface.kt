package com.zillit.desktop.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The update strip, and the blocking screen, around a page that is not the
 * shell — the QR sign-in page and the production picker.
 *
 * ## Why these screens need it at all
 *
 * The strip used to live only inside [AppShell], so it appeared once a
 * production was open and nowhere else. Someone who signs in and stops at the
 * picker — or who never gets past the QR page, which is exactly where a build
 * too old to authenticate leaves them — was told nothing, while the one
 * version number they could read sat in the corner of the page saying only
 * what they already had.
 *
 * A mandatory update matters more here, not less: below the floor the server
 * will refuse the calls these pages are made of, so the blocking screen has to
 * cover them too or the person simply watches sign-in fail.
 *
 * ## Why it wraps rather than being built in
 *
 * The shell places its own strip under the top bar, between chrome it owns.
 * These pages have no chrome to sit under, so the strip goes above them and
 * the page takes what is left — which is layout the page should not have to
 * know about. [AppShell] therefore keeps its own placement and this exists for
 * everything else; both draw the same [UpdateBanner] and [ForceUpdateScreen].
 */
@Composable
@Suppress("LongParameterList") // one per callback the strip exposes
fun UpdateSurface(
    notice: UpdateNotice?,
    modifier: Modifier = Modifier,
    onDownload: (String) -> Unit = {},
    onInstall: () -> Unit = {},
    onCancel: () -> Unit = {},
    onOpen: () -> Unit = {},
    onRestart: () -> Unit = {},
    /** Quit, offered on the blocking screen; null leaves it off. */
    onQuit: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Per version and per session, as the shell's is: dismissing 1.2.0 must
    // not also silence 1.3.0.
    var dismissed by remember { mutableStateOf<UpdateDismissal?>(null) }
    val strip = notice?.takeIf { !it.blocking && it.shownAfter(dismissed) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            strip?.let {
                UpdateBanner(
                    notice = it,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onRestart = onRestart,
                    onCancel = onCancel,
                    onOpenDownloaded = onOpen,
                    onDismiss = { dismissed = UpdateDismissal(it.latestVersion, it.requests) },
                )
            }
            Box(Modifier.weight(1f)) { content() }
        }

        // Over the page, as it is over the shell: below the floor nothing
        // behind it is worth reaching.
        notice?.takeIf { it.blocking }?.let {
            ForceUpdateScreen(
                notice = it,
                onDownload = onDownload,
                onInstall = onInstall,
                onRestart = onRestart,
                onCancel = onCancel,
                onOpenDownloaded = onOpen,
                onQuit = onQuit,
            )
        }
    }
}

package com.zillit.desktop.core.workspace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect

/**
 * How many times this machine has woken from sleep.
 *
 * ## Why a composition local
 *
 * A tool loads once, in `LaunchedEffect(viewModel) { viewModel.start() }`, and
 * then waits for the socket. A lid closed on that tool is an outage the socket
 * cannot report — whatever was posted, approved or sent while the machine slept
 * was announced to a connection that no longer existed, and nothing replays it
 * (see `WakeWatcher` and `AppGraph.resyncAfterSleep`). The screen keeps showing
 * what it read before the lid went down, with no error and no spinner.
 *
 * Reading the signal **through the composition** is what makes it cheap:
 * [WorkspaceHost] disposes an inactive window's composition, so a tool is
 * composed only while it is the tab on show. A wake therefore costs one re-read
 * for the tool somebody is actually looking at, not forty fan-outs for every
 * tool opened since sign-in. (A tool switched away from and back re-runs its
 * own `LaunchedEffect` on the fresh composition, so it is already current — the
 * tool left *on screen* across the sleep is the only one that goes stale.)
 *
 * A counter rather than the sleep's duration: two wakes could report the same
 * number of milliseconds, and a key that repeats fires nothing.
 *
 * Defaults to zero, so a composition nobody has provided this to — a tool
 * widget in its own OS window — simply never sees a wake rather than failing.
 */
val LocalResumeToken = compositionLocalOf { 0 }

/**
 * Calls [reload] when the machine wakes while this screen is on show.
 *
 * Sits next to a tool's own `LaunchedEffect(viewModel) { start() }` and means
 * the same thing a wake means everywhere else in this app: **re-read, never
 * reset**. Point it at the tool's idempotent load — the one a mutation already
 * runs — and never at something that clears a draft, a selected tab or a
 * scroll position. The reader left the tool open on purpose; a wake must cost
 * them nothing.
 *
 * The token in force when the screen appeared is remembered and skipped, so
 * this never duplicates the load the screen makes for itself. [remember], not
 * `rememberSaveable`: a tab switched away and back is a new composition that
 * loads again anyway, and a *saved* baseline would suppress the re-read of a
 * tool restored into a session that has since woken.
 */
@Composable
fun ReloadOnWake(reload: suspend () -> Unit) {
    val token = LocalResumeToken.current
    // What was true when this screen opened. It loaded then, so that value is
    // a baseline, not a wake.
    val openedAt = remember { token }
    val current = rememberUpdatedState(reload)
    LaunchedEffect(token) {
        if (token != openedAt) current.value()
    }
}

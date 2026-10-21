package com.zillit.desktop.core.workspace

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.workspace.ui.LocalResumeToken
import com.zillit.desktop.core.workspace.ui.ReloadOnWake
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wake re-read every adopting tool shares.
 *
 * All ten adoptions are one line calling this, so the contract is tested here
 * rather than ten times over: a tool that re-read when it should not would
 * double every page load in the app, and one that did not re-read is the bug
 * this was built for.
 */
@OptIn(ExperimentalTestApi::class)
class ReloadOnWakeTest {

    @Test
    fun `the token in force when the screen opens is a baseline, not a wake`() = runComposeUiTest {
        var reloads = 0
        setContent {
            // Not zero: a tool opened *after* a wake must not re-read merely
            // because the count is already up.
            CompositionLocalProvider(LocalResumeToken provides 3) {
                ReloadOnWake { reloads += 1 }
            }
        }
        waitForIdle()

        assertEquals(0, reloads, "the screen loads for itself; this must not double it")
    }

    @Test
    fun `a wake while the screen is on show re-reads once`() = runComposeUiTest {
        var reloads = 0
        var token by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalResumeToken provides token) {
                ReloadOnWake { reloads += 1 }
            }
        }
        waitForIdle()

        token = 1
        waitForIdle()

        assertEquals(1, reloads)
    }

    @Test
    fun `two wakes re-read twice`() = runComposeUiTest {
        var reloads = 0
        var token by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalResumeToken provides token) {
                ReloadOnWake { reloads += 1 }
            }
        }
        waitForIdle()

        token = 1
        waitForIdle()
        token = 2
        waitForIdle()

        // A counter, not the sleep's length, precisely so the second wake is
        // a different key from the first.
        assertEquals(2, reloads)
    }

    @Test
    fun `the newest reload is the one called`() = runComposeUiTest {
        var stale = 0
        var fresh = 0
        var token by mutableStateOf(0)
        var useFresh by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalResumeToken provides token) {
                // `rememberUpdatedState` inside: a lambda that closes over the
                // tool's current state must not be the one captured when the
                // screen opened.
                ReloadOnWake(if (useFresh) ({ fresh += 1 }) else ({ stale += 1 }))
            }
        }
        waitForIdle()

        useFresh = true
        waitForIdle()
        token = 1
        waitForIdle()

        assertEquals(0, stale)
        assertEquals(1, fresh)
    }

    @Test
    fun `a tool composed again after a wake does not re-read`() = runComposeUiTest {
        var reloads = 0
        var token by mutableStateOf(0)
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalResumeToken provides token) {
                if (shown) ReloadOnWake { reloads += 1 }
            }
        }
        waitForIdle()

        // The tab is switched away from — WorkspaceHost disposes it — the
        // machine sleeps, and the tab comes back. The fresh composition runs
        // the tool's own load, so this must not add a second one.
        shown = false
        waitForIdle()
        token = 1
        waitForIdle()
        shown = true
        waitForIdle()

        assertEquals(0, reloads)
    }
}

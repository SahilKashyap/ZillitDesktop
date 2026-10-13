package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full screen means the clip fills the screen — not the app filling it around
 * a clip that stayed the size of its pane.
 *
 * That was the shipped behaviour: the button reached for the OS window, so on
 * a Mac the whole of Zillit went full screen and the picture sat in the same
 * strip of thread pane it had always been in. The fix moves the player out of
 * the pane into the window's own slot, which is what these tests measure: the
 * picture's width, against the pane's and against the window's.
 */
@OptIn(ExperimentalTestApi::class)
class VideoFullScreenTest {

    @Test
    fun `full screen takes the picture out of its pane and fills the window`() = runComposeUiTest {
        val engine = FakeEngine()
        setContent { Pane(engine) }
        waitForIdle()

        val pane = onNodeWithText(URL).fetchSemanticsNode().size.width
        val window = onRoot().fetchSemanticsNode().size.width
        assertTrue(pane < window, "the clip starts inside its pane ($pane of $window)")

        onNodeWithContentDescription("Full screen").performClick()
        waitForIdle()

        assertEquals(window, onNodeWithText(URL).fetchSemanticsNode().size.width)
        // The same clip, still playing: moved, not opened again from the top.
        assertEquals(1, engine.opened.size)
        assertEquals(false, engine.opened.single().closed)
    }

    @Test
    fun `collapsing puts it back in the pane, still the same clip`() = runComposeUiTest {
        val engine = FakeEngine()
        setContent { Pane(engine) }
        waitForIdle()

        val pane = onNodeWithText(URL).fetchSemanticsNode().size.width
        onNodeWithContentDescription("Full screen").performClick()
        waitForIdle()
        onNodeWithContentDescription("Exit full screen").performClick()
        waitForIdle()

        assertEquals(pane, onNodeWithText(URL).fetchSemanticsNode().size.width)
        assertEquals(1, engine.opened.size)
        assertEquals(false, engine.opened.single().closed)
    }

    @Test
    fun `the window follows the clip, and goes back only if we took it`() = runComposeUiTest {
        val window = FakeWindow()
        setContent {
            CompositionLocalProvider(LocalFullScreen provides window) { Pane(FakeEngine()) }
        }
        waitForIdle()

        onNodeWithContentDescription("Full screen").performClick()
        waitForIdle()
        assertTrue(window.isFullScreen, "the display, not merely this window")

        onNodeWithContentDescription("Exit full screen").performClick()
        waitForIdle()
        assertEquals(false, window.isFullScreen)
    }

    @Test
    fun `a window the viewer found full screen is left that way`() = runComposeUiTest {
        val window = FakeWindow().apply { isFullScreen = true }
        setContent {
            CompositionLocalProvider(LocalFullScreen provides window) { Pane(FakeEngine()) }
        }
        waitForIdle()

        onNodeWithContentDescription("Full screen").performClick()
        waitForIdle()
        onNodeWithContentDescription("Exit full screen").performClick()
        waitForIdle()

        assertTrue(window.isFullScreen, "it was the user's full screen, not ours to undo")
    }

    @Test
    fun `no slot in the window, no button offering one`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides FakeEngine()) {
                    Box(Modifier.size(PANE, PANE)) {
                        ZillitVideoView(url = URL, failed = false, onOpenOutside = null)
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Full screen").assertDoesNotExist()
    }

    /** A clip in a small pane, in a window with a full-screen slot: the real shape. */
    @Composable
    private fun Pane(engine: FakeEngine) {
        ZillitTheme {
            CompositionLocalProvider(LocalVideoEngine provides engine) {
                ZillitVideoStage {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(PANE, PANE)) {
                            ZillitVideoView(
                                url = URL,
                                failed = false,
                                onOpenOutside = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val URL = "https://b.s3.r.amazonaws.com/s3/stunt.mp4?X-Amz-Signature=abc"
        val PANE = 200.dp
    }
}

/** The window's switch, as a host would provide it, with nothing behind it. */
private class FakeWindow : FullScreen {
    override var isFullScreen = false

    override fun toggle() {
        isFullScreen = !isFullScreen
    }
}

/** Says what it was given and remembers being shut — no decoder, no window. */
private class FakeEngine : VideoEngine {
    val opened = mutableListOf<FakePlayback>()

    override fun open(url: String): VideoPlayback = FakePlayback(url).also { opened += it }

    @Composable
    override fun Picture(playback: VideoPlayback, modifier: Modifier) {
        // Text rather than a box: its width is the picture's, and the node is
        // findable by the address the engine was handed.
        ZillitText(text = (playback as FakePlayback).url, modifier = modifier)
    }
}

private class FakePlayback(val url: String) : VideoPlayback {
    override val state = MutableStateFlow(
        VideoPlaybackState(durationMillis = CLIP_MILLIS, isPlaying = true, started = true),
    )
    var closed = false
        private set

    override fun toggle() {
        state.value = state.value.copy(isPlaying = !state.value.isPlaying)
    }

    override fun toggleMute() {
        state.value = state.value.copy(isMuted = !state.value.isMuted)
    }

    override fun seek(fraction: Float) = Unit

    override fun close() {
        closed = true
    }
}

private const val CLIP_MILLIS = 3_000L

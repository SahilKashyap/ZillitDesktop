package com.zillit.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.LocalVideoEngine
import com.zillit.desktop.core.designsystem.component.VideoEngine
import com.zillit.desktop.core.designsystem.component.VideoPlayback
import com.zillit.desktop.core.designsystem.component.VideoPlaybackState
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A clip in a thread plays in the pane, and only the Download beside it is
 * gated.
 *
 * Composed rather than unit-tested for the reason the whole feature exists: a
 * bubble wired to the wrong handler is a click that silently leaves the app,
 * and no model test can see which handler a bubble was given.
 */
@OptIn(ExperimentalTestApi::class)
class ChatVideoViewerRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val now = System.currentTimeMillis()

    private val clip = ChatAttachment(
        media = "s3/stunt.mp4",
        name = "stunt.mp4",
        contentType = "video/mp4",
        thumbnail = "s3/stunt.jpg",
    )

    private val theirClip = ChatMessage(
        id = "srv-1",
        uniqueId = "w-1",
        senderId = "u1",
        receiverId = "me",
        body = "",
        timestampMillis = now - 60_000L,
        isMine = false,
        attachment = clip,
    )


    @Test
    fun `a clip opens the player rather than leaving for the OS`() = runComposeUiTest {
        var handedOut: ChatAttachment? = null
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalChatSeams provides ChatSeams(videoUrl = { SIGNED_URL }),
                    LocalVideoEngine provides FakeVideoEngine(),
                ) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(theirClip)),
                        onEvent = {},
                        loadThumbnail = { ImageBitmap(4, 4) },
                        onOpenAttachment = { handedOut = it },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithContentDescription("stunt.mp4").performClick()
        waitForIdle()

        onNodeWithText(SIGNED_URL).assertExists("the player got the signed object")
        onNodeWithContentDescription("Close viewer").assertExists()
        // Watching is looking: nothing was saved to disk to make it happen.
        assertNull(handedOut)
    }

    @Test
    fun `watching is ungated, saving is not`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalChatSeams provides ChatSeams(
                        canDownload = { false },
                        videoUrl = { SIGNED_URL },
                    ),
                    LocalVideoEngine provides FakeVideoEngine(),
                ) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(theirClip)),
                        onEvent = {},
                        loadThumbnail = { ImageBitmap(4, 4) },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithContentDescription("stunt.mp4").performClick()
        waitForIdle()

        // It plays for someone with no download right at all…
        onNodeWithText(SIGNED_URL).assertExists()
        // …and the Download beside it still refuses, in Android's own words.
        onNodeWithText("Download").performClick()
        waitForIdle()
        onNodeWithText(com.zillit.desktop.feature.chat.ui.DOWNLOAD_REFUSED).assertExists()
    }

    @Test
    fun `with no seam to sign one, the clip says so and offers the hand-off`() = runComposeUiTest {
        var handedOut: ChatAttachment? = null
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides FakeVideoEngine()) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(theirClip)),
                        onEvent = {},
                        loadThumbnail = { ImageBitmap(4, 4) },
                        onOpenAttachment = { handedOut = it },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithContentDescription("stunt.mp4").performClick()
        waitForIdle()

        onNodeWithText("Could not play this video.").assertExists()
        onNodeWithText("Open outside the app").performClick()
        waitForIdle()
        assertEquals(clip, handedOut)
        // The viewer closed first, so nothing is left behind the OS's window.
        onNodeWithContentDescription("Close viewer").assertDoesNotExist()
    }

    private companion object {
        const val SIGNED_URL = "https://b.s3.r.amazonaws.com/s3/stunt.mp4?X-Amz-Signature=abc"
    }
}

/**
 * A player that only has to say what it was given, and remember being shut.
 *
 * Stands in for JavaFX: the real engine needs a window, a decoder and a
 * network, none of which a render test has or should want.
 */
private class FakeVideoEngine : VideoEngine {
    val opened = mutableListOf<FakePlayback>()

    override fun open(url: String): VideoPlayback = FakePlayback(url).also { opened += it }

    @Composable
    override fun Picture(playback: VideoPlayback, modifier: Modifier) {
        ZillitText(text = (playback as FakePlayback).url)
    }
}

private class FakePlayback(val url: String) : VideoPlayback {
    override val state = MutableStateFlow(
        // Started: most tests are about a clip already on screen. The wait
        // before the first frame has its own test.
        VideoPlaybackState(durationMillis = CLIP_MILLIS, isPlaying = true, started = true),
    )
    var closed = false
        private set
    var seekedTo: Float? = null
        private set

    override fun toggle() {
        state.value = state.value.copy(isPlaying = !state.value.isPlaying)
    }

    override fun toggleMute() {
        state.value = state.value.copy(isMuted = !state.value.isMuted)
    }

    override fun seek(fraction: Float) {
        seekedTo = fraction
    }

    override fun close() {
        closed = true
    }

    /** Whatever the player hits part way through — a codec, an expired signature. */
    fun fail() {
        state.value = state.value.copy(failed = true, isPlaying = false)
    }

    /** Back to before the first frame — what the viewer opens into. */
    fun notStartedYet() {
        state.value = state.value.copy(started = false, isPlaying = false, durationMillis = 0)
    }
}

private const val CLIP_MILLIS = 3_000L

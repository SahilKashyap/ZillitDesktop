package com.zillit.desktop.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.LocalVideoEngine
import com.zillit.desktop.core.designsystem.component.VideoEngine
import com.zillit.desktop.core.designsystem.component.VideoPlayback
import com.zillit.desktop.core.designsystem.component.VideoPlaybackState
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The board's two viewers, on the screen rather than in the model: a clip
 * opens the in-app player instead of being handed to the OS, and both viewers
 * carry a cross.
 *
 * Worth composing rather than unit-testing because the whole bug class here is
 * a click that goes nowhere — which handler the bubble was wired with is
 * exactly what a model test cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class VideoViewerRenderTest {

    private val now = System.currentTimeMillis()

    private val unit = HomeUnit(
        id = "u1",
        identifier = "general_tool",
        unitName = "general_label",
        canView = true,
        canPost = true,
        canDownload = true,
    )

    private val clip = NoticeAttachment(
        media = "home/clip.mp4",
        fileName = "clip.mp4",
        thumbnail = "home/clip.jpg",
        bucket = "b",
        region = "r",
    )

    private val videoPost = Notice(
        id = "n1",
        body = "the stunt, take four",
        authorName = "Sam",
        authorId = "them",
        createdAtMillis = now,
        kind = NoticeKind.Video,
        attachment = clip,
    )

    private val photoPost = Notice(
        id = "n2",
        body = "the door we need",
        authorName = "Sam",
        authorId = "them",
        createdAtMillis = now,
        kind = NoticeKind.Image,
        attachment = NoticeAttachment(media = "home/door.jpg", fileName = "door.jpg", bucket = "b", region = "r"),
    )

    /** Serves posters, answers (or refuses) a stream URL, and counts full fetches. */
    private class Source(private val url: String?) : NoticeMediaSource {
        /** Full-object fetches — what a clip must never cause. */
        var wholeObjectFetches = 0
            private set

        override suspend fun fetch(
            attachment: NoticeAttachment,
            preview: Boolean,
        ): ZillitResult<ByteArray> {
            if (!preview) {
                wholeObjectFetches++
                return ZillitResult.Failure(ZillitError.NoConnection(null))
            }
            return ZillitResult.Success(POSTER)
        }

        override suspend fun streamUrl(attachment: NoticeAttachment): String? = url
    }

    private fun board(vararg posts: Notice) = HomeFeedUiState(
        units = listOf(unit),
        selectedUnitId = unit.id,
        notices = posts.toList(),
        currentUserId = "me",
        nowMillis = now,
    )

    @Test
    fun `clicking a clip plays the signed URL in-app, and downloads nothing`() = runComposeUiTest {
        var handedOut: NoticeAttachment? = null
        val source = Source(SIGNED_URL)
        val engine = FakeVideoEngine()

        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides engine) {
                    HomeFeedScreen(
                        state = board(videoPost),
                        onEvent = {},
                        media = source,
                        onOpenAttachment = { handedOut = it },
                    )
                }
            }
        }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()

        // The viewer is up, playing the presigned object, with a way out.
        onNodeWithText("clip.mp4").assertExists()
        onNodeWithText(SIGNED_URL).assertExists("the player got the signed object")
        onNodeWithContentDescription("Close viewer").assertExists()
        // Nothing was saved to disk on the way — that was the old behaviour —
        // and the player was never handed a whole file either.
        assertNull(handedOut)
        assertEquals(0, source.wholeObjectFetches)
    }

    @Test
    fun `the wait before the first frame is a spinner, not a black box`() = runComposeUiTest {
        val engine = FakeVideoEngine()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides engine) {
                    HomeFeedScreen(state = board(videoPost), onEvent = {}, media = Source(SIGNED_URL))
                }
            }
        }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()
        engine.opened.single().notStartedYet()
        waitForIdle()

        // Signing, a header read and a decoder starting are seconds of nothing
        // on a large clip; the viewer says it is working rather than showing a
        // black rectangle that reads as a failure.
        onNodeWithText("Preparing video…").assertExists()
        // No picture before the first frame.
        onNodeWithText(SIGNED_URL).assertDoesNotExist()
    }

    @Test
    fun `the transport mutes and unmutes, and offers full screen only when the host can`() =
        runComposeUiTest {
            val engine = FakeVideoEngine()
            setContent {
                ZillitTheme {
                    CompositionLocalProvider(LocalVideoEngine provides engine) {
                        HomeFeedScreen(state = board(videoPost), onEvent = {}, media = Source(SIGNED_URL))
                    }
                }
            }

            onNodeWithContentDescription("Play").performClick()
            waitForIdle()

            onNodeWithContentDescription("Mute").performClick()
            waitForIdle()
            onNodeWithContentDescription("Unmute").assertExists()
            onNodeWithContentDescription("Unmute").performClick()
            waitForIdle()
            onNodeWithContentDescription("Mute").assertExists()

            // No host switch in a render test, so no dead button is drawn —
            // the thread's rule for every affordance the host cannot honour.
            onNodeWithContentDescription("Full screen").assertDoesNotExist()
        }

    @Test
    fun `closing the viewer stops the clip`() = runComposeUiTest {
        val engine = FakeVideoEngine()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides engine) {
                    HomeFeedScreen(state = board(videoPost), onEvent = {}, media = Source(SIGNED_URL))
                }
            }
        }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()
        val playing = engine.opened.single()
        assertEquals(false, playing.closed)

        onNodeWithContentDescription("Close viewer").performClick()
        waitForIdle()

        // Not merely hidden: a player left alive is sound coming out of a
        // window that is no longer on screen.
        assertEquals(true, playing.closed)
    }

    @Test
    fun `a player that gives up part way through says so`() = runComposeUiTest {
        val engine = FakeVideoEngine()
        val fired = mutableListOf<HomeFeedEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalVideoEngine provides engine) {
                    HomeFeedScreen(state = board(videoPost), onEvent = fired::add, media = Source(SIGNED_URL))
                }
            }
        }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()
        onNodeWithText(SIGNED_URL).assertExists()

        engine.opened.single().fail()
        waitForIdle()

        // A frozen picture and a dead player look identical, so it says which.
        onNodeWithText("Could not play this video.").assertExists()
        onNodeWithText("Open outside the app").performClick()
        waitForIdle()
        assertEquals(listOf<HomeFeedEvent>(HomeFeedEvent.OpenAttachment("n1", clip)), fired)
    }

    @Test
    fun `a clip nothing can sign says so and offers the way out`() = runComposeUiTest {
        val fired = mutableListOf<HomeFeedEvent>()
        setContent {
            ZillitTheme {
                HomeFeedScreen(state = board(videoPost), onEvent = fired::add, media = Source(null))
            }
        }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()

        // Not a black rectangle: the sentence, and the hand-off behind it.
        onNodeWithText("Could not play this video.").assertExists()
        onNodeWithText("Open outside the app").performClick()
        waitForIdle()

        // The save-and-open goes through the model, as every other open does —
        // the post's id is what lets a call sheet open as its watermarked copy.
        assertEquals(listOf<HomeFeedEvent>(HomeFeedEvent.OpenAttachment("n1", clip)), fired)
        // The viewer went first, so the OS window does not open behind it.
        onNodeWithContentDescription("Close viewer").assertDoesNotExist()
    }

    @Test
    fun `the picture lightbox has a cross of its own`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(state = board(photoPost), onEvent = {}, media = Source(null))
            }
        }

        onNodeWithContentDescription("Close viewer").assertDoesNotExist()

        onNodeWithContentDescription("door.jpg").performClick()
        waitForIdle()

        onNodeWithContentDescription("Close viewer").performClick()
        waitForIdle()
        onNodeWithContentDescription("Close viewer").assertDoesNotExist()
    }

    private companion object {
        const val SIGNED_URL = "https://b.s3.r.amazonaws.com/home/clip.mp4?X-Amz-Signature=abc"

        /** A decodable poster, so bubbles draw pictures rather than file chips. */
        val POSTER: ByteArray = ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()
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

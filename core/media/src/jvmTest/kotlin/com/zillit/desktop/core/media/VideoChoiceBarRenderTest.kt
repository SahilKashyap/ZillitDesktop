package com.zillit.desktop.core.media

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The quality row as a sender reads it: three options, each with what it would
 * weigh, and the two rows that say something else instead.
 *
 * The figures behind the labels were measured against the real encoders — a
 * 99 MB, 59.8 s 1080p iPhone clip came out at 10.9 MB where SD promised
 * ~11.1 MB, and at 50.1 MB where HD promised ~52 MB.
 */
@OptIn(ExperimentalTestApi::class)
class VideoChoiceBarRenderTest {

    // A coherent clip: the bytes are the file's real weight and the header its
    // real length, because an estimate is clamped to the size of the file it
    // would replace — a stub of a few bytes prices every quality at a few bytes.
    private val item = PreviewItem("IMG_7551.MOV", "video/quicktime", ByteArray(12 * 1024 * 1024))

    /** Ten seconds of 1080p, the shape `Mp4Probe` reads off such a clip. */
    private val info = VideoInfo(durationSeconds = 10.0, width = 1920, height = 1080)

    @Test
    fun `each option carries its size, the estimates marked as estimates`() = runComposeUiTest {
        var picked: VideoSend? = null
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                VideoChoiceBar(item, VideoSend.Original, info) { picked = it }
            }
        }
        onNodeWithText("This video is 12.0 MB. Send it as:").assertIsDisplayed()
        onNodeWithText("SD 480p · ~1.8 MB").assertIsDisplayed()
        onNodeWithText("HD 1080p · ~8.7 MB").assertIsDisplayed()
        // Exact, not approximate: it is the file in hand.
        onNodeWithText("Original · 12.0 MB").assertIsDisplayed()

        onNodeWithText("SD 480p · ~1.8 MB").performClick()
        assertEquals(VideoSend.Sd, picked)
    }

    @Test
    fun `a machine with no encoder shows one option and says why`() = runComposeUiTest {
        var picked: VideoSend? = null
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                VideoChoiceBar(item, VideoSend.Original, info.copy(compressible = false)) { picked = it }
            }
        }
        onNodeWithText("This computer has no video encoder, so this clip can only be sent as it is.")
            .assertIsDisplayed()
        onNodeWithText("Original · 12.0 MB").assertIsDisplayed()
        onNodeWithText("SD 480p · ~1.8 MB").assertDoesNotExist()
        onNodeWithText("HD 1080p · ~8.7 MB").assertDoesNotExist()
        assertNull(picked)
    }

    @Test
    fun `a clip nobody can draw explains itself and is priced above the original`() = runComposeUiTest {
        // The measured AV1 clip: 894 KB in, 1.0 MB and 2.3 MB out — so its
        // figures are allowed to stand above the file they replace.
        val av1 = PreviewItem("av1-clip.mp4", "video/mp4", ByteArray(894_455))
        val header = VideoInfo(durationSeconds = 6.0, width = 1280, height = 720, unplayableFormat = "AV1")
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                VideoChoiceBar(av1, VideoSend.Hd, header) {}
            }
        }
        onNodeWithText(
            "This video is AV1, which many phones, browsers and this app can't play. " +
                "SD or HD converts it to H.264, which plays everywhere.",
        ).assertIsDisplayed()
        onNodeWithText("SD 480p · ~1.1 MB").assertIsDisplayed()
        onNodeWithText("HD 1080p · ~2.3 MB").assertIsDisplayed()
        onNodeWithText("Original · 873.4 KB").assertIsDisplayed()
    }

    @Test
    fun `a container that will not say how long it is shows bare labels`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = true, animateThemeChange = false) {
                VideoChoiceBar(item, VideoSend.Original, VideoInfo()) {}
            }
        }
        onNodeWithText("SD 480p").assertIsDisplayed()
        onNodeWithText("HD 1080p").assertIsDisplayed()
        // The original's size is the file in hand, always known.
        onNodeWithText("Original · 12.0 MB").assertIsDisplayed()
    }
}

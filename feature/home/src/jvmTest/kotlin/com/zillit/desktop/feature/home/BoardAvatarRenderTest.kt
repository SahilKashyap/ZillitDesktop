package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A poster's photo opens full size (web PR #6520).
 *
 * The disc beside a post is 28dp — enough to recognise a face you know, not
 * enough to read one you do not. A disc showing only initials has nothing to
 * open, so it must stay inert rather than offer a click that does nothing.
 */
@OptIn(ExperimentalTestApi::class)
class BoardAvatarRenderTest {

    private val unit = HomeUnit(
        id = "u1",
        identifier = "general_tool",
        unitName = "general_label",
        canView = true,
        canPost = true,
    )

    private val post = Notice(
        id = "n1",
        body = "the crew call moved to seven",
        authorName = "Sam Okafor",
        authorId = "them",
        createdAtMillis = System.currentTimeMillis(),
    )

    private fun state() = HomeFeedUiState(
        units = listOf(unit),
        selectedUnitId = unit.id,
        notices = listOf(post),
    )

    /** A real PNG, because the board decodes what the loader hands it. */
    private fun photoBytes(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    @Test
    fun `a sender's photo opens full size`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = state(),
                    onEvent = {},
                    resolveAuthor = { "Sam Okafor" },
                    loadAvatar = { photoBytes() },
                )
            }
        }
        waitForIdle()

        // The disc carries the poster's name, and so does the lightbox it
        // opens — so the lightbox is the SECOND node with that description.
        onAllNodesWithContentDescription("Sam Okafor").assertCountEquals(1)
        onNodeWithContentDescription("Sam Okafor").performClick()
        waitForIdle()

        onAllNodesWithContentDescription("Sam Okafor").assertCountEquals(2)
    }

    @Test
    fun `a disc with only initials opens nothing`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = state(),
                    onEvent = {},
                    resolveAuthor = { "Sam Okafor" },
                    // No photo for this crew member.
                    loadAvatar = { null },
                )
            }
        }
        waitForIdle()

        onAllNodesWithContentDescription("Sam Okafor").assertCountEquals(0)
    }
}

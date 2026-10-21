package com.zillit.desktop.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeMediaSource
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A posted document opens *in the app*, where the board is — it used to be
 * saved to Downloads and handed to whatever the machine keeps for the
 * extension, so reading a call sheet meant leaving Zillit and waiting out a
 * download to find out it was the wrong draft.
 *
 * Composed rather than unit-tested for the reason the clip's viewer is: the
 * whole bug class here is which handler the chip was wired with, and that is
 * exactly what a model test cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class DocumentViewerRenderTest {

    private val now = System.currentTimeMillis()

    private val unit = HomeUnit(
        id = "u1",
        identifier = "general_tool",
        unitName = "general_label",
        canView = true,
        canPost = true,
        canDownload = true,
    )

    private fun post(attachment: NoticeAttachment) = Notice(
        id = "n1",
        body = "day twelve",
        authorName = "Sam",
        authorId = "them",
        createdAtMillis = now,
        kind = NoticeKind.Document,
        attachment = attachment,
    )

    private val sides = NoticeAttachment(
        media = "home/sides.txt",
        fileName = "sides.txt",
        contentType = "text/plain",
        bucket = "b",
        region = "r",
    )

    private val budget = NoticeAttachment(
        media = "home/budget.xlsx",
        fileName = "budget.xlsx",
        contentType = "application/vnd.ms-excel",
        bucket = "b",
        region = "r",
    )

    /** Hands back [body] for a whole-object fetch, and nothing for a poster. */
    private class Source(private val body: ByteArray) : NoticeMediaSource {
        var fullFetches = 0
            private set

        override suspend fun fetch(
            attachment: NoticeAttachment,
            preview: Boolean,
        ): ZillitResult<ByteArray> {
            if (preview) return ZillitResult.Failure(ZillitError.NoConnection(null))
            fullFetches++
            return ZillitResult.Success(body)
        }
    }

    private fun board(attachment: NoticeAttachment) = HomeFeedUiState(
        units = listOf(unit),
        selectedUnitId = unit.id,
        notices = listOf(post(attachment)),
        currentUserId = "me",
        nowMillis = now,
    )

    @Test
    fun `clicking a document reads it in the app and hands nothing to the OS`() = runComposeUiTest {
        var handedOut: NoticeAttachment? = null
        val source = Source("SCENE 12 — INT. STAGE 4".encodeToByteArray())

        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = board(sides),
                    onEvent = {},
                    media = source,
                    onOpenAttachment = { handedOut = it },
                )
            }
        }

        onNodeWithText("sides.txt").performClick()
        waitForIdle()

        // The document itself is on screen, with a way out — and the OS was
        // never asked to open anything.
        onNodeWithText("SCENE 12 — INT. STAGE 4").assertExists("the document is drawn in the app")
        onNodeWithContentDescription("Close viewer").assertExists()
        assertNull(handedOut, "clicking a document no longer leaves the app")
        assertTrue(source.fullFetches > 0, "the viewer fetched the object itself")
    }

    @Test
    fun `the viewer's Download is still what saves the file`() = runComposeUiTest {
        val events = mutableListOf<HomeFeedEvent>()

        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = board(sides),
                    onEvent = { events += it },
                    media = Source("a line".encodeToByteArray()),
                )
            }
        }

        onNodeWithText("sides.txt").performClick()
        waitForIdle()
        onNodeWithText("Download").performClick()
        waitForIdle()

        // Looking was free; saving goes through the model, which names the
        // rendition before the host writes it to disk.
        assertEquals(
            listOf<HomeFeedEvent>(HomeFeedEvent.OpenAttachment("n1", sides)),
            events.filterIsInstance<HomeFeedEvent.OpenAttachment>(),
        )
    }

    @Test
    fun `a file the app cannot draw says so and offers the download instead`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = board(budget),
                    onEvent = {},
                    media = Source(byteArrayOf(1, 2, 3)),
                )
            }
        }

        onNodeWithText("budget.xlsx").performClick()
        waitForIdle()

        // A spreadsheet has no renderer here, so the viewer is honest about
        // it rather than showing an empty stage — and the download is then
        // the way in, where it is the only one.
        onNodeWithText("Preview not available for this file type.").assertExists()
        onNodeWithText("Download to view").assertExists()
    }
}

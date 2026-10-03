package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Composes the real screen, WhatsApp's media editor: the caption pill, the
 * strip, the send disc with its count; Send hands back every item with the
 * caption typed; a picture offers its tools and a file does not; remove only
 * while more than one is selected; per-item captions ride on each result;
 * and "+" asks the host for more without starting over.
 */
@OptIn(ExperimentalTestApi::class)
class MediaPreviewDialogRenderTest {

    private fun pngBytes(): ByteArray {
        val image = ImageBitmap(24, 16)
        Canvas(image).drawRect(0f, 0f, 24f, 16f, Paint().apply { color = Color.White })
        return assertNotNull(encodeImage(image, ImageEncoding.Png, quality = 100))
    }

    @Test
    fun `a picture shows its tools, the count and Send, and Send returns the caption`() = runComposeUiTest {
        var sent: Pair<List<PreviewResult>, String>? = null
        val items = listOf(PreviewItem("door.png", "image/png", pngBytes()))
        setContent {
            ZillitTheme {
                MediaPreviewDialog(
                    items = items,
                    onSend = { results, caption -> sent = results to caption },
                    onCancel = {},
                    initialCaption = "the door ",
                )
            }
        }

        onNodeWithText("1").assertIsDisplayed()
        onNodeWithContentDescription("Draw").assertExists()
        onNodeWithContentDescription("Crop").assertExists()
        onNodeWithText("the door ").performTextReplacement("the door we need")
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { sent != null }

        val (results, caption) = assertNotNull(sent)
        assertEquals("the door we need", caption)
        assertEquals("door.png", results.single().name)
    }

    @Test
    fun `Close hands back nothing, and Remove appears only with several items`() = runComposeUiTest {
        var cancelled = false
        val items = listOf(
            PreviewItem("call.pdf", "application/pdf", ByteArray(8)),
            PreviewItem("take.mp4", "video/mp4", ByteArray(8)),
        )
        setContent {
            ZillitTheme {
                MediaPreviewDialog(items = items, onSend = { _, _ -> }, onCancel = { cancelled = true })
            }
        }

        onNodeWithText("2").assertIsDisplayed()
        // A document takes no tools; its name heads the screen instead.
        assertEquals(0, onAllNodesWithContentDescription("Draw").fetchSemanticsNodes().size)
        onAllNodesWithText("call.pdf").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        onNodeWithContentDescription("Remove").performClick()
        onNodeWithText("1").assertIsDisplayed()
        assertEquals(0, onAllNodesWithContentDescription("Remove").fetchSemanticsNodes().size)

        onNodeWithContentDescription("Close").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `each item keeps its own caption where the host asks for one per item`() = runComposeUiTest {
        var sent: List<PreviewResult>? = null
        val items = listOf(
            PreviewItem("one.png", "image/png", pngBytes()),
            PreviewItem("two.png", "image/png", pngBytes()),
        )
        setContent {
            ZillitTheme {
                MediaPreviewDialog(
                    items = items,
                    onSend = { results, _ -> sent = results },
                    onCancel = {},
                    captionPerItem = true,
                )
            }
        }

        onNode(hasSetTextAction()).performTextReplacement("first")
        onNodeWithContentDescription("two.png").performClick()
        waitForIdle()
        // The second item's field starts empty: the first caption stayed with the first.
        onNode(hasSetTextAction()).performTextReplacement("second")
        onNodeWithContentDescription("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { sent != null }

        assertEquals(listOf("first", "second"), assertNotNull(sent).map { it.caption })
    }

    @Test
    fun `plus asks the host for more, and what it adds joins the same screen`() = runComposeUiTest {
        val asked = mutableListOf<PreviewKind>()
        val first = PreviewItem("one.png", "image/png", pngBytes())
        var items by mutableStateOf(listOf(first))
        setContent {
            ZillitTheme {
                MediaPreviewDialog(
                    items = items,
                    onSend = { _, _ -> },
                    onCancel = {},
                    onAddMore = { kind -> asked += kind },
                    addKinds = listOf(PreviewKind.Document),
                )
            }
        }

        onNodeWithContentDescription("Add more").performClick()
        assertEquals(listOf(PreviewKind.Document), asked)

        items = items + PreviewItem("call.pdf", "application/pdf", ByteArray(8))
        waitForIdle()
        onNodeWithText("2").assertIsDisplayed()
        // The new file comes on screen: its name heads the bar.
        assertTrue(onAllNodesWithText("call.pdf").fetchSemanticsNodes().isNotEmpty())
    }
}

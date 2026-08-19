package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Composes the real dialog: the caption field, the count, Send and Cancel
 * are on screen, Send hands back every item with the caption typed, and
 * the per-item controls follow Android's rules — Edit for pictures,
 * Remove only while more than one is selected.
 */
@OptIn(ExperimentalTestApi::class)
class MediaPreviewDialogRenderTest {

    private fun pngBytes(): ByteArray {
        val image = ImageBitmap(24, 16)
        Canvas(image).drawRect(0f, 0f, 24f, 16f, Paint().apply { color = Color.White })
        return assertNotNull(encodeImage(image, ImageEncoding.Png, quality = 100))
    }

    @Test
    fun `the dialog shows the caption field, the count and Send, and Send returns the caption`() =
        runComposeUiTest {
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

            onNodeWithText("Media selected: 1").assertIsDisplayed()
            onNodeWithText("Edit").assertIsDisplayed()
            onNodeWithText("the door ").performTextReplacement("the door we need")
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 5_000) { sent != null }

            val (results, caption) = assertNotNull(sent)
            assertEquals("the door we need", caption)
            assertEquals("door.png", results.single().name)
        }

    @Test
    fun `Cancel hands back nothing, and Remove appears only with several items`() = runComposeUiTest {
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

        onNodeWithText("Media selected: 2").assertIsDisplayed()
        // A document takes no tools.
        assertTrue(onAllNodesWithTextCount("Edit") == 0, "documents must not offer Edit")
        onNodeWithText("Remove").performClick()
        onNodeWithText("Media selected: 1").assertIsDisplayed()
        assertTrue(onAllNodesWithTextCount("Remove") == 0, "the last item cannot be removed")

        onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
